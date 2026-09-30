package com.ragagent.retrieval.graph;

import com.ragagent.common.graph.GraphData;
import com.ragagent.common.graph.NameSpace;
import java.util.List;

/** 图检索仓储端口（原为 chatpipeline.PipelinePorts 的嵌套接口；由本域实现，故下沉到实现方）。 */
/**
 * 对照 interfaces.RetrieveGraphRepository（types/interfaces/retriever_graph.go 全文）：
 * 图库的写（AddGraph）/删（DelGraph）/读（SearchNode）。
 *
 * <p>D 批起由 {@code com.ragagent.retrieval.graph.Neo4jGraphRepository} 提供真实实现
 * （NEO4J_ENABLE=true 才建驱动；否则 driver 为 null，三个操作都告警并静默——照 Go 的
 * nil driver 分支）。流水线只用 {@link #searchNode}；写/删由抽取与清理链路调用。</p>
 */
public interface RetrieveGraphRepository {
    /** 对照 AddGraph：把一批图写进仓库（driver 缺失 → 告警 + 静默返回）。 */
    void addGraph(NameSpace namespace, List<GraphData> graphs);

    /** 对照 DelGraph：按命名空间删除（driver 缺失 → 告警 + 静默返回）。 */
    void delGraph(List<NameSpace> namespaces);

    /** 对照 SearchNode：按节点名（CONTAINS）匹配并取回一跳子图。 */
    GraphData searchNode(NameSpace namespace, List<String> nodes);
}
