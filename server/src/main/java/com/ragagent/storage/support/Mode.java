package com.ragagent.storage.support;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.storage.StorageRuntimeEnv;

/**
 * 存储文件在 API 响应里的引用方式。
 *
 * <p>字符串值固定为 {@code "handle"} / {@code "public"}，
 * 因为 {@code RESOURCE_URL_MODE} 是运维可见的配置面。</p>
 */
public enum Mode {

    /**
     * 返回内部的 {@code resource://<handle>} 引用，客户端必须经认证过的 {@code /files}
     * 代理取字节。**默认值**，也是唯一不依赖外部可达性的模式。
     */
    HANDLE("handle"),

    /**
     * 返回客户端可直接加载的、带时效的 HTTP(S) URL，第三方应用无需二次认证即可渲染图片。
     * <b>转不成 HTTP URL 的引用保持为 handle。</b>
     */
    PUBLIC("public");

    /** 请求级选择模式的查询参数。 */
    public static final String QUERY_PARAM = "resource_urls";

    /**
     * 部署级默认模式的 env。
     * 它与 {@code APP_EXTERNAL_URL} 配套——后者才是让 {@code resource://} 手柄
     * 真正能解析成公网 {@code /r/<token>} 的那个开关。
     */
    public static final String ENV_VAR = "RESOURCE_URL_MODE";

    /**
     * 调用方要求 PUBLIC，但其凭据不得获得匿名、带时效的文件 URL。
     * 调用方映射成 <b>403</b> 而非 400：
     * 请求本身合法，是<b>授权范围</b>不允许。
     */
    public static final String PUBLIC_MODE_FORBIDDEN_MESSAGE =
            "resource_urls=public is not available for a knowledge-base-restricted API key";

    private static final Logger log = LoggerFactory.getLogger(Mode.class);

    private final String value;

    Mode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * 校验客户端或配置给出的模式。
     * 空值落到 {@link #HANDLE}，让"未设置参数/配置"保持默认。
     */
    public static Mode parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "handle" -> HANDLE;
            case "public" -> PUBLIC;
            default -> throw new ResourceModeException(
                    "invalid " + QUERY_PARAM + " value \"" + raw + "\": expected \""
                            + HANDLE.value + "\" or \"" + PUBLIC.value + "\"");
        };
    }

    /**
     * 部署级默认模式。未设置或解析不了都落到
     * {@link #HANDLE}——一个笔误应当降级到安全默认，而不是让每个请求都失败。
     */
    public static Mode defaultMode() {
        String raw = StorageRuntimeEnv.resourceUrlMode();
        if (raw == null || raw.isBlank()) {
            return HANDLE;
        }
        try {
            return parse(raw);
        } catch (ResourceModeException e) {
            warnBadDefaultOnce(raw.trim());
            return HANDLE;
        }
    }

    /**
     * 合并"每请求查询值"与"部署默认"，再施加两条任何调用方都不得绕过的限制。
     *
     * <ul>
     *   <li>{@link StorageUrlContext#isHandleModeForced()} 的请求（匿名 embed 流量）
     *       <b>静默降级</b>而非拒绝——顺带发了该参数的 embed 客户端继续能用，只是永远拿不到公网 URL。</li>
     *   <li>受知识库限制的 API Key 抛 {@link PublicModeForbiddenException}。
     *       这类 Key 本来就被拒于 {@code /files} 代理之外，
     *       再给它匿名文件 URL 等于把它的范围从"chunk 文本"偷偷扩到"文件字节"。</li>
     * </ul>
     */
    public static Mode resolve(String queryValue) {
        if (StorageUrlContext.isHandleModeForced()) {
            return HANDLE;
        }
        Mode mode = requestedMode(queryValue);
        if (mode == PUBLIC) {
            TenantAPIKeyScope scope = APIKeyScopeContext.current();
            if (scope != null && scope.isKnowledgeBaseRestricted()) {
                throw new PublicModeForbiddenException(PUBLIC_MODE_FORBIDDEN_MESSAGE);
            }
        }
        return mode;
    }

    private static Mode requestedMode(String queryValue) {
        if (queryValue != null && !queryValue.trim().isEmpty()) {
            return parse(queryValue);
        }
        return defaultMode();
    }

    /**
     * 每个不同的坏值只告警一次。
     *
     * <p>值本身每次重读，所以运维改回正确值不需要重启；但如果再写一个新笔误，
     * 仍会告警——这正是"按值去重"而不是"只告警一次"的原因。</p>
     */
    private static final java.util.Set<String> WARNED_BAD_DEFAULTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnBadDefaultOnce(String raw) {
        if (WARNED_BAD_DEFAULTS.add(raw)) {
            log.warn("ignoring {}: invalid value \"{}\"; falling back to \"{}\"",
                    ENV_VAR, raw, HANDLE.value);
        }
    }
}
