package com.ragagent.agent.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次工具执行的结果。
 *
 * <p>字段序即线上契约，不要重排。{@code outputFiles} 是**运行时**字段
 * （沙箱引用；历史用最终答案的持久资源引用）——故 {@code @JsonIgnore}。</p>
 */
public class ToolResult {

    /**
     * 沙箱引用，**仅本次活结果**有效；历史用的是最终答案的持久资源引用。
     */
    @JsonIgnore
    private List<String> outputFiles;

    private boolean success;

    /** 人能读的输出（恒输出，含空串）。 */
    private String output = "";

    /** 结构化数据，供程序化使用。空时省略。键序递归恒排序（与既有 jsonb 记录逐字节一致）。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> data;

    /** 执行失败时的错误信息。空时省略。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String error;

    /** 工具产出的 base64 data URI（如 MCP 图片内容）。空时省略。 */
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

    public String getError() { return error == null ? "" : error; }

    /** 字符串零值约定为 ""：传入 null 归一为 ""（消费侧可直接 isEmpty）。 */
    public void setError(String v) { error = v == null ? "" : v; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> v) { images = v; }
}
