package com.ragagent.knowledge.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.domain.ChunkNotFoundException;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.springframework.stereotype.Component;

/**
 * chunk 路由的 KB 访问/所有权守卫。
 * <ol>
 *   <li><b>所有权</b>：{@code middleware.RequireOwnershipOrRole(Admin, creatorLookup, cfg)}
 *       —— 角色达标或资源创建者本人，否则 403 纯字符串
 *       {@code "Forbidden: must own the resource or have the required role"}；
 *       资源在调用者空间里不存在时返回 ErrResourceNotFound，中间件<b>放行</b>
 *       （交给后续守卫/handler 出真正的 404）。</li>
 *   <li><b>KB 访问</b>：{@code middleware.RequireKBAccess(KBIDFromXxxParam, Viewer/Editor, ...)}
 *       —— 解析 KB（404）→ API-Key 白名单 → 同空间授予（跨空间 403 信封）。</li>
 * </ol>
 * <p>Wiki 控制器（{@code WikiPageController#requireWikiKB}）已确立同样的模式；
 * chunk 与 wiki 的差别在解析链多一跳（knowledge_id/chunk_id → kb_id），且
 * by-id 路由的 ownership 查找显式重校验租户（GetChunkByIDOnly 无空间过滤）。</p>
 * <p><b>判定顺序必须逐层复刻</b>（契约样例依赖顺序）：</p>
 * <ul>
 *   <li>写路由（:knowledge_id）：ownership（缺失→放行）→ KB 访问（knowledge 缺失→404
 *       "Knowledge not found"；KB 缺失→404 "knowledge base not found"；跨租户→403）→
 *       handler（chunk 缺失→404 "Chunk not found"；chunk 与 knowledge_id 不符→403
 *       "No permission to access this chunk"）。</li>
 *   <li>by-id 写路由：ownership（chunk 缺失/跨租户→放行）→ KB 访问（chunk 缺失→404
 *       "Chunk not found"）→ handler。</li>
 * </ul>
 * 还认 org-share 与 shared-agent 两条路径，Java 侧 kb_shares / agent shares 未翻译，
 */
@Component
public class ChunkAccessGuard {

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final ChunkRepository chunkRepository;

    public ChunkAccessGuard(KnowledgeMapper knowledgeMapper,
                            KnowledgeBaseMapper kbMapper,
                            ChunkMapper chunkMapper,
                            ChunkRepository chunkRepository) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.chunkRepository = chunkRepository;
    }

    /**
     * {@code :knowledge_id} → knowledge（<b>无租户过滤</b>）→ kb_id。
     * knowledge 缺失 → 404 {@code "Knowledge not found"}（AppError 信封）。
     */
    public String kbIdFromKnowledgeParam(String knowledgeId) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw BizException.notFound("Knowledge not found");
        }
        return k.getKnowledgeBaseId();
    }

    /**
     * {@code chunk.KnowledgeBaseID} 反范式在行上，单跳即可。
     * chunk 缺失（或 kb_id 为空的 legacy 行）→ 404 {@code "Chunk not found"}。
     * ⚠️ 这里<b>不</b>校验租户——跨租户 chunk 解析出外部 KB，由
     */
    public String kbIdFromChunkParam(String chunkId) {
        Chunk c = chunkMapper.selectById(chunkId);
        if (c == null || c.getDeletedAt() != null) {
            throw BizException.notFound("Chunk not found");
        }
        if (c.getKnowledgeBaseId() == null || c.getKnowledgeBaseId().isEmpty()) {
            throw BizException.notFound("Chunk not found");
        }
        return c.getKnowledgeBaseId();
    }

    /**
     * <ol>
     *   <li>API-Key KB 白名单（数据面收口点，与 KnowledgeService.requireKb 同源）；</li>
     *   <li>跨租户 → 403 信封 {@code "Permission denied to access this knowledge base"}。</li>
     * </ol>
     * 成功返回 KB 行（handler/后续守卫复用，免二次查询）。
     */
    public KnowledgeBase requireKbAccess(String kbId) {
        TenantAPIKeyScope.authorizeKnowledgeBases(
                kbId == null ? java.util.List.of() : java.util.List.of(kbId));
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || !tenantId.equals(kb.getTenantId())) {
            throw BizException.forbidden("Permission denied to access this knowledge base");
        }
        return kb;
    }

    /**
     * 链路 knowledge_id → KB.CreatorID（tenant 范围内查询）。
     * knowledge 在调用者空间不存在 → <b>放行</b>；存在但调用者既非 Admin+ 也非创建者 → 403 纯字符串。
     */
    public void requireOwnedChunkKbByKnowledge(String knowledgeId) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, TenantContext.currentTenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            return; // ErrResourceNotFound → 中间件放行
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, k.getKnowledgeBaseId())
                .eq(KnowledgeBase::getTenantId, k.getTenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return; // ErrKnowledgeBaseNotFound → 同上
        }
        checkOwnership(kb);
    }

    /**
     * 链路 chunk_id → chunk.KnowledgeID → KB.CreatorID。chunk 无空间过滤，
     */
    public void requireOwnedChunkKbByChunk(String chunkId) {
        Chunk c = chunkMapper.selectById(chunkId);
        if (c == null || c.getDeletedAt() != null) {
            return;
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || c.getTenantId() == null || !tenantId.equals(c.getTenantId())) {
            return; // 跨租户撞库挡在 ownership（照抄显式重校验）
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, c.getKnowledgeBaseId())
                .eq(KnowledgeBase::getTenantId, tenantId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return;
        }
        checkOwnership(kb);
    }

    /**
     * 角色 ≥ Admin 直接放行（不查 lookup）；
     * 系统管理员放行；创建者空串（tenant-owned/legacy）或非本人 → 403 纯字符串。
     */
    private static void checkOwnership(KnowledgeBase kb) {
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId().isEmpty() || !kb.getCreatorId().equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    /** KB 已在手的所有权判定（OwnedKBOrAdmin 的路由级形态，供复用）。 */
    public void requireOwnedKb(KnowledgeBase kb) {
        if (kb == null) {
            return; // 与 ErrResourceNotFound 放行语义一致
        }
        checkOwnership(kb);
    }

    /**
     * FAQ（OwnedKBOrAdmin 的 URL :id 直指 KB 形态）：先在<b>调用者空间</b>查 KB
     * （缺失 → 放行，交给后续 KBAccess 层出 404/403），存在则判创建者/Admin+。
     * FAQ 的写路由（POST /entry 等）用这条；判定顺序 契约样例依赖，不能重排。
     */
    public void requireOwnedKbInCallerSpace(String kbId) {
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .eq(KnowledgeBase::getTenantId, TenantContext.currentTenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return; // ErrResourceNotFound → 中间件放行
        }
        checkOwnership(kb);
    }
    /** 行级写上下文：chunk 所属 knowledge 与 KB。 */
    public record KnowledgeWrite(Knowledge knowledge, KnowledgeBase kb) {
    }

    /** metadata 里跨库搬移状态的键（operation/phase）。 */
    private static final String KNOWLEDGE_TRANSFER_METADATA_KEY = "_knowledge_transfer";

    /**
     * 写路径的 chunk 守卫：执行租户 → chunk 存在性/归属 → knowledge 写绑定 →
     * chunk 挂在其 knowledge 的 KB 上。返回<b>副本</b>，调用方的就地变更不回流仓储层。
     * <p>错误形态（全部 BizException 信封）：租户缺 → 401 "workspace context
     * unavailable"；chunk 缺 → 404 "chunk not found"；knowledge 缺 → 404
     * "knowledge not found"；KB 不匹配 → 403 "chunk does not belong to its
     * knowledge base"；moving 中 → 409。</p>
     */
    public Chunk writableChunk(String id) {
        long tenantId = writeExecutionTenant();
        Chunk chunk;
        try {
            chunk = chunkRepository.getChunkById(tenantId, id);
        } catch (ChunkNotFoundException e) {
            throw BizException.notFound("chunk not found");
        }
        if (chunk == null || !id.equals(chunk.getId()) || !Objects.equals(chunk.getTenantId(), tenantId)) {
            throw BizException.notFound("chunk not found");
        }
        KnowledgeWrite write = loadKnowledgeWrite(chunk.getKnowledgeId());
        if (!chunk.getKnowledgeBaseId().equals(write.knowledge().getKnowledgeBaseId())) {
            throw BizException.forbidden("chunk does not belong to its knowledge base");
        }
        return copyChunk(chunk);
    }

    /**
     * knowledge 写路径绑定：执行租户 → knowledge（tenant 过滤）→ 搬移中拒绝 → KB 解析。
     * 调用方只读返回值，不做行拷贝。
     */
    public KnowledgeWrite loadKnowledgeWrite(String id) {
        long tenantId = writeExecutionTenant();
        Knowledge knowledge = findKnowledgeRow(tenantId, id);
        if (knowledge == null || !id.equals(knowledge.getId())
                || !Objects.equals(knowledge.getTenantId(), tenantId)) {
            throw BizException.notFound("knowledge not found");
        }
        rejectMovingKnowledge(knowledge);
        KnowledgeBase kb = knowledgeWriteKB(knowledge);
        return new KnowledgeWrite(knowledge, kb);
    }

    /**
     * 解析 knowledge 的 KB 行：绑定不完整 → 404 "knowledge not found"；KB 行查不到 →
     * IllegalStateException（500 面）；KB 与 knowledge 跨租户 → 403。
     */
    public KnowledgeBase knowledgeWriteKB(Knowledge knowledge) {
        if (knowledge.getId() == null || knowledge.getId().isEmpty()
                || knowledge.getKnowledgeBaseId() == null || knowledge.getKnowledgeBaseId().isEmpty()
                || knowledge.getTenantId() == null || knowledge.getTenantId() == 0L) {
            throw BizException.notFound("knowledge not found");
        }
        KnowledgeBase kb = findKbRow(knowledge.getKnowledgeBaseId());
        if (kb == null) {
            throw new IllegalStateException("knowledge base not found");
        }
        if (!kb.getId().equals(knowledge.getKnowledgeBaseId())
                || !Objects.equals(kb.getTenantId(), knowledge.getTenantId())) {
            throw BizException.forbidden("knowledge does not belong to its knowledge base");
        }
        return kb;
    }

    /**
     * 拒绝搬移中的 knowledge：metadata 的 _knowledge_transfer 里 operation=move 且
     * phase=moving → 409 "knowledge has an unfinished move; retry the move first"。
     * transfer 值非对象/字段非字符串 → IllegalStateException（500 面）。
     */
    public static void rejectMovingKnowledge(Knowledge knowledge) {
        if (knowledge == null) {
            throw BizException.notFound("knowledge not found");
        }
        JsonNode fields = knowledge.getMetadata();
        if (fields == null || fields.isNull() || !fields.isObject()) {
            return;
        }
        JsonNode raw = fields.get(KNOWLEDGE_TRANSFER_METADATA_KEY);
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return;
        }
        if (!raw.isObject()) {
            throw new IllegalStateException("malformed knowledge transfer state");
        }
        JsonNode opNode = raw.get("operation");
        JsonNode phaseNode = raw.get("phase");
        if (opNode != null && !opNode.isNull() && !opNode.isTextual()) {
            throw new IllegalStateException("malformed knowledge transfer state");
        }
        if (phaseNode != null && !phaseNode.isNull() && !phaseNode.isTextual()) {
            throw new IllegalStateException("malformed knowledge transfer state");
        }
        String operation = opNode == null || opNode.isNull() ? "" : opNode.asText();
        String phase = phaseNode == null || phaseNode.isNull() ? "" : phaseNode.asText();
        if ("move".equals(operation) && "moving".equals(phase)) {
            throw BizException.conflict("knowledge has an unfinished move; retry the move first");
        }
    }

    /**
     * 编辑文本块可能连带改写父块与图片子块——首次落 revision 前校验持久化的父子关系。
     * 父块查不到时让 ChunkNotFoundException 直通（500 面，与 writableChunk 的
     * 404 形态刻意不同）。子块/父块与编辑块不同文档 → 403。
     */
    public void validateDocumentChunkRelations(long tenantId, Chunk chunk) {
        List<Chunk> parents = new ArrayList<>();
        parents.add(chunk);
        if (chunk.getParentChunkId() != null && !chunk.getParentChunkId().isEmpty()) {
            Chunk parent = chunkRepository.getChunkById(tenantId, chunk.getParentChunkId());
            if (parent == null || !chunk.getParentChunkId().equals(parent.getId())
                    || !sameChunkDocument(chunk, parent)) {
                throw BizException.forbidden("parent chunk does not belong to its document");
            }
            parents.add(parent);
        }
        for (Chunk parent : parents) {
            for (Chunk child : chunkRepository.listChunkByParentId(tenantId, parent.getId())) {
                if (!sameChunkDocument(chunk, child) || !parent.getId().equals(child.getParentChunkId())) {
                    throw BizException.forbidden("child chunk does not belong to its document");
                }
            }
        }
    }

    /** 两 chunk 是否同一文档（同租户+同 KB+同 knowledge）。⚠️ Long 比较必须 equals。 */
    public static boolean sameChunkDocument(Chunk a, Chunk b) {
        return a != null && b != null
                && Objects.equals(a.getTenantId(), b.getTenantId())
                && Objects.equals(a.getKnowledgeBaseId(), b.getKnowledgeBaseId())
                && Objects.equals(a.getKnowledgeId(), b.getKnowledgeId());
    }

    /** 执行租户：缺或 0 → 401 "workspace context unavailable"。 */
    private static long writeExecutionTenant() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null || tid == 0L) {
            throw BizException.unauthorized("workspace context unavailable");
        }
        return tid;
    }

    /** 浅拷贝（各字段皆不可变类型；调用方就地变更不回流仓储行）。 */
    private static Chunk copyChunk(Chunk c) {
        Chunk copy = new Chunk();
        copy.setId(c.getId());
        copy.setSeqId(c.getSeqId());
        copy.setTenantId(c.getTenantId());
        copy.setKnowledgeId(c.getKnowledgeId());
        copy.setKnowledgeBaseId(c.getKnowledgeBaseId());
        copy.setTagId(c.getTagId());
        copy.setContent(c.getContent());
        copy.setSourceContent(c.getSourceContent());
        copy.setContentRevision(c.getContentRevision());
        copy.setIndexStatus(c.getIndexStatus());
        copy.setLastEditorId(c.getLastEditorId());
        copy.setChunkIndex(c.getChunkIndex());
        copy.setIsEnabled(c.isIsEnabled());
        copy.setFlags(c.getFlags());
        copy.setStatus(c.getStatus());
        copy.setStartAt(c.getStartAt());
        copy.setEndAt(c.getEndAt());
        copy.setPreChunkId(c.getPreChunkId());
        copy.setNextChunkId(c.getNextChunkId());
        copy.setChunkType(c.getChunkType());
        copy.setParentChunkId(c.getParentChunkId());
        copy.setRelationChunks(c.getRelationChunks());
        copy.setIndirectRelationChunks(c.getIndirectRelationChunks());
        copy.setMetadata(c.getMetadata());
        copy.setContentHash(c.getContentHash());
        copy.setImageInfo(c.getImageInfo());
        copy.setContextHeader(c.getContextHeader());
        copy.setCreatedAt(c.getCreatedAt());
        copy.setUpdatedAt(c.getUpdatedAt());
        copy.setDeletedAt(c.getDeletedAt());
        return copy;
    }

    /** 未软删的 knowledge 行（租户过滤）。 */
    private Knowledge findKnowledgeRow(long tenantId, String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 未软删的 KB 行（按 id 直查，无租户过滤——跨租户判定在其后）。 */
    private KnowledgeBase findKbRow(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }
}
