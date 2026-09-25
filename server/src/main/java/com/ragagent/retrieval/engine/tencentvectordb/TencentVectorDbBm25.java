package com.ragagent.retrieval.engine.tencentvectordb;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.ragagent.searchutil.SearchTextUtil;

/**
 * 腾讯 VectorDB 的客户端 BM25 稀疏向量编码——对照 SDK {@code tcvdbtext/encoder} 的
 * {@code BM25Encoder}（v1.8.4）：
 *
 * <ul>
 *   <li><b>分词</b>：Go 用 {@code gse/jieba}（HMM 开、cutAll 关、forSearch 关）+ 停用词表；</li>
 *   <li><b>哈希</b>：token → <b>murmur3 32 位</b>（{@code spaolacci/murmur3}，x86_32 种子 0）
 *       → 无符号 int64 的十进制串；</li>
 *   <li><b>文档权重</b>：{@code tf/(K1*(1-B+B*(len/avgDocLen))+tf)}（B=0.75/K1=1.2）；</li>
 *   <li><b>查询权重</b>：{@code idf=ln((docCount+1)/(df+0.5))} 再按 Σidf 归一化；</li>
 *   <li><b>语料统计</b>：从 COS 下载的 {@code bm25_zh_default.json}（85 MB / 389 万词条：
 *       b/k1/doc_count/average_doc_length/token_freq），缓存于
 *       {@code /tmp/tencent/vectordatabase/data/}（照 Go 的 {@code DefaultStorageDir}）。</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ol>
 *   <li><b>分词接缝</b>：Go 内嵌 gse（jieba 词典 + HMM）；本仓复用既有分词接缝
 *       {@link SearchTextUtil#segmenter()}（默认是"按空白 + CJK 二字滑窗"的近似实现，
 *       见 SearchTextUtil 的降级说明）。因此 <b>Java 写出的稀疏向量与 Go/jieba 写出的
 *       不逐词一致</b>——同一 collection 的读写需同一实现（跨实现迁移需重导入数据）。
 *       接缝可替换：将来接入真实 jieba 后即与 Go 同源。</li>
 *   <li>统计表解析：Go {@code json.Unmarshal} 进 {@code map[string]float64}（数百 MB 堆）；
 *       本仓用<b>流式解析 + 排序长整型数组</b>（约 47 MB）——查询结果一致，查找走二分。</li>
 *   <li>停用词：Go 默认从 COS 下 {@code default_stopwords.txt}（1.1 KB）并启用；本仓同款
 *       （下载失败只 WARN 且不去停用词，不阻塞主链）。</li>
 * </ol>
 */
public final class TencentVectorDbBm25 {

    private static final Logger log = LoggerFactory.getLogger(TencentVectorDbBm25.class);

    /** 照 Go {@code DefaultBM25EncoderB} / {@code DefaultBM25EncoderK1}。 */
    static final double DEFAULT_B = 0.75;
    static final double DEFAULT_K1 = 1.2;

    /** 照 Go {@code tcvdbtext.DefaultStorageDir}（下载缓存目录）。 */
    public static final String DEFAULT_STORAGE_DIR = "/tmp/tencent/vectordatabase/data/";
    static final String COS_SPARSEVECTOR_DIR =
            "https://vectordb-public-1310738255.cos.ap-guangzhou.myqcloud.com/sparsevector/";
    static final String ZH_PARAMS_FILE = "bm25_zh_default.json";
    static final String STOPWORDS_FILE = "default_stopwords.txt";

    /** 一条稀疏向量项（照 {@code encoder.SparseVecItem}）。 */
    public record SparseVecItem(long termId, float score) {
    }

    /** 分词接缝（默认走仓库既有近似分词；可替换为真实 jieba）。 */
    public interface Tokenizer {

        List<String> tokens(String text);
    }

    private final double b;
    private final double k1;
    private final long docCount;
    private final double averageDocLength;
    /** DF 表：key 为 token 的无符号 32 位哈希（十进制串）转 long，升序。 */
    private final long[] tokenKeys;
    private final double[] tokenFreqs;
    private final Tokenizer tokenizer;

    TencentVectorDbBm25(double b, double k1, long docCount, double averageDocLength,
                        long[] tokenKeys, double[] tokenFreqs, Tokenizer tokenizer) {
        this.b = b;
        this.k1 = k1;
        this.docCount = docCount;
        this.averageDocLength = averageDocLength;
        this.tokenKeys = tokenKeys;
        this.tokenFreqs = tokenFreqs;
        this.tokenizer = tokenizer;
    }

    /** 入口：下载/缓存参数 + 停用词，构造编码器（照 Go 的 {@code SetDefaultParams("zh")}）。 */
    public static TencentVectorDbBm25 create() {
        Path dir = Path.of(DEFAULT_STORAGE_DIR);
        Path paramsFile = dir.resolve(ZH_PARAMS_FILE);
        try {
            ensureCached(dir, paramsFile, COS_SPARSEVECTOR_DIR + ZH_PARAMS_FILE);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "tencent vectordb init BM25 encoder: cannot fetch " + ZH_PARAMS_FILE
                            + ": " + e.getMessage(), e);
        }
        Set<String> stopWords = loadStopWords(dir);
        Params params = parseParams(paramsFile);
        Tokenizer tokenizer = new SeamTokenizer(stopWords);
        log.info("[TencentVectorDB] BM25 encoder ready: docCount={}, avgDocLen={}, tokens={}",
                params.docCount(), params.averageDocLength(), params.tokenKeys().length);
        return new TencentVectorDbBm25(params.b(), params.k1(), params.docCount(),
                params.averageDocLength(), params.tokenKeys(), params.tokenFreqs(), tokenizer);
    }

    /** 测试/离线口：用内存参数构造（不触网）。 */
    static TencentVectorDbBm25 inMemory(double b, double k1, long docCount, double avgDocLen,
                                        Map<String, Double> tokenFreq, Tokenizer tokenizer) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(tokenFreq.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        long[] keys = new long[entries.size()];
        double[] freqs = new double[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            keys[i] = Long.parseUnsignedLong(entries.get(i).getKey());
            freqs[i] = entries.get(i).getValue();
        }
        return new TencentVectorDbBm25(b, k1, docCount, avgDocLen, keys, freqs, tokenizer);
    }

    /** 写库：文本 → 稀疏向量（照 {@code EncodeTexts} 的每篇 tf 归一）。 */
    public List<SparseVecItem> encodeText(String text) {
        List<Long> hashes = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        tf(text, hashes, counts);
        return encodeTextFromIds(hashes, counts);
    }

    /** 供测试/校验：按 token ID（已 murmur3）+ 词频直接算文档权重（绕开分词接缝）。 */
    List<SparseVecItem> encodeTextFromIds(List<Long> ids, List<Long> counts) {
        long sum = 0;
        for (long c : counts) {
            sum += c;
        }
        List<SparseVecItem> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            double tfNorm = counts.get(i)
                    / (k1 * (1.0 - b + b * ((double) sum / averageDocLength)) + counts.get(i));
            out.add(new SparseVecItem(ids.get(i), (float) tfNorm));
        }
        return out;
    }

    /** 检索：查询文本 → 稀疏向量（照 {@code EncodeQueries} 的 idf 归一）。 */
    public List<SparseVecItem> encodeQuery(String text) {
        List<Long> hashes = new ArrayList<>();
        List<Long> ignored = new ArrayList<>();
        tf(text, hashes, ignored);
        return encodeQueryFromIds(hashes);
    }

    /** 供测试/校验：按 token ID（已去重）直接算查询权重。 */
    List<SparseVecItem> encodeQueryFromIds(List<Long> hashes) {
        int n = hashes.size();
        double[] df = new double[n];
        for (int i = 0; i < n; i++) {
            df[i] = dfOf(hashes.get(i));
        }
        double[] idf = new double[n];
        double idfSum = 0;
        for (int i = 0; i < n; i++) {
            idf[i] = Math.log((docCount + 1.0) / (df[i] + 0.5));
            idfSum += idf[i];
        }
        List<SparseVecItem> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new SparseVecItem(hashes.get(i), (float) (idf[i] / idfSum)));
        }
        return out;
    }

    /** 对照 {@code tf}：分词 → 逐 token 计数（保留首次出现顺序，照 Go 的 map 迭代顺序无关紧要）。 */
    private void tf(String text, List<Long> hashes, List<Long> counts) {
        Map<Long, Long> counter = new LinkedHashMap<>();
        List<String> tokens = tokenizer.tokens(text == null ? "" : text);
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            long hash = murmur3_32(token.getBytes(StandardCharsets.UTF_8));
            counter.merge(hash, 1L, Long::sum);
        }
        for (Map.Entry<Long, Long> e : counter.entrySet()) {
            hashes.add(e.getKey());
            counts.add(e.getValue());
        }
    }

    /** DF 查表（照 Go：缺失 = 0）。 */
    private double dfOf(long termId) {
        int idx = java.util.Arrays.binarySearch(tokenKeys, termId);
        return idx >= 0 ? tokenFreqs[idx] : 0d;
    }

    // ── murmur3 x86_32（种子 0）——照 spaolacci/murmur3 的 New32() ──────────

    /** 返回无符号 32 位值（放到 long 里，照 Go 的 {@code int64(Sum32())}）。 */
    public static long murmur3_32(byte[] data) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h1 = 0;
        int len = data.length;
        int nblocks = len / 4;
        for (int i = 0; i < nblocks; i++) {
            int k1 = (data[i * 4] & 0xff) | ((data[i * 4 + 1] & 0xff) << 8)
                    | ((data[i * 4 + 2] & 0xff) << 16) | ((data[i * 4 + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        int k1 = 0;
        int tail = nblocks * 4;
        switch (len & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xff) << 16;
                // fallthrough
            case 2:
                k1 ^= (data[tail + 1] & 0xff) << 8;
                // fallthrough
            case 1:
                k1 ^= data[tail] & 0xff;
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
                break;
            default:
                break;
        }
        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1 & 0xffffffffL;
    }

    // ── 参数文件 ───────────────────────────────────────────────────────────

    record Params(double b, double k1, long docCount, double averageDocLength, long[] tokenKeys,
                  double[] tokenFreqs) {
    }

    /** 流式解析（85 MB / 389 万词条）：token_freq 进排序数组，其余为标量。 */
    static Params parseParams(Path file) {
        double b = DEFAULT_B;
        double k1 = DEFAULT_K1;
        long docCount = 0;
        double avgDocLen = 0;
        Map<String, Double> freq = new HashMap<>();
        JsonFactory factory = new JsonFactory();
        try (JsonParser parser = factory.createParser(Files.newInputStream(file))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("bm25 params: not a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (name) {
                    case "b" -> b = parser.getDoubleValue();
                    case "k1" -> k1 = parser.getDoubleValue();
                    case "doc_count" -> docCount = parser.getLongValue();
                    case "average_doc_length" -> avgDocLen = parser.getDoubleValue();
                    case "token_freq" -> {
                        if (value == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String token = parser.currentName();
                                parser.nextToken();
                                freq.put(token, parser.getDoubleValue());
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    default -> {
                        if (value != null && value.isStructStart()) {
                            parser.skipChildren();
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("parse bm25 params " + file + ": " + e.getMessage(), e);
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(freq.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        long[] keys = new long[entries.size()];
        double[] freqs = new double[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            keys[i] = Long.parseUnsignedLong(entries.get(i).getKey());
            freqs[i] = entries.get(i).getValue();
        }
        return new Params(b, k1, docCount, avgDocLen, keys, freqs);
    }

    private static void ensureCached(Path dir, Path file, String url) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(dir);
        log.warn("[TencentVectorDB] downloading {} to {} (first use, ~85MB)", url, file);
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("download " + url + " failed: HTTP " + resp.statusCode());
        }
        Path tmp = dir.resolve(file.getFileName() + ".part");
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Set<String> loadStopWords(Path dir) {
        Path file = dir.resolve(STOPWORDS_FILE);
        try {
            if (!Files.exists(file)) {
                ensureCached(dir, file, COS_SPARSEVECTOR_DIR + STOPWORDS_FILE);
            }
            return new HashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[TencentVectorDB] stopwords unavailable ({}), continuing without them",
                    e.getMessage());
            return Set.of();
        }
    }

    /** 默认分词：仓库既有接缝 + 停用词过滤（照 Go 的 StopWordsEnable=true）。 */
    static final class SeamTokenizer implements Tokenizer {

        private final Set<String> stopWords;

        SeamTokenizer(Set<String> stopWords) {
            this.stopWords = stopWords;
        }

        @Override
        public List<String> tokens(String text) {
            List<String> raw = SearchTextUtil.segmenter().cutForSearch(text);
            List<String> out = new ArrayList<>(raw.size());
            for (String token : raw) {
                if (token.isEmpty() || stopWords.contains(token)) {
                    continue;
                }
                out.add(token);
            }
            return out;
        }
    }

    /** 测试用：固定分词结果（把分词接缝钉死，验证 BM25 数学与哈希）。 */
    static Tokenizer fixedTokenizer(List<String> tokens) {
        return text -> tokens;
    }
}
