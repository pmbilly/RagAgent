package com.ragagent.knowledge.dto;


/** FAQ 命中项：分数、命中类型（标准问/相似问/负例）与命中文本。 */
public record FaqMatch(double score, int type, String matchedQuestion) {
}
