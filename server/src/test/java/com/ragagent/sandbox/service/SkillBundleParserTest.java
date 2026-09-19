package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 对照 Go {@code service/tenant_skill_bundle_test.go} 的语义子集 + golden 钉死的
 * 400 文案：bundle 本地解析（frontmatter/缺 SKILL.md/坏 zip）、根目录判定、
 * 路径守卫、文件投影三态。
 */
class SkillBundleParserTest {

    private static Map<String, String> filesOf(String... kv) {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            files.put(kv[i], kv[i + 1]);
        }
        return files;
    }

    // ── ParseSkillBundle ─────────────────────────────────────────────────

    @Test
    void parsesFlatBundle() {
        byte[] zip = SkillBundleParser.zipOf(filesOf(
                "SKILL.md", "---\nname: probe-upload\ndescription: probe upload skill\n"
                        + "---\n\nBody instructions.\n",
                "scripts/run.sh", "echo hi\n"));

        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundle(zip);
        assertEquals("probe-upload", bundle.name);
        assertEquals("probe upload skill", bundle.description);
        assertEquals("Body instructions.", bundle.instructions);
        assertEquals("", bundle.version);
        assertFalse(bundle.frontmatterRepaired);
        assertEquals(2, bundle.files.size());
        assertEquals(64, bundle.sha256.length());
        // SHA256 对上传字节：同归档重传可辨认
        assertEquals(SkillBundleParser.skillArchiveSHA256(zip), bundle.sha256);
    }

    @Test
    void parsesWrappedBundleAndReadsVersion() {
        byte[] zip = SkillBundleParser.zipOf(filesOf(
                "myskill/SKILL.md", "---\nname: wrapped\nslug: wrapped-slug\n"
                        + "description: wrapped skill\nversion: 2.1.0\n---\nbody",
                "myskill/lib/a.py", "x = 1\n"));

        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundle(zip);
        assertEquals("wrapped", bundle.name);
        assertEquals("2.1.0", bundle.version);
        // 重挂根：SKILL.md 在根上，包裹目录被剥掉
        assertEquals(2, bundle.files.size());
        assertNotNull(bundle.files.get("lib/a.py"));
        assertNull(bundle.files.get("myskill/SKILL.md"));
    }

    @Test
    void rejectsNonZipWithGoZipWording() {
        // golden sbk-upload-invalid 的文案逐字节：zip 措辞照抄 Go 的 ErrFormat
        SkillBundleParser.BundleInvalidException e = assertThrows(
                SkillBundleParser.BundleInvalidException.class,
                () -> SkillBundleParser.parseSkillBundle(
                        "this is not a zip file at all, just some bytes\n"
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals("skill bundle is invalid: not a readable zip archive: "
                + "zip: not a valid zip file", e.getMessage());
    }

    @Test
    void rejectsMissingSkillMd() {
        byte[] zip = SkillBundleParser.zipOf(filesOf("README.md", "no skill here\n"));
        SkillBundleParser.BundleInvalidException e = assertThrows(
                SkillBundleParser.BundleInvalidException.class,
                () -> SkillBundleParser.parseSkillBundle(zip));
        assertEquals("skill bundle is invalid: SKILL.md is missing", e.getMessage());
    }

    @Test
    void rejectsBundleWithoutFrontmatter() {
        byte[] zip = SkillBundleParser.zipOf(filesOf("SKILL.md", "just prose, no frontmatter\n"));
        SkillBundleParser.BundleInvalidException e = assertThrows(
                SkillBundleParser.BundleInvalidException.class,
                () -> SkillBundleParser.parseSkillBundle(zip));
        assertEquals("skill bundle is invalid: SKILL.md must start with YAML frontmatter (---)",
                e.getMessage());
    }

    @Test
    void rejectsUnclosedFrontmatter() {
        byte[] zip = SkillBundleParser.zipOf(filesOf("SKILL.md",
                "---\nname: x\ndescription: y\n"));
        SkillBundleParser.BundleInvalidException e = assertThrows(
                SkillBundleParser.BundleInvalidException.class,
                () -> SkillBundleParser.parseSkillBundle(zip));
        assertEquals("skill bundle is invalid: SKILL.md frontmatter is not properly closed with ---",
                e.getMessage());
    }

    @Test
    void rejectsMultipleSkills() {
        byte[] zip = SkillBundleParser.zipOf(filesOf(
                "a/SKILL.md", "---\nname: a\ndescription: a\n---\n",
                "b/SKILL.md", "---\nname: b\ndescription: b\n---\n"));
        SkillBundleParser.BundleInvalidException e = assertThrows(
                SkillBundleParser.BundleInvalidException.class,
                () -> SkillBundleParser.parseSkillBundle(zip));
        assertEquals("skill bundle is invalid: archive holds more than one skill", e.getMessage());
    }

    @Test
    void rejectsTraversalEntry() {
        byte[] zip = SkillBundleParser.zipOf(filesOf(
                "../evil.txt", "boom\n",
                "SKILL.md", "---\nname: a\ndescription: a\n---\n"));
        SkillBundleParser.BundleInvalidException e = assertThrows(
                SkillBundleParser.BundleInvalidException.class,
                () -> SkillBundleParser.parseSkillBundle(zip));
        assertEquals("skill bundle is invalid: entry \"../evil.txt\" escapes the archive root",
                e.getMessage());
    }

    @Test
    void rejectsControlCharacterInEntryName() {
        byte[] zip = SkillBundleParser.zipOf(filesOf(
                "bad\nname.txt", "x\n",
                "SKILL.md", "---\nname: a\ndescription: a\n---\n"));
        SkillBundleParser.BundleInvalidException e = assertThrows(
                SkillBundleParser.BundleInvalidException.class,
                () -> SkillBundleParser.parseSkillBundle(zip));
        assertEquals("skill bundle is invalid: entry \"bad\\nname.txt\" holds unsupported "
                + "character '\\n'", e.getMessage());
    }

    // ── frontmatter 修复（skill_frontmatter_test.go 的两个修复场景） ─────

    @Test
    void repairsAccidentalNestedFrontmatter() {
        // 对照 Go 测试：version/description 缩进在 name: 之下 → outdent 修复后可解析
        byte[] zip = SkillBundleParser.zipOf(filesOf("SKILL.md", "---\n"
                + "name: 命理大师\n"
                + "  description: sub-stats predictor\n"
                + "---\nbody\n"));
        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundle(zip);
        assertEquals("命理大师", bundle.name);
        assertEquals("sub-stats predictor", bundle.description);
        assertTrue(bundle.frontmatterRepaired);
    }

    @Test
    void repairsUnquotedColonInScalar() {
        // description: A: B —— 未加引号的冒号 → 引号包住修复
        byte[] zip = SkillBundleParser.zipOf(filesOf("SKILL.md", "---\n"
                + "name: colonful\n"
                + "description: Use with care: it bites\n"
                + "---\nbody\n"));
        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundle(zip);
        assertEquals("colonful", bundle.name);
        assertEquals("Use with care: it bites", bundle.description);
        assertTrue(bundle.frontmatterRepaired);
    }

    @Test
    void applyInstallNamePrefersSlug() {
        // display title 不是合法安装身份 → slug 胜出（Go applyInstallName）
        byte[] zip = SkillBundleParser.zipOf(filesOf("SKILL.md", "---\n"
                + "name: Word / DOCX\n"
                + "slug: word-docx\n"
                + "description: office toolkit\n---\nbody\n"));
        assertEquals("word-docx", SkillBundleParser.parseSkillBundle(zip).name);
    }

    @Test
    void rejectsReservedWordAndMissingDescription() {
        SkillFrontmatter.SkillFrontmatterException reserved = assertThrows(
                SkillFrontmatter.SkillFrontmatterException.class,
                () -> SkillFrontmatter.parseSkillFile(
                        "---\nname: my-claude-tool\ndescription: x\n---\n"));
        assertEquals("skill name cannot contain reserved word: claude", reserved.getMessage());

        SkillFrontmatter.SkillFrontmatterException noDesc = assertThrows(
                SkillFrontmatter.SkillFrontmatterException.class,
                () -> SkillFrontmatter.parseSkillFile("---\nname: ok\n---\n"));
        assertEquals("skill description is required", noDesc.getMessage());
    }

    // ── 文件浏览面 ───────────────────────────────────────────────────────

    @Test
    void listsAndReadsSkillRootFiles() {
        byte[] zip = SkillBundleParser.zipOf(filesOf(
                "SKILL.md", "---\nname: a\ndescription: a\n---\n",
                "docs/b.md", "# b\n"));
        List<SkillBundleParser.SkillFileEntry> entries = SkillBundleParser.listSkillZipFiles(zip);
        assertEquals(2, entries.size());
        assertEquals("SKILL.md", entries.get(0).path()); // 按名称排序
        assertEquals("---\nname: a\ndescription: a\n---\n".length(), entries.get(0).size());

        byte[] body = SkillBundleParser.readSkillZipFile(zip, "docs/b.md");
        assertEquals("# b\n", new String(body, StandardCharsets.UTF_8));

        SkillBundleParser.SkillFileNotFoundException missing = assertThrows(
                SkillBundleParser.SkillFileNotFoundException.class,
                () -> SkillBundleParser.readSkillZipFile(zip, "nope.txt"));
        assertEquals("skill file not found", missing.getMessage());
    }

    @Test
    void safeSkillFilePathGuards() {
        assertEquals("a/SKILL.md",
                SkillBundleParser.safeSkillFilePath("a/SKILL.md"));
        assertEquals("a/b",
                SkillBundleParser.safeSkillFilePath("a/./b"));

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> SkillBundleParser.safeSkillFilePath("  "));
        assertEquals("skill file path is required", empty.getMessage());

        // golden sbk-file-content：绝对路径拒绝，文案带原始路径
        String abs = "/opt/weknora/tenant/skills/probe-skill/SKILL.md";
        IllegalArgumentException traversal = assertThrows(IllegalArgumentException.class,
                () -> SkillBundleParser.safeSkillFilePath(abs));
        assertEquals("invalid skill file path: " + abs, traversal.getMessage());

        assertEquals("invalid skill file path: a/../../etc/passwd",
                assertThrows(IllegalArgumentException.class,
                        () -> SkillBundleParser.safeSkillFilePath("a/../../etc/passwd"))
                        .getMessage());
        assertEquals("invalid skill file path: a\\b",
                assertThrows(IllegalArgumentException.class,
                        () -> SkillBundleParser.safeSkillFilePath("a\\b")).getMessage());
    }

    @Test
    void projectsFileContentThreeWays() {
        // utf-8 文本 + markdown 媒体类型
        SkillBundleParser.SkillFileContent md = SkillBundleParser.projectSkillFileContent(
                "SKILL.md", "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("utf-8", md.encoding());
        assertEquals("text/markdown", md.mediaType());
        assertEquals("hello", md.content());
        assertFalse(md.truncated());
        assertFalse(md.binary());

        // NUL 字节 → binary（无 content）
        SkillBundleParser.SkillFileContent bin = SkillBundleParser.projectSkillFileContent(
                "data.bin", new byte[] {'a', 0, 'b'});
        assertEquals("binary", bin.encoding());
        assertTrue(bin.binary());
        assertEquals("", bin.content());

        // 小图 → base64 内联
        SkillBundleParser.SkillFileContent img = SkillBundleParser.projectSkillFileContent(
                "assets/logo.png", new byte[] {1, 2, 3});
        assertEquals("base64", img.encoding());
        assertEquals("image/png", img.mediaType());
        assertEquals(java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}),
                img.content());
    }

    // ── Go path.Clean 直译的抽查 ─────────────────────────────────────────

    @Test
    void goPathCleanMatchesGo() {
        assertEquals("a/b", SkillBundleParser.goPathClean("a//b"));
        assertEquals("a/b", SkillBundleParser.goPathClean("a/./b"));
        assertEquals("../a/b", SkillBundleParser.goPathClean("../a/b"));
        assertEquals("/", SkillBundleParser.goPathClean("/.."));
        assertEquals(".", SkillBundleParser.goPathClean("./"));
    }
}
