package com.ragagent.llm.chat;

import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.ChatOptions;

/**
 * thinking 策略的四个实现。
 *
 * 前端 ModelEditorDialog.vue 通过 `parameters.extra_config.thinking_control`
 * 选择策略，取值：none / enable_thinking / thinking_type / chat_template_kwargs。
 */
public final class ThinkingStrategies {

    /** extra_config 里的策略选择键。 */
    public static final String EXTRA_CONFIG_THINKING_CONTROL = "thinking_control";

    private ThinkingStrategies() {
    }

    /** 完全不发送 thinking 相关字段。 */
    public static final class None implements ThinkingStrategy {
        @Override
        public boolean apply(ObjectNode body, ChatOptions opts, boolean isStream) {
            return false; // no-op
        }

        @Override
        public String name() {
            return "none";
        }
    }

    /**
     * Qwen 的顶层 `enable_thinking` 布尔。
     *
     * @param alwaysSend         即使 opts.thinking 为 null 也固定该字段
     *                           （阿里云 Qwen thinking 模型要求每次请求都带，默认 false）
     * @param disableOnNonStream 非流式请求强制 enable_thinking=false（Qwen3 非流式拒绝 thinking）
     */
    public static final class EnableThinking implements ThinkingStrategy {
        private final boolean alwaysSend;
        private final boolean disableOnNonStream;

        public EnableThinking() {
            this(false, false);
        }

        public EnableThinking(boolean alwaysSend, boolean disableOnNonStream) {
            this.alwaysSend = alwaysSend;
            this.disableOnNonStream = disableOnNonStream;
        }

        @Override
        public boolean apply(ObjectNode body, ChatOptions opts, boolean isStream) {
            boolean thinking;
            if (opts != null && opts.getThinking() != null) {
                thinking = opts.getThinking();
            } else if (!alwaysSend) {
                return false;
            } else {
                thinking = false;
            }
            if (disableOnNonStream && !isStream) {
                thinking = false;
            }
            body.put("enable_thinking", thinking);
            return true;
        }

        @Override
        public String name() {
            return "enable_thinking";
        }
    }

    /** LKEAP / 火山引擎的 `{ "thinking": { "type": "enabled"|"disabled" } }`；thinking 未设置时不注入。 */
    public static final class ThinkingTypeField implements ThinkingStrategy {
        @Override
        public boolean apply(ObjectNode body, ChatOptions opts, boolean isStream) {
            if (opts == null || opts.getThinking() == null) {
                return false;
            }
            ObjectNode thinking = body.putObject("thinking");
            thinking.put("type", opts.getThinking() ? "enabled" : "disabled");
            return true;
        }

        @Override
        public String name() {
            return "thinking_type";
        }
    }

    /** vLLM / NVIDIA / 通用本地部署的 `chat_template_kwargs.enable_thinking`；thinking 未设置时不注入。 */
    public static final class ChatTemplateKwargs implements ThinkingStrategy {
        @Override
        public boolean apply(ObjectNode body, ChatOptions opts, boolean isStream) {
            if (opts == null || opts.getThinking() == null) {
                return false;
            }
            body.putObject("chat_template_kwargs").put("enable_thinking", opts.getThinking());
            return true;
        }

        @Override
        public String name() {
            return "chat_template_kwargs";
        }
    }

    /**
     * 读 extra_config.thinking_control 选策略。
     * 未设置（null 或空串）返回 null = 用 provider adapter 的默认策略；
     * 无法识别的非空值回退到 chat_template_kwargs（保持历史默认行为）。
     */
    public static ThinkingStrategy parseThinkingOverride(Map<String, String> extraConfig) {
        if (extraConfig == null) {
            return null;
        }
        String raw = extraConfig.get(EXTRA_CONFIG_THINKING_CONTROL);
        String v = raw == null ? "" : raw.trim().toLowerCase();
        return switch (v) {
            case "" -> null;
            case "none" -> new None();
            case "enable_thinking" -> new EnableThinking();
            case "thinking_type" -> new ThinkingTypeField();
            // "chat_template_kwargs" 及任何未知非空值
            default -> new ChatTemplateKwargs();
        };
    }
}
