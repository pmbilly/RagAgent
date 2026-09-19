package com.ragagent.sandbox.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * provider 中立的模板视图（对照 Go {@code RemoteTemplate}，
 * internal/sandbox/template_catalog.go L22-43）。作为
 * SandboxTemplateCatalog 响应体的一部分直接序列化——json tag 逐字段对照。
 *
 * <p>{@code error} 携带 provider 对构建失败的原文解释：没有它，失败的模板只是
 * 一个无法区分"registry 凭据问题"和"节点磁盘满"的红标。</p>
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteTemplate {

    @JsonProperty("id")
    public String id = "";

    @JsonProperty("name")
    public String name = "";

    @JsonProperty("status")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String status = "";

    @JsonProperty("version")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String version = "";

    @JsonProperty("image")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String image = "";

    @JsonProperty("created_at")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String createdAt = "";

    @JsonProperty("updated_at")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String updatedAt = "";

    /** 无 omitempty：恒输出（false 也输出）。 */
    @JsonProperty("standard")
    public boolean standard;

    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String error = "";

    // ── Cube 在 GET /templates 上才报告的字段；其它后端留空，设置列表省略行 ──

    @JsonProperty("instance_type")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String instanceType = "";

    @JsonProperty("network_type")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String networkType = "";

    /** Go *bool 三态：null=未报告（无 omitempty 之外的形态差异）+ omitempty 省略。 */
    @JsonProperty("allow_internet_access")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public Boolean allowInternetAccess;

    /** 对照 IsTemplateReady（template_catalog.go L122）。 */
    public static boolean isTemplateReady(String status) {
        return switch (norm(status)) {
            case "ready", "available", "complete", "completed", "success", "succeeded" -> true;
            default -> false;
        };
    }

    /** 对照 IsTemplateBuildFailed（template_catalog.go L110）。 */
    public static boolean isTemplateBuildFailed(String status) {
        return switch (norm(status)) {
            case "failed", "failure", "error", "cancelled", "canceled", "untagged" -> true;
            default -> false;
        };
    }

    /** 对照 StandardTemplateName（template_catalog.go L8）。 */
    public static final String STANDARD_TEMPLATE_NAME = "weknora";

    /** 对照 isStandardTemplate（template_catalog.go L58-64）：名字或路径尾段匹配。 */
    public static boolean isStandardTemplate(String name) {
        String trimmed = trimSlash(name == null ? "" : name.trim());
        if (trimmed.equalsIgnoreCase(STANDARD_TEMPLATE_NAME)) {
            return true;
        }
        String[] parts = trimmed.split("/");
        return parts.length > 1
                && parts[parts.length - 1].equalsIgnoreCase(STANDARD_TEMPLATE_NAME);
    }

    private static String trimSlash(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 对照 normalizeImageRepository（template_catalog.go L68-87）：把镜像引用归约到
     * 仓库路径，使 "docker.io/x/y:latest"、"x/y@sha256:…" 与裸名比较相等。
     */
    public static String normalizeImageRepository(String image) {
        String ref = image == null ? "" : image.trim();
        if (ref.isEmpty()) {
            return "";
        }
        int at = ref.indexOf('@');
        if (at >= 0) {
            ref = ref.substring(0, at);
        }
        // 最后一段斜杠之前的冒号属于 registry 端口，不是 tag
        int colon = ref.lastIndexOf(':');
        int slash = ref.lastIndexOf('/');
        if (colon > slash) {
            ref = ref.substring(0, colon);
        }
        ref = trimSlash(ref);
        if (ref.isEmpty()) {
            return "";
        }
        String[] parts = ref.split("/");
        // registry 主机可由点/端口识别，或为 "localhost"；其余头部是命名空间
        if (parts.length > 1 && (parts[0].matches(".*[.:].*") || parts[0].equals("localhost"))) {
            parts = shift(parts);
        }
        if (parts.length > 1 && parts[0].equalsIgnoreCase("library")) {
            parts = shift(parts);
        }
        return String.join("/", parts).toLowerCase();
    }

    private static String[] shift(String[] parts) {
        String[] trimmed = new String[parts.length - 1];
        System.arraycopy(parts, 1, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /** 对照 isStandardTemplateImage（template_catalog.go L66-67）。 */
    public boolean isStandardTemplateImage() {
        String candidate = normalizeImageRepository(image);
        return !candidate.isEmpty() && candidate.equals(normalizeImageRepository(
                com.ragagent.sandbox.runtime.EffectiveConfig.DEFAULT_DOCKER_IMAGE));
    }

    /** 对照 templateStatusRank（tenant_sandbox_config.go L905）。 */
    public static int statusRank(String status) {
        return switch (norm(status)) {
            case "ready", "available", "complete", "completed", "success", "succeeded" -> 3;
            case "building", "waiting", "pending", "queued", "processing", "running" -> 2;
            case "failed", "error", "cancelled", "canceled" -> 1;
            default -> 0;
        };
    }

    private static String norm(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }
}
