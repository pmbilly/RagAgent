package com.ragagent.agentm.controller;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.agentm.service.InitializationConfigService;
import com.ragagent.agentm.service.ModelConnectivityTestService;
import com.ragagent.agentm.service.OllamaManageService;
import com.ragagent.agentm.service.TextExtractionTestService;
import com.ragagent.agentm.service.AsrTranscriber;
import com.ragagent.agentm.service.ExtractPrompts;
import com.ragagent.agentm.service.OllamaDownloadTaskStore;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.security.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.model.service.ModelService;

/**
 * initialization 路由（对照 Go internal/handler/initialization.go 全文 +
 * routes_infra.go RegisterInitializationRoutes）。波 3 agents 批落地 config/{kbId}
 * 三条；收尾批 W5b 补齐系统级 14 条（ollama 管理 6 + 模型连通性测试 5 + 抽取 3，
 * routes_infra.go L109-125，JWT 侧 Viewer+/Admin+、API-Key 全部
 * manage_models(fullAccess)）。
 *
 * <p>守卫层次（golden 钉死顺序，不能重排）：</p>
 * <ol>
 *   <li>GET：KBAccessRead → {@code kbGuard.requireKbAccess}（缺失→404 "knowledge base
 *       not found" 信封、跨租户→403 信封——Go handler 里的「知识库不存在」在同租户路径
 *       不可达，录到的 404 全是中间件文案）。</li>
 *   <li>POST/PUT：OwnedKBOrAdminFromKbIDParam（缺失→404 守卫文案；存在但非创建者且非
 *       Admin+ → 403 纯字符串）→ KBAccessWrite → handler。</li>
 * </ol>
 *
 * <p>已知降级：PUT 的 storageBackendId 解析分支（StorageBackendResolver）未实现——
 * 本批场景全走 provider 兼容投影；POST 建模型的 Go 既有行为（model 行 tenant_id=0、
 * handler 不回填）照抄。</p>
 */
@RestController
public class InitializationController {


    private final InitializationConfigService configService;
    private final OllamaManageService ollamaManage;
    private final ModelConnectivityTestService modelTest;
    private final TextExtractionTestService textTest;

    public InitializationController(KnowledgeAccessGuard kbGuard, KnowledgeBaseService kbService,
            KnowledgeBaseMapper kbMapper, KnowledgeMapper knowledgeMapper,
            ModelService modelService, SsrfGuard ssrfGuard,
            OllamaService ollamaService, OllamaDownloadTaskStore downloadTasks,
            AsrTranscriber asrTranscriber, ExtractPrompts extractPrompts,
            ConcurrencyGovernor concurrencyGovernor,
            com.ragagent.knowledge.client.DocReaderClient documentReader,
            TenantService tenantService, CryptoService cryptoService) {
        this.configService = new InitializationConfigService(kbGuard, kbService, kbMapper, knowledgeMapper, modelService, ssrfGuard);
        this.ollamaManage = new OllamaManageService(ollamaService, downloadTasks);
        this.modelTest = new ModelConnectivityTestService(kbService, kbMapper, knowledgeMapper, modelService, ssrfGuard, ollamaService, concurrencyGovernor, tenantService, cryptoService, asrTranscriber, documentReader);
        this.textTest = new TextExtractionTestService(extractPrompts, modelTest);
    }

    @GetMapping("/api/v1/initialization/config/{kbId}")
    public ResponseEntity<Object> getConfig(@PathVariable("kbId") String kbId) {
        return configService.getConfig(kbId);
    }

    @PostMapping("/api/v1/initialization/initialize/{kbId}")
    public ResponseEntity<Object> initialize(@PathVariable("kbId") String kbId,
            @RequestBody(required = false) String rawBody) {
        return configService.initialize(kbId, rawBody);
    }

    @PutMapping("/api/v1/initialization/config/{kbId}")
    public ResponseEntity<Object> updateConfig(@PathVariable("kbId") String kbId,
            @RequestBody(required = false) String rawBody) {
        return configService.updateConfig(kbId, rawBody);
    }

    @GetMapping("/api/v1/initialization/ollama/status")
    public ResponseEntity<Object> ollamaStatus() {
        return ollamaManage.ollamaStatus();
    }

    @GetMapping("/api/v1/initialization/ollama/models")
    public ResponseEntity<Object> ollamaModels() {
        return ollamaManage.ollamaModels();
    }

    @PostMapping("/api/v1/initialization/ollama/models/check")
    public ResponseEntity<Object> ollamaModelsCheck(@RequestBody(required = false) String rawBody) {
        return ollamaManage.ollamaModelsCheck(rawBody);
    }

    @PostMapping("/api/v1/initialization/ollama/models/download")
    public ResponseEntity<Object> ollamaModelDownload(@RequestBody(required = false) String rawBody) {
        return ollamaManage.ollamaModelDownload(rawBody);
    }

    @GetMapping("/api/v1/initialization/ollama/download/progress/{taskId}")
    public ResponseEntity<Object> downloadProgress(@PathVariable("taskId") String taskId) {
        return ollamaManage.downloadProgress(taskId);
    }

    @GetMapping("/api/v1/initialization/ollama/download/tasks")
    public ResponseEntity<Object> downloadTasksList() {
        return ollamaManage.downloadTasksList();
    }

    @PostMapping("/api/v1/initialization/remote/check")
    public ResponseEntity<Object> remoteCheck(@RequestBody(required = false) String rawBody) {
        return modelTest.remoteCheck(rawBody);
    }

    @PostMapping("/api/v1/initialization/embedding/test")
    public ResponseEntity<Object> embeddingTest(@RequestBody(required = false) String rawBody) {
        return modelTest.embeddingTest(rawBody);
    }

    @PostMapping("/api/v1/initialization/rerank/check")
    public ResponseEntity<Object> rerankCheck(@RequestBody(required = false) String rawBody) {
        return modelTest.rerankCheck(rawBody);
    }

    @PostMapping("/api/v1/initialization/asr/check")
    public ResponseEntity<Object> asrCheck(@RequestBody(required = false) String rawBody) {
        return modelTest.asrCheck(rawBody);
    }

    @PostMapping("/api/v1/initialization/multimodal/test")
    public ResponseEntity<Object> multimodalTest(
            @RequestParam(value = "vlm_model", required = false) String vlmModel,
            @RequestParam(value = "vlm_base_url", required = false) String vlmBaseUrl,
            @RequestParam(value = "vlm_interface_type", required = false) String vlmInterfaceType,
            @RequestParam(value = "storage_type", required = false) String storageType,
            @RequestParam(value = "cos_secret_id", required = false) String cosSecretId,
            @RequestParam(value = "cos_secret_key", required = false) String cosSecretKey,
            @RequestParam(value = "cos_region", required = false) String cosRegion,
            @RequestParam(value = "cos_bucket_name", required = false) String cosBucketName,
            @RequestParam(value = "cos_app_id", required = false) String cosAppId,
            @RequestParam(value = "minio_bucket_name", required = false) String minioBucketName,
            @RequestParam(value = "chunk_size", required = false) String chunkSizeRaw,
            @RequestParam(value = "chunk_overlap", required = false) String chunkOverlapRaw,
            @RequestParam(value = "separators", required = false) String separatorsRaw,
            @RequestParam(value = "image", required = false) org.springframework.web.multipart.MultipartFile image) {
        return modelTest.multimodalTest(vlmModel, vlmBaseUrl, vlmInterfaceType, storageType, cosSecretId, cosSecretKey, cosRegion, cosBucketName, cosAppId, minioBucketName, chunkSizeRaw, chunkOverlapRaw, separatorsRaw, image);
    }

    @PostMapping("/api/v1/initialization/extract/text-relation")
    public ResponseEntity<Object> extractTextRelations(@RequestBody(required = false) String rawBody) {
        return textTest.extractTextRelations(rawBody);
    }

    @PostMapping("/api/v1/initialization/extract/fabri-tag")
    public ResponseEntity<Object> fabriTag() {
        return textTest.fabriTag();
    }

    @PostMapping("/api/v1/initialization/extract/fabri-text")
    public ResponseEntity<Object> fabriText(@RequestBody(required = false) String rawBody) {
        return textTest.fabriText(rawBody);
    }

    // ══════════════ multipart 解析失败兜底（controller 侧） ══════════════


    /** 非致命 multipart 解析错误（对照 Go "表单参数解析失败"）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<Object> multipartParseFailure() {
        throw new BizException(AppError.badRequest("表单参数解析失败"));
    }

}
