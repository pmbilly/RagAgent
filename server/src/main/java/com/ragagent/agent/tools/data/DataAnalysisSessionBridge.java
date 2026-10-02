package com.ragagent.agent.tools.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.ToolRequest;

/**
 * DataAnalysisTool 包内面的公开桥。
 *
 * <p>chat_pipeline 的 {@code PipelinePorts.DataAnalysisSession} 是 chatpipeline
 * 包私有接口（只能由该包实现），而 {@link DataAnalysisTool#loadFromKnowledge} 是
 * agent.tools 包私有方法（只能由本包触达）——两个包私有面之间的桥只能各放一半：
 * 本文件公开「本包 → 调用者」的三个调用点，chatpipeline 侧新增
 * {@code DataAnalysisSessionFactoryAdapter} 实现接口并经本桥调用工具。</p>
 */
public final class DataAnalysisSessionBridge {

    private DataAnalysisSessionBridge() {}

    /** 触达包私有 loadFromKnowledge。 */
    public static DataAnalysisTool.TableSchema loadFromKnowledge(DataAnalysisTool tool,
            DataAnalysisTool.KnowledgeData knowledge) {
        return tool.loadFromKnowledge(knowledge);
    }

    /** execute 本就 public；桥内把 JsonNode args 包成 ToolRequest。 */
    public static ToolResult execute(DataAnalysisTool tool, JsonNode args) {
        return tool.execute(ToolRequest.of(args));
    }

    /** cleanup 本就 public。 */
    public static void cleanup(DataAnalysisTool tool) {
        tool.cleanup();
    }
}
