package com.ragagent.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/** wiki 索引页概览的 agent 渲染与页面类型词表。 */
public final class WikiIndexOverview {

    private WikiIndexOverview() {
    }


    // ==================== wiki 工具共享 helper ====================

    /** 索引概览的默认条数上限。 */
    public static final int WIKI_INDEX_AGENT_TOP_K = 20;


    /** 索引概览渲染（含 "\n## " 裁剪与 top-K 标注）。 */
    public static String renderIndexOverviewForAgent(IndexOverviewView resp) {
        StringBuilder sb = new StringBuilder();
        String intro = resp.intro() == null ? "" : resp.intro().trim();
        int idx = intro.indexOf("\n## ");
        if (idx >= 0) {
            intro = intro.substring(0, idx).trim();
        }
        if (!intro.isEmpty()) {
            sb.append(intro).append('\n');
        }

        Map<String, String> typeLabels = new LinkedHashMap<>();
        typeLabels.put(WikiSlugs.WIKI_PAGE_TYPE_SUMMARY, "Summary");
        typeLabels.put(WIKI_PAGE_TYPE_ENTITY, "Entity");
        typeLabels.put(WIKI_PAGE_TYPE_CONCEPT, "Concept");
        typeLabels.put(WIKI_PAGE_TYPE_SYNTHESIS, "Synthesis");
        typeLabels.put(WIKI_PAGE_TYPE_COMPARISON, "Comparison");

        int nonEmpty = 0;
        for (IndexGroupView g : resp.groups()) {
            if (g.total() == 0) {
                continue;
            }
            String label = typeLabels.getOrDefault(g.type(), g.type());
            if (g.items().size() < g.total()) {
                sb.append("\n## ").append(label).append(" (").append(g.total())
                        .append(" total, showing top ").append(g.items().size()).append(")\n\n");
            } else {
                sb.append("\n## ").append(label).append(" (").append(g.total()).append(")\n\n");
            }
            for (IndexEntryView item : g.items()) {
                String display = item.title();
                if (display == null || display.isEmpty()) {
                    display = item.slug();
                }
                if (item.summary() != null && !item.summary().isEmpty()) {
                    sb.append("[[").append(item.slug()).append('|').append(display)
                            .append("]] — ").append(item.summary()).append('\n');
                } else {
                    sb.append("[[").append(item.slug()).append('|').append(display).append("]]\n");
                }
            }
            nonEmpty++;
        }

        if (nonEmpty == 0) {
            sb.append("\n*No wiki pages yet. Upload documents to get started.*\n");
        } else {
            sb.append("\n_To explore more pages under any category, use wiki_search with a query, "
                    + "or read a specific slug directly._\n");
        }
        return sb.toString();
    }


    /** 页面类型：实体。 */
    public static final String WIKI_PAGE_TYPE_ENTITY = "entity";

    /** 页面类型：概念。 */
    public static final String WIKI_PAGE_TYPE_CONCEPT = "concept";

    /** 页面类型：索引。 */
    public static final String WIKI_PAGE_TYPE_INDEX = "index";

    /** 页面类型：综合。 */
    public static final String WIKI_PAGE_TYPE_SYNTHESIS = "synthesis";

    /** 页面类型：对比。 */
    public static final String WIKI_PAGE_TYPE_COMPARISON = "comparison";
}
