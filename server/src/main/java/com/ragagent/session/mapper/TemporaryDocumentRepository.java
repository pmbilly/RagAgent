package com.ragagent.session.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragagent.session.domain.TemporaryDocument;
import org.springframework.stereotype.Component;

/**
 * 会话附件仓储（对照 Go internal/application/repository/temporary_document.go）。
 *
 * 注意：GetScoped / GetByID 查不到时 Go 返回 (nil, nil)——**不是错误**；
 * handler 层据 nil 判 404。Java 对应返回 null。
 */
@Component
public class TemporaryDocumentRepository {

    private final TemporaryDocumentMapper mapper;

    public TemporaryDocumentRepository(TemporaryDocumentMapper mapper) {
        this.mapper = mapper;
    }

    public TemporaryDocument create(TemporaryDocument document) {
        mapper.insert(document);
        return document;
    }

    /** 对照 Go GetByID：无租户/会话条件（parse worker 用），查不到返回 null。 */
    public TemporaryDocument getById(long tenantId, String documentId) {
        return mapper.selectOne(new LambdaQueryWrapper<TemporaryDocument>()
                .eq(TemporaryDocument::getTenantId, tenantId)
                .eq(TemporaryDocument::getId, documentId)
                .isNull(TemporaryDocument::getDeletedAt));
    }

    /** 对照 Go GetScoped：租户 + 会话 + id 三重范围，查不到返回 null。 */
    public TemporaryDocument getScoped(long tenantId, String sessionId, String documentId) {
        return mapper.selectOne(new LambdaQueryWrapper<TemporaryDocument>()
                .eq(TemporaryDocument::getTenantId, tenantId)
                .eq(TemporaryDocument::getSessionId, sessionId)
                .eq(TemporaryDocument::getId, documentId)
                .isNull(TemporaryDocument::getDeletedAt));
    }

    /** 对照 Go ListScoped：created_at ASC。 */
    public List<TemporaryDocument> listScoped(long tenantId, String sessionId) {
        return mapper.selectList(new LambdaQueryWrapper<TemporaryDocument>()
                .eq(TemporaryDocument::getTenantId, tenantId)
                .eq(TemporaryDocument::getSessionId, sessionId)
                .isNull(TemporaryDocument::getDeletedAt)
                .orderByAsc(TemporaryDocument::getCreatedAt));
    }

    /** 对照 Go MarkProcessing：status/started_at/error_message 三列。 */
    public void markProcessing(long tenantId, String documentId, OffsetDateTime startedAt) {
        mapper.update(null, new LambdaUpdateWrapper<TemporaryDocument>()
                .eq(TemporaryDocument::getTenantId, tenantId)
                .eq(TemporaryDocument::getId, documentId)
                .set(TemporaryDocument::getStatus, TemporaryDocument.STATUS_PROCESSING)
                .set(TemporaryDocument::getStartedAt, startedAt,
                        "typeHandler=com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler")
                .set(TemporaryDocument::getErrorMessage, ""));
    }

    /** 对照 Go MarkReady：终态九列。 */
    public void markReady(long tenantId, String documentId, String content, String chunks,
            String imageRefs, String metadata, int tokenCount, int chunkCount,
            OffsetDateTime readyAt) {
        mapper.update(null, new LambdaUpdateWrapper<TemporaryDocument>()
                .eq(TemporaryDocument::getTenantId, tenantId)
                .eq(TemporaryDocument::getId, documentId)
                .set(TemporaryDocument::getStatus, TemporaryDocument.STATUS_READY)
                .set(TemporaryDocument::getContent, content)
                // update-Inline 的 SET 子句不带 @TableField 的 typeHandler 信息，
                // 必须 set(col, val, mapping) 显式挂——否则真 PG 上报 jsonb/timestamptz 类型错
                .set(TemporaryDocument::getChunks, chunks, "typeHandler=com.ragagent.common.web.PgJsonTypeHandler")
                .set(TemporaryDocument::getImageRefs, imageRefs, "typeHandler=com.ragagent.common.web.PgJsonTypeHandler")
                .set(TemporaryDocument::getMetadata, metadata, "typeHandler=com.ragagent.common.web.PgJsonTypeHandler")
                .set(TemporaryDocument::getTokenCount, tokenCount)
                .set(TemporaryDocument::getChunkCount, chunkCount)
                .set(TemporaryDocument::getReadyAt, readyAt, "typeHandler=com.ragagent.common.web.GoNaiveOffsetDateTimeTypeHandler")
                .set(TemporaryDocument::getErrorMessage, ""));
    }

    /** 对照 Go MarkFailed。 */
    public void markFailed(long tenantId, String documentId, String message) {
        mapper.update(null, new LambdaUpdateWrapper<TemporaryDocument>()
                .eq(TemporaryDocument::getTenantId, tenantId)
                .eq(TemporaryDocument::getId, documentId)
                .set(TemporaryDocument::getStatus, TemporaryDocument.STATUS_FAILED)
                .set(TemporaryDocument::getErrorMessage, message));
    }

    /** 对照 Go DeleteScoped：软删（gorm DeletedAt）。 */
    public void deleteScoped(long tenantId, String sessionId, String documentId) {
        mapper.delete(new LambdaQueryWrapper<TemporaryDocument>()
                .eq(TemporaryDocument::getTenantId, tenantId)
                .eq(TemporaryDocument::getSessionId, sessionId)
                .eq(TemporaryDocument::getId, documentId));
    }
}
