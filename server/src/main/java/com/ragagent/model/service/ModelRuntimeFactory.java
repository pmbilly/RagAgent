package com.ragagent.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.embedding.Embedder;
import com.ragagent.embedding.EmbedderConfig;
import com.ragagent.embedding.EmbedderFactory;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.chat.LlmChatClients;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelService.ModelNotFoundException;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;
import com.ragagent.rerank.RerankerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 模型运行时工厂（对照 Go {@code internal/application/service/model.go} 的
 * {@code GetChatModel}/{@code GetEmbeddingModel}/{@code GetRerankModel}/
 * {@code GetVLMModel}/{@code GetASRModel}——阶段 7 的归口批次由 models/{id}/debug
 * 端点落地）。
 *
 * <p>与 Go 一致的两类取数口径（差别是契约，别统一）：</p>
 * <ul>
 *   <li><b>embedding / rerank</b> 走 {@code GetModelByID}（带状态闸门：
 *       downloading → "model is currently downloading" 等）；</li>
 *   <li><b>chat / vlm / asr</b> 走 {@code repo.GetByID} 直取（无状态闸门）。</li>
 * </ul>
 *
 * <p>错误形态：Go 这些工厂返回 {@code error}，调用方（DebugModel）把
 * {@code err.Error()} 写进 {@code data.error}（HTTP 200）。因此本类抛出的
 * {@link RuntimeException} 的 {@code getMessage()} 逐字对照 Go 错误文案；
 * 底层部件抛 {@link BizException} 时在此拆包取其 message（BizException 的
 * getMessage 带 "error code: ..." 前缀，不能直接当 Go 文案用）。</p>
 */
@Component
public class ModelRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelRuntimeFactory.class);

    private final ModelService modelService;
    private final TenantService tenantService;
    private final CryptoService cryptoService;
    private final ObjectProvider<OllamaService> ollamaService;
    private final ConcurrencyGovernor concurrencyGovernor;
    private final SsrfGuard ssrfGuard;

    public ModelRuntimeFactory(ModelService modelService, TenantService tenantService,
                               CryptoService cryptoService, ObjectProvider<OllamaService> ollamaService,
                               ConcurrencyGovernor concurrencyGovernor, SsrfGuard ssrfGuard) {
        this.modelService = modelService;
        this.tenantService = tenantService;
        this.cryptoService = cryptoService;
        this.ollamaService = ollamaService;
        this.concurrencyGovernor = concurrencyGovernor;
        this.ssrfGuard = ssrfGuard;
    }

    /** 对照 GetChatModel：repo 直取（无状态闸门）→ chat.NewChat。 */
    public LlmChatClient getChatModel(String modelId) {
        Model model = getModelDirect(modelId);
        log.info("Getting chat model: {}, source: {}", model.getName(), model.getSource());
        String[] creds = resolveWeKnoraCloudCredentials(model.getParameters());
        try {
            // C 批：langfuse generation 装饰（对照 Go NewChat 末段的 wrapChatLangfuse；
            // 管理器未启用时原样返回，零成本）
            return com.ragagent.tracing.langfuse.LangfuseChatClient.wrap(
                    LlmChatClients.create(ChatConfig.fromModel(model, creds[0], creds[1]),
                            ollamaService.getIfAvailable(), concurrencyGovernor));
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    /** 对照 GetEmbeddingModel：GetModelByID（状态闸门）→ embedding.NewEmbedder。 */
    public Embedder getEmbeddingModel(String modelId) {
        Model model = getModelGated(modelId);
        log.info("Getting embedding model: {}, source: {}", model.getName(), model.getSource());
        String[] creds = resolveWeKnoraCloudCredentials(model.getParameters());
        try {
            // Go 的 pooler 只服务 BatchEmbedWithPool；debug 只走单文本 Embed，传 null
            // C 批：langfuse generation 装饰（对照 Go NewEmbedder 末段的 wrapEmbedderLangfuse）
            return com.ragagent.tracing.langfuse.LangfuseEmbedder.wrap(
                    EmbedderFactory.newEmbedder(
                            EmbedderConfig.configFromModel(model, creds[0], creds[1]),
                            null, ollamaService.getIfAvailable(), concurrencyGovernor));
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    /** 对照 GetRerankModel：GetModelByID（状态闸门）→ rerank.NewReranker。 */
    public Reranker getRerankModel(String modelId) {
        Model model = getModelGated(modelId);
        log.info("Getting rerank model: {}, source: {}", model.getName(), model.getSource());
        String[] creds = resolveWeKnoraCloudCredentials(model.getParameters());
        try {
            // C 批：langfuse generation 装饰（对照 Go NewReranker 末段的 wrapRerankerLangfuse）
            return com.ragagent.tracing.langfuse.LangfuseReranker.wrap(
                    RerankerFactory.newReranker(
                            RerankerConfig.configFromModel(model, creds[0], creds[1])));
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    /** 对照 GetVLMModel：repo 直取（无状态闸门）。调用方再 configFromModel + predict。 */
    public Model getVlmModel(String modelId) {
        Model model = getModelDirect(modelId);
        log.info("Getting VLM model: {}, source: {}", model.getName(), model.getSource());
        return model;
    }

    /** 对照 GetASRModel：repo 直取（无状态闸门）。调用方再组 AsrConfig + transcribe。 */
    public Model getAsrModel(String modelId) {
        Model model = getModelDirect(modelId);
        log.info("Getting ASR model: {}, source: {}", model.getName(), model.getSource());
        return model;
    }

    /**
     * 对照 vlm.NewVLM 的构造期校验（validateVLMBaseURL）：SSRF 失败文案
     * "base URL SSRF check failed: ..."。
     */
    public void validateVlmBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return;
        }
        try {
            ssrfGuard.validateURLForSSRF(baseUrl);
        } catch (RuntimeException e) {
            throw new RuntimeException("base URL SSRF check failed: " + e.getMessage());
        }
    }

    // ── 取数口径 ─────────────────────────────────────────────────────────

    /** 对照 repo.GetByID（tenant 可见性含 is_builtin；无状态闸门）。 */
    private Model getModelDirect(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            throw new RuntimeException("model ID cannot be empty");
        }
        long tid = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        Model model = modelService.getByIdVisible(tid, modelId);
        if (model == null) {
            throw new RuntimeException("model not found");
        }
        return model;
    }

    /** 对照 GetModelByID（状态闸门），错误文案拆包成 Go 原文。 */
    private Model getModelGated(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            throw new RuntimeException("model ID cannot be empty");
        }
        try {
            return modelService.getModelByID(modelId);
        } catch (ModelNotFoundException e) {
            throw new RuntimeException("model not found");
        } catch (BizException e) {
            throw new RuntimeException(e.appError().message());
        }
    }

    // ── WeKnoraCloud 凭证（对照 resolveWeKnoraCloudCredentials + decryptAppSecret） ──

    private String[] resolveWeKnoraCloudCredentials(ModelParameters params) {
        String appId = params == null || params.getAppId() == null ? "" : params.getAppId();
        String appSecret = decryptAppSecret(params == null ? null : params.getAppSecret());
        String provider = params == null || params.getProvider() == null ? "" : params.getProvider();
        if (!"weknoracloud".equals(provider)) {
            return new String[] {appId, appSecret};
        }
        if (!appId.isEmpty() && !appSecret.isEmpty()) {
            return new String[] {appId, appSecret};
        }
        long tid = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        Tenant tenant = tenantService.getTenantById(tid);
        JsonNode creds = tenant == null || tenant.getCredentials() == null
                ? null : tenant.getCredentials().get("weknoracloud");
        if (creds == null) {
            return new String[] {appId, appSecret};
        }
        if (appId.isEmpty()) {
            appId = creds.path("app_id").asText("");
        }
        if (appSecret.isEmpty()) {
            var decrypted = cryptoService.decryptStoredSecretLenient(creds.path("app_secret").asText(""));
            appSecret = decrypted.ok() ? decrypted.plaintext() : "";
        }
        return new String[] {appId, appSecret};
    }

    /** 对照 decryptAppSecret：空原样返回；宽容解密（失败原样返回）。 */
    private String decryptAppSecret(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        var decrypted = cryptoService.decryptStoredSecretLenient(encrypted);
        return decrypted.ok() ? decrypted.plaintext() : encrypted;
    }
}
