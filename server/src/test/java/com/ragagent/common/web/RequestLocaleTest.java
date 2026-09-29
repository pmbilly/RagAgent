package com.ragagent.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 请求 locale 解析（对照 Go {@code middleware/language.go}）与葡萄牙语判定的单测。
 *
 * <p>说明：{@code WEKNORA_LANGUAGE} 一旦设置会优先于 {@code Accept-Language}，
 * 这里的用例只在未设置该环境变量时成立（本仓构建与测试配置均未设置）。</p>
 */
class RequestLocaleTest {

    @ParameterizedTest
    @CsvSource({
            "pt-BR, pt-BR",
            "'pt-BR,pt-PT;q=0.8', pt-BR",
            "'en-US,en;q=0.9', en-US",
            "'es-ES;q=0.9, pt-BR;q=0.5', es-ES",
            "'  pt-BR  ', pt-BR",
    })
    @DisplayName("Accept-Language 取首个 tag（去 q 权重与空白）")
    void resolveTakesFirstTag(String acceptLanguage, String want) {
        assertThat(RequestLocale.resolve(acceptLanguage)).isEqualTo(want);
    }

    @Test
    @DisplayName("无语言偏好时回落 zh-CN（对照 Go 的兜底值）")
    void resolveFallsBackToChinese() {
        assertThat(RequestLocale.resolve(null)).isEqualTo("zh-CN");
        assertThat(RequestLocale.resolve("")).isEqualTo("zh-CN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pt", "pt-BR", "pt-br", "pt-PT", "pt_BR", "pt-BR,en;q=0.9"})
    @DisplayName("葡萄牙语判定：语言子标签为 pt 即命中（地区变体与大小写都认）")
    void isPortugueseAcceptsVariants(String locale) {
        assertThat(RequestLocale.isPortuguese(locale)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh-CN", "en-US", "es-ES", "fr-FR", "de-DE", "ja-JP", "ru-RU", "xx-YY", "pot", ""})
    @DisplayName("非葡萄牙语不命中（含 pot 这类前缀陷阱）")
    void isPortugueseRejectsOthers(String locale) {
        assertThat(RequestLocale.isPortuguese(locale)).isFalse();
    }

    @Test
    @DisplayName("空值安全")
    void isPortugueseHandlesNull() {
        assertThat(RequestLocale.isPortuguese(null)).isFalse();
    }
}
