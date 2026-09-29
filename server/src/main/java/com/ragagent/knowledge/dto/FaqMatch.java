package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** FAQ 命中项：分数、命中类型（标准问/相似问/负例）与命中文本。 */
public record FaqMatch(double score, int type, String matchedQuestion) {
}
