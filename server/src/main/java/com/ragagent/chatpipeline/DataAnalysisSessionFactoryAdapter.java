package com.ragagent.chatpipeline;


import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.DataAnalysisSessionBridge;
import com.ragagent.agent.tools.DataAnalysisTool;

/**
 * DataAnalysisSessionFactory 的 chatpipeline 侧实现（波 4.6d 新增文件——
 * {@link PipelinePorts.DataAnalysisSession} 是本包包私有接口，实现只能落在本包；
 * 对 tools 包私有 {@code loadFromKnowledge} 的触达经
 * {@link DataAnalysisSessionBridge}）。
 *
 * <p><b>装配边界（备案）</b>：4.5b 的生产执行接线（SqlQueryExecutor/AnalysisDuckDb/
 * KnowledgeFileMaterializer 的 JDBC/DuckDB 生产实现）未随本批落地——DATA_ANALYSIS
 * 阶段只在 MergeResult 命中 CSV/Excel 且检索执行面可用时进入；当前检索执行面未翻译
 * （QaWiring 备案），本会话内不可达。工厂以空 seam 构造工具，装载即报
 * PipelinePortException（Go 侧无 DB 句柄时同样在装载处报错）。</p>
 */
public final class DataAnalysisSessionFactoryAdapter implements PipelinePorts.DataAnalysisSessionFactory {

    /** 每会话一个工具实例（对照 Go 插件内 tools.NewDataAnalysisTool + 三步）。 */
    @Override
    public PipelinePorts.DataAnalysisSession create(String sessionId) {
        DataAnalysisTool tool = new DataAnalysisTool(null, null, null, sessionId);
        return new SessionImpl(tool);
    }

    private static final class SessionImpl implements PipelinePorts.DataAnalysisSession {
        private final DataAnalysisTool tool;

        SessionImpl(DataAnalysisTool tool) {
            this.tool = tool;
        }

        @Override
        public DataAnalysisTool.TableSchema loadFromKnowledge(DataAnalysisTool.KnowledgeData knowledge) {
            return DataAnalysisSessionBridge.loadFromKnowledge(tool, knowledge);
        }

        @Override
        public ToolResult execute(JsonNode args) {
            return DataAnalysisSessionBridge.execute(tool, args);
        }

        @Override
        public void cleanup() {
            DataAnalysisSessionBridge.cleanup(tool);
        }
    }
}
