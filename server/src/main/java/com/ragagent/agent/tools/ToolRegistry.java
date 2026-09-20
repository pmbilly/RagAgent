package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.llm.domain.FunctionDef;

/**
 * 工具注册表（对照 Go {@code registry.go}，逐字移植）。
 *
 * <p><b>first-wins 注册</b>：同名工具重复注册时保留先到者（GHSA-67q9-58vj-32qx——
 * 防止借名字冲突劫持工具执行），后到者被拒并记 warn。</p>
 *
 * <p><b>排序决定字节稳定</b>：ListTools / GetFunctionDefinitions 都按工具名排序——
 * Go 的 map 迭代是有意随机化的，不排序则每次请求发给 LLM 的工具块都会重排，
 * 按"字节级前缀匹配"做提示词缓存的 provider（如 Qwen 显式缓存）会全部失手。</p>
 *
 * <p><b>deferred 注册</b>：RegisterDeferredTool 保留执行能力但不把完整定义发给模型
 * （GetModelFunctionDefinitions 会滤掉）；注册在执行前已完成，所以按名执行不受影响。</p>
 *
 * <p>Java 侧签名说明：Go 的 {@code ExecuteTool(ctx, name, args) (*ToolResult, error)} 双通道
 * 折叠为返回 {@link ToolResult}——Go 在 ctx 取消/工具不存在/工具返回 err 时
 * {@code result.Error} 都已置好文案，error 通道可从中完整还原。取消探测走
 * {@link ToolCancellation}（对照 ctx.Err()），元数据走 {@link ToolExecContext}
 * （对照 WithToolExecContext）。</p>
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new HashMap<>();
    private final Map<String, Boolean> deferred = new HashMap<>();
    /** 完整暴露是显式兼容路径。对照 mcpDirect/mcpPrepared：4.5c 接线，本批仅保字段形状。 */
    private boolean mcpDirect;
    private boolean mcpPrepared;
    /** 工具输出上限（字符数）；0 = 用 DefaultMaxToolOutput。 */
    private int maxToolOutputSize;

    // ---- 校验失败的追加提示（文本属于 4.5b/c 的工具文件，registry 拼接点先就位）----
    static final String MCP_CALL_ARGUMENTS_HINT = " Pass arguments as a JSON object, not a JSON-encoded string. "
            + "For a tool with no parameters use {\"tool_ref\":\"<describe reference>\",\"arguments\":{}}; "
            + "otherwise match its input_schema. If the definition is unavailable, use "
            + "discover_mcp_tools(mode=\"describe\", server_id=..., tool_name=...).";
    static final String WRITE_SANDBOX_MISSING_FIELD_HINT =
            "\nIf the previous call was truncated, retry with a complete JSON object: "
                    + "put `path` first (e.g. /workspace/output/script.py), then `content`. Split large files.";
    static final String EDIT_SANDBOX_MISSING_FIELD_HINT =
            "\nIf the previous call was truncated, retry with a complete JSON object: "
                    + "put `path` first, then `edits` as an array of {old_string, new_string}. "
                    + "Do not send the whole file — this tool replaces snippets.";

    /** 设置工具输出最大字符数；≤0 时用 DefaultMaxToolOutput。 */
    public void setMaxToolOutputSize(int maxChars) {
        this.maxToolOutputSize = maxChars;
    }

    /** 生效的输出上限（对照 getMaxToolOutput）。 */
    private int effectiveMaxToolOutput() {
        return maxToolOutputSize > 0 ? maxToolOutputSize : ToolOutput.DEFAULT_MAX_TOOL_OUTPUT;
    }

    /**
     * 注册工具（first-wins：同名已注册时保留先到者）。
     */
    public synchronized void registerTool(AgentTool tool) {
        registerTool(tool, false);
    }

    /**
     * 注册"延迟暴露"工具：保留执行能力但不把完整定义发给模型；注册在执行前已完成。
     */
    public synchronized void registerDeferredTool(AgentTool tool) {
        registerTool(tool, true);
    }

    private synchronized void registerTool(AgentTool tool, boolean deferredFlag) {
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("[ToolRegistry] Duplicate tool registration rejected: {} (first-wins policy)", name);
            return;
        }
        tools.put(name, tool);
        deferred.put(name, deferredFlag);
    }

    /**
     * 按名取工具；不存在抛 {@link ToolNotFoundException}（message = Go 的
     * "tool not found: %s"）。
     */
    public synchronized AgentTool getTool(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new ToolNotFoundException("tool not found: " + name);
        }
        return tool;
    }

    /** 工具不存在（对照 Go 的 error "tool not found: %s"）。 */
    public static class ToolNotFoundException extends RuntimeException {
        public ToolNotFoundException(String message) {
            super(message);
        }
    }

    /** 已注册工具名，按字母序（对照 ListTools）。 */
    public synchronized List<String> listTools() {
        SortedMap<String, AgentTool> sorted = new TreeMap<>(tools);
        return new ArrayList<>(sorted.keySet());
    }

    /** 全部已注册工具的函数定义，按名排序后发给 LLM。 */
    public synchronized List<FunctionDef> getFunctionDefinitions() {
        return functionDefinitions(false);
    }

    /** 面向模型的稳定投影：滤掉 deferred 注册的工具。 */
    public synchronized List<FunctionDef> getModelFunctionDefinitions() {
        return functionDefinitions(true);
    }

    private synchronized List<FunctionDef> functionDefinitions(boolean modelOnly) {
        SortedMap<String, AgentTool> sorted = new TreeMap<>(tools);
        List<FunctionDef> definitions = new ArrayList<>(sorted.size());
        for (Map.Entry<String, AgentTool> e : sorted.entrySet()) {
            if (modelOnly && Boolean.TRUE.equals(deferred.get(e.getKey()))) {
                continue;
            }
            AgentTool tool = e.getValue();
            definitions.add(new FunctionDef(tool.getName(), tool.getDescription(), tool.getParameters()));
        }
        return definitions;
    }

    /**
     * 按名执行工具（对照 ExecuteTool）：取消检查 → 取工具 → 退役重定向 →
     * MCP 目录守卫（鉴权先于 schema 校验）→ {@link #execute}。
     */
    public ToolResult executeTool(ToolCancellation cancellation, ToolExecContext meta, String name, JsonNode args) {
        ToolCancellation cancel = cancellation != null ? cancellation : ToolCancellation.LIVE;
        String cancelErr = cancel.cancellationError();
        if (cancelErr != null) {
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(cancelErr);
            return r;
        }
        logExecution("execute_start", meta, Map.of("tool", name, "args", String.valueOf(args)));
        AgentTool tool;
        try {
            tool = getTool(name);
        } catch (ToolNotFoundException e) {
            String replacement = ToolDefinitions.retiredToolReplacement(name);
            if (!replacement.isEmpty()) {
                logExecution("retired_tool", meta, Map.of("tool", name, "error", replacement));
                ToolResult r = new ToolResult();
                r.setSuccess(false);
                r.setError(replacement);
                return r;
            }
            logExecution("execute_failed", meta, Map.of("tool", name, "error", e.getMessage()));
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(e.getMessage());
            return r;
        }

        if (tool instanceof McpCatalogGuardedTool guarded) {
            // 鉴权先于 schema 校验：连参数错误的 details 都不能暴露其他引擎主体的注册工具定义。
            String authErr = guarded.authorizeCatalog();
            if (authErr != null) {
                return mcpDiscoveryFailure(authErr, "unavailable");
            }
        }
        return execute(cancel, meta, tool, args);
    }

    /** 直连执行（无取消源、无元数据）的便捷重载。 */
    public ToolResult executeTool(String name, JsonNode args) {
        return executeTool(ToolCancellation.LIVE, null, name, args);
    }

    /**
     * 直连调用与目录解析的 MCP 调用共用的执行管线（对照 execute）。
     * 代理必须对目标 schema 做校验并保留原结果——不能只校验外层参数或绕过执行管线。
     */
    private ToolResult execute(ToolCancellation cancel, ToolExecContext meta, AgentTool tool, JsonNode args) {
        String cancelErr = cancel.cancellationError();
        if (cancelErr != null) {
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(cancelErr);
            return r;
        }
        String name = tool.getName();
        // 执行前先按 schema 做参数矫正——处理 "true" 代替 true 之类的常见 LLM 怪癖。
        JsonNode castArgs = ParamCaster.castParams(args, tool.getParameters());

        // 执行前按 JSON Schema 校验参数——尽早拦截，省掉一次白费的工具执行 + LLM 回合。
        List<ParamValidator.ValidationError> validationErrs;
        if (tool instanceof ArgumentValidator validator) {
            String errText = validator.validateArguments(castArgs);
            validationErrs = errText != null
                    ? List.of(new ParamValidator.ValidationError("", errText))
                    : List.of();
        } else {
            validationErrs = ParamValidator.validateParams(castArgs, tool.getParameters());
        }
        if (!validationErrs.isEmpty()) {
            String errMsg = ParamValidator.formatValidationErrors(validationErrs);
            if (ToolDefinitions.TOOL_CALL_MCP_TOOL.equals(name)) {
                errMsg += MCP_CALL_ARGUMENTS_HINT;
            }
            if (ToolDefinitions.TOOL_WRITE_SANDBOX_FILE.equals(name)) {
                errMsg += WRITE_SANDBOX_MISSING_FIELD_HINT;
            }
            if (ToolDefinitions.TOOL_EDIT_SANDBOX_FILE.equals(name)) {
                errMsg += EDIT_SANDBOX_MISSING_FIELD_HINT;
            }
            logExecution("validation_failed", meta, Map.of("tool", name, "errors", errMsg));
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(errMsg);
            return r;
        }

        // 把上限发布出去，让有预算意识的工具自己裁剪批量结果；下面的截断只是其余工具的兜底。
        int maxOutput = effectiveMaxToolOutput();
        if (tool instanceof OutputLimitProvider provider) {
            int toolLimit = provider.outputLimitChars(castArgs);
            if (toolLimit > maxOutput) {
                maxOutput = toolLimit;
            }
        }
        ToolResult result;
        try {
            result = tool.execute(new ToolRequest(castArgs, meta, cancel, maxOutput));
        } catch (RuntimeException e) {
            // Java 侧防御：Go 的 err 返回值通道。工具抛了运行时异常时等价于 (nil, err)。
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError("tool returned no result");
            logExecution("execute_done", meta, Map.of("tool", name, "args", String.valueOf(castArgs),
                    "error", String.valueOf(e.getMessage())), true);
            return r;
        }
        if (result == null) {
            result = new ToolResult();
            result.setSuccess(false);
            result.setError("tool returned no result");
        }

        // 截断超限的工具输出，防止上下文窗口被灌爆。上限按 rune 数计（与 TruncateToolOutput
        // 一致）；这里若按字节比较，CJK 输出实际等于没封顶。
        if (result.getOutput() != null
                && result.getOutput().codePointCount(0, result.getOutput().length()) > maxOutput) {
            result.setOutput(ToolOutput.truncateToolOutput(result.getOutput(), maxOutput));
        }
        if (result.getError() != null
                && result.getError().codePointCount(0, result.getError().length()) > maxOutput) {
            result.setError(ToolOutput.truncateToolOutput(result.getError(), maxOutput));
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("tool", name);
        fields.put("args", String.valueOf(castArgs));
        fields.put("success", String.valueOf(result.isSuccess()));
        if (result.getError() != null && !result.getError().isEmpty()) {
            fields.put("error", result.getError());
        }
        boolean isError = false;
        logExecution("execute_done", meta, fields, isError);

        return result;
    }

    /** MCP 目录不可用的统一失败形态（对照 mcpDiscoveryFailure）。 */
    static ToolResult mcpDiscoveryFailure(String err, String status) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(err);
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        r.setData(data);
        return r;
    }

    /**
     * 会话收尾时释放实现了 {@link Cleanable} 的工具资源（对照 Cleanup；map 迭代序无所谓，
     * 各工具清理互不依赖）。
     */
    public synchronized void cleanup() {
        for (Map.Entry<String, AgentTool> e : tools.entrySet()) {
            if (e.getValue() instanceof Cleanable cleanable) {
                log.info("[ToolRegistry] Cleaning up tool: {}", e.getKey());
                cleanable.cleanup();
            }
        }
    }

    private static void logExecution(String stage, ToolExecContext meta, Map<String, String> fields) {
        logExecution(stage, meta, fields, "execute_failed".equals(stage) || "validation_failed".equals(stage));
    }

    private static void logExecution(String stage, ToolExecContext meta, Map<?, ?> fields, boolean warn) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String session = meta != null ? meta.sessionId() : "";
        if (warn) {
            log.warn("[AgentTool] {} session={} {}", stage, session, fields);
        } else {
            log.info("[AgentTool] {} session={} {}", stage, session, fields);
        }
    }
}
