package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.domain.ToolResult;

/**
 * todo_write 计划工具。
 *
 * <p>输出格式（字节级契约）：任务标题、Plan Steps 列表（每步 {@code N. emoji [status] desc}）、
 * Task Progress 统计（✅/🔄/⏳ + 计数）、剩余任务的 Important Reminder 或全完成后的
 * You-can-now 段。无步骤时输出建议检索工作流的引导段。status 未知名回落 ⏳，
 * {@code skipped} 有专属 ⏭️。</p>
 *
 * <p>Data map 的键序由序列化层排序，见
 * {@code TodoWriteToolTest} 的字节断言。</p>
 */
public class TodoWriteTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        // HTML 转义恒开（steps_json 是发给前端/模型的字符串，字节即契约）
        MAPPER.getFactory().setCharacterEscapes(new com.ragagent.common.web.GoJsonEscapes());
    }

    /** 单个计划步骤（json 键序固定：id/description/status）。 */
    @JsonPropertyOrder({"id", "description", "status"})
    public record PlanStep(String id, String description, String status) {
    }

    /** 输入（task 可省，steps 必需）。 */
    public record TodoWriteInput(String task, List<PlanStep> steps) {
    }

    private static final String SCHEMA_JSON = """
            {"type":"object","properties":{"task":{"type":"string","description":"The complex task or question you need to create a plan for"},"steps":{"type":["null","array"],"items":{"type":"object","properties":{"id":{"type":"string","description":"Unique identifier for this step (e.g., 'step1', 'step2')"},"description":{"type":"string","description":"Clear description of what to investigate or accomplish in this step"},"status":{"type":"string","description":"Current status: pending (not started), in_progress (executing), completed (finished)"}},"required":["id","description","status"],"additionalProperties":false},"description":"Array of research plan steps with status tracking"}},"required":["steps"],"additionalProperties":false}""";

    public TodoWriteTool() {
        super(ToolDefinitions.TOOL_TODO_WRITE, TOOL_DESCRIPTION, SCHEMA_JSON);
    }

    // description 字面量过长（7247 字节）；这段是发给模型提示词的一部分，
    // 逐字节契约由 schema 承担，全文照抄如下。
    private static final String TOOL_DESCRIPTION = """
            Use this tool to create and manage a structured task list for retrieval and research tasks. This helps you track progress, organize complex retrieval operations, and demonstrate thoroughness to the user.

            **CRITICAL - Focus on Retrieval Tasks Only**:
            - This tool is for tracking RETRIEVAL and RESEARCH tasks (e.g., searching knowledge bases, retrieving documents, gathering information)
            - DO NOT include summary or synthesis tasks in todo_write - those are handled by the thinking tool
            - Examples of appropriate tasks: "Search for X in knowledge base", "Retrieve information about Y", "Compare A and B"
            - Examples of tasks to EXCLUDE: "Summarize findings", "Generate final answer", "Synthesize results" - these are for thinking tool

            ## When to Use This Tool
            Use this tool proactively in these scenarios:

            1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
            2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
            3. User explicitly requests todo list - When the user directly asks you to use the todo list
            4. User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)
            5. After receiving new instructions - Immediately capture user requirements as todos
            6. When you start working on a task - Mark it as in_progress BEFORE beginning work. Ideally you should only have one todo as in_progress at a time
            7. After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation

            ## When NOT to Use This Tool

            Skip using this tool when:
            1. There is only a single, straightforward task
            2. The task is trivial and tracking it provides no organizational benefit
            3. The task is purely conversational or informational

            NOTE that you should not use this tool if there is only one trivial task to do. In this case you are better off just doing the task directly.

            ## Examples of When to Use the Todo List

            <example>
            User: Compare WeKnora with other RAG frameworks like LangChain and LlamaIndex.
            Assistant: I'll help you compare WeKnora with other RAG frameworks. Let me create a retrieval plan to gather information about each framework.
            *Creates a todo list with retrieval tasks: 1) Search knowledge base for WeKnora features and architecture, 2) Use web_search to find LangChain documentation and features, 3) Use web_search to find LlamaIndex documentation and features, 4) Retrieve detailed comparison points for each framework*
            *Note: Summary and synthesis will be handled by thinking tool after all retrieval tasks are completed*
            Assistant: Let me start by searching the knowledge base for detailed WeKnora information.

            <reasoning>
            The assistant used todo_write tool correctly because:
            1. The task requires multiple retrieval operations (searching KB for WeKnora, web search for LangChain and LlamaIndex)
            2. Each retrieval task is specific and actionable
            3. The todo list focuses on WHAT to retrieve, not HOW to summarize
            4. Summary and synthesis will be handled separately by thinking tool after all retrieval is complete
            5. This separation ensures clear task boundaries and avoids duplication
            </reasoning>
            </example>

            <example>
            User: I need to research the latest developments in vector databases for RAG applications.
            Assistant: I'll help you research vector databases for RAG. Let me create a retrieval plan to gather comprehensive information.
            *Creates todo list with retrieval tasks: 1) Search knowledge base for vector database information, 2) Use web_search to find latest vector database technologies, 3) Retrieve information about performance comparisons, 4) Gather information about integration approaches*
            *Note: After completing all retrieval tasks, thinking tool will synthesize the findings into a comprehensive answer*
            Assistant: Let me start by searching the knowledge base for vector database information.

            <reasoning>
            The assistant used todo_write tool correctly because:
            1. The research task requires multiple retrieval operations (KB search, web search for latest info)
            2. Each task focuses on retrieving specific information
            3. The todo list tracks retrieval progress, not synthesis
            4. Summary and analysis will be handled by thinking tool after retrieval is complete
            5. This approach separates retrieval (todo_write) from synthesis (thinking tool)
            </reasoning>
            </example>

            ## Examples of When NOT to Use the Todo List

            <example>
            User: How do I print 'Hello World' in Python?
            Assistant: In Python, you can print "Hello World" with this simple code:

            print("Hello World")

            This will output the text "Hello World" to the console when executed.</assistant>

            <reasoning>
            The assistant did not use the todo list because this is a single, trivial task that can be completed in one step. There's no need to track multiple tasks or steps for such a straightforward request.
            </reasoning>
            </example>

            <example>
            User: What does the git status command do?
            Assistant: The git status command shows the current state of your working directory and staging area. It displays which changes have been staged, which haven't, and which files aren't being tracked by Git. This is a fundamental command for understanding the current state of your repository before making commits.

            <reasoning>
            The assistant did not use the todo list because this is an informational request with no actual coding task to complete. The user is simply asking for an explanation, not for the assistant to perform multiple steps or tasks.
            </reasoning>
            </example>

            ## Task States and Management

            1. **Task States**: Use these states to track progress:
              - pending: Task not yet started
              - in_progress: Currently working on (limit to ONE task at a time)
              - completed: Task finished successfully

            2. **Task Management**:
              - Update task status in real-time as you work
              - Mark tasks complete IMMEDIATELY after finishing (don't batch completions)
              - Only have ONE task in_progress at any time
              - Complete current tasks before starting new ones
              - Remove tasks that are no longer relevant from the list entirely

            3. **Task Completion Requirements**:
              - ONLY mark a task as completed when you have FULLY accomplished it
              - If you encounter errors, blockers, or cannot finish, keep the task as in_progress
              - When blocked, create a new task describing what needs to be resolved
              - Never mark a task as completed if:
                - Tests are failing
                - Implementation is partial
                - You encountered unresolved errors
                - You couldn't find necessary files or dependencies

            4. **Task Breakdown**:
              - Create specific, actionable RETRIEVAL tasks
              - Break complex retrieval needs into smaller, manageable steps
              - Use clear, descriptive task names focused on what to retrieve or research
              - **DO NOT include summary/synthesis tasks** - those are handled separately by the thinking tool

            **Important**: After completing all retrieval tasks in todo_write, use the thinking tool to synthesize findings and generate the final answer. The todo_write tool tracks WHAT to retrieve, while thinking tool handles HOW to synthesize and present the information.

            When in doubt, use this tool. Being proactive with task management demonstrates attentiveness and ensures you complete all retrieval requirements successfully.""";

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String task = args.path("task").asText("");
        List<PlanStep> steps = parseSteps(args.get("steps"));

        if (task.isEmpty()) {
            task = "No task description provided";
        }

        String output = generatePlanOutput(task, steps);

        String stepsJson;
        try {
            stepsJson = MAPPER.writeValueAsString(steps);
        } catch (Exception e) {
            stepsJson = "null";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("steps", steps);
        data.put("steps_json", stepsJson);
        data.put("total_steps", steps == null ? 0 : steps.size());
        data.put("plan_created", true);
        data.put("display_type", "plan");

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(output);
        result.setData(data);
        return result;
    }

    /** 解析入参：steps 缺省/为 null → null（序列化输出 null，不是 []——实录 R_TODO_NO_STEPS 钉死）。 */
    private static List<PlanStep> parseSteps(JsonNode stepsNode) {
        if (stepsNode == null || stepsNode.isNull()) {
            return null;
        }
        List<PlanStep> steps = new ArrayList<>();
        if (stepsNode.isArray()) {
            for (JsonNode n : stepsNode) {
                steps.add(new PlanStep(
                        n.path("id").asText(""),
                        n.path("description").asText(""),
                        n.path("status").asText("")));
            }
        }
        return steps;
    }

    /** 计划输出格式化。 */
    static String generatePlanOutput(String task, List<PlanStep> steps) {
        StringBuilder output = new StringBuilder();
        output.append("Plan created\n\n");
        output.append("**Task**: ").append(task).append("\n\n");

        if (steps == null || steps.isEmpty()) {
            output.append("Note: No specific steps provided. It is recommended to create 3-7 retrieval tasks for systematic research.\n\n");
            output.append("Suggested retrieval workflow (focused on retrieval tasks, excluding summarization):\n");
            output.append("1. Use grep_chunks to search keywords and locate relevant documents\n");
            output.append("2. Use knowledge_search for semantic search to retrieve relevant content\n");
            output.append("3. Use list_knowledge_chunks to get the full content of key documents\n");
            output.append("4. Use web_search to get supplementary information (if needed)\n");
            output.append("\nNote: Summarization and synthesis are handled by the thinking tool. Do not add summarization tasks here.\n");
            return output.toString();
        }

        // 统计任务状态
        int pendingCount = 0;
        int inProgressCount = 0;
        int completedCount = 0;
        for (PlanStep step : steps) {
            switch (step.status()) {
                case "pending" -> pendingCount++;
                case "in_progress" -> inProgressCount++;
                case "completed" -> completedCount++;
                default -> { }
            }
        }
        int totalCount = steps.size();
        int remainingCount = pendingCount + inProgressCount;

        output.append("**Plan Steps**:\n\n");

        // 按序展示全部步骤
        for (int i = 0; i < steps.size(); i++) {
            output.append(formatPlanStep(i + 1, steps.get(i)));
        }

        // 摘要 + 剩余任务强调
        output.append("\n=== Task Progress ===\n");
        output.append("Total: ").append(totalCount).append(" tasks\n");
        output.append("✅ Completed: ").append(completedCount).append('\n');
        output.append("🔄 In Progress: ").append(inProgressCount).append('\n');
        output.append("⏳ Pending: ").append(pendingCount).append('\n');

        output.append("\n=== ⚠️ Important Reminder ===\n");
        if (remainingCount > 0) {
            output.append("**").append(remainingCount).append(" tasks remaining!**\n\n");
            output.append("**All tasks must be completed before summarizing or drawing conclusions.**\n\n");
            output.append("Next steps:\n");
            if (inProgressCount > 0) {
                output.append("- Continue completing tasks currently in progress\n");
            }
            if (pendingCount > 0) {
                output.append("- Start processing ").append(pendingCount).append(" pending tasks\n");
                output.append("- Complete each task in order, do not skip\n");
            }
            output.append("- After completing each task, update todo_write to mark it as completed\n");
            output.append("- Only generate the final summary after all tasks are completed\n");
        } else {
            output.append("✅ **All tasks completed!**\n\n");
            output.append("You can now:\n");
            output.append("- Synthesize findings from all tasks\n");
            output.append("- Generate a complete final answer or report\n");
            output.append("- Ensure all aspects have been thoroughly researched\n");
        }

        return output.toString();
    }

    /** 单步格式化（未知 status 回落 ⏳；skipped → ⏭️）。 */
    static String formatPlanStep(int index, PlanStep step) {
        String emoji = switch (step.status()) {
            case "pending" -> "⏳";
            case "in_progress" -> "🔄";
            case "completed" -> "✅";
            case "skipped" -> "⏭️";
            default -> "⏳";
        };
        return "  " + index + ". " + emoji + " [" + step.status() + "] " + step.description() + "\n";
    }
}
