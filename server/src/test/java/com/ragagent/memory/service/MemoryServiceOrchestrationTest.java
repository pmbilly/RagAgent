package com.ragagent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.common.context.TenantContext;
import com.ragagent.memory.MemoryContext;
import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySettings;
import com.ragagent.memory.mapper.MemoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * service 层的编排逻辑，跑在**真实 H2 仓储**上（照 §7.5 与
 * {@code MemoryRepositoryTest} 的写法）。
 *
 * <p>覆盖的是"只有把仓储、主体、墓碑、容量、块重建串起来才看得见"的行为：
 * 写路径的去重与脱敏短路、墓碑挡住重新推导、容量归档、清空、设置合并、
 * 三处开关的降级。<b>不涉及模型调用</b>——工作区配置里 {@code embedding_model_id}
 * 为空，所以 {@code embedder()} 恒回 null，一次网络都不会出（约定 §7.5 第 7 条）。</p>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 只为与其余契约测试共用同一个 Spring 上下文缓存键。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryServiceOrchestrationTest {

    private static final long TENANT = 10009L;

    /** 记忆开着、自动抽取、容量 3、兴趣阈值 2、无向量模型。 */
    private static final String CONFIG = "{\"enabled\":true,\"writeMode\":\"auto\",\"maxItems\":3,"
            + "\"extractDelaySeconds\":5,\"extractMinIntervalSeconds\":10,"
            + "\"interestThreshold\":2,\"embeddingModelId\":\"\",\"vectorRecall\":false}";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MemoryRepository repo;
    @Autowired
    private MemoryService memoryService;

    private MemoryScope scope;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        for (String table : List.of("memory_item_embeddings", "memory_extraction_sessions",
                "memory_items", "memory_tombstones", "memory_topic_stats", "memory_doc_affinity",
                "memory_subjects")) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO tenants (id, name, memory_config) VALUES (?, ?, ?)",
                TENANT, "memory-test", CONFIG);
        signIn("m1");
        MemoryContext.clear();
        scope = new MemoryScope(TENANT, "web_user:m1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        MemoryContext.clear();
    }

    private void signIn(String userId) {
        TenantContext.set(TENANT, new TenantContext.Principal("web_user", userId), "viewer",
                false, userId, false);
    }

    @Nested
    @DisplayName("开关")
    class Switches {

        @Test
        void availableWhenTheWorkspaceHasMemoryOn() {
            assertThat(memoryService.memoryAvailable()).isTrue();
        }

        @Test
        void unavailableWithoutAPrincipal() {
            TenantContext.set(TENANT, null, "viewer", false, "", false);
            assertThat(memoryService.memoryAvailable()).isFalse();
            // ⚠️ 两条路抛的不是同一个异常，与 Go 一致：
            //   1) 经过 enabledScope 的写路径 → ErrMemoryDisabled（Go 的 enabledScope 把
            //      ResolveScope 的失败吞成了"没开"，所以 handler 给 400 而不是 401）；
            //   2) 直接调 ResolveScope 的管理器路径 → ErrNoMemoryScope（handler 给 401）。
            assertThatThrownBy(() -> memoryService.createItem("fact", "x", 3))
                    .isInstanceOf(MemoryScopeExceptions.Disabled.class);
            assertThatThrownBy(() -> memoryService.listItems(null, 10, 0))
                    .isInstanceOf(MemoryScopeExceptions.NoScope.class);
            assertThatThrownBy(() -> memoryService.getSettings())
                    .isInstanceOf(MemoryScopeExceptions.NoScope.class);
        }

        @Test
        void unavailableWhenTheWorkspaceSwitchIsOff() {
            jdbc.update("UPDATE tenants SET memory_config = ? WHERE id = ?",
                    "{\"enabled\":false}", TENANT);
            assertThat(memoryService.memoryAvailable()).isFalse();
            assertThatThrownBy(() -> memoryService.createItem("fact", "x", 3))
                    .isInstanceOf(MemoryScopeExceptions.Disabled.class);
        }

        @Test
        void unavailableWhenTheAgentMarkedThisRequestDisabled() {
            MemoryContext.markDisabled();
            assertThat(memoryService.memoryAvailable()).isFalse();
        }

        @Test
        void setEnabledFlipsTheUsersOwnOptOut() {
            memoryService.setEnabled(false);
            assertThat(memoryService.memoryAvailable()).isFalse();
            memoryService.setEnabled(true);
            assertThat(memoryService.memoryAvailable()).isTrue();
        }
    }

    @Nested
    @DisplayName("写路径")
    class WritePath {

        @Test
        void normalisesUnknownKindAndZeroImportance() {
            MemoryItem created = memoryService.createItem("bogus", "生产库用的是 MySQL", 0);
            assertThat(created.getKind()).isEqualTo(MemoryKinds.KIND_FACT);
            assertThat(created.getImportance()).isEqualTo(3);
            assertThat(created.getOrigin()).isEqualTo(MemoryKinds.ORIGIN_MANUAL);
            assertThat(created.getStatus()).isEqualTo(MemoryKinds.STATUS_ACTIVE);
            assertThat(created.getId()).hasSize(36);
            assertThat(created.getTenantId()).isEqualTo(TENANT);
            assertThat(created.getSubjectId()).isEqualTo("web_user:m1");
        }

        @Test
        void sameStatementAboutTheSameTopicIsNotWrittenTwice() {
            MemoryItem first = memoryService.createItem("fact", "生产库用的是 MySQL", 3);
            MemoryItem again = memoryService.createItem("fact", "生产库用的是 MySQL", 3);

            assertThat(again.getId()).isEqualTo(first.getId());
            assertThat(repo.countActive(scope)).isEqualTo(1);
        }

        @Test
        void rebuildsTheResidentBlockAfterEveryWrite() {
            memoryService.createItem("profile", "在医疗影像公司做后端", 4);
            var subject = repo.getSubject(scope);
            assertThat(subject.getBlockText()).contains("About the user:");
            assertThat(subject.getBlockText()).contains("在医疗影像公司做后端");
            assertThat(subject.getItemCount()).isEqualTo(1);
        }

        @Test
        void emptyContentIsRejected() {
            assertThatThrownBy(() -> memoryService.createItem("fact", "   ", 3))
                    .isInstanceOf(MemoryScopeExceptions.EmptyContent.class);
        }

        @Test
        void mostlySensitiveStatementsAreDroppedEntirely() {
            assertThatThrownBy(() -> memoryService.createItem("fact",
                    "api_key = sk-abcdefghijklmnop1234", 3))
                    .isInstanceOf(MemoryScopeExceptions.SensitiveContent.class);
            assertThat(repo.countActive(scope)).isZero();
        }

        @Test
        void sensitivePartsAreRedactedButTheStatementSurvives() {
            MemoryItem created = memoryService.createItem("fact",
                    "生产库密码是 hunter2hunter2，另外这个服务主要用 Go 写", 3);
            assertThat(created.getContent()).contains(MemoryKinds.REDACTED_PLACEHOLDER);
            assertThat(created.getContent()).doesNotContain("hunter2hunter2");
        }
    }

    @Nested
    @DisplayName("墓碑：忘掉的东西不许复活")
    class Tombstones {

        @Test
        void deleteRecordsATombstoneThatBlocksReDerivation() {
            MemoryItem created = memoryService.createItem("fact", "生产库用的是 MySQL", 3);
            memoryService.deleteItem(created.getId());
            assertThat(repo.getSubject(scope).getItemCount()).isZero();

            assertThatThrownBy(() -> memoryService.createItem("fact", "生产库用的是 MySQL", 3))
                    .isInstanceOf(MemoryScopeExceptions.PreviouslyForgotten.class);
        }

        @Test
        void deletingSomethingThatIsNotThereIsNotFound() {
            assertThatThrownBy(() -> memoryService.deleteItem("nope"))
                    .isInstanceOf(MemoryScopeExceptions.ItemNotFound.class);
        }

        @Test
        void clearLeavesTombstonesBehindAndDropsTheCounters() {
            memoryService.createItem("fact", "生产库用的是 MySQL", 3);
            memoryService.createItem("fact", "部署在自建机房", 3);
            long removed = memoryService.clear();

            assertThat(removed).isEqualTo(2);
            assertThat(repo.countActive(scope)).isZero();
            assertThat(repo.listUnpromotedTopics(scope, 50, 0).total()).isZero();

            assertThatThrownBy(() -> memoryService.createItem("fact", "生产库用的是 MySQL", 3))
                    .isInstanceOf(MemoryScopeExceptions.PreviouslyForgotten.class);
        }
    }

    @Nested
    @DisplayName("容量执行（系统里唯一的自动遗忘）")
    class Capacity {

        @Test
        void archivesTheLowestRankedOnceTheCapIsExceeded() {
            memoryService.createItem("fact", "事实一", 3);
            memoryService.createItem("fact", "事实二", 3);
            memoryService.createItem("fact", "事实三", 3);
            assertThat(repo.countActive(scope)).isEqualTo(3);

            memoryService.createItem("fact", "事实四", 3);

            // max_items = 3 → 第 4 条写入后归档掉排名最低的那条
            assertThat(repo.countActive(scope)).isEqualTo(3);
            var page = memoryService.listItems(MemoryKinds.STATUS_ARCHIVED, 50, 0);
            assertThat(page.total()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("编辑 / 提议 / 拒绝")
    class Editing {

        @Test
        void updateKeepsTheTopicAndMarksTheItemManual() {
            MemoryItem created = memoryService.createItem("fact", "生产库用的是 MySQL", 3);
            MemoryItem updated = memoryService.updateItem(created.getId(), "生产库已迁到 PostgreSQL", 5);

            assertThat(updated.getContent()).isEqualTo("生产库已迁到 PostgreSQL");
            assertThat(updated.getTopic()).isEqualTo(created.getTopic());
            assertThat(updated.getOrigin()).isEqualTo(MemoryKinds.ORIGIN_MANUAL);
            assertThat(updated.getImportance()).isEqualTo(5);
        }

        @Test
        void updateOfAMissingItemIsNotFound() {
            assertThatThrownBy(() -> memoryService.updateItem("nope", "x", 3))
                    .isInstanceOf(MemoryScopeExceptions.ItemNotFound.class);
        }

        @Test
        void anInferredStatementWaitsForConfirmation() {
            MemoryItem raw = new MemoryItem();
            raw.setKind(MemoryKinds.KIND_PROFILE);
            raw.setTopic("可能的身份");
            raw.setContent("可能在负责仓库单据管理");
            raw.setImportance(2);
            raw.setOrigin(MemoryKinds.ORIGIN_EXTRACTED);
            raw.setInferred(true);

            MemoryItem stored = memoryService.remember(raw);
            assertThat(stored.getStatus()).isEqualTo(MemoryKinds.STATUS_PENDING);

            MemoryItem confirmed = memoryService.confirmItem(stored.getId());
            assertThat(confirmed.getStatus()).isEqualTo(MemoryKinds.STATUS_ACTIVE);
        }

        @Test
        void rejectDeletesRatherThanArchives() {
            MemoryItem raw = new MemoryItem();
            raw.setKind(MemoryKinds.KIND_PROFILE);
            raw.setTopic("可能的身份");
            raw.setContent("可能在负责仓库单据管理");
            raw.setImportance(2);
            raw.setInferred(true);
            MemoryItem stored = memoryService.remember(raw);

            memoryService.rejectItem(stored.getId());

            assertThat(repo.getItem(scope, stored.getId())).isNull();
            assertThat(memoryService.listItems(null, 50, 0).total()).isZero();
        }
    }

    @Nested
    @DisplayName("设置与列表")
    class SettingsAndLists {

        @Test
        void mergesTheWorkspaceSwitchWithTheUsersOwn() {
            memoryService.createItem("fact", "生产库用的是 MySQL", 3);
            MemorySettings settings = memoryService.getSettings();

            assertThat(settings.isWorkspaceEnabled()).isTrue();
            assertThat(settings.isUserEnabled()).isTrue();
            assertThat(settings.isEffective()).isTrue();
            assertThat(settings.getWriteMode()).isEqualTo(MemoryConfig.WRITE_MODE_AUTO);
            assertThat(settings.getMaxItems()).isEqualTo(3);
            assertThat(settings.getItemCount()).isEqualTo(1);
        }

        @Test
        void listItemsReportsTotalsAndFiltersByStatus() {
            memoryService.createItem("fact", "事实一", 3);
            memoryService.createItem("fact", "事实二", 3);

            assertThat(memoryService.listItems(null, 1, 0).items()).hasSize(1);
            assertThat(memoryService.listItems(null, 1, 0).total()).isEqualTo(2);
            assertThat(memoryService.listItems(MemoryKinds.STATUS_ARCHIVED, 50, 0).total()).isZero();
        }
    }

    @Nested
    @DisplayName("按需查找（search.go）")
    class Search {

        @Test
        void unavailableWhenMemoryIsOff() {
            MemoryContext.markDisabled();
            MemorySearchResult result = memoryService.searchMemory("生产库", 5);
            assertThat(result.available()).isFalse();
            assertThat(result.items()).isNull();
        }

        @Test
        void blankQueryIsAvailableButEmpty() {
            // 空查询走到这里而不是短路，"记忆关着"仍然赢过"你要了空的东西"
            MemorySearchResult result = memoryService.searchMemory("   ", 5);
            assertThat(result.available()).isTrue();
            assertThat(result.items()).isNull();
        }

        @Test
        void findsItemsByWordingAndFiltersOutUnrelatedOnes() {
            memoryService.createItem("fact", "生产库用的是 MySQL", 3);
            memoryService.createItem("fact", "部署在自建机房", 3);

            MemorySearchResult result = memoryService.searchMemory("生产库 MySQL 迁移", 5);
            assertThat(result.available()).isTrue();
            assertThat(result.items()).isNotNull();
            assertThat(result.items()).extracting(MemoryItem::getContent)
                    .containsExactly("生产库用的是 MySQL");

            // 上限夹到 SEARCH_MAX_ITEMS、下限夹到默认值——两者都不该抛
            assertThat(memoryService.searchMemory("生产库", 999).available()).isTrue();
            assertThat(memoryService.searchMemory("生产库", -1).available()).isTrue();
        }

        @Test
        void touchAsyncDoesNotChangeTheResponse() {
            memoryService.createItem("fact", "生产库用的是 MySQL", 3);
            memoryService.searchMemory("生产库 MySQL", 5);
            // 异步的 TouchUsed 只写 use_count，绝不影响这次返回的内容
            MemoryItem stored = memoryService.listItems(null, 50, 0).items().get(0);
            assertThat(stored.getContent()).isEqualTo("生产库用的是 MySQL");
        }
    }

    @Nested
    @DisplayName("检索条件化")
    class Retrieval {

        @Test
        void assemblesBackgroundInterestsAndDocuments() {
            memoryService.createItem("profile", "在医疗影像公司做后端", 4);
            memoryService.createItem("interest", "医疗影像", 3);

            memoryService.recordAnswerSources(List.of(affinity("kb-1", "分割参数说明"),
                    affinity("kb-2", "部署手册")));

            MemoryRetrievalContext ctx = memoryService.retrievalContextFor();
            assertThat(ctx.background()).isEqualTo("在医疗影像公司做后端");
            assertThat(ctx.interests()).containsExactly("医疗影像");
            assertThat(ctx.documents()).isEmpty();
            assertThat(ctx.items()).hasSize(2);
            assertThat(ctx.empty()).isFalse();
        }

        @Test
        void documentTitlesOnlyCountOnceThereIsAHabit() {
            memoryService.recordAnswerSources(List.of(affinity("kb-1", "分割参数说明")));
            assertThat(memoryService.retrievalContextFor().documents()).isEmpty();

            memoryService.recordAnswerSources(List.of(affinity("kb-1", "分割参数说明")));
            // 两次才算是模式（MEMORY_DOC_AFFINITY_MIN_HITS = 2）
            assertThat(memoryService.retrievalContextFor().documents())
                    .containsExactly("分割参数说明");
        }

        @Test
        void affinityMapIsKeyedByKnowledgeId() {
            memoryService.recordAnswerSources(List.of(affinity("kb-1", "t")));
            assertThat(memoryService.documentAffinity(List.of("kb-1", "kb-2")))
                    .containsEntry("kb-1", 1);
            assertThat(memoryService.documentAffinity(List.of())).isNull();
        }

        @Test
        void familiarKnowledgeIdsFilterByMinHits() {
            memoryService.recordAnswerSources(List.of(affinity("kb-1", "t")));
            assertThat(memoryService.familiarKnowledgeIds()).isEmpty();

            memoryService.recordAnswerSources(List.of(affinity("kb-1", "t")));
            assertThat(memoryService.familiarKnowledgeIds()).containsExactly("kb-1");
        }

        @Test
        void emptyRetrievalContextIsEmpty() {
            assertThat(memoryService.retrievalContextFor().empty()).isTrue();
            assertThat(MemoryRetrievalContext.EMPTY.empty()).isTrue();
        }

        private MemoryDocAffinity affinity(String knowledgeId, String title) {
            MemoryDocAffinity ref = new MemoryDocAffinity();
            ref.setKnowledgeId(knowledgeId);
            ref.setKnowledgeBaseId("kbase-1");
            ref.setTitle(title);
            return ref;
        }
    }

    @Nested
    @DisplayName("主题管理器")
    class Topics {

        @Test
        void promotedTopicsAppearInTheManagerList() {
            // 手工造一条已计数但未提升的主题（observeTopics 要模型，这里只验投影）
            var stat = repo.bumpTopic(scope, "门店排班管理",
                    com.ragagent.common.settings.MemoryKeys.normalizeTopicKey("门店排班管理"),
                    "店员班次安排");

            var page = memoryService.listTopics(50, 0);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.items().get(0).getTopic()).isEqualTo("门店排班管理");
            // threshold 不是行上的字段，由 service 从工作区配置传进来（配置里是 2）
            assertThat(page.items().get(0).getThreshold()).isEqualTo(2);
            assertThat(stat.getHits()).isEqualTo(1);
        }

        @Test
        void promotingAnUnknownTopicIsNotFound() {
            assertThatThrownBy(() -> memoryService.promoteTopic("nope"))
                    .isInstanceOf(MemoryScopeExceptions.ItemNotFound.class);
        }

        @Test
        void deleteTopicRemembersTheRefusal() {
            var stat = repo.bumpTopic(scope, "门店排班管理",
                    com.ragagent.common.settings.MemoryKeys.normalizeTopicKey("门店排班管理"), "");
            memoryService.deleteTopic(stat.getId());

            assertThat(repo.topicById(scope, stat.getId())).isNull();
            assertThat(memoryService.listTopics(50, 0).total()).isZero();
        }

        @Test
        void documentsCanBeListedAndDeleted() {
            MemoryDocAffinity ref = new MemoryDocAffinity();
            ref.setKnowledgeId("kb-1");
            ref.setKnowledgeBaseId("kbase-1");
            ref.setTitle("分割参数说明");
            memoryService.recordAnswerSources(List.of(ref));
            memoryService.recordAnswerSources(List.of(ref));

            var page = memoryService.listDocuments(50, 0);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.items().get(0).getHits()).isEqualTo(2);

            memoryService.deleteDocument(page.items().get(0).getId());
            assertThat(memoryService.listDocuments(50, 0).total()).isZero();
            assertThatThrownBy(() -> memoryService.deleteDocument("nope"))
                    .isInstanceOf(MemoryScopeExceptions.ItemNotFound.class);
        }
    }
}
