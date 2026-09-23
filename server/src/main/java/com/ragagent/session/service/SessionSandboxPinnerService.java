package com.ragagent.session.service;

import com.ragagent.session.mapper.SessionMapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * 会话沙箱 pin（对照 Go SessionSandboxPinner，session_sandbox_pin.go L56-140）。
 *
 * <p>pin 是 sessions.sandbox_config_id 列——"这个会话的活沙箱跑在哪个配置上"的
 * 权威记录。读：NULL 与缺行都归空串（"无活沙箱"）；写：CAS 认领（仅当列为空时
 * 写入），并发首建沙箱时败者采纳赢者的配置。</p>
 */
@Service
public class SessionSandboxPinnerService {

    private final SessionMapper sessions;

    public SessionSandboxPinnerService(SessionMapper sessions) {
        this.sessions = sessions;
    }

    /** 对照 Read：会话已绑定的配置；无绑定返回空串（NULL/缺行折叠，Go 同）。 */
    public String read(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        String pin = sessions.selectSandboxConfigPin(sessionId);
        return pin == null ? "" : pin;
    }

    /**
     * 对照 Pin：CAS 认领（WHERE sandbox_config_id IS NULL OR ''）。返回生效的
     * 配置 ID——本调用者赢则返回 configId，输则回读现有 pin（Go L98-140 语义）。
     */
    public String pin(String sessionId, String configId) {
        if (sessionId == null || sessionId.isBlank() || configId == null || configId.isBlank()) {
            return configId == null ? "" : configId;
        }
        int claimed = sessions.updateSandboxConfigPin(sessionId, configId, OffsetDateTime.now());
        if (claimed > 0) {
            return configId;
        }
        String existing = read(sessionId);
        return existing.isEmpty() ? configId : existing;
    }
}
