package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.TestSchema;

/**
 * AgentWebPages 生产存储接缝（2026-09-24 存储写字节面批）。
 *
 * <p>对照 Go {@code agent_web_pages.go}：Save 检查 assistant 消息在位 → 落盘 →
 * web_page 绑定 → web:// 地址；Read 按 web:// → resource:// 解析 + 租户与绑定
 * 双重授权。授权链 fail-closed：消息缺失 / 绑定缺失 / 租户不符全部拒绝。</p>
 */
@SpringBootTest
class AgentWebPagesStoreTest {

    private static final long TENANT = 10002L;
    private static final String OWNER = "wp-owner-1";
    private static final String SESSION = "wp-session-1";
    private static final String MESSAGE = "wp-assistant-1";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.ragagent.storage.service.ResourceCatalogService catalog;
    @Autowired
    private ArtifactCollectorWiring wiring;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        // 会话 + assistant 消息（agent_web_pages 的授权链锚点）
        jdbc.update("INSERT INTO sessions (id, tenant_id, user_id, title, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'wp', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", SESSION, TENANT, OWNER);
        jdbc.update("INSERT INTO messages (id, session_id, role, content, created_at, "
                + "updated_at) VALUES (?, ?, 'assistant', '', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                MESSAGE, SESSION);
    }

    private AgentWebPages pages() {
        return new AgentWebPages(jdbc.getDataSource(), catalog,
                wiring.webPageStore(), wiring.webPageBinding(),
                TENANT, OWNER, SESSION, MESSAGE);
    }

    @Test
    void saveRegistersWebHandleAndBinding() throws Exception {
        AgentWebPages pages = pages();
        String address = pages.save("# 标题\n\n正文");
        assertThat(address).startsWith("web://");

        // 绑定行：web_page 关系绑到 assistant 消息
        String resourceId = catalog.resolve(
                "resource://" + address.substring("web://".length())).orElseThrow().getId();
        Integer bindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_bindings WHERE resource_id = ? AND owner_type = 'message' "
                        + "AND owner_id = ? AND relation = 'web_page'",
                Integer.class, resourceId, MESSAGE);
        assertThat(bindings).isEqualTo(1);

        // 读回环：web:// → resource:// → 物理文件
        byte[] back = pages.read(address);
        assertThat(new String(back, StandardCharsets.UTF_8)).isEqualTo("# 标题\n\n正文");
    }

    @Test
    void readRejectsMissingBindingOrForeignTenant() throws Exception {
        AgentWebPages pages = pages();
        String address = pages.save("内容");
        String handle = address.substring("web://".length());
        String resourceId = catalog.resolve("resource://" + handle).orElseThrow().getId();

        // 绑定被删 → 拒绝
        jdbc.update("DELETE FROM resource_bindings WHERE resource_id = ?", resourceId);
        assertThatThrownBy(() -> pages.read(address))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("saved web page is unavailable in this session");

        // 他租户上下文的 reader：租户不符 → 拒绝（fail-closed）
        jdbc.update("INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, "
                        + "owner_id, relation, created_at) VALUES ('wp-rb-1', ?, ?, 'message', ?, 'web_page', "
                        + "CURRENT_TIMESTAMP)", resourceId, TENANT, MESSAGE);
        AgentWebPages foreign = new AgentWebPages(jdbc.getDataSource(), catalog,
                wiring.webPageStore(), wiring.webPageBinding(),
                999L, OWNER, SESSION, MESSAGE);
        assertThatThrownBy(() -> foreign.read(address))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("saved web page is unavailable in this session");
    }

    @Test
    void saveFailsWhenAssistantMessageMissing() {
        AgentWebPages orphan = new AgentWebPages(jdbc.getDataSource(), catalog,
                wiring.webPageStore(), wiring.webPageBinding(),
                TENANT, OWNER, SESSION, "no-such-message");
        assertThatThrownBy(() -> orphan.save("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("web page message is unavailable");
    }

    @Test
    void saveFailsWithSizeLimitMessage() {
        AgentWebPages pages = pages();
        byte[] big = new byte[(int) (AgentWebPages.MAX_SAVED_WEB_PAGE_BYTES + 1)];
        assertThatThrownBy(() -> pages.save(new String(big, StandardCharsets.ISO_8859_1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("web page exceeds the saved-page size limit");
    }
}
