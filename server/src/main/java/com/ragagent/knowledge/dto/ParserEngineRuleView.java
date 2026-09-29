package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

/** 解析器引擎规则视图：扩展名 → 引擎（可带 xlsx 首行作表头开关）。 */
public record ParserEngineRuleView(List<String> fileTypes, String engine,
                                   Boolean xlsxFirstRowAsHeader) {
}
