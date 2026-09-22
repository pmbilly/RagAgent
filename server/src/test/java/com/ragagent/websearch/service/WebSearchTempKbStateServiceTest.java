package com.ragagent.websearch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;

/**
 * web 搜索临时 KB 状态服务（对照 Go web_search_state.go 全文）。
 * 重点钉 Delete 的四分支语义与 Get 的空三元组兜底；save 的 JSON 键序
 * （kbID → knowledgeIDs → seenURLs）必须与 Go marshal 一致（双端可互读）。
 */
class WebSearchTempKbStateServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private KnowledgeService knowledgeService;
    private KnowledgeBaseService knowledgeBaseService;
    private WebSearchTempKbStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        knowledgeService = mock(KnowledgeService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        service = new WebSearchTempKbStateService(redis, knowledgeService, knowledgeBaseService);
    }

    /** Go：getErr / unmarshal err / 键缺失 → ("", 空 map, 空 []string)。 */
    @Test
    void getReturnsEmptyTripleWhenMissingOrCorrupt() {
        when(valueOps.get("tempkb:s1")).thenReturn(null);
        WebSearchTempKbStateService.TempKbState empty = service.getTempKbState("s1");
        assertThat(empty.kbId()).isEmpty();
        assertThat(empty.knowledgeIds()).isEmpty();
        assertThat(empty.seenUrls()).isEmpty();

        when(valueOps.get("tempkb:s1")).thenReturn("not-json{");
        WebSearchTempKbStateService.TempKbState corrupt = service.getTempKbState("s1");
        assertThat(corrupt.kbId()).isEmpty();
        assertThat(corrupt.seenUrls()).isEmpty();
    }

    /** save 落键的 JSON 形态：键序与 Go 匿名 struct 一致。 */
    @Test
    void saveWritesGoShapedJson() {
        service.saveTempKbState("s1", "kb-1", Map.of("http://a", true), List.of("k1", "k2"));
        verify(valueOps).set(
                org.mockito.ArgumentMatchers.eq("tempkb:s1"),
                org.mockito.ArgumentMatchers.eq(
                        "{\"kbID\":\"kb-1\",\"knowledgeIDs\":[\"k1\",\"k2\"],"
                                + "\"seenURLs\":{\"http://a\":true}}"));
    }

    /** Delete 分支 1：键不存在 → 无事可做。 */
    @Test
    void deleteMissingKeyIsNoop() {
        when(valueOps.get("tempkb:s1")).thenReturn(null);
        service.deleteTempKbState("s1");
        verify(redis, never()).delete(anyString());
        verify(knowledgeService, never()).deleteKnowledge(anyString());
    }

    /** Delete 分支 2：JSON 损坏 → 只删键。 */
    @Test
    void deleteCorruptStateDeletesKeyOnly() {
        when(valueOps.get("tempkb:s1")).thenReturn("garbage{");
        service.deleteTempKbState("s1");
        verify(redis).delete("tempkb:s1");
        verify(knowledgeService, never()).deleteKnowledge(anyString());
        verify(knowledgeBaseService, never()).deleteKnowledgeBase(anyString());
    }

    /** Delete 分支 3：kbID 空白 → 只删键。 */
    @Test
    void deleteBlankKbIdDeletesKeyOnly() {
        when(valueOps.get("tempkb:s1")).thenReturn(
                "{\"kbID\":\"  \",\"knowledgeIDs\":[\"k1\"],\"seenURLs\":{}}");
        service.deleteTempKbState("s1");
        verify(redis).delete("tempkb:s1");
        verify(knowledgeService, never()).deleteKnowledge(anyString());
    }

    /** Delete 分支 4：逐条删知识（单条失败继续）→ 删 KB → 删键。 */
    @Test
    void deleteFullStateCleansKnowledgeKbAndKey() {
        when(valueOps.get("tempkb:s1")).thenReturn(
                "{\"kbID\":\"kb-1\",\"knowledgeIDs\":[\"k1\",\"k2\"],"
                        + "\"seenURLs\":{\"http://a\":true}}");
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(knowledgeService).deleteKnowledge("k1");
        service.deleteTempKbState("s1");
        verify(knowledgeService).deleteKnowledge("k1");
        verify(knowledgeService).deleteKnowledge("k2");
        verify(knowledgeBaseService).deleteKnowledgeBase("kb-1");
        verify(redis).delete("tempkb:s1");
    }

    /** Delete 唯一上抛点：最后的 Redis 删除失败（Go "failed to delete Redis key: %w"）。 */
    @Test
    void deleteKeyFailurePropagates() {
        when(valueOps.get("tempkb:s1")).thenReturn(
                "{\"kbID\":\"kb-1\",\"knowledgeIDs\":[],\"seenURLs\":{}}");
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(redis).delete("tempkb:s1");
        assertThatThrownBy(() -> service.deleteTempKbState("s1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to delete Redis key");
    }
}
