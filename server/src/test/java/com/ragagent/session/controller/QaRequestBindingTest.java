package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.ragagent.common.error.BizException;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.dto.QaRequests.SearchKnowledgeRequest;

/**
 * QA 请求面绑定契约（§14.9l S4）：键名＝Java 字段名（camelCase），旧 snake 键静默失效；
 * 绑定错误文案**保持 Go 措辞**（错误形态统一是独立批次）。
 *
 * <p>纯绑定单测（不起 Spring）：HTTP 层的连通性由真实服务冒烟覆盖；这里钉住的是
 * "哪些键会被读到"这条契约——它没有别的守卫，写错了整条问答链路会静默丢作用域。</p>
 */
class QaRequestBindingTest {

    private static final String FULL_BODY = """
            {
              "query": "检索问题",
              "knowledgeBaseIds": ["kb-1"],
              "knowledgeIds": ["k-1"],
              "agentEnabled": true,
              "agentId": "agent-1",
              "agentSourceTenantId": 10002,
              "webSearchEnabled": true,
              "summaryModelId": "model-1",
              "mcpServiceIds": ["mcp-1"],
              "skillNames": ["skill-1"],
              "tagIds": ["tag-1"],
              "mentionedItems": [{"id": "kb-1", "name": "库", "type": "kb",
                                  "kbType": "faq", "kbId": "kb-1", "kbName": "库",
                                  "serviceId": "mcp-1", "skillName": "skill-1"}],
              "disableTitle": true,
              "images": [{"data": "data:image/png;base64,AAA", "caption": "图"}],
              "attachmentUploads": [{"data": "QUJD", "fileName": "a.txt", "fileSize": 3}],
              "attachmentIds": ["att-1"],
              "channel": "web",
              "suggestionAttribution": {"suggestionSetId": "set-1", "questionId": "q-1"}
            }
            """;

    @Test
    void createRequestReadsCamelCaseKeys() {
        CreateKnowledgeQARequest req = QaRequestBinder.bindQaRequest(FULL_BODY);

        assertThat(req.query).isEqualTo("检索问题");
        assertThat(req.knowledgeBaseIds()).containsExactly("kb-1");
        assertThat(req.knowledgeIds()).containsExactly("k-1");
        assertThat(req.agentEnabled).isTrue();
        assertThat(req.agentId).isEqualTo("agent-1");
        assertThat(req.agentSourceTenantId).isEqualTo(10002L);
        assertThat(req.webSearchEnabled).isTrue();
        assertThat(req.summaryModelId).isEqualTo("model-1");
        assertThat(req.mcpServiceIds()).containsExactly("mcp-1");
        assertThat(req.skillNames()).containsExactly("skill-1");
        assertThat(req.tagIds()).containsExactly("tag-1");
        assertThat(req.mentionedItems()).hasSize(1);
        var mention = req.mentionedItems().get(0);
        assertThat(mention.type).isEqualTo("kb");
        assertThat(mention.kbType).isEqualTo("faq");
        assertThat(mention.kbId).isEqualTo("kb-1");
        assertThat(mention.kbName).isEqualTo("库");
        assertThat(mention.serviceId).isEqualTo("mcp-1");
        assertThat(mention.skillName).isEqualTo("skill-1");
        assertThat(req.disableTitle).isTrue();
        assertThat(req.images()).hasSize(1);
        assertThat(req.images().get(0).caption).isEqualTo("图");
        assertThat(req.attachmentUploads()).hasSize(1);
        assertThat(req.attachmentUploads().get(0).fileName).isEqualTo("a.txt");
        assertThat(req.attachmentUploads().get(0).fileSize).isEqualTo(3L);
        assertThat(req.attachmentIds()).containsExactly("att-1");
        assertThat(req.channel).isEqualTo("web");
        assertThat(req.suggestionAttribution.path("suggestionSetId").asText()).isEqualTo("set-1");
        assertThat(req.suggestionAttribution.path("questionId").asText()).isEqualTo("q-1");
    }

    @Test
    void searchRequestReadsCamelCaseKeys() {
        SearchKnowledgeRequest req = QaRequestBinder.bindSearchRequest("""
                {"query": "q", "knowledgeBaseId": "kb-1", "knowledgeBaseIds": ["kb-2"],
                 "knowledgeIds": ["k-1"], "tagIds": ["t-1"],
                 "mentionedItems": [{"id": "tag-1", "type": "tag"}]}
                """);

        assertThat(req.knowledgeBaseId).isEqualTo("kb-1");
        assertThat(req.knowledgeBaseIds()).containsExactly("kb-2");
        assertThat(req.knowledgeIds()).containsExactly("k-1");
        assertThat(req.tagIds()).containsExactly("t-1");
        assertThat(req.mentionedItems()).hasSize(1);
    }

    /**
     * 旧 snake 键**不再映射**（{@code @JsonIgnoreProperties} 静默忽略）——这是换锚的必要代价：
     * 客户端必须同批改造。此处把"静默"钉成显式断言，避免以后误以为还能兼容读。
     */
    @Test
    void legacySnakeKeysAreIgnoredNotMapped() {
        CreateKnowledgeQARequest req = QaRequestBinder.bindQaRequest("""
                {"query": "q", "knowledge_base_ids": ["kb-1"], "agent_enabled": true,
                 "mentioned_items": [{"id": "kb-1", "type": "kb", "kb_type": "faq"}],
                 "attachment_uploads": [{"data": "QUJD", "file_name": "a.txt", "file_size": 3}],
                 "suggestion_attribution": {"suggestionSetId": "set-1", "questionId": "q-1"}}
                """);

        assertThat(req.knowledgeBaseIds()).isEmpty();
        assertThat(req.agentEnabled).isFalse();
        assertThat(req.mentionedItems()).isEmpty();
        assertThat(req.attachmentUploads()).isEmpty();
        assertThat(req.suggestionAttribution).isNull();
    }

    /** 必填校验文案仍是 Go 措辞（`Key: '<结构体>.<字段>' … on the 'required' tag`）。 */
    @Test
    void requiredErrorKeepsGoWording() {
        assertThatThrownBy(() -> QaRequestBinder.bindQaRequest("{\"query\":\"\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Key: 'CreateKnowledgeQARequest.Query' Error:Field validation"
                        + " for 'Query' failed on the 'required' tag");
        assertThatThrownBy(() -> QaRequestBinder.bindSearchRequest("{}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Key: 'SearchKnowledgeRequest.Query'");
    }

    /** 字段级类型错误里的 json 名随之变成 camelCase（值类型仍是 Go 的 uint64）。 */
    @Test
    void fieldTypeErrorUsesCamelCaseJsonName() {
        assertThatThrownBy(() -> QaRequestBinder.bindQaRequest(
                "{\"query\":\"q\",\"agentSourceTenantId\":\"not-a-number\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("json: cannot unmarshal string into Go struct field"
                        + " CreateKnowledgeQARequest.agentSourceTenantId of type uint64");
    }

    /**
     * 畸形 body 的措辞沿用 GoJsonBindError（报文层文案未动）：
     * 空体 → Go 的 {@code EOF}；深层结构坏掉 → 按既定口径回落 Jackson 措辞
     * （已知差异，见 GoJsonBindError 类注释）。
     */
    @Test
    void malformedBodyKeepsGoJsonWording() {
        assertThatThrownBy(() -> QaRequestBinder.bindQaRequest(""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("EOF");
        assertThatThrownBy(() -> QaRequestBinder.bindQaRequest("not-json"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("invalid character 'o' in literal null (expecting 'u')");
        assertThatThrownBy(() -> QaRequestBinder.bindQaRequest("{\"query\":"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Unexpected end-of-input");
    }
}
