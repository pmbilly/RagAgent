package com.ragagent.knowledge.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.JsonMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 知识库配置 jsonb（{@code knowledge_bases.config} 列）的**序列化契约**。
 *
 * <p>为什么需要它：这一列是 jsonb、形状由 {@link KnowledgeBaseChunkingConfig} 等值类型决定，
 * 但**没有任何 HTTP 端点契约测试覆盖它**——改了字段名或去掉输出都可能悄无声息地改变落库形状，
 * 而前端知识库编辑器与 agent.management 的初始化端点都在读它。本测试把形状钉死：
 *
 * <ul>
 *   <li><b>键名 = Java 字段名</b>（camelCase；防回退成 snake）；</li>
 *   <li><b>字段一律输出</b>：空值输出 {@code null}、{@code false}/{@code 0} 照常输出（不再 omitempty）；</li>
 *   <li><b>round-trip 不丢字段</b>：JSON → 对象 → JSON 必须完全一致；</li>
 *   <li><b>读取容错</b>：null / 空对象 / 未知键 → 默认值；旧 snake 键**按政策不再映射**（无别名）。</li>
 * </ul>
 */
class KnowledgeBaseConfigJsonContractTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static Set<String> keysOf(JsonNode node) {
        return StreamSupport.stream(((Iterable<String>) () -> node.fieldNames()).spliterator(), false)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("分块配置：键名 = Java 字段名，且空值/假值一律输出")
    void chunkingConfigKeysArePinned() {
        JsonNode n = MAPPER.valueToTree(new KnowledgeBaseChunkingConfig());

        assertEquals(Set.of("chunkSize", "chunkOverlap", "separators", "parserEngineRules",
                "enableParentChild", "parentChunkSize", "childChunkSize", "strategy",
                "tokenLimit", "languages", "tableMetadataInstructions"), keysOf(n),
                "分块配置的键集合变了——落库 jsonb 形状随之改变，需同步前端与 agent.management 初始化端点");

        // 字段一律输出：null / false / 0 都要在
        assertTrue(n.has("separators") && n.get("separators").isNull(), "null 字段必须显式输出");
        assertTrue(n.has("enableParentChild") && !n.get("enableParentChild").asBoolean(),
                "false 必须显式输出（不能因等值省略）");
        assertTrue(n.has("chunkSize") && n.get("chunkSize").asInt() == 0, "0 必须显式输出");
        // 防回退：不得出现 snake 键
        assertFalse(n.has("chunk_size"), "键名必须是 Java 字段名（camelCase）");
    }

    @Test
    @DisplayName("分块配置：嵌套解析器引擎规则的形状")
    void parserEngineRuleShape() {
        KnowledgeBaseChunkingConfig.ParserEngineRule rule = new KnowledgeBaseChunkingConfig.ParserEngineRule();
        rule.setFileTypes(List.of("xlsx", "csv"));
        rule.setEngine("excel");
        rule.setXlsxFirstRowAsHeader(true);

        JsonNode n = MAPPER.valueToTree(rule);
        assertEquals(Set.of("fileTypes", "engine", "xlsxFirstRowAsHeader"), keysOf(n));
        assertEquals("excel", n.get("engine").asText());
        assertTrue(n.get("xlsxFirstRowAsHeader").asBoolean());
    }

    @Test
    @DisplayName("分块配置：JSON → 对象 → JSON 全字段保真")
    void chunkingConfigRoundTrips() {
        String json = """
                {"chunkSize":500,"chunkOverlap":50,"separators":["\\n\\n","。"],
                 "parserEngineRules":[{"fileTypes":["md"],"engine":"markdown","xlsxFirstRowAsHeader":null}],
                 "enableParentChild":true,"parentChunkSize":2000,"childChunkSize":300,
                 "strategy":"heading","tokenLimit":8000,"languages":["zh","en"],
                 "tableMetadataInstructions":"表头优先"}
                """;
        JsonNode original = read(json);
        KnowledgeBaseChunkingConfig parsed = KnowledgeBaseChunkingConfig.from(original);

        assertEquals(500, parsed.getChunkSize());
        assertEquals(List.of("\n\n", "。"), parsed.getSeparators());
        assertEquals("heading", parsed.getStrategy());
        assertEquals(1, parsed.getParserEngineRules().size());
        assertEquals(List.of("md"), parsed.getParserEngineRules().get(0).getFileTypes());
        assertEquals(original, MAPPER.valueToTree(parsed), "round-trip 后 JSON 必须与输入完全一致");
    }

    @Test
    @DisplayName("其余配置类型：JSON → 对象 → JSON 全字段保真（含恒输出的空串）")
    void otherConfigTypesRoundTrip() {
        assertRoundTrips("索引策略", """
                {"vectorEnabled":true,"keywordEnabled":false,"wikiEnabled":false,"graphEnabled":true}
                """, KnowledgeBaseIndexingStrategy.class);
        assertRoundTrips("VLM 配置", """
                {"enabled":true,"modelId":"qwen-vl","descriptionLanguage":"zh",
                 "customInstructions":"描述图片","modelName":"qwen-vl-max","baseUrl":"https://x/v1",
                 "apiKey":"","interfaceType":"openai"}
                """, KnowledgeBaseVlmConfig.class);
        assertRoundTrips("ASR 配置", """
                {"enabled":false,"modelId":"whisper","language":"zh"}
                """, KnowledgeBaseAsrConfig.class);
        assertRoundTrips("图像处理配置", """
                {"modelId":"qwen-vl"}
                """, KnowledgeBaseImageProcessingConfig.class);
        assertRoundTrips("对象存储配置", """
                {"secretId":"id","secretKey":"key","region":"ap-guangzhou","bucketName":"bucket",
                 "appId":"125","pathPrefix":"kb/","provider":"cos","endpoint":null,
                 "useSsl":false,"forcePathStyle":false}
                """, KnowledgeBaseStorageConfig.class);
        assertRoundTrips("存储提供方配置", """
                {"provider":"cos"}
                """, KnowledgeBaseStorageProviderConfig.class);
    }

    @Test
    @DisplayName("读取容错：null / 空对象 / 未知键 → 默认值；旧 snake 键按政策不再映射")
    void fromIsTolerant() {
        assertTrue(KnowledgeBaseChunkingConfig.from(null).getSeparators() == null, "null → 默认值");
        assertEquals(0, KnowledgeBaseChunkingConfig.from(read("{}")).getChunkSize(), "空对象 → 默认值");
        assertEquals(500, KnowledgeBaseChunkingConfig.from(read("{\"chunkSize\":500,\"unknownKey\":1}"))
                .getChunkSize(), "未知键必须被忽略（lenient）");
        assertEquals(0, KnowledgeBaseChunkingConfig.from(read("{\"chunk_size\":999}")).getChunkSize(),
                "旧 snake 键不再映射（无别名政策）——命中即为回归");
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试用 JSON 不合法: " + json, e);
        }
    }

    private static <T> void assertRoundTrips(String label, String json, Class<T> type) {
        JsonNode original = read(json);
        try {
            T parsed = MAPPER.treeToValue(original, type);
            assertEquals(original, MAPPER.valueToTree(parsed), label + " round-trip 后应完全一致");
        } catch (Exception e) {
            throw new AssertionError(label + " 解析失败（字段缺 setter？）", e);
        }
    }
}
