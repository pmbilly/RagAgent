package com.ragagent.browserskill.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 对照 Go {@code browserskill.clusterRequest}（cluster.go L23-31）：internal 路由的
 * 签名请求体。⚠️ Go 的 {@code Scope} struct **没有 json tag**——集群转发的键是
 * 首字母大写的 {@code "Tenant"/"User"}（encoding/json 解析对大小写不敏感，
 * 但转发侧写出的是大写开头）；其余键按 tag 蛇形。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterRequest {

    @JsonProperty("node")
    private String node;

    @JsonProperty("scope")
    private ScopeJson scope;

    @JsonProperty("session")
    private String session;

    @JsonProperty("operation")
    private String operation;

    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Map<String, Object> params;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeJson {
        @JsonProperty("Tenant")
        @JsonAlias({"tenant", "Tenant"})
        private long tenant;

        @JsonProperty("User")
        @JsonAlias({"user", "User"})
        private String user;

        public long getTenant() { return tenant; }
        public void setTenant(long tenant) { this.tenant = tenant; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }

        public Scope toScope() {
            return new Scope(tenant, user == null ? "" : user);
        }
    }

    public String getNode() { return node; }
    public void setNode(String node) { this.node = node; }
    public ScopeJson getScope() { return scope; }
    public void setScope(ScopeJson scope) { this.scope = scope; }
    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
