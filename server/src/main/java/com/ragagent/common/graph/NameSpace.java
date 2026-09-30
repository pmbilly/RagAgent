package com.ragagent.common.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 图形模型值类型（原为 {@code chatpipeline.ChatManage} 的嵌套记录）。
 *
 * <p>被 knowledge（抽取）、retrieval（Neo4j 落库）与 chatpipeline 三域共用——纯值、零域依赖，
 * 故提到 common（先例：ResponseType、StorageAllowList）。</p>
 */
public record NameSpace(String knowledgeBase, String knowledge) {

    /** 对照 NameSpace.Labels()：非空部分按 KB → Knowledge 序（两端都空 → 空列表）。 */
    public List<String> labels() {
        List<String> res = new ArrayList<>();
        if (knowledgeBase != null && !knowledgeBase.isEmpty()) {
            res.add(knowledgeBase);
        }
        if (knowledge != null && !knowledge.isEmpty()) {
            res.add(knowledge);
        }
        return res;
    }
}
