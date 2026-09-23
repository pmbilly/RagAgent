package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;

/**
 * query_knowledge_graph 工具（对照 Go {@code query_knowledge_graph.go}，逐字移植）。
 *
 * <p>seam：{@link GraphSearch} 对照 interfaces.KnowledgeBaseService 被用子集
 * （GetKnowledgeBaseByIDOnly + HybridSearch）。Go 的 per-KB goroutine 并发在 Java
 * 顺序执行（结果按入参序汇集，等价）。</p>
 *
 * <p>已知差异（继承 Go 行为）：按 score 的 sort.Slice 非稳定；graphConfigs/kbCounts
 * 的 map 迭代序随机——多 KB 时输出文本段序不定（Go 自身亦然），探针只用单 KB
 * 场景做文本比对。</p>
 */
public class QueryKnowledgeGraphTool extends BaseTool {

    /**
     * 对照 Go {@code utils.GenerateSchema[QueryKnowledgeGraphInput]()} 的输出
     * （2026-09-23 A/B 对拍修正：补 {@code additionalProperties:false} 与
     * 可空数组类型 {@code ["null","array"]}；键序字母序）。
     */
    private static final String SCHEMA_JSON = """
            {
              "additionalProperties": false,
              "properties": {
                "knowledge_base_ids": {
                  "description": "Array of short bN knowledge base IDs to query",
                  "items": { "type": "string" },
                  "type": ["null", "array"]
                },
                "query": {
                  "description": "Query content (entity name or query text)",
                  "type": "string"
                }
              },
              "required": ["knowledge_base_ids", "query"],
              "type": "object"
            }""";

    private static final String DESCRIPTION =
            "Query knowledge graph to explore entity relationships and knowledge networks.\n"
                    + "\n"
                    + "## Core Function\n"
                    + "Explores relationships between entities in knowledge bases that have graph extraction configured.\n"
                    + "\n"
                    + "## When to Use\n"
                    + "✅ **Use for**:\n"
                    + "- Understanding relationships between entities (e.g., \"relationship between Docker and Kubernetes\")\n"
                    + "- Exploring knowledge networks and concept associations\n"
                    + "- Finding related information about specific entities\n"
                    + "- Understanding technical architecture and system relationships\n"
                    + "\n"
                    + "❌ **Don't use for**:\n"
                    + "- General text search → use knowledge_search\n"
                    + "- Knowledge base without graph extraction configured\n"
                    + "- Need exact document content → use knowledge_search\n"
                    + "\n"
                    + "## Parameters\n"
                    + "- **knowledge_base_ids** (required): Array of short bN knowledge base IDs (1-10). Only KBs with graph extraction configured will be effective.\n"
                    + "- **query** (required): Query content - can be entity name, relationship query, or concept search.\n"
                    + "\n"
                    + "## Graph Configuration\n"
                    + "Knowledge graph must be pre-configured in knowledge bases:\n"
                    + "- **Entity types** (Nodes): e.g., \"Technology\", \"Tool\", \"Concept\"\n"
                    + "- **Relationship types** (Relations): e.g., \"depends_on\", \"uses\", \"contains\"\n"
                    + "\n"
                    + "If KB is not configured with graph, tool will return regular search results.\n"
                    + "\n"
                    + "## Workflow\n"
                    + "1. **Relationship exploration**: query_knowledge_graph → list_knowledge_chunks (for detailed content)\n"
                    + "2. **Network analysis**: query_knowledge_graph → knowledge_search (for comprehensive understanding)\n"
                    + "3. **Topic research**: knowledge_search → query_knowledge_graph (for deep entity relationships)\n"
                    + "\n"
                    + "## Notes\n"
                    + "- Results indicate graph configuration status\n"
                    + "- Cross-KB results are automatically deduplicated\n"
                    + "- Results are sorted by relevance";

    /** 图配置视图（对照 types.ExtractConfig 被用子集）。 */
    public record ExtractConfigView(List<GraphNodeView> nodes, List<GraphRelationView> relations) {
    }

    /** 对照 types.GraphNode 被用子集。 */
    public record GraphNodeView(String name) {
    }

    /** 对照 types.GraphRelation 被用子集。 */
    public record GraphRelationView(String type) {
    }

    /** 知识库视图（对照 types.KnowledgeBase 被用子集）。 */
    public record KnowledgeBaseView(String id, ExtractConfigView extractConfig) {
    }

    /** 检索结果视图（对照 types.SearchResult 被用子集；matchType 对照 MatchType int）。 */
    public record SearchResultView(String id, double score, String content, String knowledgeId,
            String knowledgeBaseId, String knowledgeTitle, int chunkIndex, String chunkType,
            int matchType) {
    }

    /** 对照 interfaces.KnowledgeBaseService 被用子集。hybridSearch 失败抛 RuntimeException。 */
    public interface GraphSearch {
        KnowledgeBaseView getKnowledgeBaseByIdOnly(String kbId);

        List<SearchResultView> hybridSearch(String kbId, String queryText, int matchCount);
    }

    /** 对照 graphConfigSummary。 */
    private record GraphConfigSummary(List<String> nodes, List<String> relations) {
    }

    private final GraphSearch graphSearch;
    private final SearchTargets searchTargets;
    private final boolean scopeEnforced;
    private final SearchAuth.KnowledgeTagsFetcher tagsFetcher;

    public QueryKnowledgeGraphTool(GraphSearch graphSearch, SearchTargets searchTargets,
            SearchAuth.KnowledgeTagsFetcher tagsFetcher) {
        super(ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH, DESCRIPTION, SCHEMA_JSON);
        this.graphSearch = graphSearch;
        this.searchTargets = searchTargets;
        this.scopeEnforced = searchTargets != null;
        this.tagsFetcher = tagsFetcher;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        List<String> knowledgeBaseIDs = new ArrayList<>();
        JsonNode kbNode = args.get("knowledge_base_ids");
        if (kbNode != null && kbNode.isArray()) {
            for (JsonNode item : kbNode) {
                if (item.isTextual()) {
                    knowledgeBaseIDs.add(item.asText());
                }
            }
        }
        if (knowledgeBaseIDs.isEmpty()) {
            return failure("knowledge_base_ids is required and must be a non-empty array");
        }
        if (knowledgeBaseIDs.size() > 10) {
            return failure("knowledge_base_ids must contain at most 10 KB IDs");
        }
        if (scopeEnforced) {
            try {
                SearchAuth.validateKnowledgeBaseIdsInSearchTargets(searchTargets, knowledgeBaseIDs);
            } catch (RuntimeException e) {
                return failure(e.getMessage());
            }
        }

        String query = args.path("query").asText("");
        if (query.isEmpty()) {
            return failure("query is required");
        }

        // 逐 KB 查询（Go 并发，Java 顺序，结果等价）
        Map<String, GraphQueryResult> kbResults = new LinkedHashMap<>();
        for (String kbID : knowledgeBaseIDs) {
            GraphQueryResult r = new GraphQueryResult();
            r.kbID = kbID;
            KnowledgeBaseView kb;
            try {
                kb = graphSearch.getKnowledgeBaseByIdOnly(kbID);
            } catch (RuntimeException e) {
                r.err = "failed to get knowledge base: " + e.getMessage();
                kbResults.put(kbID, r);
                continue;
            }
            if (kb == null || kb.extractConfig() == null
                    || ((kb.extractConfig().nodes() == null || kb.extractConfig().nodes().isEmpty())
                            && (kb.extractConfig().relations() == null
                                    || kb.extractConfig().relations().isEmpty()))) {
                r.err = "graph extraction not configured";
                kbResults.put(kbID, r);
                continue;
            }
            r.kb = kb;
            List<SearchResultView> results;
            try {
                results = graphSearch.hybridSearch(kbID, query, 10);
            } catch (RuntimeException e) {
                r.err = "query failed: " + e.getMessage();
                kbResults.put(kbID, r);
                continue;
            }
            if (scopeEnforced) {
                try {
                    results = SearchAuth.filterSearchResultsInSearchTargets(
                            searchTargets, kbID, results,
                            SearchResultView::knowledgeId, SearchResultView::knowledgeBaseId,
                            tagsFetcher);
                } catch (RuntimeException e) {
                    r.err = e.getMessage();
                    kbResults.put(kbID, r);
                    continue;
                }
            }
            r.results = results;
            kbResults.put(kbID, r);
        }

        // 汇集去重
        Map<String, SearchResultView> seenChunks = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Map<String, GraphConfigSummary> graphConfigs = new LinkedHashMap<>();
        Map<String, Integer> kbCounts = new LinkedHashMap<>();

        for (String kbID : knowledgeBaseIDs) {
            GraphQueryResult result = kbResults.get(kbID);
            if (result == null) {
                continue;
            }
            if (result.err != null) {
                errors.add("KB " + kbID + ": " + result.err);
                continue;
            }
            if (result.kb != null && result.kb.extractConfig() != null) {
                graphConfigs.put(kbID, summarizeGraphConfig(result.kb.extractConfig()));
            }
            kbCounts.put(kbID, result.results == null ? 0 : result.results.size());
            if (result.results != null) {
                for (SearchResultView sr : result.results) {
                    seenChunks.putIfAbsent(sr.id(), sr);
                }
            }
        }

        List<SearchResultView> allResults = new ArrayList<>(seenChunks.values());
        allResults.sort((a, b) -> Double.compare(b.score(), a.score())); // 非稳定，对照 Go sort.Slice

        if (allResults.isEmpty()) {
            ToolResult toolResult = new ToolResult();
            toolResult.setSuccess(true);
            toolResult.setOutput("No relevant graph information found.");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("knowledge_base_ids", knowledgeBaseIDs);
            data.put("query", query);
            data.put("results", List.of());
            data.put("graph_configs", graphConfigsToData(graphConfigs));
            data.put("graph_config", aggregateGraphConfig(graphConfigs));
            data.put("errors", errors.isEmpty() ? null : errors);
            toolResult.setData(data);
            return toolResult;
        }

        StringBuilder output = new StringBuilder();
        output.append("=== Knowledge Graph Query ===\n\n");
        output.append(String.format("📊 Query: %s\n", query));
        output.append(String.format("🎯 Target Knowledge Bases: %s\n", goSliceString(knowledgeBaseIDs)));
        output.append(String.format("✓ Found %d relevant results (deduplicated)\n\n", allResults.size()));

        if (!errors.isEmpty()) {
            output.append("=== ⚠️ Partial Failures ===\n");
            for (String errMsg : errors) {
                output.append("  - ").append(errMsg).append('\n');
            }
            output.append('\n');
        }

        boolean hasGraphConfig = false;
        output.append("=== 📈 Graph Configuration Status ===\n\n");
        for (Map.Entry<String, GraphConfigSummary> e : graphConfigs.entrySet()) {
            hasGraphConfig = true;
            GraphConfigSummary config = e.getValue();
            output.append(String.format("Knowledge Base [%s]:\n", e.getKey()));
            if (!config.nodes().isEmpty()) {
                output.append(String.format("  ✓ Entity Types (%d): %s\n",
                        config.nodes().size(), goSliceString(config.nodes())));
            } else {
                output.append("  ⚠️ No entity types configured\n");
            }
            if (!config.relations().isEmpty()) {
                output.append(String.format("  ✓ Relationship Types (%d): %s\n",
                        config.relations().size(), goSliceString(config.relations())));
            } else {
                output.append("  ⚠️ No relationship types configured\n");
            }
            output.append('\n');
        }

        if (!hasGraphConfig) {
            output.append("⚠️ None of the queried knowledge bases have graph extraction configured\n");
            output.append("💡 Hint: Configure entity and relationship types in knowledge base settings\n\n");
        }

        if (!kbCounts.isEmpty()) {
            output.append("=== 📚 Knowledge Base Coverage ===\n");
            for (Map.Entry<String, Integer> e : kbCounts.entrySet()) {
                output.append(String.format("  - %s: %d results\n", e.getKey(), e.getValue()));
            }
            output.append('\n');
        }

        output.append("=== 🔍 Query Results ===\n\n");
        if (!hasGraphConfig) {
            output.append("💡 Returning relevant document chunks (knowledge base has no graph configuration)\n\n");
        } else {
            output.append("💡 Content retrieval based on graph configuration\n\n");
        }

        List<Map<String, Object>> formattedResults = new ArrayList<>();
        String currentKB = "";

        for (int i = 0; i < allResults.size(); i++) {
            SearchResultView result = allResults.get(i);
            if (!result.knowledgeId().equals(currentKB)) {
                currentKB = result.knowledgeId();
                if (i > 0) {
                    output.append('\n');
                }
                output.append(String.format("[Source Document: %s]\n\n", result.knowledgeTitle()));
            }

            String relevanceLevel = BaseTool.getRelevanceLevel(result.score());

            output.append(String.format("Result #%d:\n", i + 1));
            output.append(String.format("  📍 Relevance: %.2f (%s)\n", result.score(), relevanceLevel));
            output.append(String.format("  🔗 Match Type: %s\n", BaseTool.formatMatchType(result.matchType())));
            output.append(String.format("  📄 Content: %s\n", result.content()));
            output.append(String.format("  🆔 chunk_id: %s\n\n", result.id()));

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("result_index", i + 1);
            formatted.put("chunk_id", result.id());
            formatted.put("chunk_index", result.chunkIndex());
            formatted.put("chunk_type", result.chunkType());
            formatted.put("content", result.content());
            formatted.put("score", result.score());
            formatted.put("relevance_level", relevanceLevel);
            formatted.put("knowledge_id", result.knowledgeId());
            formatted.put("knowledge_base_id", result.knowledgeBaseId());
            formatted.put("knowledge_title", result.knowledgeTitle());
            formatted.put("match_type", BaseTool.formatMatchType(result.matchType()));
            formattedResults.add(formatted);
        }

        output.append("=== 💡 Tips ===\n");
        output.append("- ✓ Results are deduplicated across knowledge bases and sorted by relevance\n");
        output.append("- ✓ Use get_chunk_detail to get full content\n");
        output.append("- ✓ Use list_knowledge_chunks to explore context\n");
        if (!hasGraphConfig) {
            output.append("- ⚠️ Configure graph extraction for more precise entity-relationship results\n");
        }
        output.append("- ⏳ Full graph query language (Cypher) support is under development\n");

        Map<String, Object> graphData = buildGraphVisualizationData(allResults);

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(output.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("knowledge_base_ids", knowledgeBaseIDs);
        data.put("query", query);
        data.put("results", formattedResults);
        data.put("count", allResults.size());
        data.put("kb_counts", kbCounts);
        data.put("graph_configs", graphConfigsToData(graphConfigs));
        data.put("graph_config", aggregateGraphConfig(graphConfigs));
        data.put("graph_data", graphData);
        data.put("has_graph_config", hasGraphConfig);
        data.put("errors", errors.isEmpty() ? null : errors);
        data.put("display_type", "graph_query_results");
        toolResult.setData(data);
        return toolResult;
    }

    private static final class GraphQueryResult {
        String kbID;
        KnowledgeBaseView kb;
        List<SearchResultView> results;
        String err;
    }

    private static GraphConfigSummary summarizeGraphConfig(ExtractConfigView config) {
        if (config == null) {
            return new GraphConfigSummary(List.of(), List.of());
        }
        return new GraphConfigSummary(uniqueSortedNodeNames(config.nodes()),
                uniqueSortedRelationNames(config.relations()));
    }

    private static List<String> uniqueSortedNodeNames(List<GraphNodeView> nodes) {
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        if (nodes != null) {
            for (GraphNodeView node : nodes) {
                if (node == null || node.name() == null || node.name().isEmpty() || !seen.add(node.name())) {
                    continue;
                }
                names.add(node.name());
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    private static List<String> uniqueSortedRelationNames(List<GraphRelationView> relations) {
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        if (relations != null) {
            for (GraphRelationView relation : relations) {
                if (relation == null || relation.type() == null || relation.type().isEmpty()
                        || !seen.add(relation.type())) {
                    continue;
                }
                names.add(relation.type());
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    private static Map<String, Map<String, Object>> graphConfigsToData(
            Map<String, GraphConfigSummary> graphConfigs) {
        if (graphConfigs.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> data = new LinkedHashMap<>();
        for (Map.Entry<String, GraphConfigSummary> e : graphConfigs.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodes", e.getValue().nodes());
            m.put("relations", e.getValue().relations());
            data.put(e.getKey(), m);
        }
        return data;
    }

    private static Map<String, Object> aggregateGraphConfig(Map<String, GraphConfigSummary> graphConfigs) {
        if (graphConfigs.isEmpty()) {
            return null;
        }
        List<String> nodes = new ArrayList<>();
        List<String> relations = new ArrayList<>();
        for (GraphConfigSummary config : graphConfigs.values()) {
            nodes.addAll(config.nodes());
            relations.addAll(config.relations());
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("nodes", uniqueStrings(nodes));
        merged.put("relations", uniqueStrings(relations));
        return merged;
    }

    private static List<String> uniqueStrings(List<String> values) {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isEmpty() || !seen.add(value)) {
                continue;
            }
            result.add(value);
        }
        java.util.Collections.sort(result);
        return result;
    }

    /** 对照 buildGraphVisualizationData。 */
    private static Map<String, Object> buildGraphVisualizationData(List<SearchResultView> results) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seenEntities = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            SearchResultView result = results.get(i);
            if (seenEntities.add(result.id())) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", result.id());
                node.put("label", String.format("Chunk %d", i + 1));
                node.put("content", result.content());
                node.put("kb_id", result.knowledgeId());
                node.put("kb_title", result.knowledgeTitle());
                node.put("score", result.score());
                node.put("type", "chunk");
                nodes.add(node);
            }
        }
        Map<String, Object> graphData = new LinkedHashMap<>();
        graphData.put("nodes", nodes);
        graphData.put("edges", edges);
        graphData.put("total_nodes", nodes.size());
        graphData.put("total_edges", edges.size());
        return graphData;
    }

    /** Go 的 %v 打印 []string：" [a b c]"。 */
    private static String goSliceString(List<String> items) {
        return "[" + String.join(" ", items) + "]";
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
