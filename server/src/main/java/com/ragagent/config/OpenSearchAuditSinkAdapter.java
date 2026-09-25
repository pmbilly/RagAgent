package com.ragagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository;

/**
 * OpenSearch 驱动审计事件的适配器——对照 Go {@code container/audit_sink.go} 的
 * {@code auditSinkAdapter}（driver 自有 AuditSink 接口，依赖箭头单向；
 * 服务层实现它，驱动只调用）。
 *
 * <p>emit 语义照 Go：审计服务缺失 → no-op；上下文无租户 → WARN + 跳过
 * （后台任务上下文可能触发惰性建索引，写 tenant_id=0 会污染审计线）；
 * details 只装受限的非敏感字段（alias/dim/src_dst/docs——绝不带集群 reason
 * 或连接密钥）。注册期上下文（env-path）无租户 → 自跳过（Go 同款行为）。</p>
 */
@Component
public class OpenSearchAuditSinkAdapter implements OpenSearchRetrieveRepository.AuditSink {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchAuditSinkAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 Go 的 target_type 常量（audit_sink_adapter emit）。 */
    static final String TARGET_TYPE_OPENSEARCH_INDEX = "opensearch_index";

    private final AuditLogService audit;

    public OpenSearchAuditSinkAdapter(AuditLogService audit) {
        this.audit = audit;
    }

    @Override
    public void emitIndexCreated(String alias, int dim) {
        emit(AuditAction.OPENSEARCH_INDEX_CREATED, alias,
                MAPPER.createObjectNode().put("alias", alias).put("dim", dim));
    }

    @Override
    public void emitReindexExecuted(String srcAlias, String dstAlias, long docs) {
        emit(AuditAction.OPENSEARCH_REINDEX_EXECUTED, dstAlias,
                MAPPER.createObjectNode().put("src_alias", srcAlias)
                        .put("dst_alias", dstAlias).put("docs", docs));
    }

    private void emit(String action, String target, com.fasterxml.jackson.databind.JsonNode details) {
        if (audit == null) {
            return;
        }
        Long tid = TenantContext.currentTenantId();
        if (tid == null) {
            log.warn("[audit] {}: no tenant in context, skipping audit (target={})", action,
                    target);
            return;
        }
        AuditLog entry = new AuditLog();
        entry.setTenantId(tid);
        entry.setAction(action);
        entry.setTargetType(TARGET_TYPE_OPENSEARCH_INDEX);
        entry.setTargetId(target);
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(details);
        audit.log(entry);
    }
}
