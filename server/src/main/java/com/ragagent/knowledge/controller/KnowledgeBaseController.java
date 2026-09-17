package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeBaseResponseBuilder;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/knowledgebase.go（阶段 3 子集：
 * create/list/get/update/delete/pin/move-targets；copy/duplicate/clear/progress 随后续阶段）。
 *
 * 请求体直接绑定 types.KnowledgeBase（无 CreateRequest 结构）——含 legacy cos_config 兼容
 * （对照 UnmarshalJSON L412）。响应 data 经「实体→map 合并」输出，全部键字母序
 * （KnowledgeBaseResponseBuilder）。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeBaseService kbService;

    public KnowledgeBaseController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    /** 对照 CreateKnowledgeBase — Contributor+ */
    @PostMapping
    public ResponseEntity<?> createKnowledgeBase(@RequestBody(required = false) String rawBody) {
        log.info("Start creating knowledge base");
        KnowledgeBase kb = bindKnowledgeBase(rawBody);
        kb = kbService.createKnowledgeBase(kb);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(envelope(KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    /** 对照 ShouldBindJSON(&types.KnowledgeBase) + UnmarshalJSON legacy 兼容 */
    private static KnowledgeBase bindKnowledgeBase(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new KnowledgeBase();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails(e.getMessage()));
        }
        // legacy cos_config → storage_config（对照 UnmarshalJSON）
        if (node.hasNonNull("cos_config") && !node.hasNonNull("storage_config")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                    .set("storage_config", node.get("cos_config"));
        }
        if (node.hasNonNull("storage_config") && node.get("storage_config").hasNonNull("provider")
                && !node.hasNonNull("storage_provider_config")) {
            ObjectNodeCompat.setProvider(node, node.get("storage_config").get("provider").asText());
        }
        try {
            return MAPPER.convertValue(node, KnowledgeBase.class);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails(e.getMessage()));
        }
    }

    /** 对照 ListKnowledgeBases — Viewer+；creator=mine|others 过滤 */
    @GetMapping
    public ResponseEntity<?> listKnowledgeBases(
            @RequestParam(value = "creator", required = false) String creator) {
        log.info("Start listing knowledge bases");
        List<KnowledgeBase> kbs = kbService.listKnowledgeBases(creator);
        List<Map<String, Object>> data = new ArrayList<>(kbs.size());
        for (KnowledgeBase kb : kbs) {
            data.add(KnowledgeBaseResponseBuilder.buildListItem(kb, kbService.retrieveDriver()));
        }
        return ResponseEntity.ok(envelope(data));
    }

    /** 对照 GetKnowledgeBase — Viewer+（KBAccessRead：本租户全员可读） */
    @GetMapping("/{id}")
    public ResponseEntity<?> getKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.getKnowledgeBase(id);
        return ResponseEntity.ok(envelope(KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    /** 对照 UpdateKnowledgeBase — OwnedKBOrAdmin + KBAccessWrite（handler 内 permission 校验阶段 3 简化） */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateKnowledgeBase(@PathVariable("id") String id,
                                                 @RequestBody(required = false) String rawBody) {
        log.info("Start updating knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        UpdateKbRequest req = parseUpdate(rawBody);
        if (req == null) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        if (req.name() != null && req.name().isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'UpdateKnowledgeBaseRequest.Name' Error:Field validation for 'Name' failed on the 'required' tag"));
        }
        existing = kbService.updateKnowledgeBase(existing, req.name(), req.description(), req.config());
        return ResponseEntity.ok(envelope(KnowledgeBaseResponseBuilder.build(existing, kbService.retrieveDriver())));
    }

    /** 对照 OwnedKBOrAdmin：创建者本人或 Admin+，否则 403（阶段 3 不泄漏存在性语义同 Go） */
    private static void checkOwnership(KnowledgeBase kb) {
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId().isEmpty() || !kb.getCreatorId().equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    private record UpdateKbRequest(String name, String description, JsonNode config) {}

    private static UpdateKbRequest parseUpdate(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            JsonNode config = node.hasNonNull("config") ? node.get("config") : null;
            return new UpdateKbRequest(text(node, "name"), text(node, "description"), config);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails(e.getMessage()));
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** 对照 DeleteKnowledgeBase — 所有者租户 + Admin */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start deleting knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        if (!TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can delete"));
        }
        kbService.deleteKnowledgeBase(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Knowledge base deleted successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 TogglePinKnowledgeBase — Viewer+（per-(user,kb)） */
    @PutMapping("/{id}/pin")
    public ResponseEntity<?> togglePin(@PathVariable("id") String id) {
        log.info("Start toggling pin for knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.togglePin(id);
        return ResponseEntity.ok(envelope(KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    /** 对照 ListMoveTargets — Viewer+；返回原始实体序列化（struct 声明序） */
    @GetMapping("/{id}/move-targets")
    public ResponseEntity<?> listMoveTargets(@PathVariable("id") String id) {
        log.info("Start listing move targets, ID: {}", id);
        List<KnowledgeBase> targets = kbService.listMoveTargets(id);
        List<Map<String, Object>> data = new ArrayList<>(targets.size());
        for (KnowledgeBase kb : targets) {
            data.add(KnowledgeBaseResponseBuilder.buildRaw(kb));
        }
        return ResponseEntity.ok(envelope(data));
    }

    /** gin.H 信封：key 字母序（data < success） */
    static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    /** legacy 兼容的小工具（避免在 bind 里散落强转） */
    static final class ObjectNodeCompat {
        static void setProvider(JsonNode node, String provider) {
            ObjectMapper m = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode spc = m.createObjectNode();
            spc.put("provider", provider);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("storage_provider_config", spc);
        }
    }
}
