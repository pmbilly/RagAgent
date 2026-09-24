package com.ragagent.retrieval.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelinePorts;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Neo4j 图仓储（对照 Go {@code internal/application/repository/retriever/neo4j/repository.go}
 * 全文 236 行）：实体/关系图谱的写（AddGraph）/删（DelGraph）/读（SearchNode）。
 *
 * <h2>标签模型（与 Go 逐字一致）</h2>
 * <ul>
 *   <li>节点标签 = {@code ENTITY} + 命名空间各段（{@code -} 换 {@code _}），
 *       形如 {@code ENTITYkbId:ENTITYknowledgeId}；</li>
 *   <li>节点键 = {@code {name, kg: knowledge_id}}（同名实体在同知识内合并）；</li>
 *   <li>写入走 APOC：{@code apoc.merge.node} / {@code apoc.coll.union}（chunks 并集）/
 *       {@code apoc.merge.relationship}；删除走 {@code apoc.periodic.iterate}
 *       （batchSize 1000、parallel）；</li>
 *   <li>检索：一跳子图（{@code MATCH (n)-[r]-(m)} + 节点名 CONTAINS 任一关键词），
 *       节点去重、关系不去重——照抄 Go。</li>
 * </ul>
 *
 * <h2>driver 为 null 的语义</h2>
 * <p>对照 Go 的 {@code n.driver == nil} 三个分支：告警 {@code NOT SUPPORT RETRIEVE GRAPH}
 * 后静默返回（写/删返回 void，检索返回 null）。因此 NEO4J_ENABLE 未启用时，调用方无需判空。</p>
 */
public class Neo4jGraphRepository implements PipelinePorts.RetrieveGraphRepository {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphRepository.class);

    /** 对照 {@code NewNeo4jRepository} 的 nodePrefix。 */
    public static final String NODE_PREFIX = "ENTITY";

    /** 对照 {@code node_import_query}（文本逐字一致，缩进由 text block 归一）。 */
    static final String NODE_IMPORT_QUERY = """
            UNWIND $data AS row
            CALL apoc.merge.node(row.labels, {name: row.name, kg: row.knowledge_id}, row.props, {}) YIELD node
            SET node.chunks = apoc.coll.union(node.chunks, row.chunks)
            RETURN distinct 'done' AS result
            """;

    /** 对照 {@code rel_import_query}。 */
    static final String REL_IMPORT_QUERY = """
            UNWIND $data AS row
            CALL apoc.merge.node(row.source_labels, {name: row.source, kg: row.knowledge_id}, {}, {}) YIELD node as source
            CALL apoc.merge.node(row.target_labels, {name: row.target, kg: row.knowledge_id}, {}, {}) YIELD node as target
            CALL apoc.merge.relationship(source, row.type, {}, row.attributes, target) YIELD rel
            RETURN distinct 'done'
            """;

    /** 对照 {@code deleteRelsQuery}（{@code %s} 处填标签表达式）。 */
    static final String DELETE_RELS_TEMPLATE =
            "CALL apoc.periodic.iterate("
            + "\"MATCH (n:`%s` {kg: $knowledge_id})-[r]-(m:`%s` {kg: $knowledge_id}) RETURN r\", "
            + "\"DELETE r\", "
            + "{batchSize: 1000, parallel: true, params: {knowledge_id: $knowledge_id}}"
            + ") YIELD batches, total RETURN total";

    /** 对照 {@code deleteNodesQuery}。 */
    static final String DELETE_NODES_TEMPLATE =
            "CALL apoc.periodic.iterate("
            + "\"MATCH (n:`%s` {kg: $knowledge_id}) RETURN n\", "
            + "\"DELETE n\", "
            + "{batchSize: 1000, parallel: true, params: {knowledge_id: $knowledge_id}}"
            + ") YIELD batches, total RETURN total";

    /** 对照 SearchNode 的查询（标签表达式拼进语句，照 Go 的字符串拼接）。 */
    static String searchQuery(String labelExpr) {
        return "MATCH (n:" + labelExpr + ")-[r]-(m:" + labelExpr + ") "
                + "WHERE ANY(nodeText IN $nodes WHERE n.name CONTAINS nodeText) "
                + "RETURN n, r, m";
    }

    private final Driver driver;

    public Neo4jGraphRepository(Driver driver) {
        this.driver = driver;
    }

    /** 对照 Go 的 {@code n.driver != nil} 判定（供装配与自检用）。 */
    public boolean enabled() {
        return driver != null;
    }

    // ── 标签（对照 Labels / Label / _remove_hyphen） ──

    /** 对照 {@code _remove_hyphen}：把连字符换成下划线。 */
    static String removeHyphen(String s) {
        return s == null ? "" : s.replace("-", "_");
    }

    /** 对照 {@code Labels}：命名空间各段加 {@code ENTITY} 前缀。 */
    public List<String> labels(ChatManage.NameSpace namespace) {
        List<String> res = new ArrayList<>();
        if (namespace == null) {
            return res;
        }
        for (String label : namespace.labels()) {
            res.add(NODE_PREFIX + removeHyphen(label));
        }
        return res;
    }

    /** 对照 {@code Label}：以 {@code :} 连接（Cypher 标签表达式）。 */
    public String label(ChatManage.NameSpace namespace) {
        return String.join(":", labels(namespace));
    }

    // ── 写（对照 AddGraph / addGraph） ──

    @Override
    public void addGraph(ChatManage.NameSpace namespace, List<ChatManage.GraphData> graphs) {
        if (driver == null) {
            log.warn("NOT SUPPORT RETRIEVE GRAPH");
            return;
        }
        if (graphs == null) {
            return;
        }
        for (ChatManage.GraphData graph : graphs) {
            if (graph != null) {
                addGraphOne(namespace, graph);
            }
        }
    }

    private void addGraphOne(ChatManage.NameSpace namespace, ChatManage.GraphData graph) {
        List<Map<String, Object>> nodeData = new ArrayList<>();
        for (ChatManage.GraphNode node : graph.node() == null ? List.<ChatManage.GraphNode>of() : graph.node()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", node.getName());
            row.put("knowledge_id", namespace.knowledge());
            Map<String, Object> props = new HashMap<>();
            props.put("attributes", node.getAttributes());
            row.put("props", props);
            row.put("chunks", node.getChunks());
            row.put("labels", labels(namespace));
            nodeData.add(row);
        }

        List<Map<String, Object>> relData = new ArrayList<>();
        for (ChatManage.GraphRelation rel : graph.relation() == null
                ? List.<ChatManage.GraphRelation>of() : graph.relation()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", rel.node1());
            row.put("target", rel.node2());
            row.put("knowledge_id", namespace.knowledge());
            row.put("type", rel.type());
            row.put("source_labels", labels(namespace));
            row.put("target_labels", labels(namespace));
            relData.add(row);
        }

        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(AccessMode.WRITE).build())) {
            session.executeWrite(tx -> {
                tx.run(NODE_IMPORT_QUERY, Map.of("data", nodeData));
                tx.run(REL_IMPORT_QUERY, Map.of("data", relData));
                return null;
            });
        } catch (RuntimeException e) {
            // 对照 Go：failed to add graph → 记日志并上抛（由调用方决定是否吞）
            log.error("failed to add graph: {}", e.toString());
            throw e;
        }
    }

    // ── 删（对照 DelGraph） ──

    @Override
    public void delGraph(List<ChatManage.NameSpace> namespaces) {
        if (driver == null) {
            log.warn("NOT SUPPORT RETRIEVE GRAPH");
            return;
        }
        if (namespaces == null || namespaces.isEmpty()) {
            return;
        }
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(AccessMode.WRITE).build())) {
            session.executeWrite(tx -> {
                for (ChatManage.NameSpace namespace : namespaces) {
                    String labelExpr = label(namespace);
                    Map<String, Object> params = Map.of("knowledge_id",
                            namespace.knowledge() == null ? "" : namespace.knowledge());
                    tx.run(DELETE_RELS_TEMPLATE.formatted(labelExpr, labelExpr), params);
                    tx.run(DELETE_NODES_TEMPLATE.formatted(labelExpr), params);
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.error("failed to delete graph: {}", e.toString());
            throw e;
        }
    }

    // ── 读（对照 SearchNode） ──

    @Override
    public ChatManage.GraphData searchNode(ChatManage.NameSpace namespace, List<String> nodes) {
        if (driver == null) {
            log.warn("NOT SUPPORT RETRIEVE GRAPH");
            return null;
        }
        String query = searchQuery(label(namespace));
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(AccessMode.READ).build())) {
            List<Record> records = session.executeRead(tx ->
                    tx.run(query, Map.of("nodes", nodes == null ? List.of() : nodes)).list());

            List<ChatManage.GraphNode> outNodes = new ArrayList<>();
            List<ChatManage.GraphRelation> outRelations = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Record record : records) {
                Node n = record.get("n").asNode();
                Relationship rel = record.get("r").asRelationship();
                Node m = record.get("m").asNode();

                for (Node each : List.of(n, m)) {
                    String name = each.get("name").asString("");
                    if (seen.add(name)) {
                        outNodes.add(new ChatManage.GraphNode(name,
                                listI2listS(each.get("chunks")),
                                listI2listS(each.get("attributes"))));
                    }
                }
                outRelations.add(new ChatManage.GraphRelation(
                        n.get("name").asString(""),
                        m.get("name").asString(""),
                        rel.type()));
            }
            return new ChatManage.GraphData(outNodes, outRelations);
        } catch (RuntimeException e) {
            log.error("search node failed: {}", e.toString());
            throw e;
        }
    }

    /** 对照 {@code listI2listS}：Cypher 列表 → 字符串列表（{@code fmt.Sprintf("%v")} 语义）。 */
    static List<String> listI2listS(Value value) {
        if (value == null || value.isNull()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object v : value.asList()) {
            result.add(String.valueOf(v));
        }
        return result;
    }
}
