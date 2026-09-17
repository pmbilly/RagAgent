package com.ragagent.agent.approval;

import java.time.Duration;
import java.util.UUID;

/**
 * Gate 的装配参数（对照 Go {@code NewGate(cfg *config.Config, checker, rdb)} 里读取的配置面）。
 *
 * <p>Go 的两个来源：{@code cfg.Agent.ToolApprovalTimeoutSeconds}（&gt; 0 生效，否则 10 分钟）与
 * 环境变量 {@code WEKNORA_AGENT_TOOL_APPROVAL_FAIL_OPEN}（仅当值为 "true"，忽略大小写与空白，
 * 才 fail-open，默认 <b>fail-close</b>：策略查询失败时仍要求人工批准）。</p>
 *
 * <p><b>Java 侧新增两个字段（Go 里是硬编码/包级变量）</b>，都带默认值、不影响默认行为：
 * <ul>
 *   <li>{@code ackTimeout}：跨实例 ack 等待窗口（Go 硬编码 3s），提为可配置以便测试不必真等 3 秒；</li>
 *   <li>{@code instanceId}：本实例标识（Go 是包级 uuid），用于忽略自己发布的 pubsub 报文；
 *       同一 JVM 内模拟多实例（测试）时必须区分。</li>
 * </ul>
 */
public record GateOptions(
        Duration timeout,
        boolean failClose,
        Duration ackTimeout,
        String instanceId) {

    /** 对照 Go NewGate 的默认值 {@code timeout := 10 * time.Minute} */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    /** 对照 Go resolveCrossInstance 里硬编码的 3 秒 ack 窗口 */
    public static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(3);
    /** 对照 Go 包级变量 {@code instanceID = uuid.New().String()} */
    public static final String DEFAULT_INSTANCE_ID = UUID.randomUUID().toString();

    public GateOptions {
        timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        ackTimeout = ackTimeout == null || ackTimeout.isZero() || ackTimeout.isNegative() ? DEFAULT_ACK_TIMEOUT : ackTimeout;
        instanceId = (instanceId == null || instanceId.isEmpty()) ? DEFAULT_INSTANCE_ID : instanceId;
    }

    /** 全默认：10 分钟超时 + fail-close */
    public static GateOptions defaults() {
        return new GateOptions(DEFAULT_TIMEOUT, true, DEFAULT_ACK_TIMEOUT, DEFAULT_INSTANCE_ID);
    }

    /**
     * 对照 Go NewGate 的配置读取：
     * {@code cfg.Agent.ToolApprovalTimeoutSeconds > 0 ? 该值 : 10min}；
     * fail-close 由环境变量决定（见类注释）。
     *
     * @param toolApprovalTimeoutSeconds 对照 {@code cfg.Agent.ToolApprovalTimeoutSeconds}；null 视同 cfg.Agent == nil
     */
    public static GateOptions fromConfig(Integer toolApprovalTimeoutSeconds) {
        return fromConfig(toolApprovalTimeoutSeconds, failCloseFromEnv());
    }

    /** 同上，但显式指定 failClose（便于测试与后续把开关接到配置文件） */
    public static GateOptions fromConfig(Integer toolApprovalTimeoutSeconds, boolean failClose) {
        Duration timeout = DEFAULT_TIMEOUT;
        if (toolApprovalTimeoutSeconds != null && toolApprovalTimeoutSeconds > 0) {
            timeout = Duration.ofSeconds(toolApprovalTimeoutSeconds);
        }
        return new GateOptions(timeout, failClose, DEFAULT_ACK_TIMEOUT, DEFAULT_INSTANCE_ID);
    }

    /**
     * 对照 Go：
     * {@code failClose := !strings.EqualFold(strings.TrimSpace(os.Getenv("WEKNORA_AGENT_TOOL_APPROVAL_FAIL_OPEN")), "true")}
     */
    public static boolean failCloseFromEnv() {
        String raw = System.getenv(Gate.FAIL_OPEN_ENV);
        return !"true".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    public GateOptions withTimeout(Duration v) {
        return new GateOptions(v, failClose, ackTimeout, instanceId);
    }

    public GateOptions withFailClose(boolean v) {
        return new GateOptions(timeout, v, ackTimeout, instanceId);
    }

    public GateOptions withAckTimeout(Duration v) {
        return new GateOptions(timeout, failClose, v, instanceId);
    }

    public GateOptions withInstanceId(String v) {
        return new GateOptions(timeout, failClose, ackTimeout, v);
    }
}
