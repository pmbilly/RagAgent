package com.ragagent.retrieval.engine.tencentvectordb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbBm25.SparseVecItem;

/**
 * 腾讯 BM25 编码器对照 <b>Go SDK v1.8.4 的实测输出</b>（基准生成命令：在 WeKnora 仓跑
 * {@code encoder.NewBM25Encoder(&BM25EncoderParams{Bm25Language:"zh"})} 后打印
 * {@code EncodeText/EncodeQuery}，参数文件即 COS 的 {@code bm25_zh_default.json}）。
 *
 * <p>基准值（取自实跑）：
 * <pre>
 * "中文检索测试 hello" → ids [1872693679, 4269123640, 613153351]
 *   DOC   三项均 0.7627807
 *   QUERY {1872693679:0.44923997, 4269123640:0.10152008, 613153351:0.44923997}
 * "第二条 中文 hello world" → ids [1132926213, 3676729751, 613153351, 4220927227]
 *   DOC   四项均 0.7606547
 *   QUERY {4220927227:0.38338965, 1132926213:0.16701655, 3676729751:0.066204146, 613153351:0.38338965}
 * "腾讯 向量 数据库" → ids [2502995674, 1169440797, 1075178782]
 *   DOC   三项均 0.7627807
 *   QUERY {2502995674:0.3233622, 1169440797:0.33634922, 1075178782:0.34028858}
 * murmur3: "hello"→613153351, "world"→4220927227, "the"→3162218338, "中文"→3676729751,
 *          "腾讯"→2502995674, "数据库"→1075178782, ""→0, "a"→1009084850
 * </pre>
 * （DF 与语料统计取自 {@code bm25_zh_default.json}：b=0.75、k1=1.2、doc_count=382835、
 * avg_doc_len=245.61638；测试用内存参数表复现同值——不依赖 85 MB 的线上文件。）</p>
 *
 * <p><b>分词接缝</b>：这里用"按 token ID 直算"的口绕开分词（Go 的 ID 由 gse/jieba 分词后
 * murmur3 得到）；Java 默认分词是仓库既有近似实现，<b>与 jieba 不逐词一致</b>——故只校验
 * 哈希与 BM25 数学（它们与分词无关），分词差异见 known-issues。</p>
 */
class TencentVectorDbBm25Test {

    private static final long DOC_COUNT = 382835L;
    private static final double AVG_DOC_LEN = 245.61638;

    /** 测试 token 的真实 df（从 bm25_zh_default.json 摘出）。 */
    private static final Map<String, Double> DF = Map.of(
            "1872693679", 0.0,
            "4269123640", 17919.0,
            "613153351", 0.0,
            "1132926213", 1046.0,
            "3676729751", 36893.0,
            "4220927227", 0.0,
            "2502995674", 6581.0,
            "1169440797", 5590.0,
            "1075178782", 5320.0);

    private static TencentVectorDbBm25 encoder() {
        return TencentVectorDbBm25.inMemory(0.75, 1.2, DOC_COUNT, AVG_DOC_LEN,
                new LinkedHashMap<>(DF), TencentVectorDbBm25.fixedTokenizer(List.of()));
    }

    private static Map<Long, Float> byId(List<SparseVecItem> items) {
        Map<Long, Float> out = new LinkedHashMap<>();
        for (SparseVecItem item : items) {
            out.put(item.termId(), item.score());
        }
        return out;
    }

    @Test
    @DisplayName("murmur3 x86_32 与 Go（spaolacci/murmur3）逐值一致")
    void murmur3MatchesGo() {
        assertThat(TencentVectorDbBm25.murmur3_32("hello".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(613153351L);
        assertThat(TencentVectorDbBm25.murmur3_32("world".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(4220927227L);
        assertThat(TencentVectorDbBm25.murmur3_32("the".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(3162218338L);
        assertThat(TencentVectorDbBm25.murmur3_32("中文".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(3676729751L);
        assertThat(TencentVectorDbBm25.murmur3_32("腾讯".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(2502995674L);
        assertThat(TencentVectorDbBm25.murmur3_32("数据库".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(1075178782L);
        assertThat(TencentVectorDbBm25.murmur3_32("a".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(1009084850L);
        assertThat(TencentVectorDbBm25.murmur3_32(new byte[0])).isEqualTo(0L);
    }

    @Test
    @DisplayName("文档权重：tf 归一（各 token 出现一次 → 同一权重，1.2*(1-0.75+0.75*len/avg) 形态）")
    void documentWeightsMatchGo() {
        TencentVectorDbBm25 encoder = encoder();
        Map<Long, Float> docA = byId(encoder.encodeTextFromIds(
                List.of(1872693679L, 4269123640L, 613153351L), List.of(1L, 1L, 1L)));
        assertThat(docA).containsOnlyKeys(1872693679L, 4269123640L, 613153351L);
        for (float score : docA.values()) {
            assertThat((double) score).isCloseTo(0.7627807, within(1e-6));
        }

        Map<Long, Float> docB = byId(encoder.encodeTextFromIds(
                List.of(1132926213L, 3676729751L, 613153351L, 4220927227L),
                List.of(1L, 1L, 1L, 1L)));
        for (float score : docB.values()) {
            assertThat((double) score).isCloseTo(0.7606547, within(1e-6));
        }
    }

    @Test
    @DisplayName("查询权重：idf 归一（df=0 的 token 拿到大 idf；逐值与 Go 一致）")
    void queryWeightsMatchGo() {
        TencentVectorDbBm25 encoder = encoder();
        Map<Long, Float> queryA = byId(encoder.encodeQueryFromIds(
                List.of(1872693679L, 4269123640L, 613153351L)));
        assertThat(queryA.get(1872693679L)).isCloseTo(0.44923997f, within(1e-6f));
        assertThat(queryA.get(4269123640L)).isCloseTo(0.10152008f, within(1e-6f));
        assertThat(queryA.get(613153351L)).isCloseTo(0.44923997f, within(1e-6f));

        Map<Long, Float> queryB = byId(encoder.encodeQueryFromIds(
                List.of(1132926213L, 3676729751L, 613153351L, 4220927227L)));
        assertThat(queryB.get(4220927227L)).isCloseTo(0.38338965f, within(1e-6f));
        assertThat(queryB.get(1132926213L)).isCloseTo(0.16701655f, within(1e-6f));
        assertThat(queryB.get(3676729751L)).isCloseTo(0.066204146f, within(1e-6f));
        assertThat(queryB.get(613153351L)).isCloseTo(0.38338965f, within(1e-6f));

        Map<Long, Float> queryC = byId(encoder.encodeQueryFromIds(
                List.of(2502995674L, 1169440797L, 1075178782L)));
        assertThat(queryC.get(2502995674L)).isCloseTo(0.3233622f, within(1e-6f));
        assertThat(queryC.get(1169440797L)).isCloseTo(0.33634922f, within(1e-6f));
        assertThat(queryC.get(1075178782L)).isCloseTo(0.34028858f, within(1e-6f));
    }

    @Test
    @DisplayName("查询 idf 归一后权重和 ≈ 1；文档权重分母含 |D| 归一")
    void normalizationInvariants() {
        TencentVectorDbBm25 encoder = encoder();
        List<SparseVecItem> query = encoder.encodeQueryFromIds(
                List.of(1132926213L, 3676729751L, 613153351L, 4220927227L));
        double sum = query.stream().mapToDouble(SparseVecItem::score).sum();
        assertThat(sum).isCloseTo(1.0, within(1e-6));

        // 同一文本重复出现两次（tf=2）：权重必须 > tf=1 的情形
        Map<Long, Float> tf2 = byId(encoder.encodeTextFromIds(List.of(613153351L), List.of(2L)));
        assertThat((double) tf2.get(613153351L)).isGreaterThan(0.7627807);
    }
}
