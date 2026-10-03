package com.ragagent.llm.extract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ragagent.common.graph.GraphNode;
import com.ragagent.common.graph.GraphRelation;

/**
 * 管线消费的配置切片：对话改写与意图系统提示词、实体抽取模板。
 *
 * <p>装配时从 system setting / prompt template 加载后构造本类；
 * 缺省行为为全空 + 未配置模板。</p>
 */
public final class PipelineConfig {

    private String rewritePromptSystem = "";
    private String rewritePromptUser = "";
    /** intent（如 greeting/chitchat）→ 系统提示词；nil 等价空表。 */
    private Map<String, String> intentSystemPrompts;
    /** 实体抽取模板（ExtractManager.ExtractEntity；nil = 未配置）。 */
    private PromptTemplateStructured extractEntity;

    public String getRewritePromptSystem() { return rewritePromptSystem; }
    public void setRewritePromptSystem(String v) { rewritePromptSystem = v == null ? "" : v; }
    public String getRewritePromptUser() { return rewritePromptUser; }
    public void setRewritePromptUser(String v) { rewritePromptUser = v == null ? "" : v; }
    public Map<String, String> getIntentSystemPrompts() {
        return intentSystemPrompts == null ? new LinkedHashMap<>() : intentSystemPrompts;
    }
    public void setIntentSystemPrompts(Map<String, String> v) { intentSystemPrompts = v; }
    public PromptTemplateStructured getExtractEntity() { return extractEntity; }
    public void setExtractEntity(PromptTemplateStructured v) { extractEntity = v; }

    /**
     * 结构化抽取模板（对照 types.PromptTemplateStructured：Description/Tags/Examples，
     * Examples 的元素是 GraphData 的 Text/Node/Relation）。
     */
    public static final class PromptTemplateStructured {
        private String description = "";
        private List<String> tags;
        private List<Example> examples;

        public String getDescription() { return description; }
        public void setDescription(String v) { description = v == null ? "" : v; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> v) { tags = v; }
        public List<Example> getExamples() { return examples; }
        public void setExamples(List<Example> v) { examples = v; }

        /** 对照 GraphData 例子的管线消费面（Text + Node/Relation 切片）。 */
        public static final class Example {
            private String text = "";
            private List<GraphNode> node;
            private List<GraphRelation> relation;

            public String getText() { return text; }
            public void setText(String v) { text = v == null ? "" : v; }
            public List<GraphNode> getNode() { return node; }
            public void setNode(List<GraphNode> v) { node = v; }
            public List<GraphRelation> getRelation() { return relation; }
            public void setRelation(List<GraphRelation> v) { relation = v; }
        }
    }
}
