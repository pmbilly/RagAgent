package com.ragagent.agentm.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineConfig.PromptTemplateStructured;

/**
 * config.yaml 的 {@code extract} 段装载（对照 Go internal/config ExtractManagerConfig，
 * config.go:462-472 + config/config.yaml L49-107）。
 *
 * <p>vendor 资源 {@code agentm/extract_config.yaml} 与 Go 仓 config.yaml 的 extract 段
 * <b>逐字节同源</b>（复制即校验）；YAML 未知键按 Go 强类型 Unmarshal 语义丢弃
 * （snakeyaml 裸 load → 手工取键的等价实现，同 ConversationProperties/BuiltinAgentRegistry
 * 的装载惯例）。text-relation / fabri-text 两条抽取路由消费这里的三份模板。</p>
 */
public final class ExtractPrompts {

    private static final String RESOURCE = "agentm/extract_config.yaml";

    private final PromptTemplateStructured extractGraph;
    private final PromptTemplateStructured extractEntity;
    private final FabriText fabriText;

    public record FabriText(String withTag, String withNoTag) {}

    @SuppressWarnings("unchecked")
    public ExtractPrompts() {
        Map<String, Object> root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing vendored resource " + RESOURCE);
            }
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load " + RESOURCE, e);
        }
        Map<String, Object> extract = root == null ? Map.of() : (Map<String, Object>) root.get("extract");
        Map<String, Object> graph = extract == null ? null : (Map<String, Object>) extract.get("extract_graph");
        Map<String, Object> entity = extract == null ? null : (Map<String, Object>) extract.get("extract_entity");
        Map<String, Object> fabri = extract == null ? null : (Map<String, Object>) extract.get("fabri_text");
        this.extractGraph = parseStructured(graph);
        this.extractEntity = parseStructured(entity);
        this.fabriText = new FabriText(
                fabri == null || fabri.get("with_tag") == null ? "" : String.valueOf(fabri.get("with_tag")),
                fabri == null || fabri.get("with_no_tag") == null ? "" : String.valueOf(fabri.get("with_no_tag")));
    }

    public PromptTemplateStructured extractGraph() {
        return extractGraph;
    }

    public PromptTemplateStructured extractEntity() {
        return extractEntity;
    }

    public FabriText fabriText() {
        return fabriText;
    }

    /** 对照 types.PromptTemplateStructured 的 yaml 反序列化（缺失字段零值）。 */
    @SuppressWarnings("unchecked")
    private static PromptTemplateStructured parseStructured(Map<String, Object> node) {
        PromptTemplateStructured tpl = new PromptTemplateStructured();
        if (node == null) {
            return tpl;
        }
        if (node.get("description") != null) {
            tpl.setDescription(String.valueOf(node.get("description")));
        }
        if (node.get("tags") instanceof List<?> tags) {
            List<String> list = new ArrayList<>();
            for (Object t : tags) {
                list.add(String.valueOf(t));
            }
            tpl.setTags(list);
        }
        if (node.get("examples") instanceof List<?> examples) {
            List<PromptTemplateStructured.Example> list = new ArrayList<>();
            for (Object raw : examples) {
                if (!(raw instanceof Map<?, ?> ex)) {
                    continue;
                }
                PromptTemplateStructured.Example example = new PromptTemplateStructured.Example();
                if (ex.get("text") != null) {
                    example.setText(String.valueOf(ex.get("text")));
                }
                if (ex.get("node") instanceof List<?> nodes) {
                    List<ChatManage.GraphNode> nodeList = new ArrayList<>();
                    for (Object n : nodes) {
                        if (!(n instanceof Map<?, ?> nm)) {
                            continue;
                        }
                        List<String> attrs = new ArrayList<>();
                        if (nm.get("attributes") instanceof List<?> al) {
                            for (Object a : al) {
                                attrs.add(String.valueOf(a));
                            }
                        }
                        nodeList.add(new ChatManage.GraphNode(
                                nm.get("name") == null ? "" : String.valueOf(nm.get("name")),
                                null, attrs));
                    }
                    example.setNode(nodeList);
                }
                if (ex.get("relation") instanceof List<?> rels) {
                    List<ChatManage.GraphRelation> relList = new ArrayList<>();
                    for (Object r : rels) {
                        if (!(r instanceof Map<?, ?> rm)) {
                            continue;
                        }
                        relList.add(new ChatManage.GraphRelation(
                                rm.get("node1") == null ? "" : String.valueOf(rm.get("node1")),
                                rm.get("node2") == null ? "" : String.valueOf(rm.get("node2")),
                                rm.get("type") == null ? "" : String.valueOf(rm.get("type"))));
                    }
                    example.setRelation(relList);
                }
                list.add(example);
            }
            tpl.setExamples(list);
        }
        return tpl;
    }
}
