package com.ragagent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.sandbox.domain.SkillEnvVar;
import com.ragagent.sandbox.domain.SkillEnvVars;
import com.ragagent.sandbox.service.SkillBundleParser.SkillBundle;
import com.ragagent.sandbox.service.SkillEnvDeclaration.DeclaredSkillEnv;

/**
 * env 声明纯函数族（对照 Go tenant_skill_env_declare_test.go 的可测面语义）。
 */
class SkillEnvDeclarationTest {

    private static SkillBundle bundle(Map<String, String> files) {
        SkillBundle b = new SkillBundle();
        b.name = "demo";
        files.forEach((k, v) -> b.files.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return b;
    }

    private static DeclaredSkillEnv declared(String name) {
        DeclaredSkillEnv d = new DeclaredSkillEnv();
        d.name = name;
        d.description = "what for";
        d.required = true;
        d.value = "SHOULD-NOT-LEAK";
        return d;
    }

    // ── parseEnvDeclaration ──────────────────────────────────────────────

    @Test
    void parseAcceptsValidDeclaration() {
        byte[] raw = """
                {"env":[{"name":"TAVILY_API_KEY","description":"search","required":true}]}
                """.getBytes(StandardCharsets.UTF_8);
        List<DeclaredSkillEnv> out = SkillEnvDeclaration.parseEnvDeclaration(raw);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).name).isEqualTo("TAVILY_API_KEY");
        assertThat(out.get(0).required).isTrue();
    }

    @Test
    void parseRejectsProseAndBareNull() {
        assertThatThrownBy(() -> SkillEnvDeclaration.parseEnvDeclaration(
                "the skill needs an api key".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse skill env declaration");
        assertThatThrownBy(() -> SkillEnvDeclaration.parseEnvDeclaration(
                "{\"env\":null}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("env must be an explicit JSON array");
    }

    @Test
    void parseRejectsNonArrayEnvAndTooManyEntries() {
        assertThatThrownBy(() -> SkillEnvDeclaration.parseEnvDeclaration(
                "{\"env\":\"null\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("env must be an array");

        StringBuilder many = new StringBuilder("{\"env\":[");
        for (int i = 0; i <= 200; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"name\":\"V").append(i).append("\"}");
        }
        many.append("]}");
        assertThatThrownBy(() -> SkillEnvDeclaration.parseEnvDeclaration(
                many.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many entries (201 exceeds 200)");
    }

    // ── validateEnvDeclarations ──────────────────────────────────────────

    @Test
    void validateKeepsOnlyNamesMentionedInBundle() {
        SkillBundle b = bundle(Map.of(
                "SKILL.md", "uses TAVILY_API_KEY; the other name appears nowhere"));
        SkillEnvVars out = SkillEnvDeclaration.validateEnvDeclarations(
                List.of(declared("TAVILY_API_KEY"), declared("HALLUCINATED_KEY"),
                        declared("lowercase_key")), b);
        // 幻觉名与格式不合格各自被单独丢弃；真名幸存
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getName()).isEqualTo("TAVILY_API_KEY");
        // value 刻意不复制：声明是元数据
        assertThat(out.get(0).getValue()).isEmpty();
        assertThat(out.get(0).isRequired()).isTrue();
    }

    @Test
    void validateSearchesEveryBundleFileNotOnlySkillMd() {
        SkillBundle b = bundle(Map.of(
                "SKILL.md", "no variables here",
                "scripts/run.py", "os.environ[\"DEMO_TOKEN\"]"));
        SkillEnvVars out = SkillEnvDeclaration.validateEnvDeclarations(
                List.of(declared("DEMO_TOKEN")), b);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getName()).isEqualTo("DEMO_TOKEN");
    }

    @Test
    void validateDropsReservedAndDuplicateNames() {
        SkillBundle b = bundle(Map.of("SKILL.md", "PATH HOME_USER TAVILY_API_KEY"));
        SkillEnvVars out = SkillEnvDeclaration.validateEnvDeclarations(
                List.of(declared("PATH"),               // 保留名
                        declared("WEKNORA_SKILL_DIR"),  // 注入名单
                        declared("WEKNORA_OTHER_KEY"),  // WEKNORA_SKILL_ 前缀
                        declared("TAVILY_API_KEY"),
                        declared("TAVILY_API_KEY")),    // 重复
                b);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getName()).isEqualTo("TAVILY_API_KEY");
    }

    @Test
    void validateCapsAtTwentyAcceptedEntries() {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            names.add("VAR_" + i);
        }
        SkillBundle b = bundle(Map.of("SKILL.md", String.join(" ", names)));
        List<DeclaredSkillEnv> many = new java.util.ArrayList<>();
        for (String name : names) {
            many.add(declared(name));
        }
        SkillEnvVars out = SkillEnvDeclaration.validateEnvDeclarations(many, b);
        assertThat(out).hasSize(SkillEnvDeclaration.MAX_SKILL_ENV_DECLARATIONS);
    }

    // ── mergeEnvDeclaration ──────────────────────────────────────────────

    @Test
    void mergeCarriesStoredValueByNameAndReplacesDeclaration() {
        SkillEnvVars previous = new SkillEnvVars();
        previous.add(new SkillEnvVar("TAVILY_API_KEY", "old", true, "sk-stored"));
        previous.add(new SkillEnvVar("GONE_KEY", "old", false, "sk-gone"));

        SkillEnvVars declaredSet = new SkillEnvVars();
        declaredSet.add(new SkillEnvVar("TAVILY_API_KEY", "new description", false, ""));

        SkillEnvVars merged = SkillEnvDeclaration.mergeEnvDeclaration(previous, declaredSet);
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getName()).isEqualTo("TAVILY_API_KEY");
        assertThat(merged.get(0).getDescription()).isEqualTo("new description");
        assertThat(merged.get(0).isRequired()).isFalse();
        // 人输入的凭据按名字携带；重装不是删除它的请求
        assertThat(merged.get(0).getValue()).isEqualTo("sk-stored");
    }

    @Test
    void mergeReturnsNullWhenNothingDeclared() {
        assertThat(SkillEnvDeclaration.mergeEnvDeclaration(new SkillEnvVars(), new SkillEnvVars()))
                .isNull();
        assertThat(SkillEnvDeclaration.mergeEnvDeclaration(null, null)).isNull();
    }

    @Test
    void declaredEntryWithValueNeverCarriesValueOut() {
        SkillBundle b = bundle(Map.of("SKILL.md", "TAVILY_API_KEY"));
        SkillEnvVars out = SkillEnvDeclaration.validateEnvDeclarations(
                List.of(declared("TAVILY_API_KEY")), b);
        assertThat(out.get(0).getValue()).isEmpty();
    }
}
