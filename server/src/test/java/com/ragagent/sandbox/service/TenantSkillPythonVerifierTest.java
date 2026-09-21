package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 内嵌 Python 校验器本身的行为验收（对照 Go {@code tenant_skill_verify_python_test.go}
 * 全表逐用例镜像）。资源 {@code sandbox/tenant_skill_verify.py} 与 Go go:embed 的文件
 * 逐字节相同（命令构造契约测试钉住），这里用真解释器从 stdin 喂它——判定太严会冤枉
 * 好技能，太松会放过坏安装，两侧必须同坏同好。
 *
 * <p>python3 不在 PATH 时整组跳过（Go t.Skip 同法）。"false marker 是 note 还是静默"
 * 取决于该解释器有没有 {@code packaging}，用例内按探测结果分支（Go 同法）。</p>
 */
class TenantSkillPythonVerifierTest {

    private static byte[] verifierSource() {
        try (var in = TenantSkillPythonVerifierTest.class
                .getResourceAsStream("/sandbox/tenant_skill_verify.py")) {
            assumeTrue(in != null, "verifier resource missing");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean python3Available() {
        try {
            new ProcessBuilder("python3", "-c", "pass").start().waitFor(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean pythonCanEvaluateMarkers() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "from packaging.markers import Marker")
                    .start();
            p.waitFor(30, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    record Result(int exitCode, String stdout, String stderr) {
    }

    /** 与沙箱命令同一喂法：stdin 进源码、argv 进根目录与文件名单、--optional 收尾。 */
    private static Result runVerifier(String root, List<String> scripts,
            List<String> optional) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(List.of("python3", "-", root));
        argv.addAll(scripts);
        if (!optional.isEmpty()) {
            argv.add("--optional");
            argv.addAll(optional);
        }
        Process p = new ProcessBuilder(argv).start();
        p.getOutputStream().write(verifierSource());
        p.getOutputStream().close();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "verifier hung");
        return new Result(p.exitValue(), stdout, stderr);
    }

    private static Path writeSkillTree(@TempDir Path tmp, Map<String, String> files)
            throws IOException {
        Path root = Files.createTempDirectory(tmp, "skill");
        for (var e : files.entrySet()) {
            Path full = root.resolve(e.getKey());
            Files.createDirectories(full.getParent());
            Files.write(full, e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private static List<String> pythonFiles(Map<String, String> files) {
        List<String> scripts = new ArrayList<>();
        for (String rel : files.keySet()) {
            if (rel.endsWith(".py")) {
                scripts.add(rel);
            }
        }
        scripts.sort(String::compareTo);
        return scripts;
    }

    private record Case(String name, Map<String, String> files, List<String> optional,
            String wantProblem, Integer wantExit, String wantNote) {
        Case(String name, Map<String, String> files, String wantProblem, int wantExit) {
            this(name, files, List.of(), wantProblem, wantExit, "");
        }

        Case(String name, Map<String, String> files, List<String> optional, String wantNote) {
            this(name, files, optional, "", null, wantNote);
        }

        Case(String name, Map<String, String> files) {
            this(name, files, List.of(), "", null, "");
        }
    }

    @Test
    void skillPythonVerifierCaseTable(@TempDir Path tmp) throws Exception {
        assumeTrue(python3Available(), "python3 is not on PATH");
        // 有 packaging 时 false marker 被静默求值；没有时它是 note。两种环境安装都必须成。
        String unevaluableMarkerNote = pythonCanEvaluateMarkers()
                ? ""
                : "requirements.txt declares pywin32 but it is not installed";

        List<Case> cases = List.of(
                new Case("a syntax error",
                        files("scripts/run.py", "def broken(:\n    pass\n"),
                        "scripts/run.py has a syntax error on line 1", 1),
                new Case("a syntax error in a library module rather than an entry script",
                        files("scripts/run.py", "x = 1\n",
                                "scripts/helper.py", "def broken(:\n"),
                        "scripts/helper.py has a syntax error", 1),
                new Case("a syntax error alongside a missing requirement",
                        files("requirements.txt", "pandas==3.0.1\n",
                                "scripts/bad.py", "def broken(:\n"),
                        "scripts/bad.py has a syntax error", 1),
                new Case("a requirement the venv does not carry",
                        files("requirements.txt", "# pinned\npandas==3.0.1\n-r other.txt\n",
                                "scripts/run.py", "x = 1\n"),
                        "requirements.txt declares pandas but it is not installed", 2),
                new Case("a requirement gated by an environment marker",
                        files("requirements.txt",
                                "pywin32; sys_platform == \"win32\"\n"
                                        + "totally_absent_package; extra == \"dev\"\n",
                                "scripts/run.py", "x = 1\n"),
                        List.of(), unevaluableMarkerNote),
                new Case("an extras-gated requirement is not reported at all",
                        files("requirements.txt",
                                "totally_absent_package; extra == \"dev\"\n",
                                "scripts/run.py", "x = 1\n")),
                new Case("a poetry dependency marked optional",
                        files("pyproject.toml",
                                "[tool.poetry.dependencies]\npython = \"^3.11\"\n"
                                        + "totally_absent_package = { version = \"^1.0\", optional = true }\n",
                                "scripts/run.py", "x = 1\n")),
                new Case("a pyproject.toml dependency the venv does not carry",
                        files("pyproject.toml",
                                "[project]\nname = \"demo\"\ndependencies = [\n"
                                        + "  \"totally_absent_package>=1.0\",\n]\n",
                                "scripts/run.py", "x = 1\n"),
                        "pyproject.toml declares totally_absent_package but it is not installed",
                        2),
                new Case("requirements that point at a VCS, an archive or a local path",
                        files("requirements.txt",
                                "git+https://example.com/x/y.git#egg=y\n"
                                        + "./vendor/local-wheel.whl\n"
                                        + "https://example.com/pkg-1.0.tar.gz\n"
                                        + "--index-url https://example.com/simple\n",
                                "scripts/run.py", "x = 1\n")),
                new Case("a bundled test file that does not parse",
                        files("scripts/run.py", "x = 1\n",
                                "tests/conftest.py", "def broken(:\n",
                                "examples/demo.py", "def also_broken(:\n"),
                        List.of("examples/demo.py", "tests/conftest.py"),
                        "auxiliary file; this does not fail the install"));

        for (Case tc : cases) {
            Path root = writeSkillTree(tmp, tc.files());
            List<String> entry = pythonFiles(tc.files());
            if (!tc.optional().isEmpty()) {
                entry = withoutPaths(entry, tc.optional());
            }
            Result r = runVerifier(root.toString(), entry, tc.optional());

            if (tc.wantProblem().isEmpty()) {
                assertEquals(0, r.exitCode(),
                        tc.name() + ": this skill must install; stderr: " + r.stderr());
                assertTrue(r.stdout().contains("verified"), tc.name());
            } else {
                assertTrue(r.exitCode() != 0,
                        tc.name() + ": this skill is broken and must not reach a snapshot");
                assertTrue(r.stderr().contains(tc.wantProblem()),
                        tc.name() + ": " + r.stderr());
                assertEquals(tc.wantExit(), r.exitCode(),
                        tc.name() + ": the exit code is what decides whether an installer "
                                + "round can fix this; stderr: " + r.stderr());
            }
            if (!tc.wantNote().isEmpty()) {
                assertTrue(r.stdout().contains("note: "),
                        tc.name() + ": a finding that does not refuse the install must still "
                                + "be reported");
                assertTrue(r.stdout().contains(tc.wantNote()), tc.name() + ": " + r.stdout());
            }
        }
    }

    private static List<String> withoutPaths(List<String> all, List<String> drop) {
        List<String> kept = new ArrayList<>(all);
        kept.removeAll(drop);
        return kept;
    }

    /** 校验必须能在 skill 一 import 就写文件/开套接字时读它，而两者都不发生。 */
    @Test
    void neverExecutesTheSkill(@TempDir Path tmp) throws Exception {
        assumeTrue(python3Available(), "python3 is not on PATH");
        Path root = writeSkillTree(tmp, files("scripts/run.py",
                "import os\nopen(os.path.join(os.path.dirname(__file__), 'SIDE_EFFECT'), 'w').close()\n"));
        Result r = runVerifier(root.toString(), List.of("scripts/run.py"), List.of());
        assertEquals(0, r.exitCode(), r.stderr());
        assertFalse(Files.exists(root.resolve("scripts/SIDE_EFFECT")),
                "the checker ran the skill's module body instead of reading it");
    }

    /** root 也能读 000 文件，此用例只在非 root 下有意义（Go 同款 skip）。 */
    @Test
    void reportsAnUnreadableScript(@TempDir Path tmp) throws Exception {
        assumeTrue(python3Available(), "python3 is not on PATH");
        assumeTrue(!isRoot(), "root can read a 000 file, so this states nothing as root");
        Path root = writeSkillTree(tmp, files("scripts/run.py", "x = 1\n"));
        Files.setPosixFilePermissions(root.resolve("scripts/run.py"),
                java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        Result r = runVerifier(root.toString(), List.of("scripts/run.py"), List.of());
        assertTrue(r.exitCode() != 0);
        assertTrue(r.stderr().contains("cannot be read by the skill execution user"),
                r.stderr());
        assertEquals(1, r.exitCode(),
                "a file the execution user cannot read is not something installing a "
                        + "package fixes");
    }

    private static boolean isRoot() {
        try {
            return new ProcessBuilder("id", "-u").start().getInputStream()
                    .readAllBytes()[0] == '0';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 现契约：import 形态永远不是裁决。每个用例都曾是（别的静态检查器下的）一次被拒
     * 安装——证明 import 能否解析属于持 root shell 的安装器 agent，本 pass 只证明文件
     * 能解析。
     */
    @Test
    void neverJudgesImports(@TempDir Path tmp) throws Exception {
        assumeTrue(python3Available(), "python3 is not on PATH");
        Map<String, Map<String, String>> shapes = new LinkedHashMap<>();
        shapes.put("a package the image genuinely does not carry",
                files("scripts/run.py", "import totally_absent_package\n"));
        shapes.put("a sibling module reached only by a sys.path bootstrap",
                files("lib/image_video.py", "def generate_image():\n    pass\n",
                        "scripts/generate.py", "import sys, os\n"
                                + "sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'lib'))\n"
                                + "from image_video import generate_image\n"));
        shapes.put("a sibling module with no bootstrap at all",
                files("lib/image_video.py", "def generate_image():\n    pass\n",
                        "scripts/generate.py", "from image_video import generate_image\n"));
        shapes.put("a bootstrap whose argument cannot be evaluated statically",
                files("lib/helper.py", "x = 1\n",
                        "scripts/run.py", "import sys, os\n"
                                + "sys.path.insert(0, os.environ['LIB_DIR'])\n"
                                + "import helper\n"));
        shapes.put("a bootstrap written inside a helper function",
                files("lib/helper.py", "x = 1\n",
                        "scripts/run.py", "import sys\n"
                                + "from pathlib import Path\n"
                                + "def _setup():\n"
                                + "    sys.path.insert(0, str(Path(__file__).parent.parent / 'lib'))\n"
                                + "_setup()\n"
                                + "import helper\n"));
        shapes.put("a path constant imported from a sibling module",
                files("scripts/paths.py", "from pathlib import Path\n"
                                + "LIB = Path(__file__).resolve().parent.parent / 'lib'\n",
                        "lib/helper.py", "x = 1\n",
                        "scripts/run.py", "import sys\n"
                                + "from paths import LIB\n"
                                + "if str(LIB) not in sys.path:\n"
                                + "    sys.path.insert(0, str(LIB))\n"
                                + "import helper\n"));
        shapes.put("a vendored module sharing a distribution's name",
                files("vendor/totally_absent_package.py", "x = 1\n",
                        "scripts/run.py", "import totally_absent_package\n"));
        shapes.put("a relative import in a directory with no __init__.py",
                files("scripts/run.py", "from .helper import go\n",
                        "scripts/helper.py", "def go():\n    pass\n"));
        shapes.put("a relative import reaching a module the skill does not ship",
                files("pkg/__init__.py", "",
                        "pkg/sub/__init__.py", "",
                        "pkg/sub/run.py", "from ..missing import go\n"));

        for (var e : shapes.entrySet()) {
            Path root = writeSkillTree(tmp, e.getValue());
            Result r = runVerifier(root.toString(), pythonFiles(e.getValue()), List.of());
            assertEquals(0, r.exitCode(),
                    e.getKey() + ": an import shape must not refuse an install; stderr: "
                            + r.stderr());
            assertTrue(r.stdout().contains("verified"), e.getKey());
        }
    }

    /** 起点：官方 office 工具包的布局——入口脚本与兄弟包同住，库模块按短名 import 兄弟。能解析，就能安装。 */
    @Test
    void acceptsTheOfficeToolkitLayout(@TempDir Path tmp) throws Exception {
        assumeTrue(python3Available(), "python3 is not on PATH");
        Map<String, String> files = files(
                "SKILL.md", "# xlsx\n",
                "scripts/recalc.py", "import json\nimport sys\nfrom pathlib import Path\n"
                        + "from office.soffice import run_soffice\n",
                "scripts/office/soffice.py", "import subprocess\nimport tempfile\n"
                        + "def run_soffice():\n    pass\n",
                "scripts/office/validate.py", "import argparse\n"
                        + "from helpers import safe_extract\n"
                        + "from validators import DOCXSchemaValidator\n",
                "scripts/office/helpers/__init__.py", "import zipfile\n"
                        + "def safe_extract():\n    pass\n",
                "scripts/office/validators/__init__.py",
                "from .docx import DOCXSchemaValidator\n",
                "scripts/office/validators/base.py", "import re\n"
                        + "from helpers import safe_extract\n"
                        + "class BaseSchemaValidator:\n    pass\n",
                "scripts/office/validators/docx.py", "from helpers import safe_extract\n"
                        + "from .base import BaseSchemaValidator\n"
                        + "class DOCXSchemaValidator(BaseSchemaValidator):\n    pass\n",
                "scripts/office/helpers/pptx_chart.py", "from __future__ import annotations\n"
                        + "import re\nfrom . import part_text\n");
        Path root = writeSkillTree(tmp, files);
        Result r = runVerifier(root.toString(), pythonFiles(files), List.of());
        assertEquals(0, r.exitCode(),
                "the toolkit's own layout must not be a failed install; stderr: " + r.stderr());
        assertTrue(r.stdout().contains("verified"));
        assertFalse(r.stderr().contains("helpers"),
                "helpers is a sibling package of validators/, reachable from scripts/office/");
    }

    private static Map<String, String> files(String... kv) {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            files.put(kv[i], kv[i + 1]);
        }
        return files;
    }
}
