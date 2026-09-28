package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.service.AgentResolver;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.TemporaryDocumentService;

/**
 * 附件上传入口的 agent 语义验收（对照 Go UploadTemporaryDocument，L19-93）：
 * 共享 agent 命中/未命中（404）/门控（supported_file_types、音频 ASR）/
 * parser_engine 绑定与 agent 级回落 / resource_tenant_id 写入。
 */
class TemporaryDocumentUploadContractTest {

    private static final long TENANT = 1L;

    private SessionService sessionService;
    private TemporaryDocumentService temporaryDocuments;
    private AgentResolver agentResolver;
    private TemporaryDocumentController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        temporaryDocuments = mock(TemporaryDocumentService.class);
        agentResolver = mock(AgentResolver.class);
        controller = new TemporaryDocumentController(sessionService, temporaryDocuments,
                agentResolver);
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MockHttpServletRequest multipartRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("multipart/form-data; boundary=x");
        return request;
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream",
                "hello".getBytes());
    }

    private static CustomAgentEntity agent(String configJson) {
        CustomAgentEntity a = new CustomAgentEntity();
        a.setId("a-1");
        a.setName("shared-agent");
        a.setTenantId(42L);
        a.setConfig(configJson);
        return a;
    }

    private void stubCreate() {
        when(temporaryDocuments.create(anyLong(), anyString(), any(), any(), anyLong(), any(),
                any())).thenReturn(new TemporaryDocument());
    }

    private TemporaryDocumentService.CreateOptions capturedOptions() {
        ArgumentCaptor<TemporaryDocumentService.CreateOptions> captor =
                ArgumentCaptor.forClass(TemporaryDocumentService.CreateOptions.class);
        verify(temporaryDocuments).create(eq(TENANT), eq("s-1"), any(), any(), anyLong(), any(),
                captor.capture());
        return captor.getValue();
    }

    @Test
    void missingSharedAgentIs404() {
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(null, 0, false));

        assertThatThrownBy(() -> controller.upload("s-1", file("notes.txt"), "42", "a-1", "",
                multipartRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Shared agent not found");
    }

    @Test
    void invalidSourceTenantIs400WithGoText() {
        assertThatThrownBy(() -> controller.upload("s-1", file("notes.txt"), "42abc", "a-1", "",
                multipartRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("invalid agent_source_tenant_id: strconv.ParseUint");
    }

    @Test
    void sharedAgentUploadsWithResourceTenantAnd202() {
        CustomAgentEntity shared = agent("{}");
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(shared, 42L, true));
        stubCreate();

        var response = controller.upload("s-1", file("notes.txt"), "42", "a-1", "",
                multipartRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(capturedOptions().resourceTenantId()).isEqualTo(42L);
    }

    @Test
    void unsupportedFileTypeForAgentIs400() {
        CustomAgentEntity shared = agent("{\"supported_file_types\":[\"pdf\"]}");
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(shared, 42L, true));

        assertThatThrownBy(() -> controller.upload("s-1", file("notes.txt"), "42", "a-1", "",
                multipartRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("file type is not supported by this agent");
    }

    @Test
    void supportedFileTypeMatchesWithDotAndCase() {
        CustomAgentEntity shared = agent("{\"supported_file_types\":[\".TXT\"]}");
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(shared, 42L, true));
        stubCreate();

        var response = controller.upload("s-1", file("notes.txt"), "42", "a-1", "",
                multipartRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void audioWithoutAsrConfigIs400() {
        CustomAgentEntity shared = agent("{\"audio_upload_enabled\":false}");
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(shared, 42L, true));

        assertThatThrownBy(() -> controller.upload("s-1", file("voice.mp3"), "42", "a-1", "",
                multipartRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("audio upload is not enabled or no ASR model is configured");
    }

    @Test
    void audioWithAsrConfigWritesAsrModelId() {
        CustomAgentEntity shared = agent(
                "{\"audio_upload_enabled\":true,\"asr_model_id\":\"asr-1\"}");
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(shared, 42L, true));
        stubCreate();

        controller.upload("s-1", file("voice.mp3"), "42", "a-1", "", multipartRequest());

        assertThat(capturedOptions().asrModelId()).isEqualTo("asr-1");
    }

    @Test
    void agentParserRuleFillsEngineWhenNotExplicit() {
        CustomAgentEntity shared = agent(
                "{\"chat_parser_engine_rules\":[{\"file_types\":[\"txt\"],\"engine\":\"markitdown\"}]}");
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(shared, 42L, true));
        stubCreate();

        controller.upload("s-1", file("notes.txt"), "42", "a-1", "auto", multipartRequest());

        assertThat(capturedOptions().parserEngine()).isEqualTo("markitdown");
    }

    @Test
    void explicitParserEngineIsKept() {
        CustomAgentEntity shared = agent(
                "{\"chat_parser_engine_rules\":[{\"file_types\":[\"txt\"],\"engine\":\"markitdown\"}]}");
        when(agentResolver.resolve("a-1", 42L))
                .thenReturn(new AgentResolver.ResolvedAgent(shared, 42L, true));
        stubCreate();

        controller.upload("s-1", file("notes.txt"), "42", "a-1", " simple ", multipartRequest());

        assertThat(capturedOptions().parserEngine()).isEqualTo("simple");
    }

    @Test
    void withoutAgentKeepsCallerScopeAndEmptyOptions() {
        when(agentResolver.resolve(null, 0)).thenReturn(new AgentResolver.ResolvedAgent(null, 0, false));
        stubCreate();

        var response = controller.upload("s-1", file("notes.txt"), null, null, null,
                multipartRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        TemporaryDocumentService.CreateOptions options = capturedOptions();
        assertThat(options.resourceTenantId()).isZero();
        assertThat(options.parserEngine()).isEmpty();
    }
}
