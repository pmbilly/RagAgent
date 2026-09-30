package com.ragagent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.common.settings.MemoryKeys;
import com.ragagent.memory.domain.MemoryTopicStat;
import com.ragagent.memory.mapper.MemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryTopicResolver} 的三层解析（对照 Go topic_resolve.go）。
 *
 * <p>第 1、2 层的期望值是 Go 实录（{@code go test -run TestTruthTopicResolve}）。
 * 第 3 层（模型裁决）用一个返回固定 JSON 的假 {@link LlmChatClient} 驱动，
 * <b>不依赖任何网络</b>（约定 §7.5 第 7 条）。</p>
 */
class MemoryTopicResolverTest {

    private static MemoryTopicStat stat(String id, String topic, String... aliases) {
        MemoryTopicStat s = new MemoryTopicStat();
        s.setId(id);
        s.setTopic(topic);
        s.setNormalizedKey(MemoryKeys.normalizeTopicKey(topic));
        s.setAliases(new ArrayList<>(List.of(aliases)));
        s.setHits(1);
        s.setLastSeenAt(java.time.OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        s.setCreatedAt(java.time.OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        s.setUpdatedAt(java.time.OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        return s;
    }

    /** 与 Go 实录里那份 existing 逐条对应。 */
    private static List<MemoryTopicStat> existing() {
        return List.of(
                stat("t1", "门店排班管理", "店员班次安排"),
                stat("t2", "PostgreSQL 连接池"),
                stat("t3", "数据"));
    }

    private static String idOf(MemoryTopicStat s) {
        return s == null ? "<nil>" : s.getId();
    }

    @Nested
    @DisplayName("tier 1：归一化相等（含别名）")
    class TierOne {

        @Test
        void exactAndAliasAndNormalisedFormsAllHit() {
            // Go 实录：全部 "t1"
            assertThat(idOf(MemoryTopicResolver.matchTopicExactly("门店排班管理", existing())))
                    .isEqualTo("t1");
            assertThat(idOf(MemoryTopicResolver.matchTopicExactly("店员班次安排", existing())))
                    .isEqualTo("t1");
            // "门店的排班管理" 只差一个无信息的"的"——归一化后同一个 key
            assertThat(idOf(MemoryTopicResolver.matchTopicExactly("门店的排班管理", existing())))
                    .isEqualTo("t1");
            // Go 实录："<nil>"（空 key 直接短路，不去查一个不可能命中的条件）
            assertThat(MemoryTopicResolver.matchTopicExactly("  ", existing())).isNull();
        }
    }

    @Nested
    @DisplayName("tier 2：二元组重合 + 具体性门禁")
    class TierTwo {

        @Test
        void highOverlapOnSpecificLabelsMatches() {
            // Go 实录："t2"
            assertThat(idOf(MemoryTopicResolver.matchTopicLoosely(
                    "PostgreSQL 连接池调优", existing()))).isEqualTo("t2");
            // 短标签被门禁挡在模糊匹配之外（Go 实录："<nil>"）
            assertThat(MemoryTopicResolver.matchTopicLoosely("数据", existing())).isNull();
            // 同一领域的不同问题不匹配（Go 实录："<nil>"）
            assertThat(MemoryTopicResolver.matchTopicLoosely("订单接口用法", existing())).isNull();
        }

        @Test
        void thresholdIsTheDocumentedOne() {
            assertThat(MemoryTopicResolver.TOPIC_FUZZY_THRESHOLD).isEqualTo(0.80);
            assertThat(MemoryTopicResolver.TOPIC_CANDIDATE_LIMIT).isEqualTo(40);
        }
    }

    @Nested
    @DisplayName("同一次运行内折叠新主题")
    class Collapse {

        @Test
        void laterDuplicatesAdoptTheEarlierSurface() {
            MemoryTopicStat canonical = existing().get(0);
            List<MemoryTopicResolver.Resolution> resolutions = new ArrayList<>();
            resolutions.add(new MemoryTopicResolver.Resolution("订单接口用法"));
            resolutions.add(new MemoryTopicResolver.Resolution("订单的接口用法"));
            MemoryTopicResolver.Resolution reused = new MemoryTopicResolver.Resolution("门店排班管理");
            reused.canonical = canonical;
            resolutions.add(reused);
            resolutions.add(new MemoryTopicResolver.Resolution("订单接口用法"));

            MemoryTopicResolver.collapseNewTopicsWithinRun(resolutions);

            // Go 实录：surface 依次为 订单接口用法 / 订单接口用法 / 门店排班管理 / 订单接口用法
            assertThat(resolutions.get(0).surface()).isEqualTo("订单接口用法");
            assertThat(resolutions.get(1).surface()).isEqualTo("订单接口用法");
            assertThat(resolutions.get(2).surface()).isEqualTo("门店排班管理");
            assertThat(resolutions.get(3).surface()).isEqualTo("订单接口用法");
            // tier 未被改动，只有 surface 变了
            assertThat(resolutions.get(2).tierOrNew()).isEqualTo("new");
        }
    }

    @Nested
    @DisplayName("tier 3：模型裁决")
    class TierThree {

        private MemoryTopicResolver resolverReturning(String json) {
            MemoryModelResolver models = mock(MemoryModelResolver.class);
            when(models.getChatModel(anyString())).thenReturn(new FakeChat(json));
            return new MemoryTopicResolver(mock(MemoryRepository.class), models);
        }

        private List<MemoryTopicResolver.Resolution> unresolvedPair() {
            List<MemoryTopicResolver.Resolution> resolutions = new ArrayList<>();
            resolutions.add(new MemoryTopicResolver.Resolution("订单接口用法"));
            resolutions.add(new MemoryTopicResolver.Resolution("门店排班管理"));
            return resolutions;
        }

        @Test
        void assignsCanonicalAndTierForPendingIndexes() {
            MemoryTopicResolver resolver = resolverReturning(
                    "{\"resolutions\":[{\"index\":0,\"same_as\":1,\"label\":null}]}");
            List<MemoryTopicResolver.Resolution> resolutions = unresolvedPair();

            resolver.adjudicateTopics(MemoryRunBudget.UNBOUNDED, "m", existing(), resolutions,
                    List.of(0, 1));

            assertThat(idOf(resolutions.get(0).canonical())).isEqualTo("t2");
            assertThat(resolutions.get(0).tier()).isEqualTo("model");
            assertThat(resolutions.get(1).canonical()).isNull();
            assertThat(resolutions.get(1).tier()).isEmpty();
        }

        @Test
        void ignoresIndexesTheModelWasNeverAskedAbout() {
            // 模型返回了没给过它的下标 2 → 绝不能覆盖更可靠那一层已经做出的匹配
            MemoryTopicResolver resolver = resolverReturning(
                    "{\"resolutions\":[{\"index\":2,\"same_as\":1,\"label\":null}]}");
            List<MemoryTopicResolver.Resolution> resolutions = unresolvedPair();
            resolutions.get(1).canonical = existing().get(0);

            resolver.adjudicateTopics(MemoryRunBudget.UNBOUNDED, "m", existing(), resolutions,
                    List.of(0));

            assertThat(resolutions.get(0).canonical()).isNull();
            assertThat(idOf(resolutions.get(1).canonical())).isEqualTo("t1");
            assertThat(resolutions.get(1).tier()).isEmpty();
        }

        @Test
        void ignoresOutOfRangeAndNullSameAs() {
            MemoryTopicResolver resolver = resolverReturning(
                    "{\"resolutions\":[{\"index\":0,\"same_as\":99},{\"index\":1,\"same_as\":null}]}");
            List<MemoryTopicResolver.Resolution> resolutions = unresolvedPair();

            resolver.adjudicateTopics(MemoryRunBudget.UNBOUNDED, "m", existing(), resolutions,
                    List.of(0, 1));

            assertThat(resolutions.get(0).canonical()).isNull();
            assertThat(resolutions.get(1).canonical()).isNull();
        }

        @Test
        void acceptsABetterLabelOnlyWhenItAnchorsBothLabels() {
            MemoryTopicResolver resolver = resolverReturning(
                    "{\"resolutions\":[{\"index\":1,\"same_as\":1,\"label\":\"PostgreSQL 连接池调优\"}]}");
            List<MemoryTopicResolver.Resolution> resolutions = unresolvedPair();

            resolver.adjudicateTopics(MemoryRunBudget.UNBOUNDED, "m", existing(), resolutions,
                    List.of(1));

            // "PostgreSQL 连接池调优" 既是 t2 的超集、又与 surface（门店排班管理）毫无重合
            // → TopicLabelIsAnImprovement 必须拒绝它（锚定测试）。
            assertThat(resolutions.get(1).mergedLabel()).isEmpty();
        }

        @Test
        void missingModelDegradesWithoutFailing() {
            MemoryModelResolver models = mock(MemoryModelResolver.class);
            MemoryTopicResolver resolver = new MemoryTopicResolver(mock(MemoryRepository.class), models);
            List<MemoryTopicResolver.Resolution> resolutions = unresolvedPair();

            // modelId 为空 → 只记 warn，什么都不改（这一层是优化，不是硬依赖）
            resolver.adjudicateTopics(MemoryRunBudget.UNBOUNDED, "", existing(), resolutions, List.of(0));

            assertThat(resolutions.get(0).canonical()).isNull();
            assertThat(resolutions.get(0).tier()).isEmpty();
        }

        @Test
        void unparsableModelOutputIsIgnored() {
            MemoryTopicResolver resolver = resolverReturning("not json at all");
            List<MemoryTopicResolver.Resolution> resolutions = unresolvedPair();

            resolver.adjudicateTopics(MemoryRunBudget.UNBOUNDED, "m", existing(), resolutions,
                    List.of(0, 1));

            assertThat(resolutions.get(0).canonical()).isNull();
            assertThat(resolutions.get(1).canonical()).isNull();
        }
    }

    /** 返回固定正文的假聊天客户端（不出网）。 */
    private static final class FakeChat implements LlmChatClient {
        private final String content;

        FakeChat(String content) {
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            ChatResponse response = new ChatResponse();
            response.setContent(content);
            return response;
        }

        @Override
        public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public String getModelId() {
            return "fake";
        }
    }
}
