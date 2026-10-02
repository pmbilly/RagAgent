package com.ragagent.vectorstore.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 对照 Go {@code types.ConnectionConfig}（internal/types/vectorstore.go L120-236）。
 * 全字段 omitempty（@JsonInclude(NON_DEFAULT)/NON_NULL）；未知键容忍（jsonb 演进，§9）。
 * password / api_key 落库加密由 {@link ConnectionConfigTypeHandler} 处理。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionConfig {

    /** 通用（ES/Milvus/Tencent/Doris 的 URL 或 host:port） */
    public String addr = "";
    public String username = "";
    /** AES-GCM 加密落库 */
    public String password = "";
    /** AES-GCM 加密落库 */
    public String apiKey = "";
    /** OpenSearch：跳过 TLS 证书校验 */
    public boolean insecureSkipVerify;
    /** Qdrant */
    public String host = "";
    public int port;
    public boolean useTls;
    /** Weaviate */
    public String grpcAddress = "";
    public String scheme = "";
    /** Milvus / Tencent VectorDB / Doris 的库名 */
    public String database = "";
    /** Postgres：绑定应用默认连接 */
    public boolean useDefaultConnection;
    /** Doris：Stream Load 的 FE HTTP 端口 */
    public int httpPort;
    /** TestConnection 探测到的服务端版本（成功后回存） */
    public String version = "";

    /** 对照 GetEndpoint：去重判定的规范化端点（Qdrant 缺省端口 6334）。
     *  ⚠️ §7.5 第 2 条：Go 的**方法**——必须 @JsonIgnore，否则 Jackson 把它当
     *  "endpoint" 属性写进响应/jsonb（实测 vs-get 抓回）。 */
    @JsonIgnore
    public String getEndpoint() {
        if (addr != null && !addr.isEmpty()) {
            if (database != null && !database.isEmpty()) {
                return addr + "/" + database;
            }
            return addr;
        }
        if (host != null && !host.isEmpty()) {
            int p = port;
            if (p == 0) {
                p = 6334;
            }
            return host + ":" + p;
        }
        if (useDefaultConnection) {
            return "__default_postgres__";
        }
        return "";
    }

    /** 对照 MaskSensitiveFields：非空密码/密钥 → "***"（空保持空，前端区分未配置） */
    public ConnectionConfig maskSensitiveFields() {
        ConnectionConfig out = copy();
        if (out.password != null && !out.password.isEmpty()) {
            out.password = "***";
        }
        if (out.apiKey != null && !out.apiKey.isEmpty()) {
            out.apiKey = "***";
        }
        return out;
    }

    public ConnectionConfig copy() {
        ConnectionConfig c = new ConnectionConfig();
        c.addr = addr;
        c.username = username;
        c.password = password;
        c.apiKey = apiKey;
        c.insecureSkipVerify = insecureSkipVerify;
        c.host = host;
        c.port = port;
        c.useTls = useTls;
        c.grpcAddress = grpcAddress;
        c.scheme = scheme;
        c.database = database;
        c.useDefaultConnection = useDefaultConnection;
        c.httpPort = httpPort;
        c.version = version;
        return c;
    }
}
