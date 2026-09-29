package com.ragagent.agent.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoMapSerializer;

/**
 * 解析后的实际调用目标（对照 Go {@code types.ToolCallTarget}，
 * internal/types/agent.go:379-384）。
 *
 * <p>它记录"真正被调用的那个东西是谁"，而 {@link ToolCall#getName()} /
 * {@link ToolCall#getArgs()} 保留**模型发出的原始调用**——后者必须原样留着供历史回放与
 * 厂商状态复原，改写它会把"模型说过什么"和"服务端实际做了什么"抹平。</p>
 *
 * <p>四个字段都**没有 omitempty**：恒输出（字符串为空串、args 为 {@code null}）。</p>
 */
public class ToolCallTarget {

    private String name = "";

    /** 键序递归对齐 Go（map 恒排序）。无 omitempty：nil → {@code null}。 */
    @JsonSerialize(using = GoMapSerializer.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> args;

    private String serviceName = "";

    private String toolName = "";

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public Map<String, Object> getArgs() { return args; }
    public void setArgs(Map<String, Object> v) { args = v; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String v) { serviceName = v == null ? "" : v; }

    public String getToolName() { return toolName; }
    public void setToolName(String v) { toolName = v == null ? "" : v; }
}
