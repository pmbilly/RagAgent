package com.ragagent.chatpipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管线消费的配置切片（对照 Go {@code config.Config} 被 chat_pipeline 读到的部分：
 * Conversation.RewritePromptSystem/RewritePromptUser/IntentSystemPrompts 与
 * ExtractManager.ExtractEntity，internal/config/config.go:462+ / types/extract_graph.go:12-16）。
 *
 * <p>Go 的实际默认值由 DB prompt 模板回填（backfillConversationDefaults）；4.6d 装配时
 * 从 system setting / prompt template 加载后构造本类。缺省行为（全空 + nil 模板）与
 * Go 的未配置部署一致。</p>
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
            private List<ChatManage.GraphNode> node;
            private List<ChatManage.GraphRelation> relation;

            public String getText() { return text; }
            public void setText(String v) { text = v == null ? "" : v; }
            public List<ChatManage.GraphNode> getNode() { return node; }
            public void setNode(List<ChatManage.GraphNode> v) { node = v; }
            public List<ChatManage.GraphRelation> getRelation() { return relation; }
            public void setRelation(List<ChatManage.GraphRelation> v) { relation = v; }
        }
    }
}
