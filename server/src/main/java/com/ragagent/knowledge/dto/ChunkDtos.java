package com.ragagent.knowledge.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;

/**
 * chunk 域传输对象：编辑/回滚/生成问题的请求 record 与列表/更新响应信封。
 * 请求 record 用标准 {@code @JsonNaming(SnakeCaseStrategy)} + 校验注解（消息自含
 * snake_case 字段前缀）；分页响应保持既有五键契约形状。
 */
public final class ChunkDtos {

    private ChunkDtos() {
    }

    /** chunk 编辑请求：全指针字段，三态（不传 = 不变更）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateChunkRequest(
            String content,
            Boolean isEnabled,
            Integer expectedRevision) {
    }

    /** chunk 回滚请求：目标修订号必填。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RevertChunkRequest(
            @NotNull(message = "revision: 不能为空")
            Integer revision,
            Integer expectedRevision) {
    }

    /** question 的 null 判定在 controller（空白串放行，由 service 落域文案）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpsertGeneratedQuestionRequest(
            String questionId,
            String question) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeleteGeneratedQuestionRequest(
            String questionId) {
    }

    /** chunk 列表响应：data/page/page_size/success/total 五键（既有契约形状）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkPageResponse<T>(T data, int page,
            @com.fasterxml.jackson.annotation.JsonProperty("page_size") int pageSize,
            boolean success, long total) {
    }

    /** chunk 更新/回滚响应：knowledge 摘要信息重载失败时 description/summary_status 缺席。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkUpdateResponse<T>(T data, String description, boolean success,
            @com.fasterxml.jackson.annotation.JsonProperty("summary_status") String summaryStatus) {
    }

    /** 无 data 的操作确认响应。 */
    public record ChunkMessageResponse(String message, boolean success) {
    }
}
