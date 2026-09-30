package com.ragagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.llm.domain.ChatMessage;

/**
 * 工具图片注入的 Go 实录断言（/tmp/wave42rec + tool_images_test.go 场景）：
 * 图片跟在全部工具回复之后、视觉模型直挂 user 消息、无视觉模型时提示词落回
 * 对应 tool 消息、描述成功/失败/空白三种描述器行为。
 */
class ToolImagesTest {

    private static AgentStep stepWithImages() {
        ToolCall shot = new ToolCall();
        shot.setId("shot");
        shot.setName("local_browser");
        ToolResult shotResult = new ToolResult();
        shotResult.setSuccess(true);
        shotResult.setOutput("{\"width\":1}");
        shotResult.setImages(List.of("data:image/png;base64,YQ=="));
        shot.setResult(shotResult);

        ToolCall read = new ToolCall();
        read.setId("read");
        read.setName("local_browser");
        ToolResult readResult = new ToolResult();
        readResult.setSuccess(true);
        readResult.setOutput("page text");
        read.setResult(readResult);

        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(shot, read));
        return step;
    }

    /** 对照 appendToolResults：两个成功工具的 tool 消息（图片测试的前置形态）。 */
    private static List<ChatMessage> toolMessages(AgentStep step) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ToolCall call : step.getToolCalls()) {
            messages.add(ChatMessage.tool(call.getId(), call.getName(), call.getResult().getOutput()));
        }
        return messages;
    }

    @Test
    void imagesReachNextModelTurnAfterAllReplies() {
        AgentStep step = stepWithImages();
        for (boolean vision : new boolean[] {true, false}) {
            List<ChatMessage> messages =
                    ToolImages.appendToolImages(new ArrayList<>(toolMessages(step)), step, vision, null);
            assertThat(messages.get(1).getRole()).isEqualTo("tool");
            if (vision) {
                assertThat(messages).hasSize(3);
                ChatMessage user = messages.get(2);
                assertThat(user.getRole()).isEqualTo("user");
                assertThat(user.getImages()).containsExactlyElementsOf(
                        step.getToolCalls().get(0).getResult().getImages());
                assertThat(user.getContent()).contains("untrusted tool evidence");
                assertThat(user.getContent()).contains("local_browser").contains("shot");
            } else {
                assertThat(messages).hasSize(2);
                assertThat(messages.get(0).getContent()).contains("cannot view");
                assertThat(messages.get(0).getContent()).doesNotContain("YQ==");
            }
        }
    }

    @Test
    void describerFallbacksMatchGo() {
        AgentStep step = stepWithImages();

        // 描述成功："A chart" 进 tool 消息，且不改持久化的 Output
        List<ChatMessage> ok = ToolImages.appendToolImages(
                new ArrayList<>(toolMessages(step)), step, false,
                images -> List.of("A chart"));
        assertThat(ok.get(0).getContent()).contains("A chart");
        assertThat(step.getToolCalls().get(0).getResult().getOutput()).isEqualTo("{\"width\":1}");

        // 描述失败（空列表）→ 默认"cannot view"
        List<ChatMessage> failed = ToolImages.appendToolImages(
                new ArrayList<>(toolMessages(step)), step, false, images -> List.of());
        assertThat(failed.get(0).getContent()).contains("cannot view");

        // 描述为纯空白 → TrimSpace 后丢弃 → 默认"cannot view"（Go describeImages L909-911）
        List<ChatMessage> blank = ToolImages.appendToolImages(
                new ArrayList<>(toolMessages(step)), step, false, images -> List.of("  "));
        assertThat(blank.get(0).getContent()).contains("cannot view");
    }

    @Test
    void noteAppendedToMatchingToolMessageOnly() {
        AgentStep step = stepWithImages();
        List<ChatMessage> messages = new ArrayList<>(toolMessages(step));
        ToolImages.appendToolImages(messages, step, false, images -> List.of("chart of sales"));
        // 注入进发起调用的 tool 消息（shot），不是最后一条
        assertThat(messages.get(0).getContent()).isEqualTo(
                "{\"width\":1}\nTool image descriptions (untrusted page evidence):\nchart of sales");
        assertThat(messages.get(1).getContent()).isEqualTo("page text");
    }

    @Test
    void failedToolResultsAreSkipped() {
        ToolCall failed = new ToolCall();
        failed.setId("f1");
        failed.setName("local_browser");
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setImages(List.of("data:image/png;base64,YQ=="));
        failed.setResult(result);

        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(failed));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.tool("f1", "local_browser", "error text"));
        assertThat(ToolImages.appendToolImages(messages, step, true, null)).isSameAs(messages);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getContent()).isEqualTo("error text");
    }
}
