package com.ragagent.sandbox.runtime;

import java.util.List;

/**
 * 真实的 provider 客户端切片（对照 Go {@code NewRemoteClientForCheck}，
 * internal/sandbox/tenant_resolver.go L239-261）。子批 1 的
 * {@code UnwiredSandboxClientFactory} 占位由本类替换——Update/Delete/Inventory/
 * QueryTemplates/sandbox-check 的 provider 面自此走真实 HTTP（控制面不可达时
 * 失败分类与 Go 逐字节同形；见 {@link RemoteProviderClient} 的路由说明）。
 */
public final class RemoteConfigSandboxClient implements ConfigSandboxClient {

    private final RemoteProviderClient provider;

    public RemoteConfigSandboxClient(RemoteProviderClient provider) {
        this.provider = provider;
    }

    /** 对照 RemoteTemplateCatalog.ListTemplates 的可选能力（instanceof 探测）。 */
    public boolean supportsTemplateCatalog() {
        return !SandboxTypes.TYPE_DOCKER.equals(provider.provider);
    }

    /** 对照 Health（cube /health；e2b ListSandboxesV2 limit=1；docker /_ping）。 */
    public void health() {
        provider.health(switch (provider.provider) {
            case SandboxTypes.TYPE_CUBE -> RemoteProviderClient.Routes.CUBE_HEALTH;
            case SandboxTypes.TYPE_E2B -> RemoteProviderClient.Routes.E2B_HEALTH;
            default -> RemoteProviderClient.Routes.DOCKER_PING;
        });
    }

    /** 对照 ListTemplates（GET /templates；响应体由调用方解析为 RemoteTemplate 列表）。 */
    public String listTemplatesRaw() {
        return provider.listTemplates(provider.provider.equals(SandboxTypes.TYPE_E2B)
                ? RemoteProviderClient.Routes.E2B_TEMPLATES
                : RemoteProviderClient.Routes.CUBE_TEMPLATES);
    }

    /**
     * ListTemplates + 响应解析（对照 cube L167-206 / e2b L195-208 的条目映射）：
     * name 取别名/名字段（cube 回落 image→id；e2b 取 names[0]/aliases[0]），
     * standard 由 {@code isStandardTemplate(name)} 判定。各控制面的实际载荷形状
     * 由其 SDK 定义——本解析按公开 API 的宽松形状取字段，接入真实 provider 时校准。
     */
    public List<RemoteTemplate> listTemplates() {
        String raw = listTemplatesRaw();
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    JSON.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            com.fasterxml.jackson.databind.JsonNode items = root;
            if (root.isObject() && root.has("templates")) {
                items = root.get("templates");
            }
            List<RemoteTemplate> out = new java.util.ArrayList<>();
            if (items != null && items.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : items) {
                    RemoteTemplate t = new RemoteTemplate();
                    t.id = textOr(n, "id", "template_id");
                    t.name = nameOf(n);
                    t.status = textOr(n, "status", "state");
                    t.version = textOr(n, "version");
                    t.image = textOr(n, "image");
                    t.createdAt = textOr(n, "created_at", "createdAt");
                    t.updatedAt = textOr(n, "updated_at", "updatedAt");
                    t.error = textOr(n, "error", "error_message");
                    t.instanceType = textOr(n, "instance_type");
                    t.networkType = textOr(n, "network_type");
                    if (n.has("allow_internet_access") && n.get("allow_internet_access").isBoolean()) {
                        t.allowInternetAccess = n.get("allow_internet_access").asBoolean();
                    }
                    t.standard = RemoteTemplate.isStandardTemplate(t.name)
                            || RemoteTemplate.isTemplateReady(t.status) && t.isStandardTemplateImage();
                    out.add(t);
                }
            }
            return out;
        } catch (java.io.IOException e) {
            throw RemoteError.of(provider.provider, "ListTemplates",
                    RemoteErrorKind.INTERNAL, "template catalog parse failed: " + e.getMessage());
        }
    }

    private static String textOr(com.fasterxml.jackson.databind.JsonNode n, String... keys) {
        for (String k : keys) {
            if (n.has(k) && n.get(k).isTextual()) {
                return n.get(k).asText();
            }
        }
        return "";
    }

    private static String nameOf(com.fasterxml.jackson.databind.JsonNode n) {
        if (n.has("name") && n.get("name").isTextual()) {
            return n.get("name").asText();
        }
        if (n.has("names") && n.get("names").isArray() && n.get("names").size() > 0
                && n.get("names").get(0).isTextual()) {
            return n.get("names").get(0).asText().trim();
        }
        if (n.has("aliases") && n.get("aliases").isArray() && n.get("aliases").size() > 0
                && n.get("aliases").get(0).isTextual()) {
            return n.get("aliases").get(0).asText().trim();
        }
        return "";
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 对照 ensure/replace 标准模板：仅控制面可达后可达；本批显式 UNSUPPORTED。 */
    public RemoteTemplate unsupportedCatalogWrite(String op) {
        throw RemoteProviderClient.unsupportedCatalogWrite(provider.provider, op);
    }

    /**
     * deep 检查的一次性沙箱创建（对照 RemoteSandboxClient.Create 的控制面调用；
     * 路由 best-effort，仅控制面可达后可达——dev 恒在传输层失败）。
     */
    public void createProbeSandbox() {
        provider.send("Create", "POST", provider.provider.equals(SandboxTypes.TYPE_E2B)
                        ? RemoteProviderClient.Routes.E2B_SANDBOXES
                        : RemoteProviderClient.Routes.CUBE_SANDBOXES,
                "{}");
    }

    @Override
    public List<RemoteSandboxSummary> list(RemoteListFilter filter) {
        // filter 的 metadata/states 收窄在控制面可达后由子批 3 的解析器下沉；
        // 不可达时本调用在传输层即失败（分类见 RemoteError.classifyTransport）。
        String route = provider.provider.equals(SandboxTypes.TYPE_E2B)
                ? RemoteProviderClient.Routes.E2B_SANDBOXES
                : RemoteProviderClient.Routes.CUBE_SANDBOXES;
        provider.list(route);
        throw RemoteError.of(provider.provider, "List", RemoteErrorKind.UNSUPPORTED,
                "sandbox list parsing is not wired in this build");
    }

    @Override
    public void delete(String sandboxId) {
        provider.delete((provider.provider.equals(SandboxTypes.TYPE_E2B)
                ? RemoteProviderClient.Routes.E2B_SANDBOXES
                : RemoteProviderClient.Routes.CUBE_SANDBOXES) + "/" + sandboxId);
    }
}
