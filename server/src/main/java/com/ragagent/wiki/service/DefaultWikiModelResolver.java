package com.ragagent.wiki.service;

import java.util.List;

import com.ragagent.knowledge.service.EmbedderClient;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.chat.LlmChatClients;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link WikiModelResolver} 的默认实现：把 Go 的
 * {@code modelService.GetChatModel} / {@code GetEmbeddingModel} 用 Java 侧已有的部件拼出来。
 *
 * <p>逐条对照：</p>
 * <ul>
 *   <li>chat：{@code ModelService.getModelByID}（含"downloading → 500"状态闸门，
 *       对照 Go GetModelByID）→ {@link ChatConfig#fromModel} → {@link LlmChatClients#create}
 *       （含并发闸门装饰器）。<b>已知差异</b>：Go 在 provider=weknoracloud 且租户未存
 *       app_id/app_secret 时会回落租户级凭据，Java 侧 {@code TenantService} 尚无该读取口
 *       （与 {@code McpServiceController#chatClientFor} 的既有取舍一致）。</li>
 *   <li>embedding：Go 的 {@code GetEmbeddingModel} 会校验模型类型并构造
 *       {@code EmbedderPooler}。Java 侧复用阶段 3 的
 *       {@link EmbedderClient}（最小 OpenAI 兼容客户端，对照
 *       {@code OpenAICompatibleEmbedder} 路径）。</li>
 * </ul>
 */
@Component
public class DefaultWikiModelResolver implements WikiModelResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultWikiModelResolver.class);

    /** 对照 Go {@code types.ModelTypeEmbedding}（model.parameters.type 的取值） */
    static final String MODEL_TYPE_EMBEDDING = "Embedding";

    private final ModelService modelService;
    private final ObjectProvider<OllamaService> ollamaService;
    private final ConcurrencyGovernor concurrencyGovernor;
    private final EmbedderClient embedderClient;

    public DefaultWikiModelResolver(ModelService modelService,
                                    ObjectProvider<OllamaService> ollamaService,
                                    ConcurrencyGovernor concurrencyGovernor,
                                    EmbedderClient embedderClient) {
        this.modelService = modelService;
        this.ollamaService = ollamaService;
        this.concurrencyGovernor = concurrencyGovernor;
        this.embedderClient = embedderClient;
    }

    @Override
    public LlmChatClient getChatModel(String modelId) {
        Model model = modelService.getModelByID(modelId);
        ModelParameters p = model.getParameters();
        ChatConfig config = ChatConfig.fromModel(model,
                p == null ? null : p.getAppId(),
                p == null ? null : p.getAppSecret());
        return LlmChatClients.create(config, ollamaService.getIfAvailable(), concurrencyGovernor);
    }

    @Override
    public WikiEmbeddingModel getEmbeddingModel(String modelId) {
        Model model = modelService.getModelByID(modelId);
        String type = model.getType();
        if (!MODEL_TYPE_EMBEDDING.equals(type)) {
            // 对照 Go GetEmbeddingModel 的类型闸门：非 embedding 模型直接报错，
            // 让调用方回落到"喂全部目录"的降级路径，而不是发一次注定失败的请求。
            throw new IllegalStateException(
                    "model " + modelId + " is not an embedding model (type=" + type + ")");
        }
        EmbedderClient.EmbedConfig config = EmbedderClient.configFrom(model);
        log.debug("wiki ingest: resolved embedding model {} (base={})", modelId, config.baseUrl());
        return texts -> embedderClient.embedBatch(config, texts);
    }
}
