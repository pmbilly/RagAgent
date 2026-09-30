package com.ragagent.retrieval.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.ragagent.common.graph.GraphData;
import com.ragagent.common.graph.GraphNode;
import com.ragagent.common.graph.GraphRelation;
import com.ragagent.common.graph.NameSpace;

/**
 * 图仓储的离线契约测试（对照 Go repository.go 的标签推导与 Cypher 文本）：
 * 覆盖命名空间 → 标签、驱动缺失时的 no-op 语义、查询文本的关键片段。
 * 真连 Neo4j 的路径（Bolt 会话）与本仓既有约定一致——不上 CI，靠 dev 环境自检。
 */
class Neo4jGraphRepositoryTest {

    private static NameSpace ns(String kb, String knowledge) {
        return new NameSpace(kb, knowledge);
    }

    @Test
    @DisplayName("标签推导：ENTITY 前缀 + 连字符换下划线；Label 以冒号连接")
    void labelsAndLabel() {
        Neo4jGraphRepository repo = new Neo4jGraphRepository(null);
        assertEquals(List.of("ENTITYkb_1", "ENTITYkn_2"), repo.labels(ns("kb-1", "kn-2")));
        assertEquals("ENTITYkb_1:ENTITYkn_2", repo.label(ns("kb-1", "kn-2")));

        // 空段被跳过（对照 NameSpace.Labels 的非空判断）
        assertEquals(List.of("ENTITYkb"), repo.labels(ns("kb", "")));
        assertEquals("", repo.label(ns("", "")));
        assertEquals(List.of(), repo.labels(null));
    }

    @Test
    @DisplayName("driver 为 null：三操作全部 no-op（写/删不抛、检索返 null）——照 Go 的 nil 分支")
    void disabledDriverIsNoop() {
        Neo4jGraphRepository repo = new Neo4jGraphRepository(null);
        assertFalse(repo.enabled());

        repo.addGraph(ns("kb", "kn"), List.of(
                new GraphData(
                        List.of(new GraphNode("A", List.of("c1"), List.of("attr"))),
                        List.of(new GraphRelation("A", "B", "REL")))));
        repo.delGraph(List.of(ns("kb", "kn")));
        assertNull(repo.searchNode(ns("kb", "kn"), List.of("A")));
    }

    @Test
    @DisplayName("Cypher 文本与 Go 一致：APOC 合并/并集、periodic.iterate(batchSize 1000/parallel)、CONTAINS 检索")
    void queryTextsMatchGo() {
        assertTrue(Neo4jGraphRepository.NODE_IMPORT_QUERY.contains("apoc.merge.node"));
        assertTrue(Neo4jGraphRepository.NODE_IMPORT_QUERY.contains("apoc.coll.union"));
        assertTrue(Neo4jGraphRepository.NODE_IMPORT_QUERY.contains("row.labels"));
        assertTrue(Neo4jGraphRepository.REL_IMPORT_QUERY.contains("apoc.merge.relationship"));
        assertTrue(Neo4jGraphRepository.REL_IMPORT_QUERY.contains("row.source_labels"));
        assertTrue(Neo4jGraphRepository.REL_IMPORT_QUERY.contains("row.target_labels"));

        String deleteRels = Neo4jGraphRepository.DELETE_RELS_TEMPLATE.formatted("L", "L");
        assertTrue(deleteRels.contains("apoc.periodic.iterate"));
        assertTrue(deleteRels.contains("batchSize: 1000"));
        assertTrue(deleteRels.contains("parallel: true"));
        assertTrue(deleteRels.contains("DELETE r"));
        assertTrue(Neo4jGraphRepository.DELETE_NODES_TEMPLATE.formatted("L").contains("DELETE n"));

        String search = Neo4jGraphRepository.searchQuery("ENTITYkb");
        assertTrue(search.startsWith("MATCH (n:ENTITYkb)-[r]-(m:ENTITYkb)"));
        assertTrue(search.contains("n.name CONTAINS nodeText"));
        assertTrue(search.contains("RETURN n, r, m"));
    }

    @Test
    @DisplayName("NameSpace.labels()：KB → Knowledge 序、空段跳过")
    void nameSpaceLabels() {
        assertEquals(List.of("kb", "kn"), ns("kb", "kn").labels());
        assertEquals(List.of("kb"), ns("kb", null).labels());
        assertEquals(List.of("kn"), ns("", "kn").labels());
        assertEquals(List.of(), ns(null, null).labels());
    }
}
