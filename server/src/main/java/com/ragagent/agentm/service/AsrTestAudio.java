package com.ragagent.agentm.service;

import java.io.IOException;
import java.io.InputStream;

/**
 * ASR 连通性测试音频（对照 Go internal/assets 的 {@code //go:embed asr_test.wav}）。
 *
 * <p>字节与 Go 仓 internal/assets/asr_test.wav 逐字节同源（复制即校验）：
 * 一段静音 WAV，asr/check 用它打 {baseURL}/audio/transcriptions。</p>
 */
public final class AsrTestAudio {

    public static final byte[] WAV = load();

    private AsrTestAudio() {}

    private static byte[] load() {
        try (InputStream in = AsrTestAudio.class.getClassLoader()
                .getResourceAsStream("agentm/asr_test.wav")) {
            if (in == null) {
                throw new IllegalStateException("missing resource agentm/asr_test.wav");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load agentm/asr_test.wav", e);
        }
    }
}
