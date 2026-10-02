package com.ragagent.agent.tools.wiki;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.ragagent.agent.tools.OutputBudgets;
import com.ragagent.agent.tools.ToolOutput;

/** wiki 页面列表的预算内渲染（截断/省略记录）。 */
public final class WikiPageRendering {

    private WikiPageRendering() {
    }


    /**
     * 待渲染页：邻居摘要/sources/body 已采集、渲染尺寸未定。
     */



    /**
     * 预算内渲染多个页面：返回 (拼接输出, 被截断 slug, 被省略 slug)。
     * rune 计账按 code point 数。
     */
    public static RenderedWikiPages renderWikiPagesWithinBudget(List<PendingWikiPage> pages, int budget) {
        if (pages.isEmpty()) {
            return new RenderedWikiPages("", List.of(), List.of());
        }
        final String separator = "\n\n";
        int separatorCost = separator.codePointCount(0, separator.length());

        int n = pages.size();
        String[] rendered = new String[n];
        int[] bodySizes = new int[n];
        int[] overheads = new int[n];
        int total = separatorCost * (n - 1);
        for (int i = 0; i < n; i++) {
            PendingWikiPage p = pages.get(i);
            rendered[i] = p.render(p.body());
            int size = rendered[i].codePointCount(0, rendered[i].length());
            bodySizes[i] = p.body() == null ? 0 : p.body().codePointCount(0, p.body().length());
            overheads[i] = size - bodySizes[i];
            total += size;
        }

        int usable = budget - WIKI_BUDGET_RESERVE;
        if (usable <= 0 || total <= usable) {
            return new RenderedWikiPages(String.join(separator, List.of(rendered)), List.of(), List.of());
        }

        int[] overheadsFinal = overheads;
        java.util.function.IntUnaryOperator fixedCost = keep -> {
            int cost = separatorCost * (keep - 1);
            for (int i = 0; i < keep; i++) {
                cost += overheadsFinal[i];
            }
            return cost;
        };

        int keep = n;
        while (keep > 1 && fixedCost.applyAsInt(keep) + keep * WIKI_MIN_PAGE_BODY > usable) {
            keep--;
        }
        List<String> omitted = new ArrayList<>();
        for (int i = keep; i < n; i++) {
            omitted.add(pages.get(i).page().slug());
        }

        int[] caps = OutputBudgets.splitBudgetFairly(usable - fixedCost.applyAsInt(keep), Arrays.copyOf(bodySizes, keep));
        List<String> outputs = new ArrayList<>(keep);
        List<String> truncated = new ArrayList<>();
        for (int i = 0; i < keep; i++) {
            if (caps[i] >= bodySizes[i]) {
                outputs.add(rendered[i]);
                continue;
            }
            String body = "(body omitted: output budget exhausted)";
            if (caps[i] > 0) {
                body = ToolOutput.truncateToolOutput(pages.get(i).body(), caps[i]);
            }
            outputs.add(pages.get(i).render(body));
            truncated.add(pages.get(i).page().slug());
        }
        return new RenderedWikiPages(String.join(separator, outputs), truncated, omitted);
    }


    /** 渲染结果三返回值。 */


    /** 链接摘要条数上限。 */
    public static final int WIKI_MAX_LINK_SUMMARIES = 20;

    /** 单条链接摘要的 rune 上限。 */
    public static final int WIKI_LINK_SUMMARY_MAX_RUNES = 150;

    /** 单页正文的最小保留 rune 数。 */
    public static final int WIKI_MIN_PAGE_BODY = 400;

    /** 预算预留（省略提示等）。 */
    public static final int WIKI_BUDGET_RESERVE = 600;
}
