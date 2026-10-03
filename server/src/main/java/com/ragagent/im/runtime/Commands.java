package com.ragagent.im.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * IM 斜杠命令基建。命令声明意图（Action 枚举），副作用由 Service 执行——
 * 命令本身不碰 DB/服务。
 */
public final class Commands {

    private Commands() {
    }

    // ── CommandAction ─────────────────────────────────────────────────────
    /** 除回复外无副作用。 */
    public static final int ACTION_NONE = 0;
    /** 软删当前 ChannelSession 并清 LLM 上下文，下条消息全新会话。 */
    public static final int ACTION_CLEAR = 1;
    /** 取消该 user+chat 的在途 QA 请求。 */
    public static final int ACTION_STOP = 2;

    /** 命令产出。 */
    public static final class CommandResult {
        /** 发回用户的 Markdown 回复。 */
        public final String content;
        /** 请求的服务级副作用。 */
        public final int action;

        public CommandResult(String content) {
            this(content, ACTION_NONE);
        }

        public CommandResult(String content, int action) {
            this.content = content;
            this.action = action;
        }
    }

    /** 命令运行时数据。服务不放这里。 */
    public static final class CommandContext {
        public IncomingMessage incoming;
        /** IM 渠道会话（user×chat 组合）。命令测试可留空。 */
        public com.ragagent.im.domain.ChannelSessionEntity session;
        public long tenantId;
        /** 绑定 agent 的显示名（未绑定为空）。 */
        public String agentName = "";
        /** 绑定的 agent 配置（可为 null；/search 读 KBSelectionMode）。 */
        public com.ragagent.agent.management.domain.CustomAgentEntity customAgent;
        /** 渠道级输出模式（"stream"/"full"）。 */
        public String channelOutputMode = "";
    }

    /** 每个斜杠命令的接口。 */
    public interface ImCommand {
        /** "/" 后的主 token（如 "kb"、"mode"）。 */
        String name();

        /** /help 里的一行说明。 */
        String description();

        /** 执行并返回回复。校验失败也返回 CommandResult（helpful 文案），error 只留给基础设施故障。 */
        CommandResult execute(CommandContext cmdCtx, List<String> args) throws Exception;
    }

    // ── CommandRegistry ───────────────────────────────────────────────────

    /** 斜杠命令名 → 处理器。 */
    public static final class CommandRegistry {

        private final java.util.Map<String, ImCommand> commands = new java.util.HashMap<>();

        /** 按名字注册；重名 panic——配置错误在启动时爆而不是被静默吞。 */
        public void register(ImCommand cmd) {
            String key = cmd.name().toLowerCase();
            if (commands.containsKey(key)) {
                throw new IllegalStateException("im: duplicate command registration: " + key);
            }
            commands.put(key, cmd);
        }

        /**
         * content 是否是斜杠命令；是则返回命令与余下 token。三种 no-match：
         * 非 "/" 开头、首个 token 无注册处理器。未识别的斜杠词**故意**不匹配，
         * 让调用方决定当未知命令（显示 help）还是落 QA 管线（如 "/api/v2/users"）。
         */
        public ParseResult parse(String content) {
            content = content == null ? "" : content.strip();
            if (!content.startsWith("/")) {
                return ParseResult.NO_MATCH;
            }
            String[] parts = fields(content.substring(1));
            if (parts.length == 0) {
                return ParseResult.NO_MATCH;
            }
            ImCommand cmd = commands.get(parts[0].toLowerCase());
            if (cmd == null) {
                return ParseResult.NO_MATCH;
            }
            List<String> args = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                args.add(parts[i]);
            }
            return new ParseResult(cmd, args, true);
        }

        /** content 是否以已注册命令名开头（比 parse 便宜）。 */
        public boolean isRegistered(String content) {
            content = content == null ? "" : content.strip();
            if (!content.startsWith("/")) {
                return false;
            }
            String[] parts = fields(content.substring(1));
            if (parts.length == 0) {
                return false;
            }
            return commands.containsKey(parts[0].toLowerCase());
        }

        /** 每个已注册命令（map 序；/help 渲染前自行排序）。 */
        public List<ImCommand> all() {
            return new ArrayList<>(commands.values());
        }
    }

    /** parse 的结果三元组：命令、参数、是否命中。 */
    public record ParseResult(ImCommand command, List<String> args, boolean matched) {
        public static final ParseResult NO_MATCH = new ParseResult(null, List.of(), false);
    }

    /**
     * content 是否像命令尝试——"/" 开头且首 token 不再含 "/"。
     * 区分 "/help"（命令尝试）与 "/api/v2/users"（应落 QA 管线的 URL 路径）。
     */
    public static boolean looksLikeCommand(String content) {
        content = content == null ? "" : content.strip();
        if (!content.startsWith("/")) {
            return false;
        }
        String[] parts = fields(content.substring(1));
        if (parts.length == 0) {
            return false;
        }
        return !parts[0].contains("/");
    }

    /** strings.Fields：按任意空白切分、丢空段。 */
    static String[] fields(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("\\s+")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out.toArray(new String[0]);
    }
}
