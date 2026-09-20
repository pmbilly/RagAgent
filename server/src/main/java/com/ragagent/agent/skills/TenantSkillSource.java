package com.ragagent.agent.skills;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.InflaterInputStream;

import com.ragagent.agent.tools.GoPath;
import com.ragagent.sandbox.domain.SkillStatus;
import com.ragagent.agent.tools.SandboxPaths;
import com.ragagent.sandbox.domain.TenantSkillEntity;

/**
 * 管理员装进一份 sandbox 配置快照镜像的 skill 投影（对照 Go
 * internal/agent/skills/tenant_source.go，全文移植）。
 *
 * <p>三个 disclosure 层刻意来自不同地方：name/description/SKILL.md 正文是行上的
 * 列——请求路径不必等对象存储来告诉模型 skill 是什么；单个资源文件来自上传的
 * 归档。执行两者都不用——文件已在镜像里。</p>
 *
 * <p>zip 限制与安装时限相同：这里读的归档就是安装时接受的那份，存储里的损坏或
 * 恶意对象不得耗尽本进程。计数在目录条目与 zipball 额外树被丢弃后进行，与安装
 * 计数一致。</p>
 */
public final class TenantSkillSource implements ImageSkillSource {

    /** 中央目录迭代上限（对照 maxBundleZipEntries=100_000）。 */
    static final int MAX_BUNDLE_ZIP_ENTRIES = 100_000;
    /** 保留为 skill 的文件上限（对照 maxBundleEntries=20_000）。 */
    static final int MAX_BUNDLE_ENTRIES = 20_000;
    /** 单条目 32 MiB（对照 maxBundleEntryBytes）。 */
    static final int MAX_BUNDLE_ENTRY_BYTES = 32 << 20;
    /** 单归档 512 MiB（对照 maxBundleBytes；安装上限）。 */
    static final long MAX_BUNDLE_BYTES = 512L << 20;

    /** LRU 预算：最多 4 份压缩包 / 64 MiB（对照 cachedBundleCount/cachedBundleBytes）。 */
    static final int CACHED_BUNDLE_COUNT = 4;
    static final long CACHED_BUNDLE_BYTES = 64L << 20;

    /** bundle 下载函数（Go 的 loadBundle func(*TenantSkillEntity) ([]byte, error)）。 */
    private final Function<TenantSkillEntity, byte[]> loadBundle;

    private final Map<String, TenantSkillEntity> byName = new LinkedHashMap<>();
    /** 保持仓储顺序，系统提示词在轮次间才稳定。 */
    private final List<String> order = new ArrayList<>();

    private final Object mu = new Object();
    /** 最近使用优先的下载归档缓存，按 bundle_sha256（或 storage ref）键。 */
    private List<CachedBundle> cache = new ArrayList<>();

    private record CachedBundle(String key, byte[] archive) {
    }

    /**
     * 以一份 sandbox 配置的行构建 source（对照 NewTenantSkillSource）。
     * 调用方传入全部行；source 自己决定哪些可用。
     */
    public TenantSkillSource(List<TenantSkillEntity> rows, Function<TenantSkillEntity, byte[]> loadBundle) {
        this.loadBundle = loadBundle;
        if (rows != null) {
            for (TenantSkillEntity row : rows) {
                if (!usableSkillRow(row)) {
                    continue;
                }
                if (byName.containsKey(row.getName())) {
                    continue;
                }
                byName.put(row.getName(), row);
                order.add(row.getName());
            }
        }
    }

    /**
     * 「agent 真正能跑这一行吗」的唯一判定（对照 usableSkillRow）。仍在安装、失败、
     * 被管理员停用的行不可见。名字守卫不是防御性的：本 source 发出的每个路径都是
     * SkillDirFor(row.Name)，非单段路径的名会产出 skill 外（或 root 外）的路径，
     * 在发现元数据与 SkillFile.Path 里直达模型。
     */
    static boolean usableSkillRow(TenantSkillEntity row) {
        return row != null
                && row.isEnabled()
                && SkillStatus.READY.equals(row.getStatus())
                && com.ragagent.agent.tools.SandboxPaths.isValidSkillName(row.getName());
    }

    @Override
    public List<Skill.SkillMetadata> discoverSkills() {
        List<Skill.SkillMetadata> metadata = new ArrayList<>(order.size());
        for (String name : order) {
            TenantSkillEntity row = byName.get(name);
            String basePath = SandboxPathsHelper.skillDirOrThrow(row.getName());
            metadata.add(new Skill.SkillMetadata(row.getName(), row.getDescription(), basePath));
        }
        return metadata;
    }

    @Override
    public Skill loadSkillInstructions(String name) {
        TenantSkillEntity row = row(name);
        String basePath = SandboxPathsHelper.skillDirOrThrow(row.getName());
        Skill skill = new Skill();
        skill.name = row.getName();
        skill.description = row.getDescription();
        skill.basePath = basePath;
        skill.filePath = SandboxPaths.join(basePath, Skill.SKILL_FILE_NAME);
        skill.instructions = row.getInstructions();
        skill.loaded = true;
        return skill;
    }

    /**
     * 从上传归档里取一个 Level 3 资源（对照 LoadSkillFile）。读归档而非镜像：
     * 从镜像读文件需要 sandbox，而 read_file 必须在本轮尚未启动 sandbox 时也能用。
     */
    @Override
    public Skill.SkillFile loadSkillFile(String name, String relativePath) {
        TenantSkillEntity row = row(name);
        String clean = safeSkillRelPath(relativePath);
        byte[] archive = bundleArchive(row);
        Map<String, SkillZipEntry> index = skillBundleFileIndex(archive);
        SkillZipEntry item = index.get(clean);
        if (item == null) {
            throw new Skill.SkillValidationException("file not found in skill " + name + ": " + relativePath);
        }
        byte[] content = readLimitedSkillZipFile(item);
        String basePath = SandboxPathsHelper.skillDirOrThrow(row.getName());
        return new Skill.SkillFile(relativePath, SandboxPaths.join(basePath, clean), new String(content, StandardCharsets.UTF_8), Skill.isScript(clean));
    }

    /** 列出一个 skill 的归档内容，排序让同轮内重复调用不像不同答案（对照 ListSkillFiles）。 */
    @Override
    public List<String> listSkillFiles(String name) {
        TenantSkillEntity row = row(name);
        byte[] archive = bundleArchive(row);
        Map<String, SkillZipEntry> index = skillBundleFileIndex(archive);
        List<String> names = new ArrayList<>(index.keySet());
        java.util.Collections.sort(names);
        return names;
    }

    @Override
    public String getSkillBasePath(String name) {
        TenantSkillEntity row = row(name);
        return SandboxPathsHelper.skillDirOrThrow(row.getName());
    }

    /**
     * 一个脚本的镜像内绝对路径，按 skill 名（也是安装器写的目录名）寻址
     * （对照 RemoteScriptPath）。刻意不查归档：执行的是镜像，归档存储失败
     * （安装只记 warning）的 skill 依然安装且可运行。
     */
    public String remoteScriptPath(String name, String relativePath) {
        TenantSkillEntity row = row(name);
        String clean = safeSkillRelPath(relativePath);
        String basePath = SandboxPathsHelper.skillDirOrThrow(row.getName());
        return SandboxPaths.join(basePath, clean);
    }

    private TenantSkillEntity row(String name) {
        TenantSkillEntity row = byName.get(name);
        if (row == null) {
            throw new Skill.SkillValidationException("skill not found: " + name);
        }
        return row;
    }

    /** 规范调用方给的相对路径，拒绝任何离开 skill 目录的（对照 safeSkillRelPath）。 */
    static String safeSkillRelPath(String relativePath) {
        String trimmed = relativePath == null ? "" : relativePath.strip();
        if (trimmed.isEmpty()) {
            throw new Skill.SkillValidationException("skill file path is required");
        }
        if (trimmed.startsWith("/")) {
            throw new Skill.SkillValidationException("invalid skill file path: " + relativePath);
        }
        String clean = GoPath.clean(trimmed);
        if (clean.equals(".") || clean.equals("..") || clean.startsWith("../")) {
            throw new Skill.SkillValidationException("invalid skill file path: " + relativePath);
        }
        return clean;
    }

    /** 一个 skill 的压缩 zip，缓存周期内至多下载一次（对照 bundleArchive）。 */
    byte[] bundleArchive(TenantSkillEntity row) {
        if (loadBundle == null) {
            throw new Skill.SkillValidationException("skill bundles are not available in this deployment");
        }
        String key = row.getBundleSha256() == null ? "" : row.getBundleSha256().strip();
        if (key.isEmpty()) {
            key = row.getBundleRef() == null ? "" : row.getBundleRef().strip();
        }
        if (key.isEmpty()) {
            key = row.getCatalogId() == null ? "" : row.getCatalogId().strip();
        }
        if (key.isEmpty()) {
            key = row.getId() == null ? "" : row.getId().strip();
        }
        byte[] cached = cached(key);
        if (cached != null) {
            return cached;
        }
        byte[] archive;
        try {
            archive = loadBundle.apply(row);
        } catch (RuntimeException e) {
            throw new Skill.SkillValidationException("download bundle of skill " + row.getName() + ": " + e.getMessage());
        } catch (Exception e) {
            throw new Skill.SkillValidationException("download bundle of skill " + row.getName() + ": " + e.getMessage());
        }
        if (archive == null || archive.length == 0) {
            throw new Skill.SkillValidationException(
                    "skill " + row.getName() + " has no stored bundle; its files cannot be read");
        }
        store(key, archive);
        return archive;
    }

    private byte[] cached(String key) {
        synchronized (mu) {
            for (int i = 0; i < cache.size(); i++) {
                CachedBundle entry = cache.get(i);
                if (!entry.key().equals(key)) {
                    continue;
                }
                cache.remove(i);
                cache.add(0, entry);
                return entry.archive();
            }
            return null;
        }
    }

    private void store(String key, byte[] archive) {
        if (key.isEmpty() || archive.length == 0 || archive.length > MAX_BUNDLE_BYTES) {
            return;
        }
        synchronized (mu) {
            cache.removeIf(entry -> entry.key().equals(key));
            cache.add(0, new CachedBundle(key, archive));
            while (cache.size() > 1 && (cache.size() > CACHED_BUNDLE_COUNT || cachedBytes() > CACHED_BUNDLE_BYTES)) {
                cache.remove(cache.size() - 1);
            }
        }
    }

    private long cachedBytes() {
        long total = 0;
        for (CachedBundle entry : cache) {
            total += entry.archive().length;
        }
        return total;
    }

    /** SkillDirFor 的包装：非法名抛错（Go 返回 error）。 */
    private static final class SandboxPathsHelper {
        private SandboxPathsHelper() {
        }

        static String skillDirOrThrow(String skillName) {
            String dir = com.ragagent.agent.tools.SandboxPaths.skillDirFor(skillName);
            if (dir == null) {
                throw new Skill.SkillValidationException(
                        "sandbox: invalid skill name " + GoQuote.quote(skillName));
            }
            return dir;
        }
    }

    /** Go strconv.Quote（错误文案里的 %q）。 */
    static final class GoQuote {
        private GoQuote() {
        }

        static String quote(String s) {
            if (s == null) {
                return "\"\"";
            }
            StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '\r' -> sb.append("\\r");
                    default -> {
                        if (c < 0x20 || c == 0x7f) {
                            sb.append(String.format("\\x%02x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }
    }

    // ---- zip 归档读取（对照 archive/zip 的中央目录语义）----

    /** 一个 zip 条目的惰性视图（对照 zip.File）。 */
    record SkillZipEntry(String name, boolean dir, boolean symlink, int method,
            long compressedSize, long uncompressedSize, long dataOffset, byte[] archive) {

        boolean isDir() {
            return dir;
        }

        boolean isSymlink() {
            return symlink;
        }
    }

    /**
     * skill 根相对路径 → zip 条目，不膨胀文件体（对照 skillBundleFileIndex）。
     * 中央目录在原始归档上解析（含 zip64 EOCD），条目体按需解压。
     */
    static Map<String, SkillZipEntry> skillBundleFileIndex(byte[] archive) {
        List<SkillZipEntry> entries = readCentralDirectory(archive);
        if (entries.size() > MAX_BUNDLE_ZIP_ENTRIES) {
            throw new Skill.SkillValidationException("archive holds more than " + MAX_BUNDLE_ZIP_ENTRIES + " entries");
        }
        Map<String, SkillZipEntry> raw = new LinkedHashMap<>(entries.size());
        for (SkillZipEntry entry : entries) {
            if (entry.isDir() || entry.isSymlink()) {
                continue;
            }
            String name = GoPath.clean(entry.name());
            if (name.equals(".") || name.startsWith("/") || name.equals("..") || name.startsWith("../")) {
                throw new Skill.SkillValidationException("entry " + GoQuote.quote(entry.name()) + " escapes the archive root");
            }
            raw.put(name, entry);
        }
        String prefix = skillZipRootPrefix(raw);
        Map<String, SkillZipEntry> out = raw;
        if (!prefix.isEmpty()) {
            out = new LinkedHashMap<>(raw.size());
            for (Map.Entry<String, SkillZipEntry> e : raw.entrySet()) {
                if (!e.getKey().startsWith(prefix + "/")) {
                    continue;
                }
                out.put(e.getKey().substring(prefix.length() + 1), e.getValue());
            }
            if (!out.containsKey(Skill.SKILL_FILE_NAME)) {
                throw new Skill.SkillValidationException(Skill.SKILL_FILE_NAME + " is missing from the archive");
            }
        }
        if (out.size() > MAX_BUNDLE_ENTRIES) {
            throw new Skill.SkillValidationException("archive holds more than " + MAX_BUNDLE_ENTRIES + " files");
        }
        return out;
    }

    /** 对照 skillZipRootPrefix：根无 SKILL.md 时找唯一一层目录前缀。 */
    static String skillZipRootPrefix(Map<String, SkillZipEntry> raw) {
        if (raw.containsKey(Skill.SKILL_FILE_NAME)) {
            return "";
        }
        String prefix = "";
        for (String name : raw.keySet()) {
            if (!SandboxPaths.base(name).equals(Skill.SKILL_FILE_NAME)) {
                continue;
            }
            String dir = dirOf(name);
            if (dir.equals(".") || dir.contains("/")) {
                continue;
            }
            if (!prefix.isEmpty() && !prefix.equals(dir)) {
                throw new Skill.SkillValidationException("archive holds more than one skill");
            }
            prefix = dir;
        }
        if (prefix.isEmpty()) {
            throw new Skill.SkillValidationException(Skill.SKILL_FILE_NAME + " is missing from the archive");
        }
        return prefix;
    }

    /** path.Dir 的等价（GoPath 只有 base/join/clean 时补的兄弟逻辑）。 */
    static String dirOf(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        path = path.substring(0, end);
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return ".";
        }
        return GoPath.clean(path.substring(0, slash + 1));
    }

    /** 单条目限额读取（对照 readLimitedSkillZipFile）。 */
    static byte[] readLimitedSkillZipFile(SkillZipEntry entry) {
        byte[] content = inflateEntry(entry, MAX_BUNDLE_ENTRY_BYTES + 1);
        if (content.length > MAX_BUNDLE_ENTRY_BYTES) {
            throw new Skill.SkillValidationException("entry " + GoQuote.quote(entry.name()) + " is too large");
        }
        return content;
    }

    private static byte[] inflateEntry(SkillZipEntry entry, int limit) {
        int len = (int) Math.min(entry.compressedSize(), entry.archive().length - entry.dataOffset());
        byte[] compressed = new byte[len];
        System.arraycopy(entry.archive(), (int) entry.dataOffset(), compressed, 0, len);
        try {
            if (entry.method() == 0) { // stored
                int n = (int) Math.min(entry.uncompressedSize(), len);
                byte[] out = new byte[n];
                System.arraycopy(compressed, 0, out, 0, n);
                return out;
            }
            ByteArrayInputStream in = new ByteArrayInputStream(compressed);
            InflaterInputStream inflater = new InflaterInputStream(in, new java.util.zip.Inflater(true), 8192);
            byte[] out = new byte[limit];
            int total = 0;
            int read;
            while ((read = inflater.read(out, total, limit - total)) > 0) {
                total += read;
                if (total >= limit) {
                    break;
                }
            }
            inflater.close();
            byte[] result = new byte[total];
            System.arraycopy(out, 0, result, 0, total);
            return result;
        } catch (IOException e) {
            throw new Skill.SkillValidationException("cannot read " + GoQuote.quote(entry.name()) + ": " + e.getMessage());
        } catch (RuntimeException e) {
            throw new Skill.SkillValidationException("cannot read " + GoQuote.quote(entry.name()) + ": " + e.getMessage());
        }
    }

    /**
     * 解析 zip 中央目录（对照 zip.NewReader）：EOCD（含 zip64 locator/EOCD）→
     * 逐条 CEN 记录。条目体不在此解压。
     */
    static List<SkillZipEntry> readCentralDirectory(byte[] archive) {
        if (archive == null || archive.length < 22) {
            throw new Skill.SkillValidationException("not a readable zip archive: zip: not a valid zip file");
        }
        long eocd = -1;
        for (long i = archive.length - 22; i >= 0 && i >= archive.length - 22 - 65536; i--) {
            if (u32(archive, i) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new Skill.SkillValidationException("not a readable zip archive: zip: not a valid zip file");
        }
        long entryCount = u16(archive, eocd + 10);
        long cdOffset = u32(archive, eocd + 16);
        long cdSize = u32(archive, eocd + 12);
        // zip64：locator 紧贴 EOCD 之前
        if (eocd >= 20 && u32(archive, eocd - 20) == 0x07064b50L) {
            long zip64Eocd = u64(archive, eocd - 20 + 8);
            if (zip64Eocd >= 0 && zip64Eocd + 56 <= archive.length && u32(archive, zip64Eocd) == 0x06064b50L) {
                entryCount = u64(archive, zip64Eocd + 32);
                cdSize = u64(archive, zip64Eocd + 40);
                cdOffset = u64(archive, zip64Eocd + 48);
            }
        }
        List<SkillZipEntry> entries = new ArrayList<>((int) Math.min(entryCount, Integer.MAX_VALUE));
        long pos = cdOffset;
        for (long i = 0; i < entryCount; i++) {
            if (pos + 46 > archive.length || u32(archive, pos) != 0x02014b50L) {
                throw new Skill.SkillValidationException("not a readable zip archive: zip: not a valid zip file");
            }
            int method = u16(archive, pos + 10);
            long compressedSize = u32(archive, pos + 20);
            long uncompressedSize = u32(archive, pos + 24);
            int nameLen = u16(archive, pos + 28);
            int extraLen = u16(archive, pos + 30);
            int commentLen = u16(archive, pos + 32);
            long externalAttrs = u32(archive, pos + 38);
            long localOffset = u32(archive, pos + 42);
            if (pos + 46 + nameLen > archive.length) {
                throw new Skill.SkillValidationException("not a readable zip archive: zip: not a valid zip file");
            }
            String name = new String(archive, (int) (pos + 46), nameLen, StandardCharsets.UTF_8);
            // zip64 extra 字段：超大尺寸/偏移从 extra 里取（本包场景极罕见，防御性支持尺寸）
            int extraStart = (int) (pos + 46 + nameLen);
            int extraEnd = extraStart + extraLen;
            int p = extraStart;
            while (p + 4 <= extraEnd) {
                int headerId = u16(archive, p);
                int dataSize = u16(archive, p + 2);
                if (headerId == 0x0001) {
                    int q = p + 4;
                    if (uncompressedSize == 0xFFFFFFFFL && q + 8 <= extraEnd) {
                        uncompressedSize = u64(archive, q);
                        q += 8;
                    }
                    if (compressedSize == 0xFFFFFFFFL && q + 8 <= extraEnd) {
                        compressedSize = u64(archive, q);
                        q += 8;
                    }
                    if (localOffset == 0xFFFFFFFFL && q + 8 <= extraEnd) {
                        localOffset = u64(archive, q);
                        q += 8;
                    }
                }
                p += 4 + dataSize;
            }
            // 目录/symlink 判定（对照 zip.FileInfo + Mode）：unix 模式在高 16 位
            int unixMode = (int) ((externalAttrs >> 16) & 0xFFFF);
            boolean isDir = name.endsWith("/")
                    || (unixMode & 0xF000) == 0x4000
                    || (unixMode == 0 && (externalAttrs & 0x10) != 0);
            boolean isSymlink = (unixMode & 0xF000) == 0xA000;
            long dataOffset = -1;
            if (localOffset + 30 <= archive.length && u32(archive, localOffset) == 0x04034b50L) {
                int localNameLen = u16(archive, localOffset + 26);
                int localExtraLen = u16(archive, localOffset + 28);
                dataOffset = localOffset + 30 + localNameLen + localExtraLen;
            }
            entries.add(new SkillZipEntry(name, isDir, isSymlink, method, compressedSize, uncompressedSize, dataOffset, archive));
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return entries;
    }

    private static int u16(byte[] b, long off) {
        return (b[(int) off] & 0xFF) | ((b[(int) (off + 1)] & 0xFF) << 8);
    }

    private static long u32(byte[] b, long off) {
        return (b[(int) off] & 0xFFL) | ((b[(int) (off + 1)] & 0xFFL) << 8)
                | ((b[(int) (off + 2)] & 0xFFL) << 16) | ((b[(int) (off + 3)] & 0xFFL) << 24);
    }

    private static long u64(byte[] b, long off) {
        return (u32(b, off) & 0xFFFFFFFFL) | (u32(b, off + 4) << 32);
    }
}
