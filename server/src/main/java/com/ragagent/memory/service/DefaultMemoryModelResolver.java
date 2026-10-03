package com.ragagent.memory.service;

import java.util.List;

import com.ragagent.knowledge.client.EmbedderClient;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.chat.LlmChatClients;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.ragagent.model.service.ModelRuntimeConfigs;

/**
 * {@link MemoryModelResolver} 的默认实现：按模型 id 取运行时的聊天 / 嵌入模型实例
 * （与 {@code wiki.service.DefaultWikiModelResolver} 同款）。
 *
 * <p>两处已知差异，两处都与 wiki 的处置一致：</p>
 * <ol>
 *   <li><b>weknoracloud 的租户级凭据回落缺失</b>：provider=weknoracloud 且租户未存
 *       app_id/app_secret 时，没有租户级凭据可回落（{@code TenantService} 尚无该读取口）。</li>
 *   <li><b>embedding 走 {@link EmbedderClient}</b>（最小 OpenAI 兼容客户端）。
 *       单条嵌入用 {@code embedBatch} 包一层。</li>
 * </ol>
 */
@Component
public class DefaultMemoryModelResolver implements MemoryModelResolver {

    /** embedding 模型的 type 值。 */
    static final String MODEL_TYPE_EMBEDDING = "Embedding";

    private final ModelService modelService;
    private final ObjectProvider<OllamaService> ollamaService;
    private final ConcurrencyGovernor concurrencyGovernor;
    private final EmbedderClient embedderClient;

    public DefaultMemoryModelResolver(ModelService modelService,
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
        ChatConfig config = ModelRuntimeConfigs.chatConfig(model,
                p == null ? null : p.getAppId(),
                p == null ? null : p.getAppSecret());
        return LlmChatClients.create(config, ollamaService.getIfAvailable(), concurrencyGovernor);
    }

    @Override
    public float[] embed(String modelId, String text) throws Exception {
        Model model = modelService.getModelByID(modelId);
        String type = model.getType();
        if (!MODEL_TYPE_EMBEDDING.equals(type)) {
            // 类型闸门：非 embedding 模型直接报错，
            // 让调用方回落到字面匹配，而不是发一次注定失败的请求。
            throw new IllegalStateException(
                    "model " + modelId + " is not an embedding model (type=" + type + ")");
        }
        List<float[]> vectors = embedderClient.embedBatch(EmbedderClient.configFrom(model), List.of(text));
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalStateException("embedding response carried no vector");
        }
        return vectors.get(0);
    }

    @Override
    public List<Model> listModels() {
        return modelService.listModels();
    }
}
