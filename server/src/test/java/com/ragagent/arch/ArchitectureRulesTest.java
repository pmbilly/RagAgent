package com.ragagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ragagent.RagAgentApplication;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.storage.UploadLimits;
import com.ragagent.common.wiki.WikiLanguageSupport;
import com.ragagent.common.deployment.AppEnvLookup;
import com.ragagent.retrieval.config.RetrievalEnvLookup;
import com.ragagent.storage.config.StorageEnvLookup;
import com.ragagent.common.storage.StorageRuntimeEnv;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * 全仓架构规则（B10：把几轮重构攒下的约定固化成 CI 红条）。
 *
 * <p><b>分工</b>：包级结构（包间环 / 分层 / 域依赖 {@code config}）由
 * {@code scripts/check-package-cycles.py}（CI 的 guards job，带基线棘轮）守；本类守
 * <b>代码级</b>规则——脚本表达不了、而这几轮真实踩过坑的：</p>
 *
 * <ol>
 *   <li><b>裸读环境变量</b>：B6 十批把 {@code System.getenv} 从 149 处清到 0，四类落点
 *       （{@code @ConfigurationProperties} / 域查找面 / 启动期值快照 / 全局查找面 + 启动钩子）
 *       已定型；本规则防止回流。</li>
 *   <li><b>{@code @ConfigurationProperties} 类必须被 {@code @ConfigurationPropertiesScan}
 *       名单覆盖</b>：漏一处的表现是<b>静默</b>的——不报错，只是值永远是默认值
 *       （B6 期间反复踩：新属性类放进未扫描的包）。</li>
 *   <li><b>配置类不得同时是 {@code @Component}/{@code @Service}</b>：属性绑定 + 组件扫描
 *       双装配，语义含糊。</li>
 *   <li><b>{@code install*} 只许装配层调用</b>：查找面/快照类的 {@code install} 是启动期
 *       一次性写入，运行期调用即「把配置当状态改」（各 holder 注释均写明此约束）。</li>
 * </ol>
 *
 * <p>新加规则请只加<b>当前零违例</b>的规则，否则等于把存量违例变成噪声；确有存量违例要
 * 棘轮化的，用 ArchUnit 的 {@code FreezingArchRule}（本仓包级棘轮已在脚本里）。</p>
 */
class ArchitectureRulesTest {

    /**
     * 全仓<b>主源集</b>（含所有域）。
     *
     * <p>必须按输出目录过滤：{@code importPackages("com.ragagent")} 会把 src/test 的类一起扫进来，
     * 而测试里读真实环境变量是<b>合法</b>的（例如各 connector 桩要读宿主 env 拼 SSRF 白名单），
     * 那些不是本规则的治理对象。</p>
     */
    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(location -> location.asURI().getPath().contains("/classes/java/main/"))
            .importPackages("com.ragagent");

    /**
     * 启动期一次性写入的查找面/快照（各类注释：只允许装配层调用 install）。
     *
     * <p>键是 {@code 属主全名#方法名}——<b>刻意不用 {@code JavaMethod.getFullName()}</b>：
     * 后者带参数类型（{@code ...install(java.util.function.Function)}），按它建集合会让规则
     * <b>永远绿</b>（B10 实测：探针类照样通过，属"空转规则"）。</p>
     */
    private static final Set<String> INSTALL_TARGETS = Set.of(
            WikiLanguageSupport.class.getName() + "#installLanguage",
            UploadLimits.class.getName() + "#installFileSizeMb",
            CryptoService.class.getName() + "#installAesKey",
            SsrfGuard.class.getName() + "#installWhitelist",
            StorageRuntimeEnv.class.getName() + "#install",
            StorageEnvLookup.class.getName() + "#install",
            RetrievalEnvLookup.class.getName() + "#install",
            AppEnvLookup.class.getName() + "#install");

    // ── R1 禁裸读环境变量 ──────────────────────────────────────────────────

    @Test
    @DisplayName("R1：主代码不得调用 System.getenv（走四类已登记落点）")
    void noRawEnvReads() {
        // 过滤失败会静默「零类可查」→ 规则永远绿；先自证导入面正常
        assertThat(MAIN.size()).as("主源集导入为空或过少：检查 ImportOption 的输出目录过滤").isGreaterThan(500);
        noClasses()
                .should().callMethod(System.class, "getenv", String.class)
                .because("裸 getenv 绕过 @ConfigurationProperties/查找面：既不能被属性源与命令行覆盖，也"
                        + "无法在测试里注入。改用：@ConfigurationProperties 注入 / 域查找面（*EnvLookup）/ "
                        + "启动期值快照 / 全局 AppEnvLookup（启动钩子装配）")
                .check(MAIN);
        noClasses()
                .should().callMethod(System.class, "getenv")
                .because("同上（无参重载）")
                .check(MAIN);
    }

    // ── R2/R3 属性类必须被扫描覆盖、且不双装配 ──────────────────────────────

    @Test
    @DisplayName("R2：@ConfigurationProperties 类必须落在 @ConfigurationPropertiesScan 名单覆盖的包内")
    void propertiesClassesAreScanned() {
        Set<String> scanned = scannedPackages();
        assertThat(scanned).as("RagAgentApplication 的 @ConfigurationPropertiesScan 名单不应为空").isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (JavaClass clazz : MAIN) {
            if (!clazz.isAnnotatedWith(ConfigurationProperties.class)) {
                continue;
            }
            String pkg = clazz.getPackageName();
            boolean covered = scanned.stream().anyMatch(s -> pkg.equals(s) || pkg.startsWith(s + "."));
            if (!covered) {
                missing.add(clazz.getFullName() + "（包 " + pkg + "）");
            }
        }
        assertThat(missing)
                .as("属性类漏扫描的后果是静默失效（值永远取默认）；把所在包加进 RagAgentApplication "
                        + "的 @ConfigurationPropertiesScan 名单")
                .isEmpty();
    }

    @Test
    @DisplayName("R3：@ConfigurationProperties 类不得同时是 @Component/@Service（避免双装配）")
    void propertiesClassesAreNotComponents() {
        noClasses()
                .that().areAnnotatedWith(ConfigurationProperties.class)
                .should().beAnnotatedWith(Component.class)
                .orShould().beAnnotatedWith(Service.class)
                .because("属性绑定已由 @ConfigurationPropertiesScan 负责，再加组件注解属双装配")
                .check(MAIN);
    }

    // ── R4 install* 只许装配层调用 ─────────────────────────────────────────

    @Test
    @DisplayName("R4：install*（启动期写入查找面/快照）只许装配层（*.config 包）调用")
    void installOnlyFromWiring() {
        // 注意：这里必须是 classes().should(customCondition)，不能用 noClasses().should(...)
        // ——后者会把条件取反，自定义条件里手写的 violation 会被反转成通过（B10 实测踩坑：
        // 该规则曾一度「永远绿」，靠探针类才暴露）。
        classes()
                .should(new ArchCondition<>("调用启动期 install* 且不在装配层") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (isWiringPackage(item.getPackageName())) {
                            return;
                        }
                        item.getMethodCallsFromSelf().stream()
                                .filter(call -> INSTALL_TARGETS.contains(call.getTarget().getOwner().getFullName()
                                        + "#" + call.getTarget().getName()))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(item,
                                        item.getFullName() + " 调用了启动期写入 "
                                                + call.getTarget().getFullName()
                                                + "——只允许 *.config 装配层调用；运行期改配置属状态变更，"
                                                + "应另行设计（见各 holder 的类注释）")));
                    }
                })
                .check(MAIN);
    }

    // ── R5 源码不得含裸 NUL 字节 ────────────────────────────────────────────

    @Test
    @DisplayName("R5：源文件不得含裸 NUL 字节（会让 grep/ripgrep 判为二进制并静默跳过该文件）")
    void noRawNulBytesInSources() throws java.io.IOException {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        for (java.nio.file.Path root : java.util.List.of(
                java.nio.file.Path.of("src/main/java"), java.nio.file.Path.of("src/test/java"))) {
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : walk.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java")).toList()) {
                    byte[] raw = java.nio.file.Files.readAllBytes(file);
                    for (byte b : raw) {
                        if (b == 0) {
                            offenders.add(file.toString());
                            break;
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("裸 NUL（常见于把 \\0 哨兵直接写成字节）会让文本工具跳过整个文件，"
                        + "审计因此静默漏文件；改写为 Java 的 \\0 八进制转义即可（B12 实测）")
                .isEmpty();
    }

    private static boolean isWiringPackage(String packageName) {
        return "com.ragagent.config".equals(packageName) || packageName.endsWith(".config");
    }

    private static Set<String> scannedPackages() {
        ConfigurationPropertiesScan scan =
                RagAgentApplication.class.getAnnotation(ConfigurationPropertiesScan.class);
        if (scan == null) {
            return Set.of();
        }
        Set<String> packages = new LinkedHashSet<>();
        packages.addAll(List.of(scan.value()));
        packages.addAll(List.of(scan.basePackages()));
        return packages;
    }
}
