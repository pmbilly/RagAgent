package com.ragagent.mcp.domain;

import java.util.List;

/**
 * stdio 传输配置。
 *
 * **运行时被禁用**：结构体与 stdio_config 列、DTO 字段都保留，但传输层硬拒绝
 * stdio（命令注入风险）。保留字段是为了历史行与 DTO 形态一致。
 */
public class McpStdioConfig {

    /** 命令："uvx" 或 "npx"。缺省为 ""（非 null） */
    private String command = "";
    private List<String> args;

    public String getCommand() { return command; }
    public void setCommand(String v) { command = v; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> v) { args = v; }
}
