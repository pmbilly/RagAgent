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
 * agent 门控（supported_file_types、音频 ASR）/ parser_engine 绑定与
 * agent 级回落。共享 agent 与 agent_source_tenant_id 面随空间分享裁撤。
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
        when(agentResolver.resolve(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new AgentResolver.ResolvedAgent(null, 0L, false));
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
        a.setName("own-agent");
        a.setTenantId(TENANT);
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
    void unsupportedFileTypeForAgentIs400() {
        CustomAgentEntity own = agent("{\"supportedFileTypes\":[\"pdf\"]}");
        when(agentResolver.resolve("a-1", 0L))
                .thenReturn(new AgentResolver.ResolvedAgent(own, 0L, false));

        assertThatThrownBy(() -> controller.upload("s-1", file("notes.txt"), "a-1", "",
                multipartRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("file type is not supported by this agent");
    }

    @Test
    void supportedFileTypeMatchesWithDotAndCase() {
        CustomAgentEntity own = agent("{\"supportedFileTypes\":[\".TXT\"]}");
        when(agentResolver.resolve("a-1", 0L))
                .thenReturn(new AgentResolver.ResolvedAgent(own, 0L, false));
        stubCreate();

        var response = controller.upload("s-1", file("notes.txt"), "a-1", "",
                multipartRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void audioWithoutAsrConfigIs400() {
        CustomAgentEntity own = agent("{\"audioUploadEnabled\":false}");
        when(agentResolver.resolve("a-1", 0L))
                .thenReturn(new AgentResolver.ResolvedAgent(own, 0L, false));

        assertThatThrownBy(() -> controller.upload("s-1", file("voice.mp3"), "a-1", "",
                multipartRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("audio upload is not enabled or no ASR model is configured");
    }

    @Test
    void audioWithAsrConfigWritesAsrModelId() {
        CustomAgentEntity own = agent(
                "{\"audioUploadEnabled\":true,\"asrModelId\":\"asr-1\"}");
        when(agentResolver.resolve("a-1", 0L))
                .thenReturn(new AgentResolver.ResolvedAgent(own, 0L, false));
        stubCreate();

        controller.upload("s-1", file("voice.mp3"), "a-1", "", multipartRequest());

        assertThat(capturedOptions().asrModelId()).isEqualTo("asr-1");
    }

    @Test
    void agentParserRuleFillsEngineWhenNotExplicit() {
        CustomAgentEntity own = agent(
                "{\"chatParserEngineRules\":[{\"file_types\":[\"txt\"],\"engine\":\"markitdown\"}]}");
        when(agentResolver.resolve("a-1", 0L))
                .thenReturn(new AgentResolver.ResolvedAgent(own, 0L, false));
        stubCreate();

        controller.upload("s-1", file("notes.txt"), "a-1", "auto", multipartRequest());

        assertThat(capturedOptions().parserEngine()).isEqualTo("markitdown");
    }

    @Test
    void explicitParserEngineIsKept() {
        CustomAgentEntity own = agent(
                "{\"chatParserEngineRules\":[{\"file_types\":[\"txt\"],\"engine\":\"markitdown\"}]}");
        when(agentResolver.resolve("a-1", 0L))
                .thenReturn(new AgentResolver.ResolvedAgent(own, 0L, false));
        stubCreate();

        controller.upload("s-1", file("notes.txt"), "a-1", " simple ", multipartRequest());

        assertThat(capturedOptions().parserEngine()).isEqualTo("simple");
    }

    @Test
    void withoutAgentKeepsCallerScopeAndEmptyOptions() {
        when(agentResolver.resolve(null, 0L)).thenReturn(new AgentResolver.ResolvedAgent(null, 0L, false));
        stubCreate();

        var response = controller.upload("s-1", file("notes.txt"), null, null,
                multipartRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        TemporaryDocumentService.CreateOptions options = capturedOptions();
        assertThat(options.resourceTenantId()).isZero();
        assertThat(options.parserEngine()).isEmpty();
    }
}
