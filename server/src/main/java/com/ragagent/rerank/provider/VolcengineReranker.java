package com.ragagent.rerank.provider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ragagent.rerank.GoJson;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.RerankHttp;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;

/**
 * 火山引擎（托管知识服务）rerank 客户端（对照 Go
 * {@code rerank/volcengine_reranker.go} 全文）。
 *
 * <p>Go 用 vikingdb-go-sdk（AuthIAM AK/SK 签名，service="air"）；本项目不允许新增
 * 依赖，Java 侧按 volcengine V4 签名（{@code base/sign.go} 的 HMAC-SHA256 方案）
 * 裸 HTTP 复刻：POST {@code {base}/api/knowledge/service/rerank}，请求体
 * {@code {"datas":[{"content":...,"query":...}],"rerank_instruction":...,"rerank_model":...}}
 * （map 键序 = 字母序，与 Go SDK 的 json tag 序一致）。</p>
 *
 * <p><b>批式语义逐条对照</b>（Go 测试钉住）：空 documents 直接返回空；API 单请求
 * 上限 50 条文档，超限切批<b>并发</b>（上限 4）重排后按原 index 合并——分数跨请求
 * 可比，不丢候选也不偏序；score 数量与文档数不等报 {@code score count mismatch}；
 * {@code code != 0} 报 {@code Volcengine rerank API error %d: %s}。</p>
 */
public final class VolcengineReranker implements Reranker {

    /** 对照 Go provider.VolcengineRerankBaseURL。 */
    public static final String RERANK_BASE_URL = "https://ark.cn-beijing.volces.com";

    static final String RERANK_PATH = "/api/knowledge/service/rerank";
    static final String DEFAULT_MODEL = "doubao-seed-rerank";
    static final String DEFAULT_REGION = "cn-beijing";
    static final String DEFAULT_INSTRUCTION =
            "Whether the Document answers the Query or matches the content retrieval intent";
public     static final int MAX_DOCUMENTS = 50;
    static final int MAX_CONCURRENCY = 4;

    private final String modelName;
    private final String instruction;
    private final String modelId;
    String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;

    public VolcengineReranker(RerankerConfig config) {
        String ak = config.getApiKey().trim();
        String sk = config.getAppSecret().trim();
        if (sk.isEmpty() && config.getExtraConfig() != null) {
            String v = config.getExtraConfig().get("secret_key");
            sk = (v == null ? "" : v).trim();
        }
        if (ak.isEmpty() || sk.isEmpty()) {
            throw new RerankHttp.RerankException(
                    "access key and secret key are required for Volcengine rerank");
        }
        String base = AliyunTrim.trimRight(config.getBaseUrl().trim(), '/');
        if (base.isEmpty()) {
            base = RERANK_BASE_URL;
        }
        RerankHttp.validateRerankBaseUrl(base);

        String name = config.getModelName().trim();
        if (name.isEmpty()) {
            name = DEFAULT_MODEL;
        }
        String r = DEFAULT_REGION;
        String ins = DEFAULT_INSTRUCTION;
        if (config.getExtraConfig() != null) {
            String v = config.getExtraConfig().get("region");
            if (v != null && !v.trim().isEmpty()) {
                r = v.trim();
            }
            v = config.getExtraConfig().get("instruction");
            if (v != null && !v.trim().isEmpty()) {
                ins = v.trim();
            }
        }
        this.modelName = name;
        this.instruction = ins;
        this.modelId = config.getModelId();
        this.endpoint = base;
        this.accessKey = ak;
        this.secretKey = sk;
        this.region = r;
    }

    @Override
    public List<RankResult> rerank(String query, List<String> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        List<RankResult> results = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            results.add(null);
        }
        Semaphore gate = new Semaphore(MAX_CONCURRENCY);
        List<Thread> threads = new ArrayList<>();
        RuntimeException[] first = new RuntimeException[1];
        for (int start = 0; start < documents.size(); start += MAX_DOCUMENTS) {
            final int s = start;
            final int e = Math.min(start + MAX_DOCUMENTS, documents.size());
            Thread t = Thread.ofVirtual().start(() -> {
                if (first[0] != null) {
                    return;
                }
                gate.acquireUninterruptibly();
                try {
                    if (first[0] != null) {
                        return;
                    }
                    List<Float> scores = rerankBatch(query, documents.subList(s, e));
                    for (int i = 0; i < scores.size(); i++) {
                        RankResult r = new RankResult();
                        r.setIndex(s + i);
                        r.getDocument().setText(documents.get(s + i));
                        r.setRelevanceScore(scores.get(i));
                        results.set(s + i, r);
                    }
                } catch (RuntimeException ex) {
                    synchronized (first) {
                        if (first[0] == null) {
                            first[0] = ex;
                        }
                    }
                } finally {
                    gate.release();
                }
            });
            threads.add(t);
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RerankHttp.RerankException("context canceled", e);
            }
        }
        if (first[0] != null) {
            throw first[0];
        }
        return results;
    }

    /** 单批打分（入参已确保 ≤ API 上限），按输入顺序返回相关性分。 */
    private List<Float> rerankBatch(String query, List<String> documents) {
        // datas[] 每项 {query, content}；Go SDK 实录顺序：datas → rerank_model →
        // rerank_instruction（A/B 钉住，不是字母序）
        var request = GoJson.object();
        ArrayNode datas = request.putArray("datas");
        for (String d : documents) {
            var item = datas.addObject();
            item.put("query", query == null ? "" : query);
            item.put("content", d == null ? "" : d);
        }
        request.put("rerank_model", modelName);
        request.put("rerank_instruction", instruction);
        byte[] payload = GoJson.marshal(request);

        RerankHttp.Result resp;
        try {
            resp = VolcengineSigner.post(endpoint + RERANK_PATH, region,
                    accessKey, secretKey, payload);
        } catch (Exception e) {
            throw new RerankHttp.RerankException("call Volcengine rerank: " + e.getMessage(), e);
        }
        if (resp.status() != 200) {
            throw new RerankHttp.RerankException("call Volcengine rerank: HTTP "
                    + resp.statusLine());
        }
        JsonNode root = GoJson.parse(resp.bodyText());
        if (root == null || (root.path("data").isMissingNode() || root.path("data").isNull())
                && root.path("code").asInt(0) == 0) {
            throw new RerankHttp.RerankException("Volcengine rerank returned an empty response");
        }
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw new RerankHttp.RerankException("Volcengine rerank API error " + code
                    + ": " + root.path("message").asText(""));
        }
        JsonNode scoresNode = root.path("data").path("scores");
        if (!scoresNode.isArray() || scoresNode.size() != documents.size()) {
            throw new RerankHttp.RerankException("Volcengine rerank score count mismatch: got "
                    + scoresNode.size() + " scores for " + documents.size() + " documents");
        }
        List<Float> scores = new ArrayList<>(documents.size());
        for (JsonNode s : scoresNode) {
            scores.add((float) s.asDouble());
        }
        return scores;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public String getModelID() {
        return modelId;
    }

    /** volcengine V4（HMAC-SHA256）签名 POST——对照 volc-sdk-golang base/sign.go。 */
    static final class VolcengineSigner {
        private VolcengineSigner() {
        }

        static RerankHttp.Result post(String url, String region, String accessKey,
                                      String secretKey, byte[] payload) throws Exception {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
            String formatDate = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            String date = formatDate.substring(0, 8);
            String service = "air";
            String bodyHash = sha256Hex(payload);
            String contentType = "application/json";

            // signed headers: content-type;host;x-content-sha256;x-date（sortHeaders 规则）
            String signedHeaders = "content-type;host;x-content-sha256;x-date";
            String canonicalHeaders = "content-type:" + contentType + "\n"
                    + "host:" + host + "\n"
                    + "x-content-sha256:" + bodyHash + "\n"
                    + "x-date:" + formatDate + "\n";
            String canonicalRequest = "POST\n" + normPath(uri.getPath()) + "\n\n"
                    + canonicalHeaders + signedHeaders + "\n" + bodyHash;
            String credentialScope = date + "/" + region + "/" + service + "/request";
            String stringToSign = "HMAC-SHA256\n" + formatDate + "\n" + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

            byte[] kDate = hmacSha256(secretKey.getBytes(StandardCharsets.UTF_8), date);
            byte[] kRegion = hmacSha256(kDate, region);
            byte[] kService = hmacSha256(kRegion, service);
            byte[] kSigning = hmacSha256(kService, "request");
            String signature = hex(hmacSha256(kSigning, stringToSign));

            String authorization = "HMAC-SHA256"
                    + " Credential=" + accessKey + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            var req = java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", contentType)
                    .header("X-Date", formatDate)
                    .header("X-Content-Sha256", bodyHash)
                    .header("Authorization", authorization)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<byte[]> resp =
                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            return new RerankHttp.Result(resp.statusCode(),
                    "HTTP " + resp.statusCode(),
                    new String(resp.body(), StandardCharsets.UTF_8));
        }

        static String normPath(String path) {
            return path == null || path.isEmpty() ? "/" : path;
        }

        static String sha256Hex(byte[] data) throws Exception {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        }

        static byte[] hmacSha256(byte[] key, String content) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        }

        static String hex(byte[] data) {
            StringBuilder sb = new StringBuilder(data.length * 2);
            for (byte b : data) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    /** 包内小工具（避免与 embedding 包互相依赖）。 */
    static final class AliyunTrim {
        private AliyunTrim() {
        }

        static String trimRight(String s, char c) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == c) {
                end--;
            }
            return s.substring(0, end);
        }
    }
}
