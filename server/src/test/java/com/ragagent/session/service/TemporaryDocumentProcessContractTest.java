package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ragagent.agentm.service.AsrTranscriber;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;

/**
 * 附件解析链路的验收（对照 Go Process/parse 的资源租户、引擎回落与音频分支）：
 * {@code resource_tenant_id} 决定解析依赖范围、租户级 chat 规则兜底 parser engine、
 * 音频走 ASR（无模型 → "audio transcription model is not configured"）。
 */
class TemporaryDocumentProcessContractTest {

    private static final long TENANT = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TemporaryDocumentRepository repo;
    private AttachmentFileStore fileStore;
    private DocReaderClient docReader;
    private ModelRuntimeFactory modelRuntimeFactory;
    private AsrTranscriber asrTranscriber;
    private TenantService tenantService;
    private TemporaryDocumentService service;

    @BeforeEach
    void setUp() {
        repo = mock(TemporaryDocumentRepository.class);
        fileStore = mock(AttachmentFileStore.class);
        docReader = mock(DocReaderClient.class);
        modelRuntimeFactory = mock(ModelRuntimeFactory.class);
        asrTranscriber = mock(AsrTranscriber.class);
        tenantService = mock(TenantService.class);
        service = new TemporaryDocumentService(repo, fileStore, docReader,
                modelRuntimeFactory, asrTranscriber, tenantService);
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static TemporaryDocument document(String fileName, String fileType,
            String optionsJson) {
        TemporaryDocument d = new TemporaryDocument();
        d.setId("d-1");
        d.setTenantId(TENANT);
        d.setSessionId("s-1");
        d.setResourceRef("local://1/exports/d-1");
        d.setFileName(fileName);
        d.setFileType(fileType);
        d.setStatus(TemporaryDocument.STATUS_UPLOADED);
        d.setProcessingOptions(optionsJson);
        return d;
    }

    private static Model asrModel() {
        Model m = new Model();
        m.setId("asr-1");
        m.setName("asr-test");
        m.setSource("remote");
        m.setParameters(new ModelParameters());
        return m;
    }

    /** 租户的 chat 解析规则（pdf → engine）。 */
    private static Tenant tenantWithRule(String ext, String engine) {
        Tenant t = new Tenant();
        ObjectNode cfg = MAPPER.createObjectNode();
        ArrayNode rules = cfg.putArray("chat_parser_engine_rules");
        ObjectNode rule = rules.addObject();
        rule.putArray("file_types").add(ext);
        rule.put("engine", engine);
        t.setParserEngineConfig(cfg);
        return t;
    }

    @Test
    void audioIsTranscribedWhenAsrConfigured() {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("voice.mp3", ".mp3",
                "{\"asr_model_id\":\"asr-1\",\"resource_tenant_id\":42}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1, 2, 3});
        when(modelRuntimeFactory.getAsrModel("asr-1")).thenReturn(asrModel());
        when(asrTranscriber.transcribe(any(), any(), any()))
                .thenReturn(new AsrTranscriber.TranscriptionResult("转写文本", null));

        service.processNow(TENANT, "d-1");

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(repo).markReady(eq(TENANT), eq("d-1"), eq("转写文本"), anyString(), eq("null"),
                metadata.capture(), anyInt(), anyInt(), any());
        assertThat(metadata.getValue()).contains("\"parser\":\"asr\"");
        // withResourceTenant 在调用后恢复原租户
        assertThat(TenantContext.currentTenantId()).isEqualTo(TENANT);
    }

    @Test
    void audioWithoutAsrModelFails() {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("voice.mp3", ".mp3", "{}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});

        service.processNow(TENANT, "d-1");

        verify(repo).markFailed(TENANT, "d-1", "audio transcription model is not configured");
    }

    @Test
    void tenantRuleFillsEngineWhenOptionsLackOne() throws Exception {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("report.pdf", ".pdf", "{}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});
        when(tenantService.getTenantById(TENANT)).thenReturn(tenantWithRule("pdf", "mineru"));
        when(docReader.read(any(), anyString(), anyString(), anyString(), eq("mineru")))
                .thenReturn(new DocReaderClient.ParseResult("# md", 0, List.of()));

        service.processNow(TENANT, "d-1");

        verify(docReader).read(any(), eq("report.pdf"), eq("pdf"), eq("report.pdf"),
                eq("mineru"));
    }

    @Test
    void resourceTenantDrivesEngineFallback() throws Exception {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("report.pdf", ".pdf",
                "{\"resource_tenant_id\":42}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});
        when(tenantService.getTenantById(42L))
                .thenReturn(tenantWithRule("pdf", "paddleocr_vl"));
        when(docReader.read(any(), anyString(), anyString(), anyString(), eq("paddleocr_vl")))
                .thenReturn(new DocReaderClient.ParseResult("# md", 0, List.of()));

        service.processNow(TENANT, "d-1");

        verify(docReader).read(any(), eq("report.pdf"), eq("pdf"), eq("report.pdf"),
                eq("paddleocr_vl"));
    }

    @Test
    void explicitEngineSkipsTenantFallback() throws Exception {
        when(repo.getById(TENANT, "d-1")).thenReturn(document("report.pdf", ".pdf",
                "{\"parser_engine\":\"simple\"}"));
        when(fileStore.getFile("local://1/exports/d-1")).thenReturn(new byte[] {1});
        when(docReader.read(any(), anyString(), anyString(), anyString(), eq("simple")))
                .thenReturn(new DocReaderClient.ParseResult("# md", 0, List.of()));

        service.processNow(TENANT, "d-1");

        verify(docReader).read(any(), eq("report.pdf"), eq("pdf"), eq("report.pdf"),
                eq("simple"));
    }
}
