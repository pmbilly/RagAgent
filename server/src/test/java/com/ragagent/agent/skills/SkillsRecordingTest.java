package com.ragagent.agent.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.ragagent.modelcontext.GoRecording46A.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.skills.Manager.ManagerConfig;
import com.ragagent.agent.tools.RemoteStatEntry;
import com.ragagent.sandbox.domain.SkillStatus;
import com.ragagent.sandbox.domain.TenantSkillEntity;

/**
 * 4.6a agent/skills 包的 Go 实录回放（探针见 /tmp/toolrec46a/internal/agent/skills/
 * zz_rec46a_sk_test.go；常量见 com.ragagent.modelcontext.GoRecording46A）。
 * loader/manager 组的宿主目录在录制时是 macOS 临时目录：结构断言对根前缀做掩码，
 * 其余（zip 限额、staging digest、错误文案）全部字节断言。
 */
class SkillsRecordingTest {

    private static JsonNode rec(String constant) {
        return com.ragagent.modelcontext.GoRecording46A.rec(constant);
    }

    private static String out(String constant) {
        return rec(constant).get("out").asText();
    }

    private static String errOf(String constant) {
        JsonNode e = rec(constant).get("err");
        return e == null || e.isNull() ? "" : e.asText();
    }

    private static List<String> strs(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        for (JsonNode n : node) {
            out.add(n.isNull() ? null : n.asText());
        }
        return out;
    }

    /** 录制时的 macOS 临时根掩码：取路径尾段（相对结构）。 */
    private static String maskTemp(String recordedBase) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/T/[^/]+/\\d+(/.*)$").matcher(recordedBase);
        return m.find() ? m.group(1) : recordedBase;
    }

    /** 把 Go %v 的 []string 形态 "[a b c]" 拆开。 */
    private static List<String> goSlice(String s) {
        if (s == null || s.equals("[]")) {
            return List.of();
        }
        return List.of(s.substring(1, s.length() - 1).split(" "));
    }

    private static TenantSkillEntity row(String id, String name, String desc, String instructions,
            boolean enabled, String status, String sha, String ref, String catalog) {
        TenantSkillEntity row = new TenantSkillEntity();
        row.setId(id);
        row.setName(name);
        row.setDescription(desc);
        row.setInstructions(instructions);
        row.setEnabled(enabled);
        row.setStatus(status);
        row.setBundleSha256(sha);
        row.setBundleRef(ref);
        row.setCatalogId(catalog);
        return row;
    }

    // ---- frontmatter / skill_helpers ----

    private Map<String, Object> parse(String content) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Skill skill = Skill.parseSkillFile(content);
            out.put("name", skill.name);
            out.put("description", skill.description);
            out.put("slug", skill.slug);
            out.put("instructions", skill.instructions);
            out.put("loaded", skill.loaded);
            out.put("repaired", skill.frontmatterRepaired);
        } catch (RuntimeException e) {
            out.put("err", e.getMessage());
        }
        return out;
    }

    private void assertFrontmatter(String constant, String content) {
        JsonNode expected = rec(constant).get("out");
        Map<String, Object> actual = parse(content);
        if (expected.has("err") && !expected.get("err").isNull()) {
            assertThat(actual).containsEntry("err", expected.get("err").asText());
            return;
        }
        assertThat(actual.get("name")).isEqualTo(expected.get("name").asText());
        assertThat(actual.get("description")).isEqualTo(expected.get("description").asText());
        assertThat(actual.get("slug")).isEqualTo(expected.get("slug").asText());
        assertThat(actual.get("instructions")).isEqualTo(expected.get("instructions").asText());
        assertThat(actual.get("loaded")).isEqualTo(expected.get("loaded").asBoolean());
        assertThat(actual.get("repaired")).isEqualTo(expected.get("repaired").asBoolean());
    }

    @Test
    void frontmatterMatrix() {
        assertFrontmatter(R_FRONTMATTER_VALID, "---\nname: pdf-tools\ndescription: PDF helpers\n---\n\n# Body\n\nStep one.\n");
        assertFrontmatter(R_FRONTMATTER_BODY_TRIM, "---\nname: trim-test\ndescription: d\n---\n\n\nbody line\n\n\n");
        assertFrontmatter(R_FRONTMATTER_SLUG_PREF, "---\nname: Word / DOCX\nslug: word-docx\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_SLUGIFY, "---\nname: Word / DOCX\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_CJK_NAME, "---\nname: 律师助手\ndescription: 律师工作辅助\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_RESERVED, "---\nname: my-claude\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_NAME_TOO_LONG, "---\nname: " + "a".repeat(65) + "\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_NAME_MAX_OK, "---\nname: " + "a".repeat(64) + "\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_XML_NAME, "---\nname: <script>x</script>\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_XML_DESC, "---\nname: ok-name\ndescription: has <b>bold</b> tag\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_DESC_TOO_LONG, "---\nname: ok-name\ndescription: " + "d".repeat(1025) + "\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_REPAIR_NESTED, "---\nname: nested-skill\n  version: 1.2.6\n  description: |\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_REPAIR_COLON, "---\nname: colon-skill\ndescription: Use it: with care\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_REPAIR_BOTH, "---\nname: both-skill\n  version: 2.0\n  description: A: B\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_BOM, "\uFEFF---\nname: bom-skill\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_NO_FRONTMATTER, "just text\n");
        assertFrontmatter(R_FRONTMATTER_UNCLOSED, "---\nname: x\ndescription: d\n");
        assertFrontmatter(R_FRONTMATTER_MISSING_NAME, "---\ndescription: d\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_MISSING_DESC, "---\nname: ok-name\n---\nbody\n");
        assertFrontmatter(R_FRONTMATTER_CRLF, "---\r\nname: crlf-skill\r\ndescription: d\r\n---\r\nbody line\r\n");

        JsonNode meta = rec(R_FRONTMATTER_METADATA_ONLY).get("out");
        Skill.SkillMetadata m = Skill.parseSkillMetadata("---\nname: meta-only\ndescription: md\n---\nbody\n");
        assertThat(m.name()).isEqualTo(meta.get("name").asText());
        assertThat(m.description()).isEqualTo(meta.get("description").asText());
        assertThat(m.basePath()).isEqualTo(meta.get("base").asText());
    }

    @Test
    void skillHelpers() {
        JsonNode isScript = rec(R_SKILL_HELPERS_IS_SCRIPT).get("out");
        isScript.fields().forEachRemaining(e -> {
            String path = e.getKey().equals(".") ? "noext" : "x" + e.getKey();
            if (e.getKey().equals("a/b.py") || e.getKey().equals("x.sh")) {
                path = e.getKey();
            }
            assertThat(Skill.isScript(path)).as("isScript %s", e.getKey()).isEqualTo(e.getValue().asBoolean());
        });
        JsonNode lang = rec(R_SKILL_HELPERS_SCRIPT_LANGUAGE).get("out");
        lang.fields().forEachRemaining(e ->
                assertThat(Skill.getScriptLanguage(e.getKey())).as("lang %s", e.getKey()).isEqualTo(e.getValue().asText()));
        JsonNode installer = rec(R_SKILL_HELPERS_INSTALLER_PATH).get("out");
        installer.fields().forEachRemaining(e ->
                assertThat(Skill.isOnDemandInstallerPath(e.getKey())).as("installer %s", e.getKey())
                        .isEqualTo(e.getValue().asBoolean()));
    }

    // ---- zip_limits ----

    private static byte[] zipOf(Map<String, String> files, List<String> dirs, Map<String, String> symlinks) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            if (dirs != null) {
                for (String d : dirs) {
                    zos.putNextEntry(new ZipEntry(d));
                    zos.closeEntry();
                }
            }
            if (symlinks != null) {
                for (Map.Entry<String, String> e : symlinks.entrySet()) {
                    ZipEntry entry = new ZipEntry(e.getValue());
                    entry.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(entry);
                    zos.write(e.getKey().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            if (files != null) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        }
        byte[] zip = buf.toByteArray();
        if (symlinks != null) {
            for (String target : symlinks.keySet()) {
                // Go 用 SetMode(os.ModeSymlink|0777) 写 CEN external attrs；
                // Java ZipOutputStream 不支持，测试侧补丁到同样的 unix 模式位
                zip = patchExternalAttrs(zip, symlinks.get(target), 0xA1FF0000L);
            }
        }
        return zip;
    }

    /** 在 CEN 记录里给指定条目写 external attrs（只服务 symlink 用例）。 */
    private static byte[] patchExternalAttrs(byte[] zip, String entryName, long attrs) {
        long eocd = -1;
        for (long i = zip.length - 22; i >= 0 && i >= zip.length - 22 - 65536; i--) {
            if (u32(zip, i) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        long count = u16(zip, eocd + 10);
        long pos = u32(zip, eocd + 16);
        for (long i = 0; i < count; i++) {
            int nameLen = u16(zip, pos + 28);
            String name = new String(zip, (int) (pos + 46), nameLen, StandardCharsets.UTF_8);
            if (name.equals(entryName)) {
                zip[(int) (pos + 38)] = (byte) (attrs & 0xFF);
                zip[(int) (pos + 39)] = (byte) ((attrs >> 8) & 0xFF);
                zip[(int) (pos + 40)] = (byte) ((attrs >> 16) & 0xFF);
                zip[(int) (pos + 41)] = (byte) ((attrs >> 24) & 0xFF);
                return zip;
            }
            pos += 46 + nameLen + u16(zip, pos + 30) + u16(zip, pos + 32);
        }
        throw new IllegalStateException("entry not found: " + entryName);
    }

    private static int u16(byte[] b, long off) {
        return (b[(int) off] & 0xFF) | ((b[(int) (off + 1)] & 0xFF) << 8);
    }

    private static long u32(byte[] b, long off) {
        return (b[(int) off] & 0xFFL) | ((b[(int) (off + 1)] & 0xFFL) << 8)
                | ((b[(int) (off + 2)] & 0xFFL) << 16) | ((b[(int) (off + 3)] & 0xFFL) << 24);
    }

    private static void assertZipIndex(String constant, byte[] archive) {
        JsonNode expected = rec(constant).get("out");
        if (expected == null || expected.get("err") != null) {
            assertThatThrownBy(() -> TenantSkillSource.skillBundleFileIndex(archive))
                    .isInstanceOf(Skill.SkillValidationException.class)
                    .hasMessage(expected.get("err").asText());
            return;
        }
        Map<String, TenantSkillSource.SkillZipEntry> index = TenantSkillSource.skillBundleFileIndex(archive);
        assertThat(index.size()).isEqualTo(expected.get("count").asInt());
        assertThat(index.keySet().stream().sorted().toList()).containsExactlyElementsOf(strs(expected.get("names")));
    }

    @Test
    void zipLimitsStructural() throws Exception {
        assertZipIndex(R_ZIP_LIMITS_FLAT, zipOf(Map.of("SKILL.md", "# s", "scripts/run.py", "print(1)", "a/b.txt", "x"), null, null));
        assertZipIndex(R_ZIP_LIMITS_WRAPPED, zipOf(Map.of("myskill/SKILL.md", "# s", "myskill/ref.md", "r"), null, null));
        assertZipIndex(R_ZIP_LIMITS_MISSING_SKILLMD, zipOf(Map.of("x.txt", "x", "sub/y.txt", "y"), null, null));
        assertZipIndex(R_ZIP_LIMITS_NESTED_SKILLMD, zipOf(Map.of("x/y/SKILL.md", "# s"), null, null));
        assertZipIndex(R_ZIP_LIMITS_TWO_SKILLS, zipOf(Map.of("a/SKILL.md", "1", "b/SKILL.md", "2"), null, null));
        assertZipIndex(R_ZIP_LIMITS_ESCAPE_ENTRY, zipOf(Map.of("../evil.txt", "e", "SKILL.md", "1"), null, null));
        assertZipIndex(R_ZIP_LIMITS_DIRS_SYMLINKS,
                zipOf(Map.of("SKILL.md", "1", "scripts/run.py", "p"), List.of("docs/", "scripts/"),
                        Map.of("../../outside", "link.md")));
        assertZipIndex(R_ZIP_LIMITS_CLEAN_COLLISION,
                zipOf(Map.of("SKILL.md", "1", "a/b.txt", "first", "a/./b.txt", "second"), null, null));
        assertZipIndex(R_ZIP_LIMITS_NOT_ZIP, "definitely not a zip".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void zipLimitsEntryCap() throws Exception {
        Map<String, String> big = new LinkedHashMap<>();
        big.put("SKILL.md", "1");
        for (int i = 0; i < 100_000; i++) {
            big.put(String.format("f/%06d.txt", i), "x");
        }
        assertZipIndex(R_ZIP_LIMITS_ENTRY_CAP, zipOf(big, null, null));
    }

    @Test
    void zipLimitsFileCap() throws Exception {
        Map<String, String> many = new LinkedHashMap<>();
        many.put("SKILL.md", "1");
        for (int i = 0; i < 20_001; i++) {
            many.put(String.format("g%06d.txt", i), "y");
        }
        assertZipIndex(R_ZIP_LIMITS_FILE_CAP, zipOf(many, null, null));
    }

    @Test
    void zipLimitsEntrySizes() throws Exception {
        byte[] huge = zipOf(Map.of("SKILL.md", "1", "big.bin", "a".repeat(33 << 20)), null, null);
        Map<String, TenantSkillSource.SkillZipEntry> index = TenantSkillSource.skillBundleFileIndex(huge);
        JsonNode tooLarge = rec(R_ZIP_LIMITS_ENTRY_TOO_LARGE).get("out");
        assertThatThrownBy(() -> TenantSkillSource.readLimitedSkillZipFile(index.get("big.bin")))
                .isInstanceOf(Skill.SkillValidationException.class)
                .hasMessage(tooLarge.get("err").asText());
        JsonNode ok = rec(R_ZIP_LIMITS_ENTRY_OK).get("out");
        byte[] content = TenantSkillSource.readLimitedSkillZipFile(index.get("SKILL.md"));
        assertThat(String.valueOf(content.length)).isEqualTo(ok.get("len").asText());
    }

    // ---- tenant_source ----

    @Test
    void tenantSourceLifecycle() throws Exception {
        List<TenantSkillEntity> rows = List.of(
                row("id1", "alpha", "Alpha desc", "Alpha body", true, SkillStatus.READY, "sha-a", "", ""),
                row("id2", "beta", "Beta desc", "Beta body", true, SkillStatus.READY, "", "ref-b", ""),
                row("id3", "gamma", "disabled", "x", false, SkillStatus.READY, "", "", ""),
                row("id4", "delta", "failed", "x", true, SkillStatus.FAILED, "", "", ""),
                row("id5", "../evil", "bad name", "x", true, SkillStatus.READY, "", "", ""),
                row("id6", "alpha", "dup alpha", "dup body", true, SkillStatus.READY, "sha-dup", "", ""),
                row("id7", "eps", "", "", false, "", "", "", ""));
        int[] loads = {0};
        byte[] arc = zipOf(Map.of("SKILL.md", "z", "ref.md", "reference here", "sub/deep.txt", "deep",
                "big.bin", "b".repeat(10)), null, null);
        TenantSkillSource src = new TenantSkillSource(rows, row -> {
            loads[0]++;
            return arc;
        });

        List<Skill.SkillMetadata> metadata = src.discoverSkills();
        JsonNode discover = rec(R_TENANT_SOURCE_DISCOVER).get("out");
        assertThat(metadata.size()).isEqualTo(discover.size());
        for (int i = 0; i < metadata.size(); i++) {
            assertThat(metadata.get(i).name()).isEqualTo(discover.get(i).get("name").asText());
            assertThat(metadata.get(i).description()).isEqualTo(discover.get(i).get("description").asText());
            assertThat(metadata.get(i).basePath()).isEqualTo(discover.get(i).get("base").asText());
        }

        Skill skill = src.loadSkillInstructions("alpha");
        JsonNode loadInstructions = rec(R_TENANT_SOURCE_LOAD_INSTRUCTIONS).get("out");
        assertThat(skill.name).isEqualTo(loadInstructions.get("name").asText());
        assertThat(skill.description).isEqualTo(loadInstructions.get("description").asText());
        assertThat(skill.basePath).isEqualTo(loadInstructions.get("base").asText());
        assertThat(skill.filePath).isEqualTo(loadInstructions.get("path").asText());
        assertThat(skill.instructions).isEqualTo(loadInstructions.get("instructions").asText());
        assertThat(skill.loaded).isEqualTo(loadInstructions.get("loaded").asBoolean());

        Skill.SkillFile file = src.loadSkillFile("alpha", "ref.md");
        JsonNode loadFile = rec(R_TENANT_SOURCE_LOAD_FILE).get("out");
        assertThat(file.name()).isEqualTo(loadFile.get("name").asText());
        assertThat(file.path()).isEqualTo(loadFile.get("path").asText());
        assertThat(file.content()).isEqualTo(loadFile.get("content").asText());
        assertThat(file.script()).isEqualTo(loadFile.get("is_script").asBoolean());

        Skill.SkillFile nested = src.loadSkillFile("alpha", "sub/deep.txt");
        JsonNode nestedExp = rec(R_TENANT_SOURCE_LOAD_FILE_NESTED).get("out");
        assertThat(nested.path()).isEqualTo(nestedExp.get("path").asText());
        assertThat(nested.content()).isEqualTo(nestedExp.get("content").asText());

        assertThatThrownBy(() -> src.loadSkillFile("alpha", "missing.md"))
                .hasMessage(errShape(R_TENANT_SOURCE_LOAD_FILE_MISSING));
        assertThatThrownBy(() -> src.loadSkillFile("alpha", "../x"))
                .hasMessage(errShape(R_TENANT_SOURCE_LOAD_FILE_ESCAPE));
        assertThatThrownBy(() -> src.loadSkillFile("alpha", ""))
                .hasMessage(errShape(R_TENANT_SOURCE_LOAD_FILE_EMPTY));
        assertThatThrownBy(() -> src.loadSkillFile("nope", "x.md"))
                .hasMessage(errShape(R_TENANT_SOURCE_LOAD_FILE_NO_SKILL));

        assertThat(src.listSkillFiles("alpha"))
                .containsExactlyElementsOf(strs(rec(R_TENANT_SOURCE_LIST_FILES).get("out")));
        assertThat(loads[0]).isEqualTo(rec(R_TENANT_SOURCE_LOAD_BUNDLE_CALLS).get("out").asInt());

        TenantSkillSource fail = new TenantSkillSource(rows.subList(0, 1), row -> {
            throw new RuntimeException("storage down");
        });
        assertThatThrownBy(() -> fail.loadSkillFile("alpha", "ref.md"))
                .hasMessage(errShape(R_TENANT_SOURCE_BUNDLE_FAIL));
        TenantSkillSource empty = new TenantSkillSource(rows.subList(0, 1), row -> new byte[0]);
        assertThatThrownBy(() -> empty.loadSkillFile("alpha", "ref.md"))
                .hasMessage(errShape(R_TENANT_SOURCE_BUNDLE_EMPTY));
        TenantSkillSource nofetch = new TenantSkillSource(rows.subList(0, 1), null);
        assertThatThrownBy(() -> nofetch.loadSkillFile("alpha", "ref.md"))
                .hasMessage(errShape(R_TENANT_SOURCE_BUNDLE_NIL));
        assertThatThrownBy(() -> nofetch.listSkillFiles("alpha"))
                .hasMessage(errShape(R_TENANT_SOURCE_LIST_NO_BUNDLE));

        String rp = src.remoteScriptPath("alpha", "scripts/run.py");
        JsonNode scriptPath = rec(R_TENANT_SOURCE_REMOTE_SCRIPT_PATH).get("out");
        assertThat(rp).isEqualTo(scriptPath.get("path").asText());
        assertThat(scriptPath.get("err").asText()).isEmpty();
        assertThatThrownBy(() -> src.remoteScriptPath("alpha", "../x"))
                .hasMessage(rec(R_TENANT_SOURCE_REMOTE_SCRIPT_PATH_ESCAPE).get("out").get("err").asText());
    }

    @Test
    void tenantSourceLruCache() throws Exception {
        Map<String, Integer> calls = new LinkedHashMap<>();
        byte[] arc = zipOf(Map.of("SKILL.md", "z"), null, null);
        List<TenantSkillEntity> rows = new ArrayList<>();
        for (String k : List.of("k1", "k2", "k3", "k4", "k5")) {
            rows.add(row(k, "s" + k.substring(1), "", "", true, SkillStatus.READY, k, "", ""));
        }
        java.util.function.Function<TenantSkillEntity, byte[]> loader = row -> {
            calls.merge(row.getBundleSha256(), 1, Integer::sum);
            return arc;
        };
        // 探针先用一个独立单行 source 访问了一次 k1（共享同一 calls 计数）
        TenantSkillSource single = new TenantSkillSource(rows.subList(0, 1), loader);
        single.listSkillFiles("s1");
        TenantSkillSource five = new TenantSkillSource(rows, loader);
        for (String n : List.of("s1", "s2", "s3", "s4", "s5")) {
            five.listSkillFiles(n);
        }
        five.listSkillFiles("s1"); // 容量 4，s1 被逐出 → 重新下载
        JsonNode lru = rec(R_TENANT_SOURCE_LRU_CALLS).get("out");
        for (String k : List.of("k1", "k2", "k3", "k4", "k5")) {
            assertThat(calls.getOrDefault(k, 0)).as("lru %s", k).isEqualTo(lru.get(k).asInt());
        }
    }

    /** fileOrErr 形态的 err 字段（{"err": ...}）或空串。 */
    private static String errShape(String constant) {
        JsonNode node = rec(constant).get("out");
        if (node != null && node.get("err") != null) {
            return node.get("err").asText();
        }
        return errOf(constant);
    }

    // ---- loader ----

    private static void writeSkillDir(Path root, String name, String desc, String body,
            Map<String, String> extra) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + desc + "\n---\n" + body);
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                Path p = dir.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue());
            }
        }
    }

    private void assertMetadataList(String constant, List<Skill.SkillMetadata> metadata, Path root) {
        JsonNode expected = rec(constant).get("out");
        if (expected.has("err") && !expected.get("err").isNull()) {
            throw new AssertionError("unexpected err: " + expected.get("err").asText());
        }
        assertThat(metadata.size()).isEqualTo(expected.size());
        for (int i = 0; i < metadata.size(); i++) {
            assertThat(metadata.get(i).name()).isEqualTo(expected.get(i).get("name").asText());
            assertThat(metadata.get(i).description()).isEqualTo(expected.get(i).get("description").asText());
            // 宿主目录前缀是录制机器的临时路径；尾段结构必须一致
            assertThat(metadata.get(i).basePath()).endsWith(maskTemp(expected.get(i).get("base").asText()));
        }
    }

    @Test
    void loaderMatrix(@TempDir Path root) throws Exception {
        writeSkillDir(root, "beta-skill", "Beta", "Beta body.",
                Map.of("scripts/run.py", "print(2)", "docs/x.md", "dx"));
        writeSkillDir(root, "alpha-skill", "Alpha", "Alpha body.", null);
        writeSkillDir(root, "broken-skill", "Broken", "body", null);
        Files.writeString(root.resolve("broken-skill").resolve("SKILL.md"), "no frontmatter");
        Files.createDirectories(root.resolve("not-a-skill"));
        Files.writeString(root.resolve("loose.txt"), "x");

        Loader loader = new Loader(List.of(root.toString()));
        assertMetadataList(R_LOADER_DISCOVER, loader.discoverSkills(), root);

        Skill skill = loader.loadSkillInstructions("beta-skill");
        JsonNode loadByDir = rec(R_LOADER_LOAD_BY_DIR).get("out");
        assertThat(skill.name).isEqualTo(loadByDir.get("name").asText());
        assertThat(skill.instructions).isEqualTo(loadByDir.get("instructions").asText());
        assertThat(skill.loaded).isEqualTo(loadByDir.get("loaded").asBoolean());

        // 目录名 ≠ skill 名：按内容 name 命中
        Path diffDir = root.resolve("dir-name");
        Files.createDirectories(diffDir);
        Files.writeString(diffDir.resolve("SKILL.md"),
                "---\nname: content-name\ndescription: desc-content-name\n---\nBody here.");
        Skill byName = loader.loadSkillInstructions("content-name");
        JsonNode loadByName = rec(R_LOADER_LOAD_BY_NAME).get("out");
        assertThat(byName.name).isEqualTo(loadByName.get("name").asText());
        assertThat(byName.basePath).endsWith(maskTemp(loadByName.get("base").asText()));

        assertThatThrownBy(() -> loader.loadSkillInstructions("missing-skill"))
                .hasMessage(rec(R_LOADER_LOAD_MISSING).get("out").get("err").asText());

        Skill.SkillFile file = loader.loadSkillFile("beta-skill", "scripts/run.py");
        JsonNode loadFile = rec(R_LOADER_LOAD_FILE).get("out");
        assertThat(file.name()).isEqualTo(loadFile.get("name").asText());
        assertThat(file.content()).isEqualTo(loadFile.get("content").asText());
        assertThat(file.script()).isEqualTo(loadFile.get("is_script").asBoolean());
        assertThatThrownBy(() -> loader.loadSkillFile("beta-skill", "../escape.txt"))
                .hasMessage(rec(R_LOADER_LOAD_FILE_ESCAPE).get("out").get("err").asText());
        assertThatThrownBy(() -> loader.loadSkillFile("beta-skill", "/abs/path.txt"))
                .hasMessage(rec(R_LOADER_LOAD_FILE_ABS).get("out").get("err").asText());

        assertThat(loader.listSkillFiles("beta-skill"))
                .containsExactlyElementsOf(strs(rec(R_LOADER_LIST_FILES).get("out")));

        Skill cached = loader.getSkillByName("beta-skill");
        JsonNode getCached = rec(R_LOADER_GET_CACHED).get("out");
        assertThat(getCached.get("ok").asBoolean()).isTrue();
        assertThat(cached.name).isEqualTo(getCached.get("name").asText());

        String basePath = loader.getSkillBasePath("alpha-skill");
        assertThat(basePath).endsWith(maskTemp(rec(R_LOADER_BASE_PATH).get("out").get("path").asText()));

        List<Skill.SkillMetadata> reloaded = loader.reload();
        assertThat(reloaded.size()).isEqualTo(rec(R_LOADER_RELOAD).get("out").asInt());

        Loader missing = new Loader(List.of(root.resolve("does-not-exist").toString()));
        JsonNode missingRec = rec(R_LOADER_MISSING_DIR).get("out");
        assertThat(missing.discoverSkills().size()).isEqualTo(missingRec.get("count").asInt());
    }

    // ---- shell_staging + env + manager ----

    /** Go 探针的 fakeStore（会话文件系统的内存实现）。 */
    static final class FakeStore implements Manager.SessionFileStore {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        int writes;

        @Override
        public RemoteStatEntry statSessionFile(String sessionId, String path) {
            byte[] content = files.get(path);
            return content == null ? null
                    : RemoteStatEntry.of(path, "file", content.length, java.time.Instant.EPOCH);
        }

        @Override
        public byte[] readSessionFile(String sessionId, String path) {
            byte[] content = files.get(path);
            if (content == null) {
                throw new RuntimeException("not found: " + path);
            }
            return content;
        }

        @Override
        public void writeSessionWorkspaceFiles(String sessionId, List<Manager.StagedFile> batch) {
            writes++;
            for (Manager.StagedFile f : batch) {
                files.put(f.path(), f.content());
            }
        }
    }

    static final class FakeGateway implements Manager.SandboxGateway {
        final FakeStore store;

        FakeGateway(FakeStore store) {
            this.store = store;
        }

        @Override
        public Manager.SessionFileStore sessionFileStore() {
            return store;
        }

        @Override
        public void cleanup() {
        }
    }

    @Test
    void shellStagingMatrix(@TempDir Path root) throws Exception {
        writeSkillDir(root, "stage-me", "Staged", "Staged body.", Map.of(
                "scripts/run.py", "print('run')",
                "assets/data.txt", "12345",
                ".venv/lib/ignored.py", "ignored",
                "node_modules/pkg/x.js", "ignored",
                ".git/config", "ignored",
                "sub/__pycache__/a.pyc", "ignored"));
        FakeStore store = new FakeStore();
        Manager mgr = new Manager(new ManagerConfig(List.of(root.toString()), List.of(), true), new FakeGateway(store));
        mgr.initialize();

        String dir = mgr.stageShellSkill("sess-1", "stage-me");
        JsonNode stage = rec(R_SHELL_STAGING_STAGE).get("out");
        assertThat(stage.get("err").asText()).isEmpty();
        assertThat(dir).isEqualTo(stage.get("dir").asText());

        assertThat(store.files.keySet().stream().sorted().toList())
                .containsExactlyElementsOf(strs(rec(R_SHELL_STAGING_STAGED_FILES).get("out")));
        assertThat(store.writes).isEqualTo(rec(R_SHELL_STAGING_BATCH_WRITES).get("out").asInt());

        int before = store.writes;
        String dir2 = mgr.stageShellSkill("sess-1", "stage-me");
        JsonNode reuse = rec(R_SHELL_STAGING_REUSE).get("out");
        assertThat(dir2).isEqualTo(reuse.get("dir").asText());
        assertThat(dir2.equals(dir)).isEqualTo(reuse.get("same").asBoolean());
        assertThat(store.writes - before).isEqualTo(reuse.get("new_writes").asInt());

        assertThatThrownBy(() -> mgr.stageShellSkill("sess-1", "not-listed"))
                .hasMessage(rec(R_SHELL_STAGING_UNLISTED).get("out").get("err").asText());
        assertThatThrownBy(() -> mgr.stageShellSkill("", "stage-me"))
                .hasMessage(rec(R_SHELL_STAGING_NO_SESSION).get("out").get("err").asText());

        boolean intact = mgr.stagedSkillStillIntact(store, "sess-1", "stage-me", dir);
        JsonNode intactRec = rec(R_SHELL_STAGING_INTACT).get("out");
        assertThat(intact).isEqualTo(intactRec.get("ok").asBoolean());
        assertThat(intactRec.get("err").asText()).isEmpty();

        store.files.put(dir + "/SKILL.md", "tampered".getBytes(StandardCharsets.UTF_8));
        boolean intact2 = mgr.stagedSkillStillIntact(store, "sess-1", "stage-me", dir);
        JsonNode intactMut = rec(R_SHELL_STAGING_INTACT_AFTER_MUTATE).get("out");
        assertThat(intact2).isEqualTo(intactMut.get("ok").asBoolean());
        assertThat(intactMut.get("err").asText()).isEmpty();

        JsonNode skip = rec(R_SHELL_STAGING_SKIP_REL).get("out");
        assertThat(Manager.skipStagedSkillRel(".venv/x")).isEqualTo(skip.get(".venv/x").asBoolean());
        assertThat(Manager.skipStagedSkillRel("a/node_modules/b")).isEqualTo(skip.get("a/node_modules/b").asBoolean());
        assertThat(Manager.skipStagedSkillRel("x/.git/y")).isEqualTo(skip.get("x/.git/y").asBoolean());
        assertThat(Manager.skipStagedSkillRel("p/__pycache__/q.pyc")).isEqualTo(skip.get("p/__pycache__/q.pyc").asBoolean());
        assertThat(Manager.skipStagedSkillRel("src/main.py")).isEqualTo(skip.get("src/main.py").asBoolean());
        assertThat(Manager.skipStagedSkillRel("venvx/y")).isEqualTo(skip.get("venvx/y").asBoolean());

        // 文件数上限：1001 个保留文件
        Path root2 = Files.createTempDirectory("stage-cap");
        Map<String, String> extra = new LinkedHashMap<>();
        for (int i = 0; i < 1001; i++) {
            extra.put(String.format("f%04d.txt", i), "x");
        }
        writeSkillDir(root2, "too-many", "Many", "b", extra);
        Manager mgr2 = new Manager(new ManagerConfig(List.of(root2.toString()), List.of(), true),
                new FakeGateway(new FakeStore()));
        mgr2.initialize();
        assertThatThrownBy(() -> mgr2.stageShellSkill("s", "too-many"))
                .hasMessage(rec(R_SHELL_STAGING_FILE_CAP).get("out").get("err").asText());
    }

    @Test
    void envMatrix(@TempDir Path root) throws Exception {
        Map<String, String> env = new LinkedHashMap<>(Map.of("A", "1", "NODE_PATH", "/existing"));
        SkillEnvResolver.applyResolvedEnv(env, Map.of("B", "2", "A", "override", "NODE_PATH", "nope"));
        JsonNode apply = rec(R_ENV_APPLY).get("out");
        assertThat(env.get("A")).isEqualTo(apply.get("A").asText());
        assertThat(env.get("B")).isEqualTo(apply.get("B").asText());
        assertThat(env.get("NODE_PATH")).isEqualTo(apply.get("NODE_PATH").asText());

        Map<String, String> np = new LinkedHashMap<>(Map.of("NODE_PATH", "/first"));
        SkillEnvResolver.applySkillNodePath(np, "/opt/weknora/tenant/skills/pdf");
        Map<String, String> npEmpty = new LinkedHashMap<>();
        SkillEnvResolver.applySkillNodePath(npEmpty, "/opt/weknora/tenant/skills/pdf");
        JsonNode nodePath = rec(R_ENV_NODE_PATH).get("out");
        assertThat(np.get("NODE_PATH")).isEqualTo(nodePath.get("existing").asText());
        assertThat(npEmpty.get("NODE_PATH")).isEqualTo(nodePath.get("fresh").asText());

        SkillEnvResolver.MissingSkillEnvError e =
                new SkillEnvResolver.MissingSkillEnvError("pdf-tools", List.of("API_KEY", "ENDPOINT"));
        assertThat(e.getMessage()).isEqualTo(out(R_ENV_MISSING_ERROR));

        // 已安装镜像 skill 的 PrepareShellEnvironment
        byte[] arc = zipOf(Map.of("SKILL.md", "z", "scripts/run.py", "p"), null, null);
        TenantSkillSource ts = new TenantSkillSource(
                List.of(row("tid", "img-skill", "Installed", "Installed body", true, SkillStatus.READY, "tsha", "", "")),
                row2 -> arc);
        Manager mgr = new Manager(
                new ManagerConfig(List.of(root.toString()), List.of("img-skill"), true), null)
                .withTenantSource(ts);
        mgr.initialize();
        // 探针先 Setenv WEKNORA_SKILL_OUTPUT_DIR=/workspace/custom-out；Java 测试不能
        // setenv，用注入环境值的解析核心断言同一解析（见 artifact_dir_override 组）
        Manager.PreparedShell prepared = mgr.prepareShellEnvironment(
                "sess-9", "img-skill", "python run.py \"$ARG\"", Map.of("MY", "v"));
        JsonNode installed = rec(R_ENV_PREPARE_INSTALLED).get("out");
        assertThat(prepared.command()).isEqualTo(installed.get("cmd").asText());
        JsonNode envRec = installed.get("env");
        assertThat(prepared.env().size()).isEqualTo(envRec.size());
        envRec.fields().forEachRemaining(f -> {
            // 两个键都来自 artifactOutputDir()（探针先 Setenv=/workspace/custom-out；
            // Java 测试不能 setenv，走同一解析核心断言）
            if (f.getKey().equals("WEKNORA_SKILL_OUTPUT_DIR") || f.getKey().equals("WEKNORA_SKILL_HISTORY_ROOT")) {
                assertThat(Manager.artifactOutputDir("/workspace/custom-out")).as("env %s", f.getKey())
                        .isEqualTo(f.getValue().asText());
                return;
            }
            assertThat(prepared.env().get(f.getKey())).as("env %s", f.getKey()).isEqualTo(f.getValue().asText());
        });
    }

    @Test
    void envPrepareHostAndDisabled(@TempDir Path host) throws Exception {
        writeSkillDir(host, "host-skill", "Host", "Host body.", Map.of("scripts/run.py", "p"));
        FakeStore store = new FakeStore();
        // 探针把 SkillDirs 指到了 skill 目录本身（writeSkill 返回的是 skill 目录），
        // 因此 discovery 为空、prepare 走到 staging 的 unlisted 分支——实录即契约
        Manager hm = new Manager(
                new ManagerConfig(List.of(host.resolve("host-skill").toString()), List.of(), true),
                new FakeGateway(store));
        hm.initialize();
        JsonNode hostRec = rec(R_ENV_PREPARE_HOST).get("out");
        assertThat(hostRec.get("cmd").asText()).isEmpty();
        assertThatThrownBy(() -> hm.prepareShellEnvironment("sess-8", "host-skill", "echo hi", null))
                .hasMessage(hostRec.get("err").asText());

        assertThatThrownBy(() -> hm.prepareShellEnvironment("sess-8", "other-skill", "echo", null))
                .hasMessage(rec(R_ENV_PREPARE_NOT_ALLOWED).get("out").get("err").asText());

        Manager dm = new Manager(new ManagerConfig(List.of(), List.of(), false), null);
        assertThatThrownBy(() -> dm.prepareShellEnvironment("s", "x", "y", null))
                .hasMessage(rec(R_ENV_PREPARE_DISABLED).get("out").get("err").asText());
    }

    @Test
    void envArtifactDirAndInjectedVars() {
        JsonNode def = rec(R_ENV_ARTIFACT_DIR_DEFAULT).get("out");
        assertThat(Manager.artifactOutputDir()).isEqualTo(def.asText());
        // 探针用 Setenv 驱动两条覆盖分支；这里走同一解析核心
        assertThat(Manager.artifactOutputDir("/workspace/output/custom"))
                .isEqualTo(out(R_ENV_ARTIFACT_DIR_OVERRIDE));
        assertThat(Manager.artifactOutputDir("/opt/evil"))
                .isEqualTo(out(R_ENV_ARTIFACT_DIR_OUTSIDE));
        JsonNode injected = rec(R_ENV_INJECTED_VARS).get("out");
        assertThat(Manager.injectedSandboxEnvVars()).containsExactlyElementsOf(strs(injected));
    }

    @Test
    void managerMatrix(@TempDir Path root) throws Exception {
        writeSkillDir(root, "skill-a", "A", "A body.", null);
        writeSkillDir(root, "skill-b", "B", "B body.", null);

        Manager am = new Manager(new ManagerConfig(List.of(root.toString()), List.of("skill-b"), true), null);
        am.initialize();
        JsonNode allowed = rec(R_MANAGER_ALLOWED_METADATA).get("out");
        List<Skill.SkillMetadata> metas = am.getAllMetadata();
        assertThat(metas.size()).isEqualTo(allowed.size());
        for (int i = 0; i < metas.size(); i++) {
            assertThat(metas.get(i).name()).isEqualTo(allowed.get(i).get("name").asText());
            assertThat(metas.get(i).description()).isEqualTo(allowed.get(i).get("description").asText());
            assertThat(metas.get(i).basePath()).endsWith(maskTemp(allowed.get(i).get("base").asText()));
        }

        Manager dm = new Manager(null, null);
        assertThat(dm.getAllMetadata()).isNull();
        assertThatThrownBy(() -> dm.loadSkill("skill-a")).hasMessage("skills are not enabled");
        assertThatThrownBy(() -> dm.readSkillFile("skill-a", "x.md")).hasMessage("skills are not enabled");
        assertThatThrownBy(() -> dm.listSkillFiles("skill-a")).hasMessage("skills are not enabled");
        assertThatThrownBy(() -> dm.getSkillInfo("skill-a")).hasMessage("skills are not enabled");
        assertThat(dm.sandboxSkillDir("skill-a").dir()).isEmpty();
        assertThat(dm.sandboxSkillDir("skill-a").installed()).isFalse();

        assertThatThrownBy(() -> am.loadSkill("skill-a")).hasMessage("skill not allowed: skill-a");
        Skill skill = am.loadSkill("skill-b");
        JsonNode loadOk = rec(R_MANAGER_LOAD_OK).get("out");
        assertThat(skill.name).isEqualTo(loadOk.get("name").asText());
        assertThat(skill.basePath).endsWith(maskTemp(loadOk.get("base").asText()));

        Manager.SkillInfo info = am.getSkillInfo("skill-b");
        JsonNode infoRec = rec(R_MANAGER_INFO).get("out");
        assertThat(info.name()).isEqualTo(infoRec.get("name").asText());
        assertThat(info.description()).isEqualTo(infoRec.get("description").asText());
        assertThat(info.instructions()).isEqualTo(infoRec.get("instructions").asText());
        assertThat(info.files()).containsExactlyElementsOf(strs(infoRec.get("files")));

        Manager.SandboxDir hostDir = am.sandboxSkillDir("skill-b");
        assertThat(hostDir.dir()).isEmpty();
        assertThat(hostDir.installed()).isFalse();

        TenantSkillSource ts = new TenantSkillSource(List.of(
                row("tid", "skill-b", "Installed B", "body", true, SkillStatus.READY, "sha", "", "")), null);
        am.withTenantSource(ts);
        Manager.SandboxDir imageDir = am.sandboxSkillDir("skill-b");
        JsonNode imageRec = rec(R_MANAGER_SANDBOX_DIR_IMAGE).get("out");
        assertThat(imageDir.dir()).isEqualTo(imageRec.get("dir").asText());
        assertThat(imageDir.installed()).isEqualTo(imageRec.get("ok").asBoolean());

        am.reload();
        JsonNode reloadRec = rec(R_MANAGER_RELOAD).get("out");
        List<String> names = new ArrayList<>();
        for (Skill.SkillMetadata m : am.getAllMetadata()) {
            names.add(m.name());
        }
        assertThat(names).containsExactlyElementsOf(strs(reloadRec));

        am.cleanup(); // nil sandbox gateway → 无异常、无返回
    }

    // ---- tools.SkillEnvironment 桥接冒烟（4.6d 装配形状）----

    @Test
    void skillEnvironmentBridge(@TempDir Path root) throws Exception {
        writeSkillDir(root, "bridge-skill", "Bridge", "Bridge body.", Map.of("scripts/run.py", "p"));
        byte[] arc = zipOf(Map.of("SKILL.md", "z"), null, null);
        Manager mgr = new Manager(
                new ManagerConfig(List.of(root.toString()), List.of(), true), new FakeGateway(new FakeStore()))
                .withTenantSource(new TenantSkillSource(
                        List.of(row("tid", "bridge-skill", "Installed", "Installed body", true, SkillStatus.READY, "sha", "", "")),
                        row2 -> arc));
        mgr.initialize();

        com.ragagent.agent.tools.SkillEnvironment env = mgr.asSkillEnvironment();
        assertThat(env.isEnabled()).isTrue();
        assertThat(env.getAllMetadata()).hasSize(1);
        assertThat(env.getAllMetadata().get(0).name()).isEqualTo("bridge-skill");

        com.ragagent.agent.tools.SkillEnvironment.SkillDocument doc = env.loadSkill("bridge-skill");
        assertThat(doc.name()).isEqualTo("bridge-skill");
        // resolveSource 恒 tenantSource 优先（Go 同源）：Level 2 来自安装行而非宿主文件
        assertThat(doc.instructions()).isEqualTo("Installed body");

        com.ragagent.agent.tools.SkillEnvironment.SkillDir dir = env.sandboxSkillDir("bridge-skill");
        assertThat(dir.installed()).isTrue();
        assertThat(dir.dir()).isEqualTo("/opt/weknora/tenant/skills/bridge-skill");

        assertThat(env.listSkillFiles("bridge-skill")).isNotEmpty();
        // Level 3 资源从安装归档读（对照 LoadSkillFile 注释：读归档而非镜像）
        assertThat(env.readSkillFile("bridge-skill", "SKILL.md")).isEqualTo("z");

        // shell_exec 装配：withSkillEnvironment(env) 后 skill env 注入与恢复提示都可达
        com.ragagent.agent.tools.SkillEnvironment.PreparedShell prepared =
                env.prepareShellEnvironment("sess-bridge", "bridge-skill", "echo hi", Map.of("K", "v"));
        assertThat(prepared.command()).startsWith("export PATH=/opt/weknora/tenant/skills/bridge-skill/.venv/bin:");
        assertThat(prepared.env().get("WEKNORA_SKILL_DIR")).isEqualTo("/opt/weknora/tenant/skills/bridge-skill");
        assertThat(prepared.env().get("K")).isEqualTo("v");
    }
}
