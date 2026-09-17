package com.ragagent.mcp.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.ragagent.TestSchema;
import com.ragagent.common.error.BizException;
import com.ragagent.mcp.domain.McpToolPolicyPatch;
import com.ragagent.mcp.mapper.McpToolApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/repository/mcp_tool_approval_repository_test.go。
 *
 * <p>核心是 UpsertPolicy 的四条语义：缺行 = enabled、插入的显式 false、补丁只动自己那一列、
 * 并发列补丁互不覆盖。</p>
 */
@SpringBootTest
class McpToolApprovalRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private McpToolApprovalRepository repo;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    @Test
    void isEnabledDefaultsTrue() {
        assertTrue(repo.isEnabled(1, "svc", "tool"), "缺行 = enabled（向后兼容）");
        assertFalse(repo.isRequired(1, "svc", "tool"), "缺行 = 不需要审批");
    }

    @Test
    void upsertPolicyInsertDefaults() {
        repo.upsertPolicy(1, "svc", "only-require", new McpToolPolicyPatch(true, null));
        assertTrue(repo.isEnabled(1, "svc", "only-require"),
                "只设 approval 时新行必须保持 enabled=true");
        assertTrue(repo.isRequired(1, "svc", "only-require"));

        repo.upsertPolicy(1, "svc", "only-enabled", new McpToolPolicyPatch(null, false));
        assertFalse(repo.isEnabled(1, "svc", "only-enabled"),
                "enabled=false 必须真的写进 DB（不能因为默认值被省略）");
        assertFalse(repo.isRequired(1, "svc", "only-enabled"),
                "只设 enabled 时新行必须保持 require_approval=false");
    }

    @Test
    void upsertPolicyPreservesOtherColumn() {
        repo.upsertPolicy(1, "svc", "tool", new McpToolPolicyPatch(null, false));
        repo.upsertPolicy(1, "svc", "tool", new McpToolPolicyPatch(true, null));

        assertFalse(repo.isEnabled(1, "svc", "tool"),
                "approval 补丁不得把已禁用的工具重新启用");
        assertTrue(repo.isRequired(1, "svc", "tool"));

        repo.upsertPolicy(1, "svc", "tool", new McpToolPolicyPatch(null, true));
        assertTrue(repo.isRequired(1, "svc", "tool"),
                "enabled 补丁不得清掉 require_approval");
    }

    @Test
    void concurrentColumnPatches() throws Exception {
        repo.upsertPolicy(1, "svc", "tool", new McpToolPolicyPatch(false, true));

        CompletableFuture<Void> disable = CompletableFuture.runAsync(
                () -> repo.upsertPolicy(1, "svc", "tool", new McpToolPolicyPatch(null, false)));
        CompletableFuture<Void> approve = CompletableFuture.runAsync(
                () -> repo.upsertPolicy(1, "svc", "tool", new McpToolPolicyPatch(true, null)));
        CompletableFuture.allOf(disable, approve).join();

        assertFalse(repo.isEnabled(1, "svc", "tool"));
        assertTrue(repo.isRequired(1, "svc", "tool"));
    }

    @Test
    void upsertPolicyRejectsEmptyPatchAndMissingKeys() {
        assertThrows(BizException.class,
                () -> repo.upsertPolicy(1, "svc", "tool", new McpToolPolicyPatch(null, null)));
        assertThrows(BizException.class,
                () -> repo.upsertPolicy(1, "", "tool", new McpToolPolicyPatch(true, null)));
        assertThrows(BizException.class,
                () -> repo.upsertPolicy(1, "svc", "", new McpToolPolicyPatch(true, null)));
    }

    @Test
    void listByServiceOrdersByToolName() {
        repo.upsertPolicy(1, "svc", "b", new McpToolPolicyPatch(true, null));
        repo.upsertPolicy(1, "svc", "a", new McpToolPolicyPatch(null, false));
        repo.upsertPolicy(2, "svc", "c", new McpToolPolicyPatch(true, null));

        List<com.ragagent.mcp.domain.McpToolApproval> rows = repo.listByService(1, "svc");
        org.junit.jupiter.api.Assertions.assertEquals(2, rows.size());
        org.junit.jupiter.api.Assertions.assertEquals("a", rows.get(0).getToolName());
        org.junit.jupiter.api.Assertions.assertEquals("b", rows.get(1).getToolName());
    }
}
