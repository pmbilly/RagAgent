package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.InputSanitizer;
import com.ragagent.agent.AgentPromptPlaceholders;
import com.ragagent.config.ConversationProperties;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.searchutil.ImageInfoEnricher;
import com.ragagent.searchutil.SearchChunkMerge;
import com.ragagent.wiki.service.WikiImageMarkup;
import com.ragagent.wiki.service.WikiLanguageSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解析生命周期操作：重新解析（复位状态后入队）与取消解析（状态机校验 + span 收口）。
 */
@Service
public class KnowledgeParseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeParseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeService facade;
    private final KnowledgeFileService fileService;
    private final KnowledgeMapper knowledgeMapper;
    private final SpanTracker spanTracker;
    private final KnowledgeService.KnowledgeProcessWorker worker;

    public KnowledgeParseService(
                            @Lazy KnowledgeService facade,
                            KnowledgeFileService fileService,
                            KnowledgeMapper knowledgeMapper,
                            SpanTracker spanTracker,
                            @Lazy KnowledgeService.KnowledgeProcessWorker worker) {
        this.facade = facade;
        this.fileService = fileService;
        this.knowledgeMapper = knowledgeMapper;
        this.spanTracker = spanTracker;
        this.worker = worker;
    }

    public Knowledge reparseKnowledge(String id) {
        Knowledge existing = facade.loadKnowledgeWrite(id);
        KnowledgeBase kb = facade.requireKb(existing.getKnowledgeBaseId());
        resetKnowledgeForReparse(existing, kb);
        fileService.updateKnowledgeRow(existing, existing.getMetadata());
        worker.enqueue(existing.getId());
        return existing;
    }

    /** */
    static void resetKnowledgeForReparse(Knowledge k, KnowledgeBase kb) {
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled");
        k.setDescription("");
        k.setProcessedAt(null);
        k.setErrorMessage("");
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setPendingSubtasksCount(0);
    }

    /**
     * cancelled 幂等、
     * completed/failed → 400 "解析已结束，无法取消"、deleting → 400 "知识正在删除中，
     * 无法取消解析"、其余状态（含 unknown）放行改 cancelled。
     */
    public Knowledge cancelKnowledgeParse(String id) {
        Knowledge existing = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, KnowledgeService.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (existing == null) {
            throw BizException.notFound("knowledge not found");
        }
        switch (existing.getParseStatus() == null ? "" : existing.getParseStatus()) {
            case Knowledge.PARSE_CANCELLED -> {
                return existing; // 幂等
            }
            case Knowledge.PARSE_COMPLETED, Knowledge.PARSE_FAILED ->
                throw BizException.badRequest("解析已结束，无法取消");
            case Knowledge.PARSE_DELETING ->
                throw BizException.badRequest("知识正在删除中，无法取消解析");
            default -> {
                // pending/processing/finalizing/unknown → 可取消（unknown Go 仅记日志放行）
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", existing.getId())
                .set("parse_status", Knowledge.PARSE_CANCELLED)
                .set("error_message", "用户已取消解析")
                .set("pending_subtasks_count", 0)
                .set("updated_at", now));
        existing.setParseStatus(Knowledge.PARSE_CANCELLED);
        existing.setErrorMessage("用户已取消解析");
        existing.setPendingSubtasksCount(0);
        existing.setUpdatedAt(now);
        // 取消时收口进度 span：LatestAttempt → AbortAttempt（平扫非终态子 span +
        // 收口 root 为 cancelled；best-effort，nil/missing attempt no-op）
        int spanAttempt = spanTracker.latestAttempt(existing.getId());
        if (spanAttempt > 0) {
            spanTracker.abortAttempt(existing.getId(), spanAttempt,
                    "USER_CANCELLED", "用户已取消解析", "用户已取消解析");
        }
        return existing;
    }

    /**
     * manual 流 metadata.content；
     * document 走本地文件。路径解析：resource:// 与
     * local://{rel}（Go provider 原生）都支持；路径越界 → Go 的
     * "invalid file path: path traversal denied: ..." 原文（golden 钉住）。
     *
     * @return (opened, filename, manual)；manual = 内存流（Go 侧 NopCloser(bytes.Reader) →
     *         非 Seeker → Accept-Ranges: none + 显式 CL），document = 存储层打开
     *         （本地 *os.File 可 seek → bytes + Range；云按 provider 能力，W5γ5.4 ①b）
     */
}
