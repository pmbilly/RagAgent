package com.ragagent.knowledge.dto;


/** FAQ 导入成功条目：序号与命中标签。 */
public record FaqSuccessEntry(
        int index,
        long seqId,
        long tagId,
        String tagName,
        String standardQuestion) {
}
