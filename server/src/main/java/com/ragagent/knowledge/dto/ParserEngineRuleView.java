package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

public record ParserEngineRuleView(List<String> fileTypes, String engine,
                                   Boolean xlsxFirstRowAsHeader) {
}
