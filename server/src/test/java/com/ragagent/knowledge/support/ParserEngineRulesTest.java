package com.ragagent.knowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 解析引擎规则解析的验收（对照 Go ResolveChatParserEngine / DefaultParserEngine /
 * normalizeParserFileType；agent 与租户两份规则的同一语义）。
 */
class ParserEngineRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode rules(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void normalizesDotsCaseAndSpaces() {
        assertThat(ParserEngineRules.normalize(" .PDF ")).isEqualTo("pdf");
        assertThat(ParserEngineRules.normalize("Pdf")).isEqualTo("pdf");
        assertThat(ParserEngineRules.normalize(null)).isEmpty();
        assertThat(ParserEngineRules.normalize(".tar.gz")).isEqualTo("tar.gz");
    }

    @Test
    void defaultEngineOnlyMapsPptFamily() {
        assertThat(ParserEngineRules.defaultEngine("ppt")).isEqualTo("markitdown");
        assertThat(ParserEngineRules.defaultEngine(".PPTX")).isEqualTo("markitdown");
        assertThat(ParserEngineRules.defaultEngine("pdf")).isEmpty();
        assertThat(ParserEngineRules.defaultEngine("")).isEmpty();
    }

    @Test
    void firstMatchingRuleWinsWithTrimmedEngine() {
        JsonNode rules = rules("""
                [{"file_types":[".PDF","docx"],"engine":" mineru "},
                 {"file_types":["pdf"],"engine":"paddleocr_vl"}]""");
        assertThat(ParserEngineRules.resolve(rules, "pdf")).isEqualTo("mineru");
        assertThat(ParserEngineRules.resolve(rules, ".PDF")).isEqualTo("mineru");
        assertThat(ParserEngineRules.resolve(rules, "docx")).isEqualTo("mineru");
    }

    @Test
    void noHitFallsBackToDefaultEngine() {
        JsonNode rules = rules("[{\"fileTypes\":[\"pdf\"],\"engine\":\"mineru\"}]");
        assertThat(ParserEngineRules.resolve(rules, "ppt")).isEqualTo("markitdown");
        assertThat(ParserEngineRules.resolve(rules, "txt")).isEmpty();
    }

    @Test
    void malformedRulesFallBackToDefault() {
        assertThat(ParserEngineRules.resolve(null, "ppt")).isEqualTo("markitdown");
        assertThat(ParserEngineRules.resolve(rules("{}"), "ppt")).isEqualTo("markitdown");
        assertThat(ParserEngineRules.resolve(rules("[]"), "pdf")).isEmpty();
        // 规则缺 file_types / 元素非对象：跳过而不是抛
        assertThat(ParserEngineRules.resolve(
                rules("[{\"engine\":\"mineru\"}, \"x\"]"), "pdf")).isEmpty();
    }
}
