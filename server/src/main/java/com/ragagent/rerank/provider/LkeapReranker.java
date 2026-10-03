package com.ragagent.rerank.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.ProviderJson;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.RerankHttp;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;

/**
 * 腾讯云 LKEAP rerank 客户端（对照 Go {@code rerank/lkeap_reranker.go} 全文）。
 *
 * <p>Go 用 tencentcloud-sdk-go；本项目不允许新增依赖，Java 侧按 TC3-HMAC-SHA256
 * 签名规范<b>裸 HTTP 复刻</b> SDK 的线格式：POST
 * {@code https://lkeap.tencentcloudapi.com}，请求体
 * {@code {"Docs":[...],"Model":...,"Query":...}}（SDK struct 序），头
 * {@code X-TC-Action: RunRerank / X-TC-Version: 2024-05-22 / Authorization: TC3-HMAC-SHA256 ...}。</p>
 *
 * <p><b>批式语义逐条对照</b>（Go 测试钉住）：空 documents 直接返回空；每请求最多
 * 60 条文档、Query+Docs 合计最多 2000 字符（rune 计）；超限的单条文档报错；
 * 超批的分片各自请求后按批起点平移 index 合并。</p>
 */
public final class LkeapReranker implements Reranker {

    /** RunRerank 单请求最大文档数。 */
    public static final int MAX_DOCUMENTS_PER_REQUEST = 60;
    /** Query 与 Docs 合计最大字符数。 */
    public static final int MAX_REQUEST_CHARACTERS = 2000;
    public static final String RERANK_ENDPOINT = "lkeap.tencentcloudapi.com";
    public static final String DEFAULT_REGION = "ap-guangzhou";
    public static final String DEFAULT_RERANK_MODEL = "lke-reranker-base";

    private static final String ACTION = "RunRerank";
    private static final String VERSION = "2024-05-22";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final String modelName;
    private final String modelId;
    private final String secretId;
    private final String secretKey;
    private final String region;

    public LkeapReranker(RerankerConfig config) {
        String id = config.getApiKey().trim();
        String key = config.getAppSecret().trim();
        if (key.isEmpty() && config.getExtraConfig() != null) {
            String v = config.getExtraConfig().get("secret_key");
            key = (v == null ? "" : v).trim();
        }
        if (id.isEmpty() || key.isEmpty()) {
            throw new RerankHttp.RerankException(
                    "secret_id and secret_key are required for LKEAP rerank (set API Key and Secret Key)");
        }
        String r = DEFAULT_REGION;
        if (config.getExtraConfig() != null) {
            String v = config.getExtraConfig().get("region");
            if (v != null && !v.trim().isEmpty()) {
                r = v.trim();
            }
        }
        String name = config.getModelName().trim();
        if (name.isEmpty()) {
            name = DEFAULT_RERANK_MODEL;
        }
        this.modelName = name;
        this.modelId = config.getModelId();
        this.secretId = id;
        this.secretKey = key;
        this.region = r;
    }

    @Override
    public List<RankResult> rerank(String query, List<String> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        List<Batch> batches = lkeapRerankBatches(query, documents);
        List<RankResult> results = new ArrayList<>(documents.size());
        for (Batch batch : batches) {
            List<RankResult> batchResults = rerankBatch(query, batch.documents());
            for (RankResult br : batchResults) {
                br.setIndex(br.getIndex() + batch.start());
            }
            results.addAll(batchResults);
        }
        return results;
    }

    public record Batch(int start, List<String> documents) {
    }

    /** 对照 lkeapRerankBatches：60 条/2000 字符双限切批；单条超限即错。 */
    public static List<Batch> lkeapRerankBatches(String query, List<String> documents) {
        int queryLength = runeCount(query);
        if (queryLength >= MAX_REQUEST_CHARACTERS) {
            throw new RerankHttp.RerankException("LKEAP rerank query is " + queryLength
                    + " characters; Query and Docs together support at most "
                    + MAX_REQUEST_CHARACTERS + " characters");
        }
        List<Batch> batches = new ArrayList<>();
        int batchStart = 0;
        int batchLength = queryLength;
        List<String> batchDocuments = new ArrayList<>(MAX_DOCUMENTS_PER_REQUEST);
        for (int index = 0; index < documents.size(); index++) {
            String document = documents.get(index);
            int documentLength = runeCount(document);
            if (queryLength + documentLength > MAX_REQUEST_CHARACTERS) {
                throw new RerankHttp.RerankException("LKEAP rerank document at index " + index
                        + " is " + documentLength + " characters; Query and each document together support at most "
                        + MAX_REQUEST_CHARACTERS + " characters");
            }
            if (batchDocuments.size() == MAX_DOCUMENTS_PER_REQUEST
                    || batchLength + documentLength > MAX_REQUEST_CHARACTERS) {
                batches.add(new Batch(batchStart, batchDocuments));
                batchStart = index;
                batchLength = queryLength;
                batchDocuments = new ArrayList<>(MAX_DOCUMENTS_PER_REQUEST);
            }
            batchDocuments.add(document);
            batchLength += documentLength;
        }
        if (!batchDocuments.isEmpty()) {
            batches.add(new Batch(batchStart, batchDocuments));
        }
        return batches;
    }

    private List<RankResult> rerankBatch(String query, List<String> documents) {
        // 对照 lkeap SDK 的 RunRerankRequest struct 序：Query/Docs/Model（models.go 实测）
        var body = ProviderJson.object();
        body.put("Query", query == null ? "" : query);
        var docs = body.putArray("Docs");
        for (String d : documents) {
            docs.add(d == null ? "" : d);
        }
        body.put("Model", modelName);
        byte[] payload = ProviderJson.marshal(body);

        RerankHttp.Result resp;
        try {
            resp = Tc3Signer.post(RERANK_ENDPOINT, ACTION, VERSION, region,
                    secretId, secretKey, payload);
        } catch (RerankHttp.RerankException e) {
            throw new RerankHttp.RerankException("LKEAP RunRerank: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RerankHttp.RerankException("LKEAP RunRerank: " + e.getMessage(), e);
        }
        if (resp.status() != 200) {
            throw new RerankHttp.RerankException("LKEAP RunRerank: HTTP " + resp.statusLine());
        }
        JsonNode root = ProviderJson.parse(resp.bodyText());
        JsonNode response = root == null ? null : root.path("Response");
        JsonNode scoreList = response == null ? null : response.path("ScoreList");
        if (scoreList == null || !scoreList.isArray() || scoreList.isEmpty()) {
            throw new RerankHttp.RerankException("LKEAP rerank API returned empty score list");
        }
        if (scoreList.size() != documents.size()) {
            throw new RerankHttp.RerankException("LKEAP rerank score count mismatch: got "
                    + scoreList.size() + " scores for " + documents.size() + " documents");
        }
        List<RankResult> results = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            JsonNode score = scoreList.get(i);
            RankResult r = new RankResult();
            r.setIndex(i);
            r.getDocument().setText(documents.get(i));
            r.setRelevanceScore(score == null || score.isNull() || !score.isNumber()
                    ? 0 : score.asDouble());
            results.add(r);
        }
        return results;
    }

    static int runeCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public String getModelID() {
        return modelId;
    }

    /**
     * TC3-HMAC-SHA256 签名 POST（对照 tencentcloud SDK common 的
     * ConstructSignedRequest；签名算法为腾讯云公开规范）。
     */
    static final class Tc3Signer {
        private Tc3Signer() {
        }

        static RerankHttp.Result post(String host, String action, String version,
                                      String region, String secretId, String secretKey,
                                      byte[] payload) throws Exception {
            long timestamp = System.currentTimeMillis() / 1000;
            String date = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    .withZone(java.time.ZoneOffset.UTC)
                    .format(java.time.Instant.ofEpochSecond(timestamp));

            String hashedPayload = sha256Hex(payload);
            String canonicalRequest = "POST\n/\n\n"
                    + "content-type:" + CONTENT_TYPE + "\n"
                    + "host:" + host + "\n"
                    + "\n"
                    + "content-type;host\n"
                    + hashedPayload;
            String credentialScope = date + "/" + region + "/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n"
                    + credentialScope + "\n" + sha256Hex(
                    canonicalRequest.getBytes(StandardCharsets.UTF_8));

            byte[] kDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] kRegion = hmacSha256(kDate, region);
            byte[] kService = hmacSha256(kRegion, "tc3");
            byte[] kSigning = hmacSha256(kService, "request");
            String signature = hex(hmacSha256(kSigning, stringToSign));

            String authorization = "TC3-HMAC-SHA256 "
                    + "Credential=" + secretId + "/" + credentialScope + ", "
                    + "SignedHeaders=content-type;host, "
                    + "Signature=" + signature;

            String url = "https://" + host + "/";
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Content-Type", CONTENT_TYPE)
                    .header("X-TC-Action", action)
                    .header("X-TC-Version", version)
                    .header("X-TC-Region", region)
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("Authorization", authorization)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            // LKEAP 域名固定公网（无 SSRF 面），直连共享客户端
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().build();
            java.net.http.HttpResponse<byte[]> resp =
                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            return new RerankHttp.Result(resp.statusCode(),
                    "HTTP " + resp.statusCode(),
                    new String(resp.body(), StandardCharsets.UTF_8));
        }

        static String sha256Hex(byte[] data) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
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
}
