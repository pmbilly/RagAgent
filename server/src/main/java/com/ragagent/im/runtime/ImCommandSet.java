package com.ragagent.im.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.tools.ToolCapabilities;
import com.ragagent.im.runtime.Commands.CommandContext;
import com.ragagent.im.runtime.Commands.CommandRegistry;
import com.ragagent.im.runtime.Commands.CommandResult;
import com.ragagent.im.runtime.Commands.ImCommand;

/**
 * 五个斜杠命令（对照 Go internal/im/{cmd_help,cmd_info,cmd_search,cmd_stop,
 * cmd_clear}.go，波 5 W5γ1 逐行翻译）。注册序 = service.go L862-866。
 *
 * <p>agent 配置在 Java 侧是 JSON（Go 是 typed struct）：{@link AgentCfgView}
 * 以同一字段语义读取（kb_selection_mode/agent_mode 等）；IsAgentMode =
 * AgentMode == "smart-reasoning"（types/custom_agent.go L554-556）。</p>
 */
public final class ImCommandSet {

    private ImCommandSet() {
    }

    /** service.go L862-866 的注册序。 */
    public static void registerDefaults(CommandRegistry registry,
            KnowledgeBaseLister kbService, KnowledgeSearcher searchService) {
        registry.register(new HelpCommand(registry));
        registry.register(new InfoCommand(kbService));
        registry.register(new SearchCommand(searchService, kbService));
        registry.register(new StopCommand());
        registry.register(new ClearCommand());
    }

    /** cmd_info/cmd_search 的 KB 读取面（Go interfaces.KnowledgeBaseService 子集）。 */
    public interface KnowledgeBaseLister {
        /** 对照 ListKnowledgeBases（org 视角全集）。 */
        List<KbView> listKnowledgeBases();

        /** 对照 ListKnowledgeBasesByTenantID。 */
        List<KbView> listKnowledgeBasesByTenantId(long tenantId);

        record KbView(String id, String name, ToolCapabilities.KbCaps capabilities) {
        }
    }

    /** /search 的检索面（Go interfaces.SessionService.SearchKnowledge）。 */
    public interface KnowledgeSearcher {
        List<SearchHit> searchKnowledge(List<String> kbIds, List<String> knowledgeIds,
                List<String> documentIds, String query);

        record SearchHit(String content, String knowledgeTitle, String knowledgeFilename,
                double score) {
        }
    }

    /** agent config 的读取视图（Go CustomAgent.Config 的字段语义）。 */
    public static final class AgentCfgView {
        private final JsonNode cfg;

        public AgentCfgView(com.ragagent.agent.management.domain.CustomAgentEntity agent) {
            JsonNode parsed = null;
            if (agent != null && agent.getConfig() != null && !agent.getConfig().isEmpty()) {
                try {
                    parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(agent.getConfig());
                } catch (Exception ignored) {
                    parsed = null;
                }
            }
            this.cfg = parsed;
        }

        public boolean isNull() {
            return cfg == null;
        }

        private String text(String field) {
            return cfg != null && cfg.hasNonNull(field) ? cfg.get(field).asText("") : "";
        }

        private List<String> strings(String field) {
            List<String> out = new ArrayList<>();
            if (cfg != null && cfg.has(field) && cfg.get(field).isArray()) {
                for (JsonNode n : cfg.get(field)) {
                    out.add(n.asText(""));
                }
            }
            return out;
        }

        /** 对照 IsAgentMode：Config.AgentMode == AgentModeSmartReasoning。 */
        public boolean isAgentMode() {
            return "smart-reasoning".equals(text("agentMode"));
        }

        public String kbSelectionMode() {
            return text("kbSelectionMode");
        }

        public List<String> knowledgeBases() {
            return strings("knowledgeBases");
        }

        public List<String> allowedTools() {
            return strings("allowedTools");
        }

        public String agentMode() {
            return text("agentMode");
        }

        public String skillsSelectionMode() {
            return text("skillsSelectionMode");
        }

        public List<String> selectedSkills() {
            return strings("selectedSkills");
        }

        public String mcpSelectionMode() {
            return text("mcpSelectionMode");
        }

        public List<String> mcpServices() {
            return strings("mcpServices");
        }

        public boolean webSearchEnabled() {
            return cfg != null && cfg.hasNonNull("webSearchEnabled")
                    && cfg.get("webSearchEnabled").asBoolean(false);
        }
    }

    // ── /help（cmd_help.go 全文） ────────────────────────────────────────

    public static final class HelpCommand implements ImCommand {
        private final CommandRegistry registry;

        HelpCommand(CommandRegistry registry) {
            this.registry = registry;
        }

        @Override
        public String name() {
            return "help";
        }

        @Override
        public String description() {
            return "显示可用指令列表，或查看某个指令的详细用法";
        }

        @Override
        public CommandResult execute(CommandContext cmdCtx, List<String> args) {
            // /help <command>：单命令详细用法
            if (args != null && !args.isEmpty()) {
                String name = args.get(0).toLowerCase();
                Commands.ParseResult parsed = registry.parse("/" + name);
                if (!parsed.matched()) {
                    return new CommandResult("未知指令 `" + args.get(0)
                            + "`，发送 `/help` 查看所有可用指令。");
                }
                return new CommandResult("**/" + parsed.command().name() + "** — "
                        + parsed.command().description());
            }

            // /help：按名字排序列出全部
            List<ImCommand> cmds = new ArrayList<>(registry.all());
            cmds.sort(Comparator.comparing(ImCommand::name));

            StringBuilder sb = new StringBuilder();
            sb.append("**可用指令**\n\n");
            for (ImCommand cmd : cmds) {
                sb.append("· `/").append(cmd.name()).append("` — ").append(cmd.description()).append('\n');
            }
            sb.append("\n发送 `/help <指令名>` 查看详细用法");
            return new CommandResult(sb.toString());
        }
    }

    // ── /clear（cmd_clear.go 全文） ──────────────────────────────────────

    public static final class ClearCommand implements ImCommand {
        @Override
        public String name() {
            return "clear";
        }

        @Override
        public String description() {
            return "清空对话记忆，下次消息将开始全新会话";
        }

        @Override
        public CommandResult execute(CommandContext cmdCtx, List<String> args) {
            return new CommandResult("✅ 对话已清空，下次消息将开始全新会话。", Commands.ACTION_CLEAR);
        }
    }

    // ── /stop（cmd_stop.go 全文） ────────────────────────────────────────

    public static final class StopCommand implements ImCommand {
        @Override
        public String name() {
            return "stop";
        }

        @Override
        public String description() {
            return "中止当前正在进行的回答";
        }

        @Override
        public CommandResult execute(CommandContext cmdCtx, List<String> args) {
            return new CommandResult("✅ 已请求中止当前回答。", Commands.ACTION_STOP);
        }
    }

    // ── /info（cmd_info.go 全文） ────────────────────────────────────────

    public static final class InfoCommand implements ImCommand {
        private final KnowledgeBaseLister kbService;

        InfoCommand(KnowledgeBaseLister kbService) {
            this.kbService = kbService;
        }

        @Override
        public String name() {
            return "info";
        }

        @Override
        public String description() {
            return "查看当前智能体的信息与能力";
        }

        @Override
        public CommandResult execute(CommandContext cmdCtx, List<String> args) {
            StringBuilder sb = new StringBuilder();

            // 注意：飞书卡片的 markdown 只有 **粗体** 独占整段才渲染；"**label：**value"
            // 同行会显示原始星号。粗体文本永远独占一行。

            // ── 头部 ──
            String name = cmdCtx.agentName;
            if (name.isEmpty()) {
                name = "未命名智能体";
            }
            sb.append("🤖 **").append(name).append("**\n");
            AgentCfgView cfgView = new AgentCfgView(cmdCtx.customAgent);
            if (cmdCtx.customAgent != null && cmdCtx.customAgent.getDescription() != null
                    && !cmdCtx.customAgent.getDescription().isEmpty()) {
                sb.append("> ").append(cmdCtx.customAgent.getDescription()).append('\n');
            }

            if (cmdCtx.customAgent == null) {
                sb.append("\n未绑定智能体，发送 `/help` 查看可用指令。");
                return new CommandResult(sb.toString());
            }

            // ── 模式 ──
            if (cfgView.isAgentMode()) {
                sb.append("\n🧠 **Agent模式**\n");
                sb.append("支持多步思考、工具调用（ReAct）\n");
            } else {
                sb.append("\n🧠 **Agent模式**\n");
                sb.append("基于知识库检索直接回答（RAG）\n");
            }

            // ── 知识库 ──
            // KBSelectionMode："all" 用租户下每个 KB（IDs 空），"selected" 用显式
            // knowledge_bases 列表，"none"/空 = 禁用。
            sb.append("\n📚 **知识库**\n");
            if (cfgView.kbSelectionMode().equals("all")) {
                List<KnowledgeBaseLister.KbView> kbs = kbService == null ? List.of()
                        : kbService.listKnowledgeBasesByTenantId(cmdCtx.tenantId);
                if (!kbs.isEmpty()) {
                    for (KnowledgeBaseLister.KbView kb : kbs) {
                        sb.append("  · ").append(kb.name()).append('\n');
                    }
                    sb.append("  共 ").append(kbs.size()).append(" 个（全部启用）\n");
                } else {
                    sb.append("  全部启用\n");
                }
            } else if (!cfgView.knowledgeBases().isEmpty()) {
                List<KnowledgeBaseLister.KbView> kbs = kbService == null ? List.of()
                        : kbService.listKnowledgeBasesByTenantId(cmdCtx.tenantId);
                java.util.Map<String, String> nameMap = new java.util.HashMap<>();
                for (KnowledgeBaseLister.KbView kb : kbs) {
                    nameMap.put(kb.id(), kb.name());
                }
                for (String id : cfgView.knowledgeBases()) {
                    sb.append("  · ").append(nameMap.getOrDefault(id, id)).append('\n');
                }
            } else {
                sb.append("  未配置\n");
            }

            // ── Skills ──
            sb.append("\n⚡ **Skills**\n");
            if (cfgView.skillsSelectionMode().equals("all")) {
                sb.append("  全部启用\n");
            } else if (cfgView.skillsSelectionMode().equals("selected")
                    && !cfgView.selectedSkills().isEmpty()) {
                for (String s : cfgView.selectedSkills()) {
                    sb.append("  · ").append(s).append('\n');
                }
            } else {
                sb.append("  未配置\n");
            }

            // ── MCP ──
            sb.append("\n🔌 **MCP 服务**\n");
            if (cfgView.mcpSelectionMode().equals("all")) {
                sb.append("  全部接入\n");
            } else if (cfgView.mcpSelectionMode().equals("selected")
                    && !cfgView.mcpServices().isEmpty()) {
                sb.append("  已接入 ").append(cfgView.mcpServices().size()).append(" 个服务\n");
            } else {
                sb.append("  未配置\n");
            }

            // ── 网络搜索 ──
            sb.append("\n🌐 **网络搜索**\n");
            if (cfgView.webSearchEnabled()) {
                sb.append("  已启用\n");
            } else {
                sb.append("  未启用\n");
            }

            // ── 尾部 ──
            String outputLabel = "流式输出";
            if ("full".equals(cmdCtx.channelOutputMode)) {
                outputLabel = "完整输出";
            }
            sb.append("\n⚙️ **输出模式**\n  ").append(outputLabel).append('\n');
            sb.append("\n---\n发送 `/help` 查看所有可用指令");

            return new CommandResult(sb.toString());
        }
    }

    // ── /search（cmd_search.go 全文） ────────────────────────────────────

    static final int SEARCH_MAX_RESULTS = 5;
    static final int SEARCH_CONTENT_MAX_LEN = 200; // 每条结果显示的 rune 数

    public static final class SearchCommand implements ImCommand {
        private final KnowledgeSearcher sessionService;
        private final KnowledgeBaseLister kbService;

        SearchCommand(KnowledgeSearcher sessionService, KnowledgeBaseLister kbService) {
            this.sessionService = sessionService;
            this.kbService = kbService;
        }

        @Override
        public String name() {
            return "search";
        }

        @Override
        public String description() {
            return "直接检索知识库原文（不经 AI 总结），例如：/search 退款政策";
        }

        @Override
        public CommandResult execute(CommandContext cmdCtx, List<String> args) throws Exception {
            if (args == null || args.isEmpty()) {
                return new CommandResult("请输入搜索内容，例如：`/search 退款政策`");
            }

            String query = String.join(" ", args);

            // 解析要搜的 KB，镜像 QA 管线的 resolveKnowledgeBasesFromAgent，
            // 让 /search 与 agent 工具实际可达范围一致。
            List<String> kbIds = new ArrayList<>();
            AgentCfgView cfgView = new AgentCfgView(cmdCtx.customAgent);
            if (cmdCtx.customAgent != null) {
                switch (cfgView.kbSelectionMode()) {
                    case "all" -> {
                        List<KnowledgeBaseLister.KbView> allKBs = kbService == null ? List.of()
                                : kbService.listKnowledgeBases();
                        // 与 QA 管线 `all` 分支相同的能力过滤；agent 模式感知
                        // （quick-answer 只允许 RAG 型 KB）。
                        String agentMode = cfgView.agentMode();
                        List<String> allowed = cfgView.allowedTools();
                        ToolCapabilities.KbFilter filter =
                                ToolCapabilities.deriveKbFilterForAgent(agentMode, allowed);
                        int skipped = 0;
                        for (KnowledgeBaseLister.KbView kb : allKBs) {
                            if (!filter.isEmpty() && !ToolCapabilities.kbSatisfiesAgentRequirements(
                                    kb.capabilities(), agentMode, allowed)) {
                                skipped++;
                                continue;
                            }
                            kbIds.add(kb.id());
                        }
                        if (skipped > 0) {
                            // Go logger.Infof("/search(agent=%s, mode=all): capability filter removed %d of %d KBs")
                            // 日志不做契约，省略。
                        }
                    }
                    case "none" -> {
                        // 未配置知识库——结果为空。
                    }
                    case "selected" -> kbIds.addAll(cfgView.knowledgeBases());
                    default -> kbIds.addAll(cfgView.knowledgeBases()); // 兼容：回落配置列表
                }
            }

            List<KnowledgeSearcher.SearchHit> results = sessionService == null ? List.of()
                    : sessionService.searchKnowledge(kbIds, null, null, query);

            if (results.isEmpty()) {
                return new CommandResult("未在知识库中找到与「" + query + "」相关的内容。");
            }

            List<KnowledgeSearcher.SearchHit> shown = results;
            if (shown.size() > SEARCH_MAX_RESULTS) {
                shown = shown.subList(0, SEARCH_MAX_RESULTS);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🔍 **搜索「").append(query).append("」** — 找到 ")
                    .append(results.size()).append(" 条结果\n\n");

            for (int i = 0; i < shown.size(); i++) {
                KnowledgeSearcher.SearchHit r = shown.get(i);
                String content = r.content() == null ? "" : r.content();
                String suffix = "";
                if (content.codePointCount(0, content.length()) > SEARCH_CONTENT_MAX_LEN) {
                    content = content.substring(0,
                            content.offsetByCodePoints(0, SEARCH_CONTENT_MAX_LEN));
                    suffix = "…";
                }

                String source = r.knowledgeTitle();
                if (source == null || source.isEmpty()) {
                    source = r.knowledgeFilename();
                }

                sb.append("**[").append(i + 1).append("]** ").append(source).append("\n> ")
                        .append(content).append(suffix).append('\n');

                if (r.score() > 0) {
                    sb.append("匹配度：").append(Math.round(r.score() * 100)).append("%\n");
                }
                sb.append('\n');
            }

            if (results.size() > SEARCH_MAX_RESULTS) {
                sb.append("_（仅显示前 ").append(SEARCH_MAX_RESULTS).append(" 条，共 ")
                        .append(results.size()).append(" 条）_");
            }

            return new CommandResult(sb.toString());
        }
    }
}
