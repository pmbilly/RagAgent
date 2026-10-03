
package com.ragagent.session.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.storage.support.FileService;
import com.ragagent.storage.support.Rewriter;
import com.ragagent.storage.support.Mode;

/**
 * 消息历史响应的存储引用重写（MessageReferenceRewriter）。
 *
 * <p>2026-09-30 从 storage/support/RewriterTest 搬来：被测代码搬到了会话侧（消 session ⇄ storage 环），
 * 用例随之搬家，断言逐字保留。</p>
 */
class MessageReferenceRewriterTest {

    /** 把 URL 重写内核包成消息级重写器。 */
    private static MessageReferenceRewriter wrap(Rewriter rewriter) {
        return new MessageReferenceRewriter(rewriter);
    }

    /** 固定解析：任何输入都返回同一 URL（与原 storage 侧用例的 StubFileService 等价）。 */
    private static final class StubFileService implements FileService {
        private final String url;

        StubFileService(String url) {
            this.url = url;
        }

        @Override
        public String getFileURL(String filePath) {
            return url;
        }
    }

    private static Rewriter stubRewriter(String url) {
        return Rewriter.forRequest(Mode.PUBLIC, null, new StubFileService(url), null);
    }

    @Test
    void rewriteMessagesCoversEveryFieldGoTouches() {
        MessageReferenceRewriter w = wrap(stubRewriter("https://cdn.example.com/x.png"));
        Message message = fullMessage();
        List<Message> messages = new ArrayList<>();
        messages.add(null);
        messages.add(message);

        w.rewriteMessages(messages);

        assertThat(message.getContent()).isEqualTo("answer ![fig](https://cdn.example.com/x.png)");
        assertThat(message.getImages().get(0).getUrl()).isEqualTo("https://cdn.example.com/x.png");
        assertThat(message.getImages().get(0).getCaption())
                .isEqualTo("shows ![inline](https://cdn.example.com/x.png)");
        assertThat(message.getKnowledgeReferences().get(0).getContent())
                .isEqualTo("chunk ![c](https://cdn.example.com/x.png)");
        assertThat(message.getKnowledgeReferences().get(0).getImageInfo())
                .isEqualTo("[{\"url\":\"https://cdn.example.com/x.png\"}]");
        assertThat(message.getAgentSteps().get(0).getThought())
                .isEqualTo("looking at ![t](https://cdn.example.com/x.png)");
        assertThat(message.getAgentSteps().get(0).getToolCalls().get(0).getReflection())
                .isEqualTo("saw ![r](https://cdn.example.com/x.png)");
        assertThat(message.getAgentSteps().get(0).getToolCalls().get(0).getResult().getOutput())
                .isEqualTo("chart ![o](https://cdn.example.com/x.png)");
    }

    @Test
    void rewriteMessagesDisabledLeavesHandles() {
        MessageReferenceRewriter w = wrap(new Rewriter(null, "TEST"));
        Message message = new Message();
        message.setContent("![a](resource://xifDo7NTSL300Lp1goVutw)");

        w.rewriteMessages(new ArrayList<>(List.of(message)));

        assertThat(message.getContent()).isEqualTo("![a](resource://xifDo7NTSL300Lp1goVutw)");
    }

    @Test
    void rewriteMessagesResponseDoesNotMutateOriginals() {
        MessageReferenceRewriter w = wrap(stubRewriter("https://cdn.example.com/x.png"));
        Message original = fullMessage();
        List<Message> messages = new ArrayList<>(List.of(original));

        List<Message> out = w.rewriteMessagesResponse(messages);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isNotSameAs(original);
        assertThat(original.getContent()).isEqualTo("answer ![fig](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out.get(0).getContent()).isEqualTo("answer ![fig](https://cdn.example.com/x.png)");
        assertThat(original.getKnowledgeReferences().get(0).getContent())
                .isEqualTo("chunk ![c](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out.get(0).getKnowledgeReferences().get(0).getContent())
                .isEqualTo("chunk ![c](https://cdn.example.com/x.png)");
        // agent steps 也必须解耦：改写不得污染缓存的原始消息
        assertThat(original.getAgentSteps().get(0).getThought())
                .isEqualTo("looking at ![t](resource://xifDo7NTSL300Lp1goVutw)");
    }

    @Test
    void rewriteMessagesResponseDisabledOrEmptyReturnsInput() {
        assertThat(wrap(new Rewriter(null, "TEST")).rewriteMessagesResponse(List.of())).isEmpty();
        assertThat(wrap(stubRewriter("https://x/y.png")).rewriteMessagesResponse(null)).isNull();
        List<Message> messages = List.of(fullMessage());
        assertThat(wrap(new Rewriter(null, "TEST")).rewriteMessagesResponse(messages)).isSameAs(messages);
    }

    /** 一条把每个被重写的字段都填上的助手消息。 */
    private static Message fullMessage() {
        Message message = new Message();
        message.setContent("answer ![fig](resource://xifDo7NTSL300Lp1goVutw)");

        MessageImage image = new MessageImage();
        image.setUrl("resource://aaaabbbbccccddddeeeeff");
        image.setCaption("shows ![inline](minio://bucket/10000/exports/a.png)");
        message.setImages(new ArrayList<>(List.of(image)));

        SearchResult ref = new SearchResult();
        ref.setContent("chunk ![c](resource://xifDo7NTSL300Lp1goVutw)");
        ref.setImageInfo("[{\"url\":\"resource://xifDo7NTSL300Lp1goVutw\"}]");
        message.setKnowledgeReferences(new ArrayList<>(List.of(ref)));

        ToolResult toolResult = new ToolResult();
        toolResult.setOutput("chart ![o](resource://xifDo7NTSL300Lp1goVutw)");
        ToolCall call = new ToolCall();
        call.setReflection("saw ![r](resource://xifDo7NTSL300Lp1goVutw)");
        call.setResult(toolResult);

        AgentStep step = new AgentStep();
        step.setThought("looking at ![t](resource://xifDo7NTSL300Lp1goVutw)");
        step.setToolCalls(List.of(call));
        message.setAgentSteps(new ArrayList<>(List.of(step)));
        return message;
    }
}
