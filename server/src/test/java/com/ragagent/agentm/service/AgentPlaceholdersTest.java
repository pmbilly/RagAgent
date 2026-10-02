package com.ragagent.agentm.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.prompt.AgentPromptPlaceholders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 占位符「两面一致」守卫。
 *
 * <p><b>为什么需要它</b>：占位符有两张表、服务两个面——</p>
 * <ul>
 *   <li><b>HTTP 面</b>（本包 {@code AgentPlaceholders}）：给 UI 展示并供用户插入，令牌名是
 *       <b>数据值</b>（{@code P.name}），不是 JSON 键；</li>
 *   <li><b>渲染面</b>（{@link AgentPromptPlaceholders}）：运行时按 {@code {{令牌}}} 做替换。</li>
 * </ul>
 *
 * <p>两面同名才能闭合：UI 给用户插的 {@code {{x}}}，渲染器必须认。两者一旦漂移，症状是
 * <b>用户插了占位符却永远不被替换</b>（不报错，提示词里留一段字面量）。B18 的批量改名正是踩了这个
 * 坑：把 {@code knowledge_bases} 令牌一并改成了 {@code knowledgeBases}，还把契约夹具同步改掉 ⇒
 * 测试全绿、缺陷静默。本守卫按渲染面表逐字校验 HTTP 面表，并逐个验证渲染器真能替换。</p>
 */
class AgentPlaceholdersTest {

    private static final String GROUP_AGENT_SYSTEM_PROMPT = "agentSystemPrompt";

    @Test
    @DisplayName("agentSystemPrompt 组：HTTP 面令牌与渲染面逐字一致，且每个令牌渲染器真能替换")
    void agentSystemPromptTokensAgreeWithRenderer() {
        JsonNode data = new AgentPlaceholders().data();
        List<String> httpSide = names(data.path(GROUP_AGENT_SYSTEM_PROMPT));
        List<String> renderSide = AgentPromptPlaceholders.placeholdersByFieldAgentSystemPrompt().stream()
                .map(AgentPromptPlaceholders.PromptPlaceholder::name)
                .toList();

        assertThat(httpSide)
                .as("HTTP 面（UI 展示/插入）与渲染面的令牌名必须逐字一致——"
                        + "不一致会让用户插入的 {{令牌}} 永远不被替换（B18 曾误改 knowledge_bases）")
                .containsExactlyInAnyOrderElementsOf(renderSide);

        for (String token : httpSide) {
            Map<String, String> vals = new LinkedHashMap<>();
            vals.put(token, "OK");
            assertThat(AgentPromptPlaceholders.renderPromptPlaceholders("{{" + token + "}}", vals))
                    .as("{{" + token + "}} 必须能被渲染器替换")
                    .isEqualTo("OK");
        }
    }

    /** 取某组里的令牌名（name 字段）。 */
    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("name").asText()));
        return out;
    }
}
