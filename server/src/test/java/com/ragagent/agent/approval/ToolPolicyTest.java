package com.ragagent.agent.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 对照 Go internal/agent/approval/tool_policy_test.go 的两个用例
 * （TestGateBatchPolicyScopesDefaultsAndFailures / TestGateBatchPolicyLegacyCheckerAndNoChecker）。
 *
 * <p>覆盖点：批量只读一次、行按 (tenant, service) 过滤、只覆盖被请求的名字、
 * 身份缺失与 ctx 取消在**查询之前**短路、底层异常原样上抛、
 * 以及不支持批量的旧 checker 退化为逐个查。</p>
 */
@Timeout(20)
class ToolPolicyTest {

    /**
     * 对照 Go TestGateBatchPolicyScopesDefaultsAndFailures。
     * 注意 Go 的 {@code batchPolicyService} 嵌入了 stubChecker（enabled == nil → true），
     * 所以未被策略行覆盖的名字默认是启用。
     */
    @Test
    void gateBatchPolicyScopesDefaultsAndFailures() {
        StubChecker.BatchPolicyService svc = new StubChecker.BatchPolicyService();
        svc.rows = List.of(
                StubChecker.row(7, "svc", "disabled", false),
                StubChecker.row(8, "svc", "default", false),      // 别的租户：忽略
                StubChecker.row(7, "other", "default", false),    // 别的服务：忽略
                StubChecker.row(7, "svc", "not-requested", true)); // 未被请求：忽略
        Gate gate = new Gate(null, new Adapter(svc), null);

        Map<String, Boolean> result =
                gate.enabledTools(Cancellation.none(), 7, "svc", List.of("default", "disabled"));
        assertEquals(Map.of("default", true, "disabled", false), result);
        assertEquals(1, svc.reads, "批量路径必须只查询一次");

        // 身份缺失：查询之前就报错，不得打到服务层
        assertThrows(ApprovalException.class,
                () -> gate.enabledTools(Cancellation.none(), 0, "svc", List.of("default")));
        assertEquals(1, svc.reads);

        // ctx 已取消：同样在查询之前短路
        TestCancellation canceled = new TestCancellation();
        canceled.cancel();
        assertThrows(CancellationException.class,
                () -> gate.enabledTools(canceled, 7, "svc", List.of("default")));
        assertEquals(1, svc.reads);

        // 底层异常原样上抛（Go: require.ErrorIs(err, svc.listErr)）
        svc.listError = new IllegalStateException("database unavailable");
        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> gate.enabledTools(Cancellation.none(), 7, "svc", List.of("default")));
        assertEquals("database unavailable", err.getMessage());
    }

    /** 对照 Go TestGateBatchPolicyLegacyCheckerAndNoChecker */
    @Test
    void gateBatchPolicyLegacyCheckerAndNoChecker() {
        // 只实现单工具契约的旧 checker → 逐个查
        Gate gate = new Gate(null, new Adapter(StubChecker.enabled(false)), null);
        Map<String, Boolean> result = gate.enabledTools(Cancellation.none(), 7, "svc", List.of("a", "b"));
        assertEquals(Map.of("a", false, "b", false), result);

        // 完全没有 checker → 全部保持启用
        Gate noChecker = new Gate(null, null, null);
        Map<String, Boolean> result2 = noChecker.enabledTools(Cancellation.none(), 7, "svc", List.of("a", "b"));
        assertEquals(Map.of("a", true, "b", true), result2);
    }

    /** 补充用例：Adapter 背后为 null（策略表未接线）时全默认启用，批量分支不被触发。 */
    @Test
    void adapterWithoutServiceDefaultsToEnabled() {
        Adapter adapter = new Adapter(null);
        assertEquals(Map.of("a", true), adapter.enabledTools(Cancellation.none(), 7, "svc", List.of("a")));
    }

    /** 补充用例：自由函数入口（对照 Go 的 package 级 EnabledTools）在 bulk checker 上走批量。 */
    @Test
    void freeFunctionUsesBulkCheckerWhenAvailable() {
        StubChecker.BatchPolicyService svc = new StubChecker.BatchPolicyService();
        svc.rows = List.of(StubChecker.row(7, "svc", "a", false));
        Adapter adapter = new Adapter(svc);

        Map<String, Boolean> viaFreeFunction =
                ToolPolicy.enabledTools(Cancellation.none(), adapter, 7, "svc", List.of("a", "b"));
        assertEquals(Map.of("a", false, "b", true), viaFreeFunction);
        assertEquals(1, svc.reads, "自由函数把批量能力转发给 Adapter，仍然只读一次");
    }
}
