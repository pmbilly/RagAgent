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
 * 会话响应体的 JSON **键序与键数**（§14.9l S1 换锚后：键名＝Java 字段名、键序＝声明序）。
 *
 * <p>两者都是裸响应体：GET /sessions/{id} 直接返回 {@link Session}，
 * GET /sessions 的 {@code items} 数组元素是 {@link SessionListItem}。</p>
 *
 * <h2>为什么专门钉 SessionListItem 的键序</h2>
 * <p>Go 用**结构体内嵌 + 外层同名字段遮蔽**：{@code SessionListItem} 自己又声明了一个
 * {@code IMPlatform}，把内嵌 {@code Session} 里那个（{@code gorm:"-"}）盖掉。
 * Java 侧若图省事用继承或 {@code @JsonUnwrapped}，两个 {@code imPlatform} 会撞成重复键，
 * 或键序由 Jackson 内部规则决定——所以平铺 + 声明序，本测试逐键核对。</p>
 *
 * <h2>这条测试自己踩过的两个坑（都留档）</h2>
 * <ol>
 *   <li>键名正则最初写成 {@code "([a-z_]+)"}，只匹配下划线命名——Jackson 因字段名带
 *       {@code is} 前缀而多吐出的驼峰重复键（{@code "pinned"}）**整个被正则过滤掉**，
 *       测试全绿而响应是错的。现在用驼峰感知的模式。</li>
 *   <li>同一个坑在**契约测试的掩码正则**里也有一份：`"([a-z_]+)":"<uuid>"` 在键名换锚后
 *       匹配不到，时间戳/UUID 不再被掩码、两侧差异直接炸出来。S1 已把 session 域五个
 *       契约测试的掩码键模式一并改成大小写感知（改键名的批次都要检查这一处）。</li>
 * </ol>
 */
class SessionJsonContractTest {

    private static final Pattern KEY = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\":");

    private static final OffsetDateTime TS =
            OffsetDateTime.of(2026, 9, 18, 10, 30, 0, 0, ZoneOffset.ofHours(8));

    /** {@link SessionLastRequestState} 的十个键（声明序）——全部恒输出（§1.6）。 */
    private static final List<String> STATE_KEYS = List.of(
            "agentId", "agentEnabled", "modelId", "knowledgeBaseIds", "knowledgeIds", "tagIds",
            "mcpServiceIds", "skillNames", "mentionedItems", "webSearchEnabled");

    private static SessionLastRequestState fullState() {
        SessionLastRequestState state = new SessionLastRequestState();
        state.setAgentId("a1");
        state.setAgentEnabled(true);
        state.setModelId("m1");
        state.setKnowledgeBaseIds(List.of("kb1"));
        return state;
    }

    /** {@link Session} 的十二个键（声明序），{@code lastRequestState} 的内层键平铺在它后面。 */
    private static List<String> sessionKeys() {
        List<String> keys = new ArrayList<>(List.of(
                "id", "title", "description", "tenantId", "userId", "pinned", "pinnedAt",
                "lastRequestState"));
        keys.addAll(STATE_KEYS);
        keys.addAll(List.of("createdAt", "updatedAt", "deletedAt", "imPlatform"));
        return keys;
    }

    /** {@link SessionListItem} = 会话键去掉末尾的 imPlatform，再补六个 IM 字段。 */
    private static List<String> listItemKeys() {
        List<String> keys = new ArrayList<>(sessionKeys());
        keys.remove("imPlatform");
        keys.addAll(List.of("imPlatform", "imChatId", "imThreadId", "imUserId", "imAgentId",
                "imChannelId"));
        return keys;
    }

    // ── Session ─────────────────────────────────────────────────────────────

    @Test
    void sessionKeyOrderMatchesDeclaration() {
        Session s = new Session();
        s.setId("s1");
        s.setTitle("T");
        s.setDescription("D");
        s.setTenantId(10002L);
        s.setUserId("u1");
        s.setPinned(true);
        s.setPinnedAt(TS);
        s.setLastRequestState(fullState());
        s.setCreatedAt(TS);
        s.setUpdatedAt(TS);
        s.setImPlatform("feishu");

        assertEquals(sessionKeys(), keys(json(s)));
    }

    @Test
    void sessionEmitsNoDuplicateBooleanKeys() {
        // 字段曾叫 isPinned：Jackson 的字段隐式名是 "isPinned"、getter 的隐式名是 "pinned"，
        // 两者对不上就会各生成一个属性。换锚后线格式键是 "pinned"（§1.24：不带 is 前缀）。
        Session s = new Session();
        s.setId("s1");
        s.setTenantId(10002L);

        String json = json(s);
        assertEquals(1, count(json, "pinned"), "pinned 只应出现一次");
        assertEquals(0, count(json, "isPinned"), "不该有 isPinned 重复键");
        assertEquals(0, count(json, "is_pinned"), "不该有下划线键");
    }

    // ── SessionListItem ─────────────────────────────────────────────────────

    @Test
    void sessionListItemKeyOrderMatchesDeclaration() {
        SessionListItem item = new SessionListItem();
        item.setId("s1");
        item.setTitle("T");
        item.setDescription("D");
        item.setTenantId(10002L);
        item.setUserId("u1");
        item.setPinned(true);
        item.setPinnedAt(TS);
        item.setLastRequestState(fullState());
        item.setCreatedAt(TS);
        item.setUpdatedAt(TS);
        item.setImPlatform("feishu");
        item.setImChatId("c1");
        item.setImThreadId("t1");
        item.setImUserId("iu");
        item.setImAgentId("ia");
        item.setImChannelId("ic");

        assertEquals(listItemKeys(), keys(json(item)));
    }

    @Test
    void imPlatformAppearsExactlyOnce() {
        // 内嵌 Session 里那个被遮蔽的 imPlatform 不能漏出来（漏出来就是重复键）
        SessionListItem item = new SessionListItem();
        item.setId("s1");
        item.setTenantId(10002L);
        item.setImPlatform("feishu");

        assertEquals(1, count(json(item), "imPlatform"), "imPlatform 只应出现一次");
    }

    /**
     * 换锚后**没有条件键**：一个只填了 id/tenantId 的列表行也输出全部 22 个键
     * （空串、{@code null}、空数组各按 §1.5/§1.6 显式写出）。
     */
    @Test
    void emptyItemStillEmitsEveryKey() {
        SessionListItem item = new SessionListItem();
        item.setId("s1");
        item.setTenantId(10002L);

        List<String> expected = new ArrayList<>(listItemKeys());
        // lastRequestState 本身为 null → 它内层的十个键不出现（§1.5：嵌套对象为 null 就是 null）
        expected.removeAll(STATE_KEYS);
        assertEquals(expected, keys(json(item)));
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
