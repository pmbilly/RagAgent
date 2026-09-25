package com.ragagent.retrieval.engine.tencentvectordb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * jieba 分词<b>差分测试</b>：Java {@link JiebaTokenizer} vs Go 基准
 * （{@code scripts/jieba-diff-probe/}，gse v0.80.3 + SDK 同参数）。
 *
 * <p>基准 {@code src/test/resources/jieba/jieba_baseline.json} 由 Go 探针生成，三份可对照：
 * {@code cutHmmOn}（裸 {@code seg.Cut(s, true)}）、{@code cutHmmOff}（关 HMM 的对照，本仓不实现）、
 * {@code sdkTokenize}（含停用词过滤的 SDK 出口）。本测试对前两者之一的 {@code cutHmmOn} 与
 * {@code sdkTokenize} 逐句、逐 token、逐序断言。</p>
 *
 * <p>重录基线（Go 侧行为变更时）：{@code cd scripts/jieba-diff-probe && 见 main.go 顶部命令}。</p>
 */
class JiebaTokenizerDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode baseline() throws Exception {
        try (InputStream in = JiebaTokenizerDiffTest.class
                .getResourceAsStream("/jieba/jieba_baseline.json")) {
            assertThat(in).as("基准缺失：server/src/test/resources/jieba/jieba_baseline.json").isNotNull();
            return MAPPER.readTree(in);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            out.add(node.asText());
        }
        return out;
    }

    @Test
    @DisplayName("前提守卫：基准里 Go 侧词典为空（LoadDict(\"\") 不加载任何词典 → 纯 HMM）")
    void goSideDictionaryIsEmpty() throws Exception {
        JsonNode dict = baseline().path("dict");
        assertThat(dict.path("totalFreq").asDouble())
                .as("若不为 0，说明 Go 侧行为变了（不再是 LoadDict(\"\") 的空词典路径），"
                        + "本仓的 HMM-only 复刻需要重新勘察")
                .isZero();
        assertThat(dict.path("numTokens").asInt()).isZero();
    }

    @Test
    @DisplayName("差分：裸切分 cut() 与 Go seg.Cut(s,true) 逐句逐 token 一致")
    void rawCutMatchesGo() throws Exception {
        JsonNode cases = baseline().path("cutHmmOn");
        JiebaTokenizer tokenizer = new JiebaTokenizer(Set.of());

        int checked = 0;
        int multiToken = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = cases.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            List<String> expected = strings(entry.getValue());
            List<String> actual = tokenizer.cut(entry.getKey());
            assertThat(actual).as("cut(%s)", entry.getKey()).containsExactlyElementsOf(expected);
            checked++;
            if (expected.size() > 1) {
                multiToken++;
            }
        }
        assertThat(checked).as("语料句数").isGreaterThanOrEqualTo(10);
        assertThat(multiToken).as("多 token 的句子数（防止语料退化成单 token 的假绿）")
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("差分：tokens()（含停用词过滤）与 Go sdkTokenize 逐句逐 token 一致")
    void sdkTokenizeMatchesGo() throws Exception {
        Path stopWordsFile =
                Path.of(TencentVectorDbBm25.DEFAULT_STORAGE_DIR, TencentVectorDbBm25.STOPWORDS_FILE);
        assumeTrue(Files.exists(stopWordsFile),
                "停用词缓存不在（" + stopWordsFile + "）→ 只跑裸切分差分（同一逻辑已由 rawCutMatchesGo 覆盖）");

        // 照 TencentVectorDbBm25.loadStopWords 的读法（行原样入集合）
        Set<String> stopWords = new HashSet<>(Files.readAllLines(stopWordsFile, StandardCharsets.UTF_8));
        JsonNode cases = baseline().path("sdkTokenize");
        JiebaTokenizer tokenizer = new JiebaTokenizer(stopWords);

        int checked = 0;
        int filteredSomewhere = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = cases.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            List<String> expected = strings(entry.getValue());
            List<String> raw = tokenizer.cut(entry.getKey());
            List<String> actual = tokenizer.tokens(entry.getKey());
            assertThat(actual).as("tokens(%s)", entry.getKey()).containsExactlyElementsOf(expected);
            if (raw.size() != actual.size()) {
                filteredSomewhere++;
            }
            checked++;
        }
        assertThat(checked).as("语料句数").isGreaterThanOrEqualTo(10);
        assertThat(filteredSomewhere).as("至少有一句真的被停用词过滤过（否则这条用例没测到过滤）")
                .isGreaterThanOrEqualTo(1);
    }
}
