package com.ragagent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionLastRequestState;
import com.ragagent.session.domain.SessionListItem;
import org.junit.jupiter.api.Test;

/**
 * 会话响应体的 JSON **键序与键数**——期望值全部是实测 Go 出来的。
 *
 * <p>两者都是裸响应体：GET /sessions/{id} 直接返回 {@link Session}，
 * GET /sessions 的 data 数组元素是 {@link SessionListItem}。Go 的 struct 按声明序输出，
 * 所以键序是契约的一部分。</p>
 *
 * <h2>为什么专门钉 SessionListItem 的键序</h2>
 * <p>Go 用**结构体内嵌 + 外层同名字段遮蔽**：{@code SessionListItem} 自己又声明了一个
 * {@code IMPlatform}，把内嵌 {@code Session} 里那个（{@code gorm:"-"}）盖掉。
 * encoding/json 按"深度浅者胜"解析，所以 {@code im_platform} **只出现一次**，
 * 位置在 {@code deleted_at} 之后。Java 侧图省事用继承或 {@code @JsonUnwrapped} 就会
 * 冒出两个 {@code im_platform}，或键序由 Jackson 内部规则决定。</p>
 *
 * <h2>这条测试自己踩过的坑</h2>
 * <p>键名正则最初写成 {@code "([a-z_]+)"}，只匹配下划线命名——Jackson 因字段名带
 * {@code is} 前缀而多吐出的驼峰重复键（{@code "pinned"}）**整个被正则过滤掉**，
 * 测试全绿而响应是错的。现在用驼峰感知的模式，Go 侧实测也用同一个模式。</p>
 */
class SessionJsonContractTest {

    private static final Pattern KEY = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\":");

    private static final OffsetDateTime TS =
            OffsetDateTime.of(2026, 9, 18, 10, 30, 0, 0, ZoneOffset.ofHours(8));

    private static SessionLastRequestState fullState() {
        SessionLastRequestState state = new SessionLastRequestState();
        state.setAgentId("a1");
        state.setAgentEnabled(true);
        state.setModelId("m1");
        state.setKnowledgeBaseIds(List.of("kb1"));
        return state;
    }

    // ── Session ─────────────────────────────────────────────────────────────

    @Test
    void sessionKeyOrderMatchesGo() {
        Session s = new Session();
        s.setId("s1");
        s.setTitle("T");
        s.setDescription("D");
        s.setTenantId(10002L);
        s.setUserId("u1");
        s.setPinned(true);
        s.setPinnedAt(TS);
        s.setLastRequestState(fullState());
        s.setSandboxConfigId("sc1");
        s.setCreatedAt(TS);
        s.setUpdatedAt(TS);
        s.setImPlatform("feishu");

        // Go: id title description tenant_id user_id is_pinned pinned_at last_request_state
        //     {agent_id agent_enabled model_id knowledge_base_ids
        //      local_browser_enabled web_search_enabled}
        //     sandbox_config_id created_at updated_at deleted_at im_platform
        assertEquals(List.of(
                "id", "title", "description", "tenant_id", "user_id", "is_pinned", "pinned_at",
                "last_request_state", "agent_id", "agent_enabled", "model_id", "knowledge_base_ids",
                "local_browser_enabled", "web_search_enabled",
                "sandbox_config_id", "created_at", "updated_at", "deleted_at", "im_platform"),
                keys(json(s)));
    }

    @Test
    void sessionEmitsNoDuplicateBooleanKeys() {
        // 字段曾叫 isPinned：Jackson 的字段隐式名是 "isPinned"、getter 的隐式名是 "pinned"，
        // 两者对不上就会各生成一个属性，JSON 里同时出现 is_pinned 与 pinned。
        Session s = new Session();
        s.setId("s1");
        s.setTenantId(10002L);

        String json = json(s);
        assertEquals(1, count(json, "is_pinned"), "is_pinned 只应出现一次");
        assertEquals(0, count(json, "pinned"), "不该有驼峰重复键");
        assertEquals(0, count(json, "isPinned"), "不该有驼峰重复键");
    }

    // ── SessionListItem ─────────────────────────────────────────────────────

    @Test
    void sessionListItemKeyOrderMatchesGoEmbeddedStructFlattening() {
        SessionListItem item = new SessionListItem();
        item.setId("s1");
        item.setTitle("T");
        item.setDescription("D");
        item.setTenantId(10002L);
        item.setUserId("u1");
        item.setPinned(true);
        item.setPinnedAt(TS);
        item.setLastRequestState(fullState());
        item.setSandboxConfigId("sc1");
        item.setCreatedAt(TS);
        item.setUpdatedAt(TS);
        item.setImPlatform("feishu");
        item.setImChatId("c1");
        item.setImThreadId("t1");
        item.setImUserId("iu");
        item.setImAgentId("ia");
        item.setImChannelId("ic");

        assertEquals(List.of(
                "id", "title", "description", "tenant_id", "user_id", "is_pinned", "pinned_at",
                "last_request_state", "agent_id", "agent_enabled", "model_id", "knowledge_base_ids",
                "local_browser_enabled", "web_search_enabled",
                "sandbox_config_id", "created_at", "updated_at", "deleted_at",
                "im_platform", "im_chat_id", "im_thread_id", "im_user_id", "im_agent_id",
                "im_channel_id"),
                keys(json(item)));
    }

    @Test
    void imPlatformAppearsExactlyOnce() {
        // 内嵌 Session 里那个被遮蔽的 im_platform 不能漏出来（漏出来就是重复键）
        SessionListItem item = new SessionListItem();
        item.setId("s1");
        item.setTenantId(10002L);
        item.setImPlatform("feishu");

        assertEquals(1, count(json(item), "im_platform"), "im_platform 只应出现一次");
    }

    @Test
    void emptyItemsOmitEveryOptionalKey() {
        SessionListItem item = new SessionListItem();
        item.setId("s1");
        item.setTenantId(10002L);

        assertEquals(List.of("id", "title", "description", "tenant_id", "is_pinned",
                "created_at", "updated_at", "deleted_at"), keys(json(item)));
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private static String json(Object value) {
        try {
            ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> keys(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = KEY.matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static int count(String json, String key) {
        return json.split("\"" + key + "\":", -1).length - 1;
    }
}
