package com.ragagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 知识库模块的编码约定门禁（源码扫描，随测试套件执行）。
 *
 * <p>用一条测试守住几轮重构的成果，避免"改着改着又长回去"。规则不引入构建期插件，
 * 失败信息直接给出文件与行号，便于就地修复。约定清单见
 * {@code docs/knowledge-api-contract-v1.md}。</p>
 *
 * <p>规则一览：
 * <ol>
 *   <li>代码体内不得内联全限定类名（**任何**包，含 {@code java.*} / {@code com.fasterxml.*} / {@code jakarta.*} —— 一律走 import）；</li>
 *   <li>不得出现空 JavaDoc（{@code /** *​/}）；</li>
 *   <li>注释不得残留历史黑话（golden 字样、波次编号、Go 出处指针）；</li>
 *   <li>controller 包不得依赖 mapper 包（分层纪律）；</li>
 *   <li>controller 包不得自行开线程（后台任务走 KnowledgeTaskExecutor）；</li>
 *   <li>不得自行 new JdbcTemplate（用容器提供的 bean）。</li>
 *   <li>不得引入 {@code @JsonInclude} 与逐字段 {@code @JsonProperty}（历史遗留已清理，勿回流）。</li>
 * </ol>
 */
class KnowledgeCodeConventionsTest {

    /** 相对 server 模块根的主源目录。 */
    private static final Path MAIN_ROOT = Path.of("src/main/java/com/ragagent/knowledge");

    /** 通用全限定类名：≥2 段小写包名后跟大写开头的类型（含 java.* / com.fasterxml.* / jakarta.*）。 */
    private static final Pattern FQN = Pattern.compile("(?:[a-z][\\w]*\\.){2,}[A-Z]\\w*");
    private static final Pattern EMPTY_JAVADOC = Pattern.compile("/\\*\\*\\s*\\*/");
    private static final Pattern JARGON = Pattern.compile("golden|波\\s*\\d|对照\\s*Go");
    /** 禁用的序列化注解（@JsonPropertyOrder 不匹配：\b 跟在 JsonProperty 后仍是字母）。 */
    private static final Pattern GO_ERA_ANNOTATION = Pattern.compile("@JsonInclude\\b|@JsonProperty\\b");

    private static final Pattern THREAD_START =
            Pattern.compile("Thread\\.ofVirtual\\(|new\\s+Thread\\(");
    private static final Pattern NEW_JDBC_TEMPLATE = Pattern.compile("new\\s+JdbcTemplate\\(");

    @Test
    @DisplayName("知识库模块：代码体内不得内联全限定类名")
    void noFullyQualifiedNamesInCode() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachSource((path, lines) -> {
            boolean inBlockComment = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (line.contains("/*") && !line.contains("*/")) {
                    inBlockComment = true;
                }
                boolean codeLine = !inBlockComment && !trimmed.startsWith("*")
                        && !trimmed.startsWith("//") && !trimmed.startsWith("import ")
                        && !trimmed.startsWith("package ");
                if (codeLine) {
                    Matcher m = FQN.matcher(line);
                    while (m.find()) {
                        if (line.substring(0, m.start()).chars().filter(c -> c == '"').count() % 2 == 0) {
                            violations.add(path.getFileName() + ":" + (i + 1) + " → " + m.group());
                        }
                    }
                }
                if (line.contains("*/")) {
                    inBlockComment = false;
                }
            }
        });
        assertTrue(violations.isEmpty(),
                "以下位置内联了全限定类名，请改为 import 后使用短名：\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("知识库模块：不得有空 JavaDoc 与移植期黑话注释")
    void noEmptyJavadocOrPortingJargon() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachSource((path, lines) -> {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (EMPTY_JAVADOC.matcher(line).find()) {
                    violations.add(path.getFileName() + ":" + (i + 1) + " → 空 JavaDoc");
                }
                if (JARGON.matcher(line).find()) {
                    violations.add(path.getFileName() + ":" + (i + 1) + " → 黑话: " + line.trim());
                }
            }
        });
        assertTrue(violations.isEmpty(),
                "以下位置的注释需要改写成人话（或删除空 JavaDoc）：\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("知识库模块：不得引入 @JsonInclude / 逐字段 @JsonProperty（Go 遗留已清理，勿回流）")
    void noGoEraSerializationAnnotations() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachSource((path, lines) -> {
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue; // 注释/Javadoc 里的说明不算违规
                }
                if (GO_ERA_ANNOTATION.matcher(lines.get(i)).find()) {
                    violations.add(path.getFileName() + ":" + (i + 1) + " → " + trimmed);
                }
            }
        });
        assertTrue(violations.isEmpty(),
                "知识库模块已分两批清理 @JsonInclude（38 处）与逐字段 @JsonProperty，且契约政策要求\n"
                        + "「字段一律显式输出、键名即 Java 字段名」——以下位置请勿回流：\n"
                        + String.join("\n", violations));
    }

    @Test
    @DisplayName("知识库模块：controller 不得依赖 mapper、不得自行开线程")
    void controllersStayThin() throws IOException {
        List<String> violations = new ArrayList<>();
        Path controllerDir = MAIN_ROOT.resolve("controller");
        try (Stream<Path> files = Files.walk(controllerDir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();
                    // 异常类型（mapper 包中的 XxxException）允许 controller 捕获并转换成 HTTP 错误，
                    // 这里只拦真正的数据访问类型（*Mapper / *Repository）
                    if (trimmed.startsWith("import com.ragagent.knowledge.mapper")
                            && (trimmed.endsWith("Mapper;") || trimmed.endsWith("Repository;"))) {
                        violations.add(path.getFileName() + ":" + (i + 1) + " → controller 依赖 mapper（分层纪律）");
                    }
                    if (!trimmed.startsWith("*") && !trimmed.startsWith("//")
                            && THREAD_START.matcher(line).find()) {
                        violations.add(path.getFileName() + ":" + (i + 1) + " → controller 自行开线程");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "controller 层约定被破坏：\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("知识库模块：不得自行 new JdbcTemplate")
    void noSelfBuiltJdbcTemplate() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachSource((path, lines) -> {
            for (int i = 0; i < lines.size(); i++) {
                if (NEW_JDBC_TEMPLATE.matcher(lines.get(i)).find()) {
                    violations.add(path.getFileName() + ":" + (i + 1) + " → new JdbcTemplate(...)");
                }
            }
        });
        assertTrue(violations.isEmpty(),
                "请注入容器提供的 JdbcTemplate bean：\n" + String.join("\n", violations));
    }

    /** 遍历知识库主源文件（跳过 package-info）。 */
    private static void forEachSource(SourceVisitor visitor) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_ROOT)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java")).toList()) {
                visitor.visit(path, Files.readAllLines(path, StandardCharsets.UTF_8));
            }
        }
    }

    @FunctionalInterface
    private interface SourceVisitor {
        void visit(Path path, List<String> lines);
    }
}
