package com.ragagent.chatpipeline.plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.event.TenantContextSnapshot;

/**
 * 查询扩展协作者（对照 Go query_expansion.go 全文，自 {@link PluginSearch} 机械搬出）：
 * 低召回时的本地变体检索（并发窗口 16）与变体查询生成（关键词/短语/去疑问词）。
 * 持门面回引取 knowledgeBaseService；{@code withTenant}/{@code QueryTextOps} 经类名访问。
 */
final class PluginExpansionOps {

    private final PluginSearch service;

    PluginExpansionOps(PluginSearch service) {
        this.service = service;
    }

    // 查询扩展（query_expansion.go 全文）
    // ------------------------------------------------------------------

    /** 对照 runQueryExpansion：低召回时的本地变体检索，并发窗口 16。 */
    public List<SearchResult> runQueryExpansion(ChatManage chatManage) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("current", chatManage.getSearchResult().size());
        f.put("threshold", chatManage.getEmbeddingTopK());
        PipelineLog.info("Search", "recall_low", f);

        List<String> expansions = expandQueries(chatManage);
        if (expansions == null || expansions.isEmpty()) {
            return null;
        }

        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("variants", expansions.size());
        PipelineLog.info("Search", "expansion_start", sf);

        int expTopK = Math.max(chatManage.getEmbeddingTopK() * 2, chatManage.getRerankTopK() * 2);
        double expKwTh = chatManage.getKeywordThreshold() * 0.8;

        List<SearchResult> expResults = new ArrayList<>();
        Object lock = new Object();

        // 统计有效作业数（跳过 nil / 空 KB ID 的目标）
        List<Object[]> jobs = new ArrayList<>();
        for (String q : expansions) {
            for (SearchTarget target : chatManage.getSearchTargets()) {
                if (target == null || target.knowledgeBaseId().isEmpty()) {
                    continue;
                }
                jobs.add(new Object[] {q, target});
            }
        }
        int jobsN = jobs.size();
        int capSem = Math.min(16, jobsN);
        if (capSem <= 0) {
            capSem = 1;
        }
        Semaphore sem = new Semaphore(capSem);
        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("jobs", jobsN);
        cf.put("cap", capSem);
        PipelineLog.info("Search", "expansion_concurrency", cf);

        TenantContextSnapshot expansionSnap = TenantContextSnapshot.capture();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Object[] job : jobs) {
                String q = (String) job[0];
                SearchTarget t = (SearchTarget) job[1];
                futures.add(executor.submit(PluginSearch.withTenant(expansionSnap, () -> {
                    sem.acquireUninterruptibly();
                    try {
                        double[] th = t.recallThresholds(chatManage.getVectorThreshold(), expKwTh);
                        SearchParams params = new SearchParams();
                        params.setQueryText(q);
                        params.setVectorThreshold(th[0]);
                        params.setKeywordThreshold(th[1]);
                        params.setMatchCount(expTopK);
                        params.setTagIds(t.tagIds());
                        params.setScopeTagIds(t.scopeTagIds());
                        params.setDisableVectorMatch(false);
                        params.setDisableKeywordsMatch(false);
                        params.setSkipContextEnrichment(true); // 上下文组装在 merge 阶段
                        if (SearchTarget.TYPE_KNOWLEDGE.equals(t.type())) {
                            params.setKnowledgeIds(t.knowledgeIds());
                        }
                        List<SearchResult> res;
                        try {
                            res = service.knowledgeBaseService.hybridSearch(t.knowledgeBaseId(), params);
                        } catch (RuntimeException e) {
                            Map<String, Object> w = new LinkedHashMap<>();
                            w.put("kb_id", t.knowledgeBaseId());
                            w.put("error", e.getMessage());
                            PipelineLog.warn("Search", "expansion_error", w);
                            return null;
                        }
                        if (res != null && !res.isEmpty()) {
                            for (SearchResult r : res) {
                                r.setKnowledgeBaseId(t.knowledgeBaseId());
                            }
                            Map<String, Object> h = new LinkedHashMap<>();
                            h.put("kb_id", t.knowledgeBaseId());
                            h.put("query", q);
                            h.put("hits", res.size());
                            PipelineLog.info("Search", "expansion_hits", h);
                            synchronized (lock) {
                                expResults.addAll(res);
                            }
                        }
                    } finally {
                        sem.release();
                    }
                    return null;
                })));
            }
            for (var fu : futures) {
                try {
                    fu.get();
                } catch (Exception e) {
                    throw new IllegalStateException("expansion job failed", e);
                }
            }
        }

        if (!expResults.isEmpty()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("added", expResults.size());
            PipelineLog.info("Search", "expansion_done", d);
        }
        return expResults;
    }

    /**
     * 对照 expandQueries：无 LLM 的本地变体生成（去停用词、引号短语、分隔符切段、
     * 去疑问词），最多 5 条。
     */
    public List<String> expandQueries(ChatManage chatManage) {
        String query = chatManage.getRewriteQuery().trim();
        if (query.isEmpty()) {
            return null;
        }

        List<String> expansions = new ArrayList<>(5);
        Set<String> seen = new HashSet<>();
        seen.add(query.toLowerCase(java.util.Locale.ROOT));
        String lowerQuery = chatManage.getQuery().toLowerCase(java.util.Locale.ROOT);
        if (!lowerQuery.isEmpty()) {
            seen.add(lowerQuery);
        }

        // 1. 去停用词 → 纯关键词变体
        List<String> keywords = QueryTextOps.extractKeywords(query);
        if (keywords.size() >= 2) {
            addIfNew(expansions, seen, String.join(" ", keywords));
        }

        // 2. 引号短语
        for (String phrase : QueryTextOps.extractPhrases(query)) {
            addIfNew(expansions, seen, phrase);
        }

        // 3. 分隔符切段取长段（Go 的 len(seg) > 5 是 UTF-8 字节数：CJK 短语按字节计入）
        for (String seg : QueryTextOps.splitByDelimiters(query)) {
            if (seg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5) {
                addIfNew(expansions, seen, seg);
            }
        }

        // 4. 去疑问词
        String cleaned = QueryTextOps.removeQuestionWords(query);
        if (!cleaned.equals(query)) {
            addIfNew(expansions, seen, cleaned);
        }

        if (expansions.size() > 5) {
            expansions = new ArrayList<>(expansions.subList(0, 5));
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("variants", expansions.size());
        PipelineLog.info("Search", "local_expansion_result", f);
        return expansions;
    }

    private static void addIfNew(List<String> expansions, Set<String> seen, String s) {
        String v = s.trim();
        // Go 的 len(s) 是 UTF-8 字节数（ASCII 短语 &lt;3 与单 CJK 字符 =3 字节的分界都要对齐）
        if (v.isEmpty() || v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 3) {
            return;
        }
        String key = v.toLowerCase(java.util.Locale.ROOT);
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        expansions.add(v);
    }
}
