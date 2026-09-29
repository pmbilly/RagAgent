package com.ragagent.agent.approval;

import java.time.Duration;
import java.util.UUID;

/**
 * Gate 的装配参数。
 *
 * <p>超时来源：{@code toolApprovalTimeoutSeconds}（&gt; 0 生效，否则 10 分钟）；
 * fail-open 开关：环境变量 {@code WEKNORA_AGENT_TOOL_APPROVAL_FAIL_OPEN}（仅当值为 "true"，
 * 忽略大小写与空白，才 fail-open，默认 <b>fail-close</b>：策略查询失败时仍要求人工批准）。</p>
 *
 * <p>{@code ackTimeout} 与 {@code instanceId} 均带默认值、不影响默认行为：
 * <ul>
 *   <li>{@code ackTimeout}：跨实例 ack 等待窗口，提为可配置以便测试不必真等 3 秒；</li>
 *   <li>{@code instanceId}：本实例标识，用于忽略自己发布的 pubsub 报文；
 *       同一 JVM 内模拟多实例（测试）时必须区分。</li>
 * </ul>
 */
public record GateOptions(
        Duration timeout,
        boolean failClose,
        Duration ackTimeout,
        String instanceId) {

    /** 默认审批等待时长：10 分钟。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    /** 默认跨实例 ack 等待窗口：3 秒。 */
    public static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(3);
    /** 默认实例标识：随机 UUID。 */
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
     * 按配置装配：{@code toolApprovalTimeoutSeconds > 0 ? 该值 : 10min}；
     * fail-close 由环境变量决定（见类注释）。
     *
     * @param toolApprovalTimeoutSeconds 审批等待秒数；null 表示未配置（用默认 10 分钟）
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
     * 只有 {@link Gate#FAIL_OPEN_ENV} 的值为 "true"（忽略大小写与空白）才返回 false（fail-open）；
     * 否则返回 true（fail-close）。
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
