package com.ragagent.embedding;

import java.util.List;

import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.limiter.Release;

/**
 * 后台并发治理装饰器（对照 Go {@code internal/models/embedding/concurrency_wrapper.go}
 * 全文）。
 *
 * <p>embedding 是量最大的后台模型调用：文档摄取会给每个 chunk 向量化。与 chat/vlm
 * 不同，这个包装器坐在<b>最内层</b>（紧贴真实 embedder、在 debug/langfuse 装饰器
 * 之下），让 {@code BatchEmbedWithPool} 扇出的每个子批 provider 往返都单独过闸
 * （信号量约束的是真实并发 provider 调用，而不是粗粒度的每文档单元）。</p>
 *
 * <p>只节流后台调用（{@code BackgroundTaskContext.isBackgroundTask()}，对照
 * {@code types.IsBackgroundTask}）；交互式查询向量化永不被节流。</p>
 */
public final class ConcurrencyEmbedder implements Embedder, EmbedderPooler {

    private final Embedder inner;
    /** 本模型的 per-model 后台上限；0 = 回退进程级默认（limiter.GateN）。 */
    private final int limit;
    private final ConcurrencyGovernor governor;

    ConcurrencyEmbedder(Embedder inner, int limit, ConcurrencyGovernor governor) {
        this.inner = inner;
        this.limit = limit;
        this.governor = governor;
    }

    @Override
    public float[] embed(String text) {
        try (var release = gate()) {
            return inner.embed(text);
        }
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        try (var release = gate()) {
            return inner.batchEmbed(texts);
        }
    }

    /**
     * 对照 BatchEmbedWithPool：把<b>本包装器</b>作为 model 下传，池器的每个子批
     * 回调都落回被闸的 batchEmbed；闸等待只覆盖真实 provider 往返。
     */
    @Override
    public List<float[]> batchEmbedWithPool(Embedder model, List<String> texts) {
        return inner instanceof EmbedderPooler pooler
                ? pooler.batchEmbedWithPool(this, texts)
                : batchEmbed(texts);
    }

    private Release gate() {
        return governor.gateNamedN(inner.getModelID(), inner.getModelName(), limit);
    }

    @Override
    public String getModelName() {
        return inner.getModelName();
    }

    @Override
    public int getDimensions() {
        return inner.getDimensions();
    }

    @Override
    public String getModelID() {
        return inner.getModelID();
    }
}
