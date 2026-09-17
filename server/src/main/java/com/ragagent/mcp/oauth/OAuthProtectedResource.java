package com.ragagent.mcp.oauth;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 9728 {@code /.well-known/oauth-protected-resource} 的响应
 * （对照 mcp-go {@code transport.OAuthProtectedResource}，oauth.go:492-497）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthProtectedResource(
        @JsonProperty("authorization_servers") List<String> authorizationServers,
        @JsonProperty("resource") String resource,
        @JsonProperty("resource_name") String resourceName) {

    public OAuthProtectedResource {
        authorizationServers = authorizationServers == null ? List.of() : List.copyOf(authorizationServers);
        resource = resource == null ? "" : resource;
        resourceName = resourceName == null ? "" : resourceName;
    }

    public boolean hasAuthorizationServers() {
        return !authorizationServers.isEmpty();
    }
}
