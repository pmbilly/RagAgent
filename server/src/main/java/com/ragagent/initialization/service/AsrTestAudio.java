package com.ragagent.initialization.service;

import java.io.IOException;
import java.io.InputStream;

/**
 * ASR 连通性测试音频。
 *
 * <p>一段静音 WAV（字节逐版本钉死，复制即校验），
 * asr/check 用它打 {baseURL}/audio/transcriptions。</p>
 */
public final class AsrTestAudio {

    public static final byte[] WAV = load();

    private AsrTestAudio() {}

    private static byte[] load() {
        try (InputStream in = AsrTestAudio.class.getClassLoader()
                .getResourceAsStream("initialization/asr_test.wav")) {
            if (in == null) {
                throw new IllegalStateException("missing resource agentm/asr_test.wav");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load agentm/asr_test.wav", e);
        }
    }
}
