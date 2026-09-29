package com.ragagent.knowledge.dto;

import java.util.List;

/** 解析器引擎规则视图：扩展名 → 引擎（可带 xlsx 首行作表头开关）。 */
public record ParserEngineRuleView(List<String> fileTypes, String engine,
                                   Boolean xlsxFirstRowAsHeader) {
}
