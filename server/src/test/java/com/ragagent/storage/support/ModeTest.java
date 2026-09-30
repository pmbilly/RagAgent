package com.ragagent.storage.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;

/**
 * {@link Mode} 的解析与合并（对照 Go {@code mode.go} 的
 * {@code TestParseMode} / {@code TestDefaultMode} / {@code TestResolveMode_*}）。
 *
 * <h2>为什么没有 {@code DefaultMode} 的 env 用例</h2>
 * <p>Go 用 {@code t.Setenv} 直接改 {@code RESOURCE_URL_MODE}。Java 的
 * {@code System.getenv()} 在进程内不可变，而本项目至今没有环境注入的测试缝
 * （其它读 env 的地方同样没测）——与其为此在生产代码里开一个只有测试用的口子，
 * 不如：{@code DefaultMode} 只测"未设置 → handle"这一支，
 * 而 env 取值本身的行为由 {@link #parse} 全覆盖（{@code defaultMode} 拿到非空值后就是调它）。</p>
 */
class ModeTest {

    @AfterEach
    void tearDown() {
        // 两个 ThreadLocal 都必须清——否则会污染同线程跑的下一个用例
        StorageUrlContext.clear();
        APIKeyScopeContext.clear();
    }

    @Test
    void parse() {
        assertThat(Mode.parse("")).isEqualTo(Mode.HANDLE);
        assertThat(Mode.parse(null)).isEqualTo(Mode.HANDLE);
        assertThat(Mode.parse("handle")).isEqualTo(Mode.HANDLE);
        assertThat(Mode.parse("public")).isEqualTo(Mode.PUBLIC);
        assertThat(Mode.parse("  PUBLIC ")).isEqualTo(Mode.PUBLIC);

        assertThatThrownBy(() -> Mode.parse("true")).isInstanceOf(ResourceModeException.class);
        assertThatThrownBy(() -> Mode.parse("signed")).isInstanceOf(ResourceModeException.class);
    }

    /** env 未设置（测试 JVM 的实际情况）→ 安全默认。 */
    @Test
    void defaultModeFallsBackToHandle() {
        assertThat(Mode.defaultMode()).isEqualTo(Mode.HANDLE);
    }

    /**
     * 匿名入口（embed 渠道）把模式钉死：查询参数与部署默认都不能给访客
     * 一个免凭据的 URL。降级是**静默**的，所以顺带转发该参数的客户端继续能用。
     */
    @Test
    void forcedHandleModeWins() {
        for (String queryValue : List.of("", "public", "handle", "nonsense")) {
            StorageUrlContext.force();
            assertThat(Mode.resolve(queryValue)).as("queryValue=%q", queryValue).isEqualTo(Mode.HANDLE);
        }
    }

    /** 受知识库限制的 API Key 被拒于 {@code /files} 代理之外，也不该从这个参数拿到匿名文件 URL。 */
    @Test
    void rejectsPublicForKbRestrictedKey() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(1L, "tenant", false, List.of("kb-1"), null));

        assertThatThrownBy(() -> Mode.resolve("public"))
                .isInstanceOf(PublicModeForbiddenException.class)
                .hasMessage(Mode.PUBLIC_MODE_FORBIDDEN_MESSAGE);

        // 默认模式仍然可用：被禁的只是公网 URL
        assertThat(Mode.resolve("handle")).isEqualTo(Mode.HANDLE);
    }

    /** 全权 Key（无 KB 限制）不受影响。 */
    @Test
    void allowsPublicForUnrestrictedKey() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(1L, "tenant", true, null, null));
        assertThat(Mode.resolve("public")).isEqualTo(Mode.PUBLIC);
    }

    /** 没有 API Key 主体（JWT 会话）→ 不受该限制。 */
    @Test
    void allowsPublicWithoutApiKeyPrincipal() {
        assertThat(Mode.resolve("public")).isEqualTo(Mode.PUBLIC);
    }

    /**
     * 显式查询值胜过部署默认；非法查询值是**客户端错误**，
     * 要抛出来让集成方看见自己的笔误，而不是悄悄收到 handle。
     */
    @Test
    void queryWinsOverDeployment() {
        assertThat(Mode.resolve("handle")).isEqualTo(Mode.HANDLE);
        assertThatThrownBy(() -> Mode.resolve("yes-please"))
                .isInstanceOf(ResourceModeException.class)
                .isNotInstanceOf(PublicModeForbiddenException.class);
    }
}
