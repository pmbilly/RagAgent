package com.ragagent.audit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * {@link AuditLogRetentionRunner} 的 Spring 接线（对照 Go
 * {@code internal/container/container.go} 里对
 * {@code service.NewAuditLogRetentionRunner} + {@code runner.Start(ctx)} 的调用点）。
 *
 * <p>Go 由容器在初始化时显式 Start、在关停时由 ResourceCleaner 调 Stop。
 * Java 侧没有等价的容器钩子，用 {@link SmartLifecycle} 表达同一件事：
 * 上下文刷新完成后启动，关停前停止。</p>
 *
 * <p>保留期读 {@code weknora.audit.retention-days}，<b>缺省 90</b>
 * ——对照 Go {@code config.applyAuditDefaults}（"audit.retention_days 缺省 90"）。
 * 配 0 即彻底关掉清扫（{@code AuditLogRetentionRunner.start} 变成 no-op）。</p>
 *
 * <p>时区/时钟语义：清扫用 {@code AuditLogService} 自己的时钟（生产 =
 * {@code Clock.systemDefaultZone()}），与 Go {@code time.Now} 的本地时区一致。</p>
 */
@Component
public class AuditRetentionLifecycle implements SmartLifecycle {

    private final AuditLogService auditLogService;
    private final int retentionDays;

    private AuditLogRetentionRunner runner;
    private volatile boolean running;

    public AuditRetentionLifecycle(
            AuditLogService auditLogService,
            @Value("${weknora.audit.retention-days:90}") int retentionDays) {
        this.auditLogService = auditLogService;
        this.retentionDays = retentionDays;
    }

    @Override
    public void start() {
        runner = new AuditLogRetentionRunner(auditLogService, retentionDays);
        runner.start();
        running = true;
    }

    @Override
    public void stop() {
        if (runner != null) {
            runner.stop();
            runner = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
