package com.ragagent.agent.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoMapSerializer;

/**
 * 一次工具执行的结果（对照 Go {@code types.ToolResult}，internal/types/agent.go:351-360）。
 *
 * <p>字段序 = Go 声明序。{@code OutputFiles} 是 {@code json:"-"}，
 * 但它是**运行时**字段（沙箱引用），历史用最终答案的持久资源引用——故 {@code @JsonIgnore}。</p>
 */
@JsonPropertyOrder({"success", "output", "data", "error", "images"})
public class ToolResult {

    /**
     * 沙箱引用，**仅本次活结果**有效；历史用的是最终答案的持久资源引用。
     * 对照 Go 的 {@code json:"-"}。
     */
    @JsonIgnore
    private List<String> outputFiles;

    @JsonProperty("success")
    private boolean success;

    /** 人能读的输出（Go 无 omitempty → 恒输出，含空串）。 */
    @JsonProperty("output")
    private String output = "";

    /** 结构化数据，供程序化使用。omitempty。键序递归对齐 Go（map 恒排序）。 */
    @JsonProperty("data")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, Object> data;

    /** 执行失败时的错误信息。omitempty。 */
    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String error;

    /** 工具产出的 base64 data URI（如 MCP 图片内容）。omitempty。 */
    @JsonProperty("images")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> images;

    public List<String> getOutputFiles() { return outputFiles; }
    public void setOutputFiles(List<String> v) { outputFiles = v; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { success = v; }

    public String getOutput() { return output; }
    public void setOutput(String v) { output = v == null ? "" : v; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> v) { data = v; }

    public String getError() { return error; }
    public void setError(String v) { error = v; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> v) { images = v; }
}
