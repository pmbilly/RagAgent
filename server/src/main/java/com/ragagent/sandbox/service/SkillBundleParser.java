package com.ragagent.sandbox.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code service/tenant_skill_bundle.go}（全文）：
 * 上传 zip 的校验、解包、SHA256、文件清单与单文件读取。
 *
 * <p>{@link #SENTINEL} 的消息恒为 {@code "skill bundle is invalid"}；每个拒绝以
 * {@code "<sentinel>: <细节>"} 组句（Go 的 {@code fmt.Errorf("%w: ...")} 形态），
 * handler 按<b>类型</b>把整类升为 400——改写措辞的错误不能悄悄开始对坏输入返回 500
 * （Go 注释原文）。细节文案逐字对应 Go，含 {@code zip: not a valid zip file}
 * （Go 的 {@code zip.ErrFormat}，对一切读不开的归档恒定）。</p>
 *
 * <h2>解压限额（zip 炸弹防线）</h2>
 * 20000 个 skill 文件 / 100000 个 zip 条目 / 单条目 32 MiB / 合计 512 MiB。
 *
 * <p>已知差异：Java 标准库不暴露 zip 外部属性，Unix symlink 位不可检测——
 * Go 的 {@code entry %q is a symlink} 拒绝分支缺失（golden 未覆盖；波 4 如需
 * 可用 commons-compress 的 ZipArchiveEntry.getUnixMode 补上）。</p>
 */
public final class SkillBundleParser {

    /** 对照 {@code ErrSkillBundleInvalid}：上传归档一切拒绝的标记（按类型映射 400）。 */
    public static final String SENTINEL = "skill bundle is invalid";

    /** 对照 {@code errSkillFileMissing}：合法归档里没有请求的路径 → 浏览器映射 404。 */
    public static final String FILE_MISSING = "skill file not found";

    static final int MAX_SKILL_BUNDLE_FILES = 20_000;
    static final int MAX_SKILL_BUNDLE_ZIP_ENTRIES = 100_000;
    static final long MAX_SKILL_BUNDLE_FILE_BYTES = 32L << 20;
    static final long MAX_SKILL_BUNDLE_TOTAL_BYTES = 512L << 20;

    static final int SKILL_FILE_TEXT_LIMIT = 1 << 20; // 1 MiB
    static final int SKILL_FILE_IMAGE_LIMIT = 2 << 20; // 2 MiB

    static final String ENCODING_UTF8 = "utf-8";
    static final String ENCODING_BASE64 = "base64";
    static final String ENCODING_BINARY = "binary";

    private SkillBundleParser() {
    }

    /** 拒绝异常：消息 = SENTINEL + ": " + detail（对照 fmt.Errorf("%w: …")）。 */
    public static final class BundleInvalidException extends RuntimeException {
        public BundleInvalidException(String detail) {
            super(SENTINEL + ": " + detail);
        }
    }

    /** 文件缺失异常（消息 = FILE_MISSING，浏览器映射 404；其余 zip 问题仍是 400）。 */
    public static final class SkillFileNotFoundException extends RuntimeException {
        public SkillFileNotFoundException() {
            super(FILE_MISSING);
        }
    }

    /** 对照 {@code SkillBundle}：一份校验过的内存归档。 */
    public static final class SkillBundle {
        public String name = "";
        public String version = "";
        public String description = "";
        public String instructions = "";
        /** 对上传字节算的 SHA256：同一归档的重传在 UI 与台账里可辨认。 */
        public String sha256 = "";
        /** skill 根相对路径 → 内容，SKILL.md 含在内。 */
        public Map<String, byte[]> files = new LinkedHashMap<>();
        public boolean frontmatterRepaired;
    }

    /** 对照 {@code SkillFileEntry}（响应体；键序 = Go struct 序）。 */
    public record SkillFileEntry(
            @JsonProperty("path") String path,
            @JsonProperty("size") long size) {
    }

    /**
     * 对照 {@code SkillFileContent}（响应体）：path/size/encoding 恒输出（size=0 的空文件
     * 也是 "size":0），content/media_type/truncated/binary 是 omitempty——注解逐字段对照，
     * 不用类级 NON_DEFAULT（会把 "size":0 吞掉）。
     */
    public record SkillFileContent(
            @JsonProperty("path") String path,
            @JsonProperty("size") long size,
            @JsonProperty("encoding") String encoding,
            @JsonProperty("content") @JsonInclude(JsonInclude.Include.NON_EMPTY) String content,
            @JsonProperty("media_type") @JsonInclude(JsonInclude.Include.NON_EMPTY) String mediaType,
            @JsonProperty("truncated") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean truncated,
            @JsonProperty("binary") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean binary) {
    }

    // ── ParseSkillBundle ────────────────────────────────────────────────

    /** 对照 {@code ParseSkillBundle}：平面归档与单层包裹目录都收（人们实际上传的两种形态）。 */
    public static SkillBundle parseSkillBundle(byte[] archive) {
        Map<String, byte[]> raw = unzipSkillArchive(archive);
        Map<String, byte[]> files = stripSkillRootPrefix(raw);
        return skillBundleFromFiles(archive, files);
    }

    private static Map<String, byte[]> unzipSkillArchive(byte[] archive) {
        List<ZipItem> entries = skillZipEntries(archive);

        Map<String, byte[]> raw = new LinkedHashMap<>();
        long totalBytes = 0;
        for (ZipItem item : entries) {
            long entryBytes = item.size;
            if (totalBytes + entryBytes > MAX_SKILL_BUNDLE_TOTAL_BYTES) {
                throw new BundleInvalidException("archive is too large");
            }
            totalBytes += entryBytes;
            byte[] content = readLimitedZipEntry(item);
            if (content.length > entryBytes) {
                long actualExcess = content.length - entryBytes;
                if (totalBytes + actualExcess > MAX_SKILL_BUNDLE_TOTAL_BYTES) {
                    throw new BundleInvalidException("archive is too large");
                }
                totalBytes += actualExcess;
            }
            raw.put(item.name, content);
        }
        return raw;
    }

    private record ZipItem(String archiveName, String name, long size, byte[] body) {
    }

    private static List<ZipItem> skillZipEntries(byte[] archive) {
        List<RawEntry> central;
        try {
            central = readCentralDirectory(archive);
        } catch (IOException e) {
            // Go 的 zip.NewReader 错误恒为 zip.ErrFormat（"zip: not a valid zip file"）
            throw new BundleInvalidException("not a readable zip archive: zip: not a valid zip file");
        }
        if (central.size() > MAX_SKILL_BUNDLE_ZIP_ENTRIES) {
            throw new BundleInvalidException(
                    "archive has more than " + MAX_SKILL_BUNDLE_ZIP_ENTRIES + " zip entries");
        }

        List<RawEntry> pending = new ArrayList<>();
        for (RawEntry entry : central) {
            String name = inspectSkillZipPath(entry.cleaned, entry.dir);
            if (name == null) {
                continue; // 目录条目被丢弃
            }
            pending.add(new RawEntry(entry.raw, name, entry.size, entry.body, entry.dir));
        }

        String prefix = skillRootPrefix(pending.stream()
                .map(p -> p.cleaned)
                .distinct()
                .toList());

        List<ZipItem> out = new ArrayList<>();
        long totalBytes = 0;
        for (RawEntry item : pending) {
            if (!prefix.isEmpty() && !item.cleaned.startsWith(prefix + "/")) {
                throw new BundleInvalidException("archive holds files outside the skill directory "
                        + quote(prefix));
            }
            inspectKeptSkillZipEntry(item.cleaned, item.size);
            if (totalBytes + item.size > MAX_SKILL_BUNDLE_TOTAL_BYTES) {
                throw new BundleInvalidException("archive is too large");
            }
            totalBytes += item.size;
            out.add(new ZipItem(item.raw, item.cleaned, item.size, item.body));
            if (out.size() > MAX_SKILL_BUNDLE_FILES) {
                throw new BundleInvalidException(
                        "skill directory holds more than " + MAX_SKILL_BUNDLE_FILES + " files");
            }
        }
        return out;
    }

    private record RawEntry(String raw, String cleaned, long size, byte[] body, boolean dir) {
    }

    /**
     * 读取全部条目体（对照 Go 分两阶段：先 central directory 检查，再 inflate）。
     * Java 标准库经 ZipFile（真 central directory 语义）一次给出两者，等价。
     */
    private static List<RawEntry> readCentralDirectory(byte[] archive) throws IOException {
        java.nio.file.Path path = java.nio.file.Files.createTempFile("skill-bundle", ".zip");
        try {
            java.nio.file.Files.write(path, archive);
            try (ZipFile zip = new ZipFile(path.toFile())) {
                List<RawEntry> out = new ArrayList<>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    byte[] body = entry.isDirectory() ? new byte[0]
                            : readLimited(entry, zip.getInputStream(entry));
                    out.add(new RawEntry(entry.getName(), entry.getName(),
                            entry.getSize() > 0 ? entry.getSize() : body.length, body,
                            entry.isDirectory()));
                }
                return out;
            } catch (ZipException | java.util.zip.ZipError e) {
                // Go 的 zip.NewReader 对一切读不开的归档恒报 zip.ErrFormat
                throw new ZipException("zip: not a valid zip file");
            }
        } catch (ZipException e) {
            throw e;
        } catch (IOException e) {
            // 非法归档在 new ZipFile 时已转成上面的 ErrFormat 文案；这里是写临时文件等 IO 失败
            throw new BundleInvalidException("not a readable zip archive: zip: not a valid zip file");
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best-effort 清理
            }
        }
    }

    private static byte[] readLimited(ZipEntry entry, java.io.InputStream in) throws IOException {
        try (in) {
            byte[] content = in.readAllBytes();
            if (content.length > MAX_SKILL_BUNDLE_FILE_BYTES) {
                throw new BundleInvalidException(
                        "entry " + quote(entry.getName()) + " is too large");
            }
            return content;
        }
    }

    /** 对照 {@code inspectSkillZipPath}：目录条目跳过（null）；穿越名拒绝。 */
    static String inspectSkillZipPath(String rawName, boolean dir) {
        if (dir) {
            return null;
        }
        String name = goPathClean(rawName);
        if (name.equals(".") || name.startsWith("..")
                || name.startsWith("/") || name.contains("../")) {
            throw new BundleInvalidException(
                    "entry " + quote(rawName) + " escapes the archive root");
        }
        return name;
    }

    /** 对照 {@code inspectKeptSkillZipEntry}：控制字符与单条目限额。 */
    static void inspectKeptSkillZipEntry(String name, long size) {
        validateSkillEntryName(name);
        if (size > MAX_SKILL_BUNDLE_FILE_BYTES) {
            throw new BundleInvalidException("entry " + quote(name) + " is too large");
        }
    }

    private static byte[] readLimitedZipEntry(ZipItem item) {
        if (item.body.length > MAX_SKILL_BUNDLE_FILE_BYTES) {
            throw new BundleInvalidException("entry " + quote(item.archiveName) + " is too large");
        }
        return item.body;
    }

    /**
     * 对照 {@code skillBundleFromFiles}：SKILL.md 必须在根上，frontmatter 校验通过，
     * 版本可缺省；SHA256 对原始上传字节。
     */
    private static SkillBundle skillBundleFromFiles(byte[] archive, Map<String, byte[]> files) {
        byte[] manifest = files.get("SKILL.md");
        if (manifest == null) {
            throw new BundleInvalidException("SKILL.md is missing");
        }
        SkillFrontmatter.Skill skill;
        try {
            skill = SkillFrontmatter.parseSkillFile(new String(manifest, StandardCharsets.UTF_8));
        } catch (SkillFrontmatter.SkillFrontmatterException e) {
            throw new BundleInvalidException(e.getMessage());
        }
        String version;
        try {
            version = parseSkillBundleVersion(new String(manifest, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new BundleInvalidException(e.getMessage());
        }

        SkillBundle bundle = new SkillBundle();
        bundle.name = skill.name;
        bundle.version = version;
        bundle.description = skill.description;
        bundle.instructions = skill.instructions;
        bundle.sha256 = skillArchiveSHA256(archive);
        bundle.files = files;
        bundle.frontmatterRepaired = skill.frontmatterRepaired;
        return bundle;
    }

    /** 对照 {@code skillArchiveSHA256}。 */
    public static String skillArchiveSHA256(byte[] archive) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] sum = md.digest(archive);
            StringBuilder sb = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 对照 {@code archiveMatchesSHA}：期望摘要在空白或归档为空时按"未知"处理，
     * 不是对磁盘上任何字节的匹配——要兜底的调用方必须显式选择（Go 注释原文）。
     */
    public static boolean archiveMatchesSHA(byte[] archive, String want) {
        want = want == null ? "" : want.trim();
        if (want.isEmpty() || archive == null || archive.length == 0) {
            return false;
        }
        return skillArchiveSHA256(archive).equals(want);
    }

    /**
     * 对照 {@code parseSkillBundleVersion}：frontmatter 里的 {@code version} 字段，
     * 可缺省（空串）。
     */
    static String parseSkillBundleVersion(String manifest) {
        if (manifest.startsWith("\uFEFF")) {
            manifest = manifest.substring(1);
        }
        String[] lines = manifest.split("\n", -1);
        int frontmatterStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                frontmatterStart = i;
                break;
            }
            if (!lines[i].trim().isEmpty()) {
                break;
            }
        }
        if (frontmatterStart < 0) {
            return "";
        }
        int frontmatterEnd = -1;
        for (int i = frontmatterStart + 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                frontmatterEnd = i;
                break;
            }
        }
        if (frontmatterEnd < 0) {
            return "";
        }
        String frontmatter = String.join("\n",
                java.util.Arrays.asList(lines).subList(frontmatterStart + 1, frontmatterEnd));
        // Go 走 UnmarshalSkillFrontmatter（带修复的解码器），Java 侧同一管线
        return SkillFrontmatter.frontmatterVersion(frontmatter);
    }

    // ── 文件浏览面（listSkillZipFiles / readSkillZipFile） ────────────────

    /** 对照 {@code listSkillZipFiles}：skill 根相对路径 + 声明大小，按名称排序。 */
    public static List<SkillFileEntry> listSkillZipFiles(byte[] archive) {
        Map<String, byte[]> raw = unzipSkillArchive(archive);
        Map<String, byte[]> stripped = stripSkillRootPrefix(raw);
        Map<String, Long> index = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : stripped.entrySet()) {
            index.put(e.getKey(), (long) e.getValue().length);
        }
        List<SkillFileEntry> out = new ArrayList<>(index.size());
        for (Map.Entry<String, Long> e : index.entrySet()) {
            out.add(new SkillFileEntry(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 对照 {@code readSkillZipFile}：按 skill 根相对路径读一个文件，其余保持压缩。 */
    public static byte[] readSkillZipFile(byte[] archive, String rel) {
        Map<String, byte[]> raw = unzipSkillArchive(archive);
        Map<String, byte[]> stripped = stripSkillRootPrefix(raw);
        byte[] body = stripped.get(rel);
        if (body == null) {
            throw new SkillFileNotFoundException();
        }
        return body;
    }

    // ── skill 根的判定与重挂根 ────────────────────────────────────────────

    /**
     * 对照 {@code stripSkillRootPrefix}：把归档重挂在放 SKILL.md 的目录上。
     */
    static Map<String, byte[]> stripSkillRootPrefix(Map<String, byte[]> raw) {
        String prefix = skillRootPrefix(raw.keySet());
        if (prefix.isEmpty()) {
            return raw;
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(prefix + "/")) {
                throw new BundleInvalidException("archive holds files outside the skill directory "
                        + quote(prefix));
            }
            out.put(name.substring(prefix.length() + 1), e.getValue());
        }
        if (!out.containsKey("SKILL.md")) {
            throw new BundleInvalidException("SKILL.md is missing");
        }
        return out;
    }

    /**
     * 对照 {@code skillRootPrefix}：zip 根上的 SKILL.md 就是这个 skill（嵌套的 SKILL.md
     * 只是附加文件）；否则恰有一个目录承载它。
     */
    static String skillRootPrefix(Iterable<String> names) {
        for (String name : names) {
            if (name.equals("SKILL.md")) {
                return "";
            }
        }
        List<String> matches = new ArrayList<>();
        for (String name : names) {
            if (!baseName(name).equals("SKILL.md")) {
                continue;
            }
            String dir = dirName(name);
            if (dir.isEmpty() || !dir.contains("/")) {
                matches.add(dir);
            }
        }
        if (matches.isEmpty()) {
            throw new BundleInvalidException("SKILL.md is missing");
        }
        List<String> uniq = uniqueStrings(matches);
        if (uniq.size() > 1) {
            throw new BundleInvalidException("archive holds more than one skill");
        }
        return uniq.get(0);
    }

    /** 对照 {@code validateSkillEntryName}：只禁控制字符（NUL 含在内）。 */
    static void validateSkillEntryName(String name) {
        for (int i = 0; i < name.length(); ) {
            int r = name.codePointAt(i);
            if (Character.isISOControl(r)) {
                throw new BundleInvalidException("entry " + quote(name)
                        + " holds unsupported character " + quoteCodePoint(r));
            }
            i += Character.charCount(r);
        }
    }

    // ── 文件浏览投影（tenant_skill_files.go） ─────────────────────────────

    /**
     * 对照 {@code safeSkillFilePath}：规范调用方给的相对路径，拒绝离开 skill 目录的一切。
     */
    public static String safeSkillFilePath(String relativePath) {
        String trimmed = relativePath == null ? "" : relativePath.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("skill file path is required");
        }
        if (trimmed.contains("\\")) {
            throw new IllegalArgumentException("invalid skill file path: " + relativePath);
        }
        if (trimmed.startsWith("/")) {
            throw new IllegalArgumentException("invalid skill file path: " + relativePath);
        }
        for (String seg : trimmed.split("/")) {
            if (seg.equals("..")) {
                throw new IllegalArgumentException("invalid skill file path: " + relativePath);
            }
        }
        String clean = goPathClean(trimmed);
        if (clean.equals(".") || clean.equals("..") || clean.startsWith("../")) {
            throw new IllegalArgumentException("invalid skill file path: " + relativePath);
        }
        return clean;
    }

    /** 对照 {@code projectSkillFileContent}。 */
    public static SkillFileContent projectSkillFileContent(String rel, byte[] body) {
        String mediaType = skillImageMediaType(rel);
        if (mediaType != null) {
            if (body.length > SKILL_FILE_IMAGE_LIMIT) {
                return new SkillFileContent(rel, body.length, ENCODING_BINARY, "", mediaType,
                        false, true);
            }
            return new SkillFileContent(rel, body.length, ENCODING_BASE64,
                    Base64.getEncoder().encodeToString(body), mediaType, false, false);
        }
        if (skillFileLooksBinary(body)) {
            return new SkillFileContent(rel, body.length, ENCODING_BINARY, "", "", false, true);
        }
        String text = new String(body, StandardCharsets.UTF_8);
        String mt = "";
        String ext = extension(rel);
        if (!ext.isEmpty()) {
            mt = ext.equals(".md") || ext.equals(".markdown") ? "text/markdown" : "text/plain";
        }
        if (body.length > SKILL_FILE_TEXT_LIMIT) {
            return new SkillFileContent(rel, body.length, ENCODING_UTF8,
                    text.substring(0, SKILL_FILE_TEXT_LIMIT), mt, true, false);
        }
        return new SkillFileContent(rel, body.length, ENCODING_UTF8, text, mt, false, false);
    }

    /** 对照 {@code skillFileLooksBinary}：NUL 字节或非法 UTF-8。 */
    static boolean skillFileLooksBinary(byte[] body) {
        for (byte b : body) {
            if (b == 0) {
                return true;
            }
        }
        return !isValidUtf8(body);
    }

    private static boolean isValidUtf8(byte[] body) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 对照 {@code skillImageMediaType}：可内联预览的图片扩展名。 */
    static String skillImageMediaType(String rel) {
        return switch (extension(rel)) {
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".ico" -> "image/x-icon";
            case ".svg" -> "image/svg+xml";
            default -> null;
        };
    }

    private static String extension(String rel) {
        int slash = rel.lastIndexOf('/');
        String name = slash >= 0 ? rel.substring(slash + 1) : rel;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    // ── Go 标准库等价物 ──────────────────────────────────────────────────

    /** Go {@code path.Clean} 的直译（斜杠分隔；保留绝对形态）。 */
    static String goPathClean(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.startsWith("/");
        String[] parts = path.split("/", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
                    out.remove(out.size() - 1);
                } else if (!rooted) {
                    out.add("..");
                }
                continue;
            }
            out.add(part);
        }
        String joined = String.join("/", out);
        if (rooted) {
            return "/" + joined;
        }
        return joined.isEmpty() ? "." : joined;
    }

    private static String baseName(String path) {
        String p = goPathClean(path);
        if (p.equals("/") || p.equals(".")) {
            return p;
        }
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String dirName(String path) {
        String p = goPathClean(path);
        int i = p.lastIndexOf('/');
        if (i < 0) {
            return "";
        }
        String dir = p.substring(0, i);
        if (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        return dir.equals(".") ? "" : dir;
    }

    private static List<String> uniqueStrings(List<String> in) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(in));
    }

    /**
     * Go {@code strconv.Quote} 的面向错误文案近似：双引号包裹，转义 {@code " \ \n \r \t}
     * 与控制字符（{@code \x} 形态）。skill 条目名是 ASCII 为主，覆盖 golden/单测语料。
     */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); ) {
            int r = s.codePointAt(i);
            switch (r) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (r < 0x20 || r == 0x7f) {
                        sb.append(String.format("\\x%02x", r));
                    } else {
                        sb.appendCodePoint(r);
                    }
                }
            }
            i += Character.charCount(r);
        }
        sb.append('"');
        return sb.toString();
    }

    /** 对照 %q 作用在 rune 上（如 {@code holds unsupported character %q}）。 */
    static String quoteCodePoint(int r) {
        StringBuilder sb = new StringBuilder(3);
        sb.append('\'');
        switch (r) {
            case '\'' -> sb.append("\\'");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
                if (r < 0x20 || r == 0x7f) {
                    sb.append(String.format("\\x%02x", r));
                } else {
                    sb.appendCodePoint(r);
                }
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    /** 供单测的便捷入口：把映射打成 zip（对照测试里 python zipfile 的角色）。 */
    public static byte[] zipOf(Map<String, String> files) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(buf)) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
