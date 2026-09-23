package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * thinking / 顺序思维工具（对照 Go {@code sequentialthinking.go}，逐字移植）。
 *
 * <p>有状态：记录思维历史与分支；输入自适应（thoughtNumber 超过 totalThoughts 时上调总数）。
 * 校验文案逐字：{@code invalid thought: must be a non-empty string}、
 * {@code invalid thoughtNumber: must be >= 1}、{@code invalid totalThoughts: must be >= 1}；
 * 外层包 {@code Validation failed: }；JSON 解析失败包 {@code Failed to parse args: }。</p>
 *
 * <p>分支键列表（branches）Go 侧来自 map 迭代（顺序随机）——Java 用插入序确定性输出
 * （已备案的已知差异；无消费方可依赖 Go 的随机序）。</p>
 */
public class SequentialThinkingTool extends BaseTool {

    /** 输入（对照 SequentialThinkingInput；可省字段用包装类型承载 omitempty 语义）。 */
    public record SequentialThinkingInput(
            String thought,
            boolean nextThoughtNeeded,
            int thoughtNumber,
            int totalThoughts,
            boolean isRevision,
            Integer revisesThought,
            Integer branchFromThought,
            String branchId,
            boolean needsMoreThoughts) {
    }

    private final List<SequentialThinkingInput> thoughtHistory = new ArrayList<>();
    private final Map<String, List<SequentialThinkingInput>> branches = new LinkedHashMap<>();

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "thought": {
                  "type": "string",
                  "description": "Your current thinking step. Write in natural, user-friendly language. NEVER mention tool names (like \\"grep_chunks\\", \\"knowledge_search\\", \\"web_search\\", etc.). Instead, describe actions in plain language (e.g., \\"I'll search for key terms\\" instead of \\"I'll use grep_chunks\\"). Focus on WHAT you're trying to find and WHY, not HOW (which tools you'll use)."
                },
                "next_thought_needed": {
                  "type": "boolean",
                  "description": "Whether another thought step is needed"
                },
                "thought_number": {
                  "type": "integer",
                  "description": "Current thought number (numeric value, e.g., 1, 2, 3)",
                  "minimum": 1
                },
                "total_thoughts": {
                  "type": "integer",
                  "description": "Estimated total thoughts needed (numeric value, e.g., 5, 10)",
                  "minimum": 1
                },
                "is_revision": {
                  "type": "boolean",
                  "description": "Whether this revises previous thinking"
                },
                "revises_thought": {
                  "type": "integer",
                  "description": "Which thought is being reconsidered",
                  "minimum": 1
                },
                "branch_from_thought": {
                  "type": "integer",
                  "description": "Branching point thought number",
                  "minimum": 1
                },
                "branch_id": {
                  "type": "string",
                  "description": "Branch identifier"
                },
                "needs_more_thoughts": {
                  "type": "boolean",
                  "description": "If more thoughts are needed"
                }
              },
              "required": ["thought", "next_thought_needed", "thought_number", "total_thoughts"]
            }""";

    public SequentialThinkingTool() {
        super(ToolDefinitions.TOOL_THINKING, TOOL_DESCRIPTION, SCHEMA_JSON);
    }

    private static final String TOOL_DESCRIPTION = """
            A detailed tool for dynamic and reflective problem-solving through thoughts.

            This tool helps analyze problems through a flexible thinking process that can adapt and evolve.

            Each thought can build on, question, or revise previous insights as understanding deepens.

            ## When to Use This Tool

            - Breaking down complex problems into steps
            - Planning and design with room for revision
            - Analysis that might need course correction
            - Problems where the full scope might not be clear initially
            - Problems that require a multi-step solution
            - Tasks that need to maintain context over multiple steps
            - Situations where irrelevant information needs to be filtered out

            ## Key Features

            - You can adjust total_thoughts up or down as you progress
            - You can question or revise previous thoughts
            - You can add more thoughts even after reaching what seemed like the end
            - You can express uncertainty and explore alternative approaches
            - Not every thought needs to build linearly - you can branch or backtrack
            - Generates a solution hypothesis
            - Verifies the hypothesis based on the Chain of Thought steps
            - Repeats the process until satisfied
            - When your thinking is complete, deliver your answer by writing it as your plain reply and stopping (no further tool calls). NEVER include the final answer directly in a thought.

            ## Parameters Explained

            - **thought**: Your current thinking step, which can include:
              * Regular analytical steps
              * Revisions of previous thoughts
              * Questions about previous decisions
              * Realizations about needing more analysis
              * Changes in approach
              * Hypothesis generation
              * Hypothesis verification
            \s\s
              **CRITICAL - User-Friendly Thinking**: Write your thoughts in natural, user-friendly language. NEVER mention tool names (like "grep_chunks", "knowledge_search", "web_search", etc.) in your thinking process. Instead, describe your actions in plain language:
              - ❌ BAD: "I'll use grep_chunks to search for keywords, then knowledge_search for semantic understanding"
              - ✅ GOOD: "I'll start by searching for key terms in the knowledge base, then explore related concepts"
              - ❌ BAD: "After grep_chunks returns results, I'll use knowledge_search"
              - ✅ GOOD: "After finding relevant documents, I'll search for semantically related content"
            \s\s
              Write thinking as if explaining your reasoning to a user, not documenting technical steps. Focus on WHAT you're trying to find and WHY, not HOW (which tools you'll use).

            - **next_thought_needed**: True if you need more thinking, even if at what seemed like the end
            - **thought_number**: Current number in sequence (can go beyond initial total if needed)
            - **total_thoughts**: Current estimate of thoughts needed (can be adjusted up/down)
            - **is_revision**: A boolean indicating if this thought revises previous thinking
            - **revises_thought**: If is_revision is true, which thought number is being reconsidered
            - **branch_from_thought**: If branching, which thought number is the branching point
            - **branch_id**: Identifier for the current branch (if any)
            - **needs_more_thoughts**: If reaching end but realizing more thoughts needed

            ## Best Practices

            1. Start with an initial estimate of needed thoughts, but be ready to adjust
            2. Feel free to question or revise previous thoughts
            3. Don't hesitate to add more thoughts if needed, even at the "end"
            4. Express uncertainty when present
            5. Mark thoughts that revise previous thinking or branch into new paths
            6. Ignore information that is irrelevant to the current step
            7. Generate a solution hypothesis when appropriate
            8. Verify the hypothesis based on the Chain of Thought steps
            9. Repeat the process until satisfied with the solution
            10. Only set next_thought_needed to false when truly done and a satisfactory answer is reached
            11. NEVER include the final answer in the thought content. When thinking is complete, deliver the final answer by writing it as your plain reply and stopping (no further tool calls)""";

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        SequentialThinkingInput input;
        try {
            input = new SequentialThinkingInput(
                    args.path("thought").asText(""),
                    args.path("next_thought_needed").asBoolean(false),
                    args.path("thought_number").asInt(0),
                    args.path("total_thoughts").asInt(0),
                    args.path("is_revision").asBoolean(false),
                    args.hasNonNull("revises_thought") ? args.get("revises_thought").asInt() : null,
                    args.hasNonNull("branch_from_thought") ? args.get("branch_from_thought").asInt() : null,
                    args.path("branch_id").asText(""),
                    args.path("needs_more_thoughts").asBoolean(false));
        } catch (RuntimeException e) {
            // 对照 Go 的 "Failed to parse args: %v"（Go 用 encoding/json 消息，Java 用 Jackson——已备案差异）
            return failure("Failed to parse args: " + e.getMessage());
        }

        String validationError = validate(input);
        if (validationError != null) {
            return failure("Validation failed: " + validationError);
        }

        int totalThoughts = input.totalThoughts();
        int thoughtNumber = input.thoughtNumber();
        // thoughtNumber 超过 totalThoughts 时上调总数
        if (thoughtNumber > totalThoughts) {
            totalThoughts = thoughtNumber;
        }
        SequentialThinkingInput adjusted = new SequentialThinkingInput(input.thought(), input.nextThoughtNeeded(),
                thoughtNumber, totalThoughts, input.isRevision(), input.revisesThought(),
                input.branchFromThought(), input.branchId(), input.needsMoreThoughts());

        // 记入思维历史
        thoughtHistory.add(adjusted);

        // 处理分支
        if (adjusted.branchFromThought() != null && !adjusted.branchId().isEmpty()) {
            branches.computeIfAbsent(adjusted.branchId(), k -> new ArrayList<>()).add(adjusted);
        }

        List<String> branchKeys = new ArrayList<>(branches.keySet());

        boolean incomplete = adjusted.nextThoughtNeeded() || adjusted.needsMoreThoughts()
                || adjusted.thoughtNumber() < totalThoughts;

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("thought_number", thoughtNumber);
        responseData.put("total_thoughts", totalThoughts);
        responseData.put("next_thought_needed", adjusted.nextThoughtNeeded());
        responseData.put("branches", branchKeys);
        responseData.put("thought_history_length", thoughtHistory.size());
        responseData.put("display_type", "thinking");
        responseData.put("thought", adjusted.thought());
        responseData.put("incomplete_steps", incomplete);

        String outputMsg = "Thought process recorded";
        if (incomplete) {
            outputMsg = "Thought process recorded - unfinished steps remain, continue exploring and calling tools";
        }

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(outputMsg);
        result.setData(responseData);
        return result;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }

    /** 输入校验（对照 validate；错误文案逐字）。 */
    private static String validate(SequentialThinkingInput data) {
        if (data.thought().isEmpty()) {
            return "invalid thought: must be a non-empty string";
        }
        if (data.thoughtNumber() < 1) {
            return "invalid thoughtNumber: must be >= 1";
        }
        if (data.totalThoughts() < 1) {
            return "invalid totalThoughts: must be >= 1";
        }
        return null;
    }

    /** 仅供测试观察历史长度（对照 Go 测试通过多次 Execute 后的 history_length 观察等价）。 */
    int thoughtHistorySizeForTest() {
        return thoughtHistory.size();
    }

    /** 仅供测试观察分支集合。 */
    Set<String> branchKeysForTest() {
        return new HashSet<>(branches.keySet());
    }
}
