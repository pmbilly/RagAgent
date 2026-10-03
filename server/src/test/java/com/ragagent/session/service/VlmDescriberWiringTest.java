package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.auth.service.TenantService;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.model.service.ModelService;
import com.ragagent.retrieval.vlm.VlmClient;

/**
 * VLM 描述器装配的验收：成功装配后逐张图片调用 predict；失败形态是
 * warn 后回落（文案逐字钉住）。
 */
class VlmDescriberWiringTest {

    private final ModelService modelService = mock(ModelService.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private final SsrfGuard ssrfGuard = mock(SsrfGuard.class);
    private final ConcurrencyGovernor governor = new ConcurrencyGovernor();

    private final List<String> requestedUrls = new ArrayList<>();

    private ModelRuntimeFactory runtimeFactory() {
        // ollama provider 在 VLM 路径不触达；vlm 面不需要它
        return new ModelRuntimeFactory(modelService, tenantService, cryptoService,
                null, governor, ssrfGuard);
    }

    private VlmDescriberWiring wiring(VlmClient.Transport transport) {
        return new VlmDescriberWiring(runtimeFactory(), governor, transport);
    }

    private static Model vlmModel(String source, String provider, String baseUrl) {
        Model m = new Model();
        m.setId("vlm-1");
        m.setName("vlm-test");
        m.setSource(source);
        ModelParameters p = new ModelParameters();
        p.setBaseUrl(baseUrl);
        p.setProvider(provider);
        m.setParameters(p);
        return m;
    }

    @Test
    void predictsThroughTransportWithOpenAiShape() {
        when(modelService.getByIdVisible(anyLong(), anyString()))
                .thenReturn(vlmModel("remote", "", "https://vision.test/v1"));
        VlmClient.Transport transport = (url, apiKey, body) -> {
            requestedUrls.add(url);
            return "{\"choices\":[{\"message\":{\"content\":\"a chart\"}}]}";
        };

        var describer = wiring(transport).create("vlm-1");

        assertThat(describer.describe(new byte[] {1, 2, 3}, "describe this"))
                .isEqualTo("a chart");
        assertThat(requestedUrls).containsExactly("https://vision.test/v1/chat/completions");
    }

    @Test
    void modelMissingFailsWithGoText() {
        when(modelService.getByIdVisible(anyLong(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> wiring((url, apiKey, body) -> "{}").create("vlm-missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("model not found");
    }

    @Test
    void weKnoraCloudMissingAppIdFailsWithGoText() {
        when(modelService.getByIdVisible(anyLong(), anyString()))
                .thenReturn(vlmModel("remote", "weknoracloud", "https://cloud.test/v1"));

        assertThatThrownBy(() -> wiring((url, apiKey, body) -> "{}").create("vlm-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("WeKnoraCloud VLM: AppID is required");
    }

    @Test
    void ssrfFailurePropagatesWithGoPrefix() {
        when(modelService.getByIdVisible(anyLong(), anyString()))
                .thenReturn(vlmModel("remote", "", "https://vision.test/v1"));
        doThrow(new RuntimeException("restricted hostname: localhost"))
                .when(ssrfGuard).validateURLForSSRF(anyString());

        assertThatThrownBy(() -> wiring((url, apiKey, body) -> "{}").create("vlm-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("base URL SSRF check failed: restricted hostname: localhost");
    }

    @Test
    void ollamaSkipsBaseUrlValidation() {
        // 本机回环基址：非 ollama 界面会被 SSRF 拒绝；ollama 分支不校验（照 NewVLM）
        when(modelService.getByIdVisible(anyLong(), anyString()))
                .thenReturn(vlmModel("local", "", "http://127.0.0.1:11434"));

        assertThat(wiring((url, apiKey, body) -> "{}").create("vlm-1")).isNotNull();
        verify(ssrfGuard, never()).validateURLForSSRF(anyString());
    }

    @Test
    void predictFailureSurfacesGoMessage() {
        when(modelService.getByIdVisible(anyLong(), anyString()))
                .thenReturn(vlmModel("remote", "", "https://vision.test/v1"));
        VlmClient.Transport transport = (url, apiKey, body) -> {
            throw new RuntimeException("connection refused");
        };

        var describer = wiring(transport).create("vlm-1");

        assertThatThrownBy(() -> describer.describe(new byte[] {1}, "p"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("OpenAI VLM request: connection refused");
    }
}
