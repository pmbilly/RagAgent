package com.ragagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.agent.domain.AgentState;

/**
 * 回归锚点：{@code llmContext} 传 null 必须当空历史处理——安装器的 installer run 正是这样调的，旧实现在入口日志
 * 就 NPE（{@code Cannot invoke "java.util.List.size()" because "llmContext" is null}），
 * 导致真实 LLM 安装第一次就失败。
 */
class AgentEngineNullContextTest {

    @Test
    @DisplayName("execute(..., llmContext=null)：不 NPE，按空历史跑完一轮")
    void nullContextTreatedAsEmpty() {
        Engine46bStubSupport.StubChat chat = new Engine46bStubSupport.StubChat(List.of(
                List.of(Engine46bStubSupport.msgChunk("answer", true, "stop"))));
        AgentEngine engine = Engine46bStubSupport.newEngine(chat);

        AgentState state = engine.execute("sess-null", "msg-1", "hi", null);

        assertThat(state).isNotNull();
        assertThat(state.getFinalAnswer()).isEqualTo("answer");
        assertThat(chat.callCount).isEqualTo(1);
        // 空历史：出站消息里不含历史消息（仅有 system/本轮 user 段）
        assertThat(chat.callJson.get(0)).doesNotContain("\"history\"");
    }
}
