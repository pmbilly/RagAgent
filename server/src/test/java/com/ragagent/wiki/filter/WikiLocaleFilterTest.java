package com.ragagent.wiki.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import com.ragagent.common.web.RequestLocale;
import com.ragagent.wiki.service.WikiLanguageSupport;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link WikiLocaleFilter}：把请求里的<b>葡萄牙语</b>喂给 wiki 语言上下文。
 *
 * <p>契约：仅当解析结果命中葡萄牙语时设置上下文，且无论成败都要清理 ThreadLocal
 * （线程池复用会污染后续请求）。</p>
 */
class WikiLocaleFilterTest {

    private final WikiLocaleFilter filter = new WikiLocaleFilter();

    @AfterEach
    void cleanup() {
        WikiLanguageSupport.clearCurrentLocale();
    }

    /** 跑一次过滤链，返回"链内部看到的语言上下文"。 */
    private String localeSeenInsideChain(String acceptLanguage) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases");
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(WikiLanguageSupport.languageFromContext());
        filter.doFilter(request, response, chain);
        return seen.get();
    }

    @Test
    @DisplayName("葡萄牙语请求：链内语言上下文 = 解析到的 locale")
    void setsLocaleForPortuguese() throws Exception {
        assertThat(localeSeenInsideChain("pt-BR,en;q=0.9")).isEqualTo("pt-BR");
    }

    @Test
    @DisplayName("葡萄牙语变体（pt / pt-PT / 大小写）同样生效")
    void setsLocaleForPortugueseVariants() throws Exception {
        assertThat(localeSeenInsideChain("pt")).isEqualTo("pt");
        assertThat(localeSeenInsideChain("pt-PT")).isEqualTo("pt-PT");
        assertThat(localeSeenInsideChain("pt-br")).isEqualTo("pt-br");
    }

    @Test
    @DisplayName("非葡萄牙语：不改变语言上下文（保持接入前行为）")
    void leavesLocaleUntouchedForOthers() throws Exception {
        // 期望值随解析器走：只有解析结果命中葡萄牙语时才该设置上下文
        String resolved = RequestLocale.resolve("fr-FR");
        String seen = localeSeenInsideChain("fr-FR");
        if (RequestLocale.isPortuguese(resolved)) {
            assertThat(seen).isEqualTo(resolved);
        } else {
            // 未设置时 languageFromContext() 返回空串（不是 null）
            assertThat(seen).isEmpty();
        }
    }

    @Test
    @DisplayName("请求结束后清理 ThreadLocal（不污染后续请求）")
    void clearsLocaleAfterRequest() throws Exception {
        localeSeenInsideChain("pt-BR");
        assertThat(WikiLanguageSupport.languageFromContext()).isEmpty();
    }

    @Test
    @DisplayName("非葡萄牙语请求也不残留上下文")
    void doesNotLeakForOthers() throws Exception {
        localeSeenInsideChain("de-DE");
        assertThat(WikiLanguageSupport.languageFromContext()).isEmpty();
    }

    @Test
    @DisplayName("链内抛异常时依旧清理（finally 语义）")
    void clearsLocaleOnFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/knowledge-bases");
        request.addHeader("Accept-Language", "pt-BR");
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        try {
            filter.doFilter(request, new MockHttpServletResponse(), boom);
        } catch (Exception ignored) {
            // 预期
        }
        assertThat(WikiLanguageSupport.languageFromContext()).isEmpty();
    }
}
