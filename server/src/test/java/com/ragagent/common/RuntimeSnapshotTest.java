package com.ragagent.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.storage.UploadLimits;
import com.ragagent.common.wiki.WikiLanguageSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 启动期快照的安装语义：装了就生效、装空回落缺省。
 *
 * <p>这批值都是**进程级静态**（纯静态工具族没有 Spring 装配点），故用例结束必须还原——
 * 否则会污染同 JVM 的后续用例（SsrfGuard 白名单互踩是 known-issues W5a 的既有教训）。</p>
 */
class RuntimeSnapshotTest {

    private final SsrfGuard.Whitelist whitelistSnapshot = SsrfGuard.snapshotWhitelist();

    @AfterEach
    void restore() {
        SsrfGuard.restoreWhitelist(whitelistSnapshot);
        WikiLanguageSupport.installLanguage("");
        UploadLimits.installFileSizeMb("");
        CryptoService.installAesKey("");
    }

    @Test
    void languageSnapshotInstallsAndFallsBack() {
        WikiLanguageSupport.installLanguage("en-US");
        assertThat(WikiLanguageSupport.defaultLanguage()).isEqualTo("en-US");
        assertThat(WikiLanguageSupport.envLanguage()).isEqualTo("en-US");

        // 空白/未装 → 缺省 zh-CN
        WikiLanguageSupport.installLanguage("   ");
        assertThat(WikiLanguageSupport.defaultLanguage()).isEqualTo("zh-CN");
    }

    @Test
    void uploadLimitSnapshotParsesAndFallsBack() {
        UploadLimits.installFileSizeMb("2");
        assertThat(UploadLimits.maxFileSizeMb()).isEqualTo(2);
        assertThat(UploadLimits.maxFileSizeBytes()).isEqualTo(2L * 1024 * 1024);

        // 非法值回落缺省 50MB；空值同（对照改前 env 语义）
        UploadLimits.installFileSizeMb("bogus");
        assertThat(UploadLimits.maxFileSizeMb()).isEqualTo(50);
        UploadLimits.installFileSizeMb("");
        assertThat(UploadLimits.maxFileSizeMb()).isEqualTo(50);
    }

    @Test
    void cryptoKeySnapshotRequiresExactly32Bytes() {
        CryptoService.installAesKey("weknora-system-aes-key-32bytes!!");
        assertThat(new CryptoService().getAESKey()).isNotNull();

        CryptoService.installAesKey("short");
        assertThat(new CryptoService().getAESKey()).isNull();
    }

    @Test
    void ssrfWhitelistSnapshotInstalls() {
        SsrfGuard.installWhitelist("example.com", "127.0.0.1");
        SsrfGuard guard = new SsrfGuard();
        assertThat(guard.isWhitelisted("example.com")).isTrue();
        assertThat(guard.isWhitelisted("127.0.0.1")).isTrue();
        assertThat(guard.isWhitelisted("evil.example.org")).isFalse();
    }
}
