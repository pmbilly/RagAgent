package com.ragagent.wiki.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.wiki.service.page.WikiPageService;

/**
 * {@link WikiIngestDedupService} 的对等测试（对照 Go
 * internal/application/service/wiki_ingest_dedup_test.go，640 行）。
 *
 * <p>Go 用 miniredis 验证 Redis 的 Lua GET-or-SET 语义；Java 侧的等价物是
 * {@link InProcessWikiIdentityClaimStore}（逐条复刻该脚本），因此这里直接对它做断言
 * ——<b>无需</b>真实 Redis，也不依赖任何网络（约定 §6）。</p>
 */
class WikiIngestDedupServiceTest {

    private final WikiPageService wikiService = mock(WikiPageService.class);
    private final InProcessWikiIdentityClaimStore claims = new InProcessWikiIdentityClaimStore();
    private final WikiIngestDedupService svc = new WikiIngestDedupService(wikiService, claims);

    private static WikiPage page(String slug, String title, String type, String... aliases) {
        WikiPage p = new WikiPage();
        p.setSlug(slug);
        p.setTitle(title);
        p.setPageType(type);
        p.setAliases(new ArrayList<>(List.of(aliases)));
        return p;
    }

    private static WikiPageLite lite(String slug, String title, String type) {
        WikiPageLite p = new WikiPageLite();
        p.setSlug(slug);
        p.setTitle(title);
        p.setPageType(type);
        return p;
    }

    private static ExtractedItem item(String slug, String name, String... aliases) {
        ExtractedItem it = new ExtractedItem();
        it.setSlug(slug);
        it.setName(name);
        it.setAliases(new ArrayList<>(List.of(aliases)));
        return it;
    }

    private static List<String> slugsOf(List<WikiPage> pages) {
        List<String> out = new ArrayList<>(pages.size());
        for (WikiPage p : pages) {
            out.add(p.getSlug());
        }
        return out;
    }

    private static boolean containsSlug(List<WikiPage> pages, String slug) {
        for (WikiPage p : pages) {
            if (p.getSlug().equals(slug)) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // dedupMergeRejectReason
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code TestDedupMergeRejectReason}：逐条表驱动地覆盖"允许/拒绝"的五种情形。
     */
    @Test
    @DisplayName("dedupMergeRejectReason：逐条表驱动（对照 Go TestDedupMergeRejectReason）")
    void dedupMergeRejectReasonTable() {
        record Case(String name, String src, String dst, Set<String> candidates, boolean wantAllowed) { }
        List<Case> cases = List.of(
                new Case("allowed: dst is a candidate for this item, same type prefix",
                        "entity/acme-corp", "entity/acme-corporation",
                        Set.of("entity/acme-corporation"), true),
                new Case("rejected: dst similar to a DIFFERENT item (union hallucination)",
                        "entity/tencent-open", "entity/hiring-agent",
                        Set.of("entity/tencent-ur"), false),
                new Case("rejected: dst is not a candidate at all (pure hallucination)",
                        "entity/hy3-preview", "entity/llm-cli-tool", null, false),
                new Case("rejected: type mismatch even when dst is a candidate",
                        "entity/foo", "concept/foo", Set.of("concept/foo"), false),
                new Case("rejected: missing type prefix",
                        "foo", "entity/foo", Set.of("entity/foo"), false));

        for (Case tc : cases) {
            String reason = svc.dedupMergeRejectReason(tc.src(), tc.dst(), tc.candidates());
            boolean allowed = reason.isEmpty();
            assertThat(allowed)
                    .as("%s: dedupMergeRejectReason(%s, %s) reason=%s",
                            tc.name(), tc.src(), tc.dst(), reason)
                    .isEqualTo(tc.wantAllowed());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 预筛
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code TestSelectDedupCandidatePages_FiltersUnrelatedHallucinationTarget}：
     * 回归 llm_debug/20260422_171316.675.log 里观测到的幻觉
     * ——deepseek-v3.2 把 concept/chengzhen-dengji-shiye-renyuan 合并进了
     * concept/zhong-hua-you-xiu-chuan-tong-wen-hua，尽管零字符重叠。
     * 有了预筛，那个传统文化页面根本不该作为候选被喂给 LLM。
     */
    @Test
    @DisplayName("预筛剔除无关的幻觉目标（对照 Go TestSelectDedupCandidatePages_FiltersUnrelatedHallucinationTarget）")
    void selectDedupCandidatePagesFiltersUnrelatedHallucinationTarget() {
        List<ExtractedItem> newItems = List.of(
                item("concept/chengzhen-dengji-shiye-renyuan", "城镇登记失业人员", "登记失业人员"),
                item("entity/beijing-nongshang-yinxing", "北京农商银行"));

        List<WikiPage> pages = new ArrayList<>(List.of(
                page("concept/zhong-hua-you-xiu-chuan-tong-wen-hua", "中华优秀传统文化", "concept"),
                page("concept/jiuye-jineng-peixun", "就业技能培训", "concept"),
                page("concept/peixun-kaohe-pingjia", "培训考核评价", "concept"),
                page("concept/ren-gong-zhi-neng-an-quan", "人工智能安全", "concept", "AI安全"),
                page("entity/bei-jing-shi-jiao-yu-wei-yuan-hui", "北京市教育委员会", "entity", "北京市教委"),
                page("entity/beijingshi-changping-zhiye-xuexiao", "北京市昌平职业学校", "entity")));
        // 填充到超过 dedupSmallCorpusBypass
        for (int i = 0; i < 40; i++) {
            pages.add(page("concept/filler-" + "x".repeat(i + 1),
                    "填充概念" + "占位".repeat(i + 1), "concept"));
        }

        List<WikiPage> got = WikiIdentityDedup.selectDedupCandidatePages(newItems, pages);

        assertThat(containsSlug(got, "concept/zhong-hua-you-xiu-chuan-tong-wen-hua"))
                .as("无关页面应被过滤，实际候选=%s", slugsOf(got))
                .isFalse();
        assertThat(got.size())
                .as("预筛应当压缩语料（%d 页 → 实际 %d）", pages.size(), got.size())
                .isLessThan(pages.size());
    }

    /**
     * 对照 Go {@code TestSelectDedupCandidatePages_KeepsRelatedPages}：相关页面
     * （与新条目共享 token / 字符）必须挺过预筛，让 LLM 仍能评估合并。
     */
    @Test
    @DisplayName("预筛保留相关页面（对照 Go TestSelectDedupCandidatePages_KeepsRelatedPages）")
    void selectDedupCandidatePagesKeepsRelatedPages() {
        List<ExtractedItem> newItems = List.of(
                item("concept/chengzhen-dengji-shiye-renyuan", "城镇登记失业人员", "登记失业人员"));

        List<WikiPage> pages = new ArrayList<>(List.of(
                // 直接相关：既有页标题与新条目在 "登记失业人员" 上重叠。预筛必须保留它。
                page("concept/deng-ji-shi-ye-ren-yuan", "登记失业人员", "concept", "城镇登记失业人员"),
                page("concept/zhong-hua-you-xiu-chuan-tong-wen-hua", "中华优秀传统文化", "concept")));
        for (int i = 0; i < 30; i++) {
            pages.add(page("entity/filler-" + "x".repeat(i + 1), "填充实体" + "占位".repeat(i + 1), "entity"));
        }

        List<WikiPage> got = WikiIdentityDedup.selectDedupCandidatePages(newItems, pages);

        assertThat(containsSlug(got, "concept/deng-ji-shi-ye-ren-yuan"))
                .as("强相关页面应被保留，实际=%s", slugsOf(got))
                .isTrue();
    }

    /**
     * 对照 Go {@code TestSelectDedupCandidatePages_SmallCorpusBypass}：小语料上预筛应当
     * 是 no-op（除页面类型过滤之外）——prompt 本来就小，砍掉合法匹配得不偿失。
     */
    @Test
    @DisplayName("小语料直接旁路（对照 Go TestSelectDedupCandidatePages_SmallCorpusBypass）")
    void selectDedupCandidatePagesSmallCorpusBypass() {
        List<ExtractedItem> newItems = List.of(item("concept/a", "A"));
        List<WikiPage> pages = List.of(
                page("concept/wholly-unrelated-1", "毫不相关一", "concept"),
                page("concept/wholly-unrelated-2", "毫不相关二", "concept"),
                page("concept/wholly-unrelated-3", "毫不相关三", "concept"));
        List<WikiPage> got = WikiIdentityDedup.selectDedupCandidatePages(newItems, pages);
        assertThat(got).hasSize(pages.size());
    }

    /**
     * 对照 Go {@code TestSelectDedupCandidatePages_DropsNonEntityConcept}：
     * 非 entity/concept 页面（摘要、对比……）无论语料大小都必须被剥掉——它们永远不是
     * 合法的合并目标。
     */
    @Test
    @DisplayName("非 entity/concept 页面无条件剥掉（对照 Go TestSelectDedupCandidatePages_DropsNonEntityConcept）")
    void selectDedupCandidatePagesDropsNonEntityConcept() {
        List<ExtractedItem> newItems = List.of(item("concept/foo", "Foo"));
        List<WikiPage> pages = List.of(
                page("summary/some-doc", "Some Doc Summary", WikiConstants.PAGE_TYPE_SUMMARY),
                page("comparison/foo-vs-bar", "Foo vs Bar", WikiConstants.PAGE_TYPE_COMPARISON),
                page("concept/foo-related", "Foo Related", WikiConstants.PAGE_TYPE_CONCEPT));
        List<WikiPage> got = WikiIdentityDedup.selectDedupCandidatePages(newItems, pages);
        for (WikiPage p : got) {
            assertThat(p.getPageType())
                    .as("非 entity/concept 页面应被过滤：%s (%s)", p.getSlug(), p.getPageType())
                    .isIn(WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.PAGE_TYPE_CONCEPT);
        }
    }

    /**
     * 对照 Go {@code TestSurfaceGrams_UnrelatedCJKPair}：真实幻觉配对的 bigram 交集必须为空，
     * 证明底层相似度信号确实在干活。
     */
    @Test
    @DisplayName("无关中文配对的 surfaceGrams 交集为空（对照 Go TestSurfaceGrams_UnrelatedCJKPair）")
    void surfaceGramsUnrelatedCjkPair() {
        Set<String> a = WikiIdentityDedup.surfaceGrams("城镇登记失业人员");
        Set<String> b = WikiIdentityDedup.surfaceGrams("中华优秀传统文化");
        Set<String> shared = new LinkedHashSet<>(a);
        shared.retainAll(b);
        assertThat(shared).as("期望零 bigram 重叠，实际共享 %s", shared).isEmpty();
    }

    /**
     * 对照 Go {@code TestDedupPairScore_AcmeCorpVariant}：拉丁缩写 ↔ 全名的配对必须得分
     * 很高（高于下限），让过滤器保留 "Acme Corp" ↔ "Acme Corporation" 这类合法合并候选。
     */
    @Test
    @DisplayName("Acme Corp ↔ Acme Corporation 得分高于下限（对照 Go TestDedupPairScore_AcmeCorpVariant）")
    void dedupPairScoreAcmeCorpVariant() {
        WikiIdentityDedup.DedupSurface a = new WikiIdentityDedup.DedupSurface(
                WikiIdentityDedup.slugBaseTokens("entity/acme-corp"),
                WikiIdentityDedup.gramsPerSurface(List.of("Acme Corp")));
        WikiIdentityDedup.DedupSurface b = new WikiIdentityDedup.DedupSurface(
                WikiIdentityDedup.slugBaseTokens("entity/acme-corporation"),
                WikiIdentityDedup.gramsPerSurface(List.of("Acme Corporation")));
        assertThat(WikiIdentityDedup.dedupPairScore(a, b))
                .as("期望 Acme Corp ↔ Corporation 得分高于下限 %s",
                        WikiBatchConstants.DEDUP_CANDIDATE_SCORE_FLOOR)
                .isGreaterThanOrEqualTo(WikiBatchConstants.DEDUP_CANDIDATE_SCORE_FLOOR);
    }

    /**
     * 对照 Go {@code TestDedupPairScore_UnrelatedCJKPair}：生产环境观测到的那对无关中文
     * 条目必须得 0 分。
     */
    @Test
    @DisplayName("无关中文配对得分为 0（对照 Go TestDedupPairScore_UnrelatedCJKPair）")
    void dedupPairScoreUnrelatedCjkPair() {
        WikiIdentityDedup.DedupSurface a = new WikiIdentityDedup.DedupSurface(
                WikiIdentityDedup.slugBaseTokens("concept/chengzhen-dengji-shiye-renyuan"),
                WikiIdentityDedup.gramsPerSurface(List.of("城镇登记失业人员", "登记失业人员")));
        WikiIdentityDedup.DedupSurface b = new WikiIdentityDedup.DedupSurface(
                WikiIdentityDedup.slugBaseTokens("concept/zhong-hua-you-xiu-chuan-tong-wen-hua"),
                WikiIdentityDedup.gramsPerSurface(List.of("中华优秀传统文化")));
        assertThat(WikiIdentityDedup.dedupPairScore(a, b))
                .as("期望无关中文配对得分低于下限 %s", WikiBatchConstants.DEDUP_CANDIDATE_SCORE_FLOOR)
                .isLessThan(WikiBatchConstants.DEDUP_CANDIDATE_SCORE_FLOOR);
    }

    // ═══════════════════════════════════════════════════════════════
    // 身份归一化 / 精确目标
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code TestNormalizeWikiIdentityTitlePreservesSemanticPunctuation}：
     * 空白 + 大小写折叠，但标点必须保留（概念 vs 作品/篇章要可区分）。
     */
    @Test
    @DisplayName("归一化标题保留语义标点（对照 Go TestNormalizeWikiIdentityTitlePreservesSemanticPunctuation）")
    void normalizeWikiIdentityTitlePreservesSemanticPunctuation() {
        assertThat(WikiIdentityDedup.normalizeWikiIdentityTitle("  Acme  Corp "))
                .isEqualTo("acmecorp");
        assertThat(WikiIdentityDedup.normalizeWikiIdentityTitle("寓言"))
                .as("概念标题与作品/篇章标题必须保持不同身份")
                .isNotEqualTo(WikiIdentityDedup.normalizeWikiIdentityTitle("《寓言》"));
    }

    /** 对照 Go {@code TestExactIdentityTargetSameTypeOnly} */
    @Test
    @DisplayName("精确同名目标只在同类型里解析（对照 Go TestExactIdentityTargetSameTypeOnly）")
    void exactIdentityTargetSameTypeOnly() {
        ExtractedItem it = item("entity/kong-zi", "孔子");
        Map<String, WikiPageLite> pages = new LinkedHashMap<>();
        pages.put("entity/confucius", lite("entity/confucius", "孔 子", WikiConstants.PAGE_TYPE_ENTITY));
        pages.put("concept/confucius", lite("concept/confucius", "孔子", WikiConstants.PAGE_TYPE_CONCEPT));
        Set<String> candidates = new LinkedHashSet<>(List.of("entity/confucius", "concept/confucius"));

        assertThat(WikiIdentityDedup.exactIdentityTarget(
                it, WikiConstants.PAGE_TYPE_ENTITY, candidates, pages))
                .isEqualTo("entity/confucius");
    }

    // ═══════════════════════════════════════════════════════════════
    // 身份认领
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code TestWikiIdentityClaimConvergesDifferentSlugs}：并发的身份认领必须
     * 收敛到同一个 slug；已验证的既有页是权威的，会替换早先 map worker 留下的临时预留。
     */
    @Test
    @DisplayName("并发身份认领收敛（对照 Go TestWikiIdentityClaimConvergesDifferentSlugs）")
    void wikiIdentityClaimConvergesDifferentSlugs() throws Exception {
        List<String> proposals = List.of(
                "entity/kong-zi", "entity/confucius", "entity/kongzi", "entity/kong-qiu");
        String[] results = new String[proposals.size()];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(proposals.size());
        for (int i = 0; i < proposals.size(); i++) {
            final int idx = i;
            final String proposal = proposals.get(i);
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results[idx] = svc.claimWikiIdentitySlug(
                        "kb-1", WikiConstants.PAGE_TYPE_ENTITY, "孔 子", proposal, false,
                        new WikiBatchContext());
                done.countDown();
            });
            t.start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        for (int i = 1; i < results.length; i++) {
            assertThat(results[i])
                    .as("并发身份认领未收敛：%s", List.of(results))
                    .isEqualTo(results[0]);
        }

        // 已验证的既有页是权威的，会替换早先 map worker 留下的临时预留。
        String authoritative = svc.claimWikiIdentitySlug(
                "kb-1", WikiConstants.PAGE_TYPE_ENTITY, "孔子", "entity/confucius", true,
                new WikiBatchContext());
        String third = svc.claimWikiIdentitySlug(
                "kb-1", WikiConstants.PAGE_TYPE_ENTITY, "孔子", "entity/kongzi", false,
                new WikiBatchContext());
        assertThat(authoritative).isEqualTo("entity/confucius");
        assertThat(third).as("权威身份未替换认领").isEqualTo(authoritative);
    }

    /**
     * 对照 Go {@code TestWikiIdentityClaimKeepsDistinctTypesAndPunctuation}：
     * 不同类型的同名条目、以及带标点的不同标题，都必须保持各自独立的 slug。
     */
    @Test
    @DisplayName("身份认领保持类型与标点区分（对照 Go TestWikiIdentityClaimKeepsDistinctTypesAndPunctuation）")
    void wikiIdentityClaimKeepsDistinctTypesAndPunctuation() {
        WikiBatchContext batch = new WikiBatchContext();
        String concept = svc.claimWikiIdentitySlug(
                "kb-1", WikiConstants.PAGE_TYPE_CONCEPT, "寓言", "concept/fable-zhuangzi", false, batch);
        String chapter = svc.claimWikiIdentitySlug(
                "kb-1", WikiConstants.PAGE_TYPE_CONCEPT, "《寓言》", "concept/yuyan-chapter", false, batch);
        String entity = svc.claimWikiIdentitySlug(
                "kb-1", WikiConstants.PAGE_TYPE_ENTITY, "寓言", "entity/yuyan-zhuangzi", false, batch);
        assertThat(concept).isEqualTo("concept/fable-zhuangzi");
        assertThat(chapter).isEqualTo("concept/yuyan-chapter");
        assertThat(entity).isEqualTo("entity/yuyan-zhuangzi");
    }

    /**
     * 对照 Go {@code TestStabilizeExtractedIdentitiesCoalescesEvidence}：收敛时必须保留
     * 每一个 alias / chunk 引用，并保留更丰富的回落文本。
     */
    @Test
    @DisplayName("身份收敛保留全部证据（对照 Go TestStabilizeExtractedIdentitiesCoalescesEvidence）")
    void stabilizeExtractedIdentitiesCoalescesEvidence() {
        WikiBatchContext batch = new WikiBatchContext();
        ExtractedItem a = item("entity/kong-zi", "孔 子", "孔丘");
        a.setDescription("思想家");
        a.setDetails("短");
        a.setSourceChunks(new ArrayList<>(List.of("chunk-1")));
        ExtractedItem b = item("entity/confucius", "孔子", "Confucius");
        b.setDescription("中国古代思想家、教育家");
        b.setDetails("更完整的说明");
        b.setSourceChunks(new ArrayList<>(List.of("chunk-2")));

        List<ExtractedItem> got = svc.stabilizeExtractedIdentities(
                "kb-1", WikiConstants.PAGE_TYPE_ENTITY, new ArrayList<>(List.of(a, b)), null, null, batch);

        assertThat(got).hasSize(1);
        assertThat(got.get(0).getSlug()).isEqualTo("entity/kong-zi");
        assertThat(got.get(0).getName()).as("未优先采用紧凑显示名：%s", got.get(0)).isEqualTo("孔子");
        assertThat(got.get(0).getDescription()).isEqualTo("中国古代思想家、教育家");
        assertThat(got.get(0).getDetails()).isEqualTo("更完整的说明");
        assertThat(got.get(0).sourceChunksOrEmpty())
                .as("source chunk 未被求并集：%s", got.get(0).sourceChunksOrEmpty())
                .hasSize(2);
    }

    /**
     * 对照 Go {@code TestWikiIdentityClaimRedisOverridesStaleLocal}：共享存储里的认领
     * 要胜过批次局部 map 里的陈旧值，并刷新后者。
     */
    @Test
    @DisplayName("共享认领覆盖陈旧本地值（对照 Go TestWikiIdentityClaimRedisOverridesStaleLocal）")
    void wikiIdentityClaimSharedStoreOverridesStaleLocal() {
        WikiBatchContext batch = new WikiBatchContext();
        String identity = WikiIdentityDedup.normalizeWikiIdentityTitle("孔子");
        batch.identityClaims().put(
                WikiBatchContext.identityPageCacheKey(WikiConstants.PAGE_TYPE_ENTITY, identity),
                "entity/kong-zi");
        // 种下共享存储里的认领（对照 Go 的 rdb.Set(...)）
        claims.claim("kb-1", WikiConstants.PAGE_TYPE_ENTITY, identity,
                "entity/confucius", true, "entity/");

        String got = svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/kongqiu", false, batch);

        assertThat(got).as("共享认领应胜过陈旧的本地 map").isEqualTo("entity/confucius");
        assertThat(batch.identityClaims().get(
                WikiBatchContext.identityPageCacheKey(WikiConstants.PAGE_TYPE_ENTITY, identity)))
                .as("本地缓存未从共享存储刷新")
                .isEqualTo("entity/confucius");
    }

    /**
     * 对照 Go {@code TestStabilizeLLMMergeDoesNotOverrideRedisClaim}：
     * 语义（LLM）合并<b>不是</b>权威的，不能劈开一个已被预留的标题。
     */
    @Test
    @DisplayName("LLM 合并不覆盖身份认领（对照 Go TestStabilizeLLMMergeDoesNotOverrideRedisClaim）")
    void stabilizeLlmMergeDoesNotOverrideClaim() {
        WikiBatchContext batch = new WikiBatchContext();
        String first = svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/kong-zi", false, batch);
        assertThat(first).isEqualTo("entity/kong-zi");

        Map<String, String> mergeTargets = new LinkedHashMap<>();
        mergeTargets.put("entity/confucius", "entity/kong-zi-institute");
        List<ExtractedItem> got = svc.stabilizeExtractedIdentities(
                "kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                new ArrayList<>(List.of(item("entity/confucius", "孔子"))),
                mergeTargets, null, batch);

        assertThat(got).hasSize(1);
        assertThat(got.get(0).getSlug())
                .as("LLM 合并覆盖了身份认领：%s", got)
                .isEqualTo("entity/kong-zi");
    }

    /**
     * 对照 Go {@code TestStabilizeExactTargetIsAuthoritative}：精确既有页命中是权威的，
     * 会替换临时认领，并且这个结果会留在共享存储里。
     */
    @Test
    @DisplayName("精确既有页目标权威（对照 Go TestStabilizeExactTargetIsAuthoritative）")
    void stabilizeExactTargetIsAuthoritative() {
        WikiBatchContext batch = new WikiBatchContext();
        svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/kong-zi", false, batch);

        Map<String, String> exactTargets = new LinkedHashMap<>();
        exactTargets.put("entity/kong-zi", "entity/confucius");
        List<ExtractedItem> got = svc.stabilizeExtractedIdentities(
                "kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                new ArrayList<>(List.of(item("entity/kong-zi", "孔子"))),
                null, exactTargets, batch);

        assertThat(got).hasSize(1);
        assertThat(got.get(0).getSlug())
                .as("精确既有页应替换临时认领：%s", got)
                .isEqualTo("entity/confucius");

        String follow = svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/kongqiu", false, new WikiBatchContext());
        assertThat(follow).as("权威精确命中未能留在共享存储里").isEqualTo("entity/confucius");
    }

    /**
     * 对照 Go {@code TestWikiIdentityClaimLiteConcurrentSameBatch}：同一批次内并发的
     * 认领也必须收敛。
     */
    @Test
    @DisplayName("同批次并发认领收敛（对照 Go TestWikiIdentityClaimLiteConcurrentSameBatch）")
    void wikiIdentityClaimConcurrentSameBatch() throws Exception {
        WikiBatchContext batch = new WikiBatchContext();
        List<String> proposals = List.of("entity/kong-zi", "entity/confucius", "entity/kongzi");
        String[] results = new String[proposals.size()];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(proposals.size());
        for (int i = 0; i < proposals.size(); i++) {
            final int idx = i;
            final String proposal = proposals.get(i);
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results[idx] = svc.claimWikiIdentitySlug(
                        "kb-1", WikiConstants.PAGE_TYPE_ENTITY, "孔子", proposal, false, batch);
                done.countDown();
            });
            t.start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        for (int i = 1; i < results.length; i++) {
            assertThat(results[i])
                    .as("同一批次的本地认领未收敛：%s", List.of(results))
                    .isEqualTo(results[0]);
        }
    }

    /**
     * 对照 Go {@code TestRemapSlugUpdatesByIdentityConverges}：remap 必须把同一标题的
     * 不同罗马化折到已认领的 slug 上，summary slug 保持不动。
     */
    @Test
    @DisplayName("remapSlugUpdatesByIdentity 收敛（对照 Go TestRemapSlugUpdatesByIdentityConverges）")
    void remapSlugUpdatesByIdentityConverges() {
        WikiBatchContext batch = new WikiBatchContext();
        svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/kong-zi", false, batch);

        Map<String, List<SlugUpdate>> in = new LinkedHashMap<>();
        in.put("entity/kong-zi", List.of(entityUpdate("entity/kong-zi", "孔子")));
        in.put("entity/confucius", List.of(entityUpdate("entity/confucius", "孔 子")));
        in.put("summary/doc", List.of(new SlugUpdate("summary/doc", SlugUpdate.TYPE_SUMMARY)));

        Map<String, List<SlugUpdate>> got = svc.remapSlugUpdatesByIdentity("kb-1", in, batch);

        assertThat(got.get("entity/kong-zi"))
                .as("entity 更新应合并到已认领 slug 上：%s", got)
                .hasSize(2);
        for (SlugUpdate u : got.get("entity/kong-zi")) {
            assertThat(u.getItem().getSlug()).as("remap 后 Item.Slug 仍是陈旧的：%s", u).isEqualTo("entity/kong-zi");
        }
        assertThat(got.get("summary/doc")).as("summary slug 不应被改动：%s", got).hasSize(1);
        assertThat(got).as("未被认领的罗马化应被 remap 掉：%s", got).doesNotContainKey("entity/confucius");
    }

    private static SlugUpdate entityUpdate(String slug, String name) {
        SlugUpdate u = new SlugUpdate(slug, SlugUpdate.TYPE_ENTITY);
        ExtractedItem it = new ExtractedItem();
        it.setSlug(slug);
        it.setName(name);
        u.setItem(it);
        return u;
    }

    /**
     * 对照 Go {@code TestReclaimExtractedIdentitiesCoalescesCitationSlugs}：引用遍的
     * new_slugs 跳过了抽取期去重，所以必须重跑一次精确解析 + 身份认领；
     * 并且不能丢掉引用证据。
     */
    @Test
    @DisplayName("重认领合并引用 slug（对照 Go TestReclaimExtractedIdentitiesCoalescesCitationSlugs）")
    void reclaimExtractedIdentitiesCoalescesCitationSlugs() {
        when(wikiService.findPagesByNormalizedTitles(anyString(), anyString(), anyList()))
                .thenReturn(List.of());

        WikiBatchContext batch = new WikiBatchContext();
        svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/kong-zi", false, batch);

        ExtractedItem e1 = item("entity/kong-zi", "孔子");
        e1.setSourceChunks(new ArrayList<>(List.of("c1")));
        ExtractedItem e2 = item("entity/confucius", "孔 子");
        e2.setSourceChunks(new ArrayList<>(List.of("c2")));

        WikiIngestDedupService.Identities got = svc.reclaimExtractedIdentities(
                "kb-1", new ArrayList<>(List.of(e1, e2)), null, batch);

        assertThat(got.entities()).hasSize(1);
        assertThat(got.entities().get(0).getSlug()).isEqualTo("entity/kong-zi");
        assertThat(got.entities().get(0).sourceChunksOrEmpty())
                .as("重认领丢掉了引用证据：%s", got.entities().get(0).sourceChunksOrEmpty())
                .hasSize(2);
        assertThat(got.concepts()).isEmpty();
    }

    /**
     * 对照 Go {@code TestWikiIdentityClaimReplacesInvalidRedisValue}：
     * 共享存储里的脏值必须被替换（调用方绝不可以在一个脏键上分叉）。
     */
    @Test
    @DisplayName("替换共享存储里的非法值（对照 Go TestWikiIdentityClaimReplacesInvalidRedisValue）")
    void wikiIdentityClaimReplacesInvalidSharedValue() {
        String identity = WikiIdentityDedup.normalizeWikiIdentityTitle("孔子");
        // "garbage" 不以 "entity/" 开头 → 视为脏值
        claims.claim("kb-1", WikiConstants.PAGE_TYPE_ENTITY, identity,
                "garbage", true, "entity/");

        String first = svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/kong-zi", false, new WikiBatchContext());
        assertThat(first).as("非法共享值应被替换").isEqualTo("entity/kong-zi");
        String second = svc.claimWikiIdentitySlug("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                "孔子", "entity/confucius", false, new WikiBatchContext());
        assertThat(second).as("替换后调用方未收敛").isEqualTo("entity/kong-zi");
    }

    // ═══════════════════════════════════════════════════════════════
    // 精确标题批量查询与缓存
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code stubNormalizedTitleWiki}：统计批量查询次数并回显命中的页面 */
    @Nested
    @DisplayName("attachExactIdentityPages 批量与缓存")
    class AttachExactIdentityPages {

        private int calls;
        private List<String> last = List.of();
        private final List<WikiPageLite> corpus = List.of(
                lite("entity/confucius", "孔 子", WikiConstants.PAGE_TYPE_ENTITY));

        private final WikiPageService stub = mock(WikiPageService.class);

        private WikiIngestDedupService service() {
            when(stub.findPagesByNormalizedTitles(anyString(), anyString(), anyList()))
                    .thenAnswer(inv -> {
                        List<String> identities = inv.getArgument(2);
                        calls++;
                        last = new ArrayList<>(identities);
                        Set<String> want = new LinkedHashSet<>(identities);
                        List<WikiPageLite> out = new ArrayList<>();
                        for (WikiPageLite p : corpus) {
                            if (p != null
                                    && want.contains(WikiIdentityDedup.normalizeWikiIdentityTitle(p.getTitle()))) {
                                out.add(p);
                            }
                        }
                        return out;
                    });
            return new WikiIngestDedupService(stub, new InProcessWikiIdentityClaimStore());
        }

        /**
         * 对照 Go {@code TestAttachExactIdentityPagesBatchesAndCaches}：一次批量查询覆盖
         * 本批次所有身份；命中页被同时绑到两个罗马化上；不相关的条目不受污染；
         * 第二次调用走缓存不再打库；新增身份只查增量。
         */
        @Test
        @DisplayName("批量查询 + 缓存（对照 Go TestAttachExactIdentityPagesBatchesAndCaches）")
        void batchesAndCaches() {
            WikiIngestDedupService service = service();
            WikiBatchContext batch = new WikiBatchContext();
            List<ExtractedItem> items = new ArrayList<>(List.of(
                    item("entity/kong-zi", "孔子"),
                    item("entity/kongqiu", "孔 子"),
                    item("entity/mencius", "孟子")));

            Map<String, WikiPageLite> candidatePages = new LinkedHashMap<>();
            Map<String, Set<String>> itemCandidates = new LinkedHashMap<>();
            service.attachExactIdentityPages("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                    items, candidatePages, itemCandidates, batch);
            assertThat(calls).as("期望 1 次批量查询，实际 %d identities=%s", calls, last).isEqualTo(1);
            assertThat(itemCandidates.get("entity/kong-zi")).contains("entity/confucius");
            assertThat(itemCandidates.get("entity/kongqiu")).contains("entity/confucius");
            assertThat(itemCandidates.getOrDefault("entity/mencius", Set.of()))
                    .doesNotContain("entity/confucius");

            service.attachExactIdentityPages("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                    items, candidatePages, itemCandidates, batch);
            assertThat(calls).as("批次缓存应跳过第二次查询，实际 %d", calls).isEqualTo(1);

            List<ExtractedItem> grown = new ArrayList<>(items);
            grown.add(item("entity/xunzi", "荀子"));
            service.attachExactIdentityPages("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                    grown, candidatePages, itemCandidates, batch);
            assertThat(calls).as("缓存未命中应只查新身份，实际 %d last=%s", calls, last).isEqualTo(2);
            assertThat(last).hasSize(1);
            assertThat(last.get(0)).isEqualTo(WikiIdentityDedup.normalizeWikiIdentityTitle("荀子"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code countEntityConceptPages}：只用于日志的压缩比 */
    @Test
    @DisplayName("countEntityConceptPages 只数 entity/concept")
    void countEntityConceptPages() {
        List<WikiPage> pages = List.of(
                page("entity/a", "A", WikiConstants.PAGE_TYPE_ENTITY),
                page("concept/b", "B", WikiConstants.PAGE_TYPE_CONCEPT),
                page("summary/c", "C", WikiConstants.PAGE_TYPE_SUMMARY));
        assertThat(WikiIdentityDedup.countEntityConceptPages(pages)).isEqualTo(2);
    }

    /** 对照 Go {@code slugBaseTokens} 的注释示例 */
    @Test
    @DisplayName("slugBaseTokens 例：entity/beijing-nongshang-yinxing")
    void slugBaseTokens() {
        assertThat(WikiIdentityDedup.slugBaseTokens("entity/beijing-nongshang-yinxing"))
                .containsExactlyInAnyOrder("beijing", "nongshang", "yinxing");
        assertThat(WikiIdentityDedup.slugBaseTokens("")).isEmpty();
    }

    /** 对照 Go {@code gramsPerSurface}：空表层形式被跳过 */
    @Test
    @DisplayName("gramsPerSurface 跳过空表层形式")
    void gramsPerSurfaceSkipsEmpty() {
        List<Set<String>> grams = WikiIdentityDedup.gramsPerSurface(
                List.of("Acme", "", "  ", "B"));
        assertThat(grams).hasSize(2);
    }

    /** 对照 Go {@code appendUniqueString}：去空白、非空、不重复 */
    @Test
    @DisplayName("appendUniqueString 去空白且不重复")
    void appendUniqueString() {
        List<String> out = WikiIdentityDedup.appendUniqueString(new ArrayList<>(), "  a ");
        out = WikiIdentityDedup.appendUniqueString(out, "a");
        out = WikiIdentityDedup.appendUniqueString(out, "  ");
        assertThat(out).containsExactly("a");
    }

    /** 未接线/空输入时的容忍语义（对照 Go 的 nil 容忍） */
    @Test
    @DisplayName("空输入容忍：null slugUpdates / null items")
    void nullTolerance() {
        assertThat(svc.remapSlugUpdatesByIdentity("kb-1", null, new WikiBatchContext())).isNull();
        assertThat(svc.stabilizeExtractedIdentities("kb-1", WikiConstants.PAGE_TYPE_ENTITY,
                null, null, null, null)).isEmpty();
        assertThat(WikiIdentityDedup.selectDedupCandidatePages(null, null)).isEmpty();
        assertThat(WikiIdentityDedup.mergeExtractedIdentity(
                new ExtractedItem(), new ExtractedItem()).getName()).isEmpty();
    }
}
