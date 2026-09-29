package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.knowledge.domain.Chunk;

/**
 * grep_chunks 工具（对照 Go {@code grep_chunks.go}，逐字移植）。
 *
 * <p>DB 直查经 {@link GrepChunkSearch} seam（对照 {@code searchChunks} 的 gorm 查询：
 * scopeClause OR 组合、{@code (content ~* ? OR knowledges.title ~* ?)} 正则条件、
 * {@code ORDER BY created_at DESC LIMIT 500}、按 knowledge_id 的 COUNT(*) 回填
 * totalChunkCount——整段 SQL 方言逻辑落在 seam 实现侧，4.5c 接真实现；
 * 本工具只留 seam 调用）。</p>
 *
 * <p>已知差异（对照 Go，详见报告）：Go RE2 与 {@code java.util.regex} 正则方言不同
 * （编译失败文案不同，探针不录非法 regex）；{@code searchutil.TokenizeSimple} 的
 * jieba 中文分词无 Java 对应物，MMR 冗余度对中文内容可能不同（探针 MMR 场景用英文）；
 * Go map 迭代序/{@code sort.Slice} 非稳定排序在完全并列时序不定（探针语料避开并列）；
 * {@code knowledge_base_ids} 在 Go 侧来自 map 迭代（序随机），Java 用出现序。</p>
 */
public class GrepChunksTool extends BaseTool {

    /** 键序对照 Go GenerateSchema 输出（字母序：properties < required < type）。 */
    private static final String SCHEMA_JSON = """
            {
              "properties": {
                "query": {
                  "description": "A single POSIX regex applied directly to chunk content (case-insensitive). Combine multiple concepts with \\"|\\" alternation in ONE regex (e.g. \\"stardust|skyvault|psionic\\") — do not split into multiple calls.",
                  "minLength": 1,
                  "type": "string"
                }
              },
              "required": ["query"],
              "type": "object"
            }""";

    private static final String DESCRIPTION = "Search knowledge base chunk content with a single POSIX regular expression, applied directly in the database (PostgreSQL ~* / MySQL/SQLite REGEXP, case-insensitive). Behaves like `grep -E -i`.\n"
            + "Pack multiple concepts into ONE regex using `|` alternation — do not call this tool repeatedly for synonyms.\n"
            + "Returns matching chunks with a short cN chunk source ID, a parent dN document ID, and a <match> snippet around the first match.\n"
            + "Examples:\n"
            + "- Alternation (RECOMMENDED): \"stardust|skyvault|psionic\" (matches any of the words)\n"
            + "- Multiple terms in order: \"psionic.*engine\" (matches both words in order)\n"
            + "- Word boundary / anchor: \"\\\\brag\\\\b\" or \"^chapter\\\\s+\\\\d+\"\n"
            + "- Plain text: \"engine\" (matches literal substring anywhere in chunk content)\n"
            + "IMPORTANT — JSON escaping: every backslash in a regex MUST be written as \\\\ inside the JSON tool arguments (e.g. to search for literal \"C++\" write \"C\\\\+\\\\+\", NOT \"C\\+\\+\"; for \"\\d+\" write \"\\\\d+\"). Plain \"\\+\" / \"\\d\" etc. are invalid JSON escapes and will fail to parse.\n"
            + "Use this to locate candidate chunks by exact identifiers, error codes, product names, or recurring terms.\n"
            + "\n"
            + "## Deep read after grep:\n"
            + "- **FAQ hit** (chunk type faq): call list_knowledge_chunks with **faq_id=cN** from the grep result (NOT the parent dN document ID).\n"
            + "- **Document hit**: call list_knowledge_chunks with **knowledge_id=dN**, or get_document_info with **knowledge_ids=[dN]**.";

    /** 对照 grep_chunks.go 的 const limit = 30。 */
    static final int LIMIT = 30;
    /** 对照 grep_chunks.go 的 const maxKnowledgeRows = 20。 */
    private static final int MAX_KNOWLEDGE_ROWS = 20;
    /** 对照 faq_snippet.go 的 snippetContextRunes。 */
    static final int SNIPPET_CONTEXT_RUNES = 200;
    /** 对照 faq_snippet.go 的 snippetMaxMatchRunes。 */
    static final int SNIPPET_MAX_MATCH_RUNES = 200;
    /** 对照 faq_snippet.go 的 snippetMaxTotalRunes。 */
    static final int SNIPPET_MAX_TOTAL_RUNES = 800;

    /** 对照 chunkWithTitle：DB 行视图（chunks 列 + knowledge_title + total_chunk_count）。 */
    public static final class GrepChunkView {
        public String id;
        public String content;
        public int chunkIndex;
        public String knowledgeId;
        public String knowledgeBaseId;
        public String chunkType;
        public JsonNode metadata;
        public String parentChunkId;
        public String knowledgeTitle;
        public int totalChunkCount;
        // 打分阶段回填（对照 chunkWithTitle 的 MatchScore/MatchedPatterns/TitleMatch）。
        public double matchScore;
        public int matchedPatterns;
        public boolean titleMatch;

        /** Go 零值语义：null 视为 ""。 */
        static String nz(String v) {
            return v == null ? "" : v;
        }

        /** 转 domain Chunk 以复用 FaqSnippet（FAQ 元数据/标准问题路径）。 */
        Chunk toChunk() {
            Chunk c = new Chunk();
            c.setId(nz(id));
            c.setContent(content);
            c.setChunkIndex(chunkIndex);
            c.setKnowledgeId(nz(knowledgeId));
            c.setKnowledgeBaseId(nz(knowledgeBaseId));
            c.setChunkType(chunkType == null ? "" : chunkType);
            c.setMetadata(metadata);
            c.setParentChunkId(parentChunkId);
            return c;
        }
    }

    /**
     * DB 直查 seam（对照 {@code GrepChunksTool.searchChunks} 的整段 gorm 查询）。
     *
     * <p>实现侧负责：{@code chunks} JOIN {@code knowledges}、is_enabled/deleted_at 过滤、
     * scopeClause 的 OR 组合（knowledge_id IN / 标签 EXISTS / kb+tenant 对）、
     * 每个 query 的 {@code (content ~* ? OR knowledges.title ~* ?)}、
     * {@code ORDER BY chunks.created_at DESC LIMIT 500}、以及按 knowledge_id 的
     * {@code COUNT(*)} 回填 {@link GrepChunkView#totalChunkCount}。
     * 无有效 scope 或 scope 子句为空时返回空表（对照 Go 的两处 early return）。</p>
     */
    public interface GrepChunkSearch {
        List<GrepChunkView> search(List<String> queries, List<String> fullKbIDs, List<String> knowledgeIDs,
                List<SearchTarget> tagTargets, Map<String, Long> kbTenantMap);
    }

    private final GrepChunkSearch chunkSearch;
    private final SearchTarget.SearchTargets searchTargets;
    /** 会话级已返回 chunk 去重（对照 seenChunks map + mutex；单实例单线程使用）。 */
    private final LinkedHashSet<String> seenChunks = new LinkedHashSet<>();

    public GrepChunksTool(GrepChunkSearch chunkSearch, SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_GREP_CHUNKS, DESCRIPTION, SCHEMA_JSON);
        this.chunkSearch = chunkSearch;
        this.searchTargets = searchTargets;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        // 对照 Go：legacy 数组形态（queries/patterns/pattern）在 Go 侧也只是注释说明，
        // GrepChunksInput 只有 Query 一个字段；JSON 键名逐字段对照。
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            return failure("query parameter is required and must be a non-empty regex string");
        }

        // 对照 regexp.Compile("(?i)" + query)。编译失败文案因正则引擎而异（已知差异）。
        final Pattern re;
        try {
            re = Pattern.compile("(?i)" + query);
        } catch (PatternSyntaxException e) {
            return failure("invalid regex query \"" + query + "\": " + e.getDescription());
        }
        List<String> queries = List.of(query);
        List<Pattern> compiled = List.of(re);

        Map<String, Long> kbTenantMap = searchTargets == null ? Map.of() : searchTargets.getKbTenantMap();
        GrepScope scope = resolveGrepScope();
        List<String> kbIDsForMeta = scope.fullKBIDs;
        if (kbIDsForMeta.isEmpty() && searchTargets != null) {
            kbIDsForMeta = searchTargets.getAllKnowledgeBaseIds();
        }

        List<GrepChunkView> results;
        try {
            results = chunkSearch.search(queries, scope.fullKBIDs, scope.knowledgeIDs,
                    scope.tagTargets, kbTenantMap);
        } catch (RuntimeException e) {
            return failure("Search failed: " + e.getMessage());
        }
        if (results == null) {
            results = List.of();
        }

        List<GrepChunkView> deduplicated = GrepChunksScoring.deduplicateChunks(results);
        List<GrepChunkView> scored = GrepChunksScoring.scoreChunks(deduplicated, compiled);

        List<GrepChunkView> finalResults = scored;
        if (scored.size() > 10) {
            int mmrK = scored.size();
            if (LIMIT > 0 && mmrK > LIMIT) {
                mmrK = LIMIT;
            }
            List<GrepChunkView> mmrResults = GrepChunksScoring.applyMMR(scored, mmrK, 0.7);
            if (!mmrResults.isEmpty()) {
                finalResults = mmrResults;
            }
        }

        // 对照 sort.Slice：TitleMatch > MatchedPatterns > MatchScore > ChunkIndex（非稳定）。
        finalResults.sort((a, b) -> {
            if (a.titleMatch != b.titleMatch) {
                return a.titleMatch ? -1 : 1;
            }
            if (a.matchedPatterns != b.matchedPatterns) {
                return Integer.compare(b.matchedPatterns, a.matchedPatterns);
            }
            if (a.matchScore != b.matchScore) {
                return Double.compare(b.matchScore, a.matchScore);
            }
            return Integer.compare(a.chunkIndex, b.chunkIndex);
        });

        if (finalResults.size() > LIMIT) {
            finalResults = new ArrayList<>(finalResults.subList(0, LIMIT));
        }

        List<Map<String, Object>> chunkResults = buildGrepChunkResults(finalResults, compiled);
        List<KnowledgeAggregation> aggregatedResults = aggregateByKnowledge(finalResults, queries, compiled);
        int documentCount = aggregatedResults == null ? 0 : aggregatedResults.size();
        List<KnowledgeAggregation> knowledgeResultsForUI = aggregatedResults;
        if (knowledgeResultsForUI != null && knowledgeResultsForUI.size() > MAX_KNOWLEDGE_ROWS) {
            knowledgeResultsForUI = new ArrayList<>(knowledgeResultsForUI.subList(0, MAX_KNOWLEDGE_ROWS));
        }

        String output = formatOutput(finalResults, queries, compiled);

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(output);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("queries", queries); // legacy alias for older frontends
        data.put("patterns", queries); // legacy alias for older frontends
        // Go 零值语义：空表/无 scope 时这些键序列化为 null（nil slice），不是 []。
        data.put("chunk_results", chunkResults == null ? null : chunkResults);
        data.put("knowledge_results", knowledgeResultsForUI == null ? null
                : knowledgeAggregationMaps(knowledgeResultsForUI));
        data.put("result_count", chunkResults == null ? 0 : chunkResults.size());
        data.put("document_count", documentCount);
        data.put("total_matches", finalResults.size());
        data.put("knowledge_base_ids",
                kbIDsForMeta == null || kbIDsForMeta.isEmpty() ? null : kbIDsForMeta);
        data.put("limit", LIMIT);
        data.put("max_results", LIMIT); // legacy alias
        data.put("display_type", "grep_results");
        result.setData(data);
        return result;
    }

    private ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }

    /** 对照 resolveGrepScope 的返回值三元组。 */
    static final class GrepScope {
        final List<String> fullKBIDs = new ArrayList<>();
        final List<String> knowledgeIDs = new ArrayList<>();
        final List<SearchTarget> tagTargets = new ArrayList<>();
    }

    /** 对照 resolveGrepScope（scope_authorization.go 的 searchTargetScope 在 SearchAuth）。 */
    GrepScope resolveGrepScope() {
        GrepScope scope = new GrepScope();
        LinkedHashSet<String> seenKB = new LinkedHashSet<>();
        LinkedHashSet<String> seenKnowledge = new LinkedHashSet<>();
        LinkedHashSet<String> seenTagScope = new LinkedHashSet<>();
        List<SearchTarget> targets = searchTargets == null ? List.of() : searchTargets.list();
        for (SearchTarget target : targets) {
            if (target == null || target.knowledgeBaseId() == null || target.knowledgeBaseId().isEmpty()) {
                continue;
            }
            SearchAuth.Scope targetScope = SearchAuth.searchTargetScope(target);
            List<String> targetKnowledgeIDs = targetScope.knowledgeIds();
            List<String> targetTagIDs = targetScope.tagIds();
            if (targetTagIDs != null && !targetTagIDs.isEmpty()) {
                long tenantID = target.tenantId();
                if (tenantID == 0) {
                    tenantID = searchTargets.getTenantIdForKb(target.knowledgeBaseId());
                }
                List<String> tagIDs = targetTagIDs;
                if (tagIDs.isEmpty() || tenantID == 0) {
                    continue;
                }
                // 对照 fmt.Sprintf("%s:%d:%s", kb, tenant, strings.Join(tags, "\x00"))
                String scopeKey = target.knowledgeBaseId() + ":" + tenantID + ":"
                        + String.join("\u0000", tagIDs);
                if (!seenTagScope.add(scopeKey)) {
                    continue;
                }
                scope.tagTargets.add(new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE,
                        target.knowledgeBaseId(), tenantID, null, tagIDs, null, false));
            } else if (targetKnowledgeIDs != null && !targetKnowledgeIDs.isEmpty()) {
                for (String kid : targetKnowledgeIDs) {
                    if (seenKnowledge.add(kid)) {
                        scope.knowledgeIDs.add(kid);
                    }
                }
            } else {
                if (seenKB.add(target.knowledgeBaseId())) {
                    scope.fullKBIDs.add(target.knowledgeBaseId());
                }
            }
        }
        return scope;
    }








    /** 对照 knowledgeAggregation。 */
    static final class KnowledgeAggregation {
        String knowledgeID;
        String knowledgeBaseID;
        String knowledgeTitle;
        String faqQuestion = "";
        boolean titleMatch;
        int chunkHitCount;
        int totalChunkCount;
        final Map<String, Integer> patternCounts = new LinkedHashMap<>();
        int totalPatternHits;
        int distinctPatterns;
        String matchSnippet = "";
    }

    /** 对照 aggregateByKnowledge（TitleMatch &gt; DistinctPatterns &gt; TotalPatternHits &gt; ChunkHitCount &gt; Title，非稳定）。 */
    static List<KnowledgeAggregation> aggregateByKnowledge(List<GrepChunkView> results,
            List<String> queries, List<Pattern> compiled) {
        if (results.isEmpty()) {
            return null; // 对照 Go return nil（data 里序列化为 null）
        }

        List<String> queryKeys = new ArrayList<>();
        for (String q : queries) {
            if (q == null || q.trim().isEmpty()) {
                continue;
            }
            queryKeys.add(q);
        }

        Map<String, KnowledgeAggregation> aggregated = new LinkedHashMap<>();
        for (GrepChunkView chunk : results) {
            String knowledgeID = GrepChunkView.nz(chunk.knowledgeId);
            if (knowledgeID.isEmpty()) {
                knowledgeID = "chunk-" + GrepChunkView.nz(chunk.id);
            }

            KnowledgeAggregation entry = aggregated.get(knowledgeID);
            if (entry == null) {
                entry = new KnowledgeAggregation();
                entry.knowledgeID = knowledgeID;
                entry.knowledgeBaseID = GrepChunkView.nz(chunk.knowledgeBaseId);
                String title = GrepChunkView.nz(chunk.knowledgeTitle);
                if (title.trim().isEmpty()) {
                    title = "Untitled";
                }
                entry.knowledgeTitle = title;
                entry.totalChunkCount = chunk.totalChunkCount;
                for (String qKey : queryKeys) {
                    entry.patternCounts.put(qKey, 0);
                }
                aggregated.put(knowledgeID, entry);
            }

            entry.chunkHitCount++;
            if (chunk.titleMatch) {
                entry.titleMatch = true;
            }
            if (entry.faqQuestion.isEmpty()) {
                String q = FaqSnippet.faqStandardQuestion(chunk.toChunk());
                if (!q.isEmpty()) {
                    entry.faqQuestion = q;
                }
            }
            if (entry.matchSnippet.isEmpty()) {
                String snippet = extractChunkMatchSnippet(chunk, compiled);
                if (!snippet.isEmpty()) {
                    entry.matchSnippet = snippet;
                }
            }

            Map<String, Integer> occurrences = countRegexHits(GrepChunkView.nz(chunk.content), compiled, queryKeys);
            for (String q : queryKeys) {
                int count = occurrences.getOrDefault(q, 0);
                if (count == 0) {
                    continue;
                }
                entry.patternCounts.merge(q, count, Integer::sum);
                entry.totalPatternHits += count;
            }
        }

        List<KnowledgeAggregation> resultSlice = new ArrayList<>(aggregated.values());
        for (KnowledgeAggregation entry : resultSlice) {
            int distinct = 0;
            for (int count : entry.patternCounts.values()) {
                if (count > 0) {
                    distinct++;
                }
            }
            entry.distinctPatterns = distinct;
        }

        resultSlice.sort((a, b) -> {
            if (a.titleMatch != b.titleMatch) {
                return a.titleMatch ? -1 : 1;
            }
            if (a.distinctPatterns != b.distinctPatterns) {
                return Integer.compare(b.distinctPatterns, a.distinctPatterns);
            }
            if (a.totalPatternHits != b.totalPatternHits) {
                return Integer.compare(b.totalPatternHits, a.totalPatternHits);
            }
            if (a.chunkHitCount != b.chunkHitCount) {
                return Integer.compare(b.chunkHitCount, a.chunkHitCount);
            }
            return a.knowledgeTitle.compareTo(b.knowledgeTitle);
        });
        return resultSlice;
    }

    /** 对照 buildGrepChunkResults（JSON omitempty 语义：空串/0/false 不入 map）。 */
    static List<Map<String, Object>> buildGrepChunkResults(List<GrepChunkView> results,
            List<Pattern> compiled) {
        if (results.isEmpty()) {
            return null; // 对照 Go return nil（data 里序列化为 null）
        }
        List<Map<String, Object>> out = new ArrayList<>(results.size());
        for (GrepChunkView r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            String chunkType = GrepChunkView.nz(r.chunkType);
            item.put("knowledge_id", GrepChunkView.nz(r.knowledgeId));
            item.put("knowledge_base_id", GrepChunkView.nz(r.knowledgeBaseId));
            item.put("knowledge_title", GrepChunkView.nz(r.knowledgeTitle));
            item.put("chunk_type", chunkType);
            if (r.titleMatch) {
                item.put("title_match", true);
            }
            String snippet = extractChunkMatchSnippet(r, compiled);
            if (!snippet.isEmpty()) {
                item.put("match_snippet", snippet);
            }
            item.put("score", r.matchScore);
            if ("faq".equals(chunkType)) {
                if (!GrepChunkView.nz(r.id).isEmpty()) {
                    item.put("faq_id", GrepChunkView.nz(r.id));
                }
                if (r.chunkIndex != 0) {
                    item.put("index", r.chunkIndex);
                }
                String q = FaqSnippet.faqStandardQuestion(r.toChunk());
                if (!q.isEmpty()) {
                    item.put("faq_question", q);
                }
            } else {
                if (!GrepChunkView.nz(r.id).isEmpty()) {
                    item.put("chunk_id", GrepChunkView.nz(r.id));
                }
                if (r.chunkIndex != 0) {
                    item.put("chunk_index", r.chunkIndex);
                }
            }
            out.add(item);
        }
        return out;
    }

    /** 对照 knowledgeAggregation → JSON（omitempty：faq_question/match_snippet 空不入）。 */
    static List<Map<String, Object>> knowledgeAggregationMaps(List<KnowledgeAggregation> entries) {
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (KnowledgeAggregation e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knowledge_id", e.knowledgeID);
            m.put("knowledge_base_id", e.knowledgeBaseID);
            m.put("knowledge_title", e.knowledgeTitle);
            if (!e.faqQuestion.isEmpty()) {
                m.put("faq_question", e.faqQuestion);
            }
            m.put("title_match", e.titleMatch);
            m.put("chunk_hit_count", e.chunkHitCount);
            m.put("total_chunk_count", e.totalChunkCount);
            m.put("pattern_counts", e.patternCounts);
            m.put("total_pattern_hits", e.totalPatternHits);
            m.put("distinct_patterns", e.distinctPatterns);
            if (!e.matchSnippet.isEmpty()) {
                m.put("match_snippet", e.matchSnippet);
            }
            out.add(m);
        }
        return out;
    }

    /** 对照 countRegexHits（key = 原始 query 串，value = 全匹配数）。 */
    static Map<String, Integer> countRegexHits(String content, List<Pattern> compiled, List<String> patterns) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (content == null || content.isEmpty() || compiled == null || compiled.isEmpty()) {
            return counts;
        }
        for (int i = 0; i < compiled.size() && i < patterns.size(); i++) {
            Pattern re = compiled.get(i);
            if (re == null) {
                continue;
            }
            int n = 0;
            java.util.regex.Matcher m = re.matcher(content);
            while (m.find()) {
                n++;
            }
            counts.put(patterns.get(i), n);
        }
        return counts;
    }

    /** 对照 extractChunkMatchSnippet（FAQ 走 faqMatchSnippet，其他走 extractSnippetRegex）。 */
    static String extractChunkMatchSnippet(GrepChunkView chunk, List<Pattern> compiled) {
        Chunk c = chunk.toChunk();
        if ("faq".equals(c.getChunkType())) {
            String s = FaqSnippet.faqMatchSnippet(c, compiled);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return extractSnippetRegex(chunk.content == null ? "" : chunk.content, compiled);
    }

    /**
     * 对照 extractSnippetRegex：跨 pattern 取最早命中（按 rune 位置比较），
     * 上下文各截 SNIPPET_CONTEXT_RUNES，match 超 200 runes 截+"..."，
     * 换行转空格并折叠连续空格，总长超 800 runes 截+"..."，"... x ..." 包裹。
     */
    static String extractSnippetRegex(String content, List<Pattern> compiled) {
        if (content == null || content.isEmpty() || compiled == null || compiled.isEmpty()) {
            return "";
        }

        // 记录最早命中的（rune 起点, 起点 char, 终点 char）。
        int earliestRune = -1;
        int earliestStart = -1;
        int earliestEnd = -1;
        for (Pattern re : compiled) {
            if (re == null) {
                continue;
            }
            java.util.regex.Matcher m = re.matcher(content);
            if (!m.find()) {
                continue;
            }
            int startRune = content.codePointCount(0, m.start());
            if (earliestRune < 0 || startRune < earliestRune) {
                earliestRune = startRune;
                earliestStart = m.start();
                earliestEnd = m.end();
            }
        }
        if (earliestRune < 0) {
            return "";
        }

        String matchStr = content.substring(earliestStart, earliestEnd);
        String before = content.substring(0, earliestStart);
        String after = content.substring(earliestEnd);

        String beforeTrimmed = lastRunes(before, SNIPPET_CONTEXT_RUNES);
        String afterTrimmed = firstRunes(after, SNIPPET_CONTEXT_RUNES);
        String matchTrimmed = firstRunes(matchStr, SNIPPET_MAX_MATCH_RUNES);
        if (matchStr.codePointCount(0, matchStr.length()) > SNIPPET_MAX_MATCH_RUNES) {
            matchTrimmed = matchTrimmed + "...";
        }

        String snippet = beforeTrimmed + matchTrimmed + afterTrimmed;
        snippet = snippet.replace("\n", " ");
        while (snippet.contains("  ")) {
            snippet = snippet.replace("  ", " ");
        }
        snippet = snippet.trim();
        if (snippet.codePointCount(0, snippet.length()) > SNIPPET_MAX_TOTAL_RUNES) {
            snippet = firstRunes(snippet, SNIPPET_MAX_TOTAL_RUNES) + "...";
        }
        return "... " + snippet + " ...";
    }

    /** 取前 maxRunes 个 rune（对照 []rune(s)[:n]）。 */
    static String firstRunes(String s, int maxRunes) {
        if (s.codePointCount(0, s.length()) <= maxRunes) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxRunes));
    }

    /** 取后 maxRunes 个 rune（对照 []rune(s)[len-n:]）。 */
    static String lastRunes(String s, int maxRunes) {
        int total = s.codePointCount(0, s.length());
        if (total <= maxRunes) {
            return s;
        }
        return s.substring(s.offsetByCodePoints(0, total - maxRunes));
    }

    /** 对照 formatOutput（XML；seenChunks 会话级去重 → already_seen）。 */
    String formatOutput(List<GrepChunkView> results, List<String> queries, List<Pattern> compiled) {
        StringBuilder b = new StringBuilder();

        b.append(String.format(Locale.ROOT, "<grep_results chunk_count=\"%d\">\n", results.size()));
        for (String q : queries) {
            b.append(String.format(Locale.ROOT, "<query>%s</query>\n", FaqSnippet.xmlEscape(q)));
        }

        if (results.isEmpty()) {
            b.append("</grep_results>");
            return b.toString();
        }

        for (GrepChunkView r : results) {
            Map<String, Integer> counts = countRegexHits(r.content == null ? "" : r.content, compiled, queries);
            String snippet = extractChunkMatchSnippet(r, compiled);

            String extraAttr = "";
            String faqQ = FaqSnippet.faqStandardQuestion(r.toChunk());
            if (!faqQ.isEmpty()) {
                extraAttr = String.format(Locale.ROOT, " faq_question=\"%s\"", FaqSnippet.xmlEscape(faqQ));
            }
            boolean isFAQ = "faq".equals(GrepChunkView.nz(r.chunkType));

            boolean seen = !seenChunks.add(GrepChunkView.nz(r.id));

            String id = GrepChunkView.nz(r.id);
            String knowledgeID = GrepChunkView.nz(r.knowledgeId);
            String knowledgeTitle = GrepChunkView.nz(r.knowledgeTitle);
            if (isFAQ) {
                if (seen) {
                    b.append(String.format(Locale.ROOT,
                            "<faq faq_id=\"%s\" knowledge_title=\"%s\"%s index=\"%d\" score=\"%.3f\" already_seen=\"true\">\n",
                            FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeTitle),
                            extraAttr, r.chunkIndex, r.matchScore));
                } else {
                    b.append(String.format(Locale.ROOT,
                            "<faq faq_id=\"%s\" knowledge_title=\"%s\"%s index=\"%d\" score=\"%.3f\">\n",
                            FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeTitle),
                            extraAttr, r.chunkIndex, r.matchScore));
                }
            } else if (seen) {
                b.append(String.format(Locale.ROOT,
                        "<chunk chunk_id=\"%s\" knowledge_id=\"%s\" knowledge_title=\"%s\"%s chunk_index=\"%d\" score=\"%.3f\" already_seen=\"true\">\n",
                        FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeID),
                        FaqSnippet.xmlEscape(knowledgeTitle),
                        extraAttr, r.chunkIndex, r.matchScore));
            } else {
                b.append(String.format(Locale.ROOT,
                        "<chunk chunk_id=\"%s\" knowledge_id=\"%s\" knowledge_title=\"%s\"%s chunk_index=\"%d\" score=\"%.3f\">\n",
                        FaqSnippet.xmlEscape(id), FaqSnippet.xmlEscape(knowledgeID),
                        FaqSnippet.xmlEscape(knowledgeTitle),
                        extraAttr, r.chunkIndex, r.matchScore));
            }

            for (String q : queries) {
                int c = counts.getOrDefault(q, 0);
                if (c > 0) {
                    b.append(String.format(Locale.ROOT, "<query_hit query=\"%s\" count=\"%d\" />\n",
                            FaqSnippet.xmlEscape(q), c));
                }
            }
            if (seen) {
                b.append("<note>(snippet omitted, already returned in a previous grep_chunks call this session)</note>\n");
            } else if (!snippet.isEmpty()) {
                b.append(String.format(Locale.ROOT, "<match_snippet>%s</match_snippet>\n",
                        FaqSnippet.xmlEscape(snippet)));
            }
            if (isFAQ) {
                b.append("</faq>\n");
            } else {
                b.append("</chunk>\n");
            }
        }

        b.append("</grep_results>");
        return b.toString();
    }
}
