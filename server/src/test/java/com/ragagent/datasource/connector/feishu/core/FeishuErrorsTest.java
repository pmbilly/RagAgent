package com.ragagent.datasource.connector.feishu.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code feishuFailure} 分类、外加 {@code feishuErrorCode} 与
 * {@code FeishuErrorItemMeta} 的直接用例。
 *
 * <p>核心不变式：分类结果里<b>绝不能</b>泄漏原始 status / JSON body /
 * log_id——那是给服务端日志的。测试用 {@code noLeak} 逐条钉住。</p>
 */
class FeishuErrorsTest {

    private record Case(String name, String err, String wantCode, String wantCodeValue,
                        String[] fallbackHas, String[] noLeak) {
    }

    @Test
    @DisplayName("TestFeishuFailure：八条分支逐条（含 noLeak 断言）")
    void feishuFailureClassifiesEachBranch() {
        Case[] cases = {
                new Case("rate limited",
                        "feishu rate limited: status=429 body={\"code\":99991400,\"msg\":\"too many request\"}",
                        "feishu_rate_limited", "", new String[]{"retry"},
                        new String[]{"body=", "{", "99991400"}),
                new Case("server 5xx",
                        "feishu server error: status=500 body={\"code\":1663}",
                        "feishu_server_unavailable", "", new String[0],
                        new String[]{"body=", "{"}),
                new Case("export timeout",
                        "export 季度报告 (docx): export task timed out after 60s (ticket=abc)",
                        "feishu_timeout", "", new String[0],
                        new String[]{"ticket=", "abc"}),
                new Case("api error carries the feishu code as a param",
                        "feishu api error: status=500 body={\"code\":1663,\"msg\":\"internal error\","
                                + "\"Error\":{\"log_id\":\"20260\"}}",
                        "feishu_api_error", "1663", new String[0],
                        new String[]{"log_id", "body=", "{"}),
                new Case("api error without a code is generic",
                        "feishu api error: status=502 body=bad gateway",
                        "feishu_api_error_generic", "", new String[0], new String[0]),
                new Case("auth failure is actionable, not a retry",
                        "feishu auth error: code=99991663 msg=Invalid access token",
                        "feishu_auth_or_permission", "", new String[0],
                        new String[]{"Invalid access token", "99991663"}),
                new Case("unknown error falls back to sync_failed",
                        "some totally unexpected failure",
                        "sync_failed", "", new String[0],
                        new String[]{"totally unexpected"}),
                // "decode"/"encode"/"unicode" 都含子串 "code"，但都不是飞书 API 错误
                new Case("decode error is not a feishu api error",
                        "failed to decode response body", "sync_failed", "", new String[0], new String[0]),
                new Case("transient message promises retry on next sync, not automatic",
                        "feishu rate limited: status=429", "feishu_rate_limited", "",
                        new String[]{"next sync"}, new String[]{"automatically"}),
        };

        for (Case tc : cases) {
            FeishuErrors.Failure got = FeishuErrors.feishuFailure(new RuntimeException(tc.err()));
            assertThat(got.code()).as(tc.name()).isEqualTo(tc.wantCode());
            assertThat(got.codeValue()).as(tc.name()).isEqualTo(tc.wantCodeValue());
            assertThat(got.fallback().trim()).as(tc.name() + " fallback must not be empty").isNotEmpty();
            for (String s : tc.fallbackHas()) {
                assertThat(got.fallback().toLowerCase()).as(tc.name()).contains(s);
            }
            for (String s : tc.noLeak()) {
                assertThat(got.code()).as(tc.name() + " code leak " + s).doesNotContain(s);
                assertThat(got.fallback()).as(tc.name() + " fallback leak " + s).doesNotContain(s);
            }
        }
    }

    @Test
    @DisplayName("err == nil 时是 sync_failed（对照 Go 的 feishuFailure(nil)）")
    void nilError() {
        FeishuErrors.Failure f = FeishuErrors.feishuFailure(null);
        assertThat(f.code()).isEqualTo("sync_failed");
        assertThat(f.codeValue()).isEmpty();
        assertThat(f.fallback()).isEqualTo("Sync failed; will retry on the next sync");
    }

    @Test
    @DisplayName("分类大小写不敏感（Go 先 ToLower 再 Contains）")
    void caseInsensitive() {
        assertThat(FeishuErrors.feishuFailure(
                new RuntimeException("Feishu RATE LIMITED: STATUS=429")).code())
                .isEqualTo("feishu_rate_limited");
        assertThat(FeishuErrors.feishuFailure(
                new RuntimeException("Download Failed: status=403 body=x")).code())
                .isEqualTo("feishu_auth_or_permission");
    }

    @Test
    @DisplayName("分支顺序：'download failed' 与 'permission' 同现时，鉴权分支先命中")
    void branchOrderMatters() {
        // 分支判定顺序：auth/permission 分支在 api error 分支之前
        assertThat(FeishuErrors.feishuFailure(
                new RuntimeException("download failed: permission denied")).code())
                .isEqualTo("feishu_auth_or_permission");
    }

    @Test
    @DisplayName("feishuErrorCode：Go 实录的 7 个语料")
    void feishuErrorCode() {
        assertThat(FeishuErrors.feishuErrorCode("body={\"code\":1663,\"msg\":\"x\"}")).isEqualTo("1663");
        assertThat(FeishuErrors.feishuErrorCode("code=1663")).isEqualTo("1663");
        assertThat(FeishuErrors.feishuErrorCode("code\": 1663")).isEqualTo("1663");
        assertThat(FeishuErrors.feishuErrorCode("failed to decode response body")).isEmpty();
        assertThat(FeishuErrors.feishuErrorCode("no codes here")).isEmpty();
        assertThat(FeishuErrors.feishuErrorCode("code : 42")).isEqualTo("42");
        assertThat(FeishuErrors.feishuErrorCode("{\"code\":99991400}")).isEqualTo("99991400");
        assertThat(FeishuErrors.feishuErrorCode(null)).isEmpty();
    }

    @Test
    @DisplayName("FeishuErrorItemMeta：内置 3 键 + 有值时的 code_value，extra 覆盖同名键")
    void errorItemMeta() {
        RuntimeException err = new RuntimeException(
                "feishu api error: status=500 body={\"code\":1663}");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("channel", "feishu");
        extra.put("node_token", "nt1");

        Map<String, String> m = FeishuErrors.feishuErrorItemMeta(err, extra);
        assertThat(m).containsEntry("error", err.getMessage())
                .containsEntry("error_reason_code", "feishu_api_error")
                .containsEntry("error_reason_code_value", "1663")
                .containsEntry("error_reason",
                        "Feishu API error (code=1663); will retry on the next sync")
                .containsEntry("channel", "feishu")
                .containsEntry("node_token", "nt1")
                .hasSize(6);
    }

    @Test
    @DisplayName("无数字码时没有 error_reason_code_value 这个键（对照 Go 的条件插入）")
    void errorItemMetaOmitsEmptyCodeValue() {
        Map<String, String> m = FeishuErrors.feishuErrorItemMeta(
                new RuntimeException("feishu rate limited: status=429"), null);
        assertThat(m).doesNotContainKey("error_reason_code_value");
        assertThat(m).containsEntry("error_reason_code", "feishu_rate_limited");
        // 键序 = 插入序（error, error_reason_code, error_reason）
        assertThat(m.keySet()).containsExactly("error", "error_reason_code", "error_reason");
    }

    @Test
    @DisplayName("extra 覆盖内置键（对照 Go 的 maps.Copy(m, extra)）")
    void extraOverridesBuiltIn() {
        Map<String, String> m = FeishuErrors.feishuErrorItemMeta(
                new RuntimeException("boom"), Map.of("error", "覆盖后的"));
        assertThat(m).containsEntry("error", "覆盖后的");
    }

    @Test
    @DisplayName("err == null 时不抛 NPE（Go 侧调用点恒传非 nil，Java 侧防御）")
    void nullErrorItemMeta() {
        Map<String, String> m = FeishuErrors.feishuErrorItemMeta(null, null);
        assertThat(m).containsEntry("error_reason_code", "sync_failed");
    }
}
