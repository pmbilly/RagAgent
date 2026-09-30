package com.ragagent.session.controller;

import java.util.ArrayList;
import java.util.List;
import com.ragagent.common.error.BizException;
import com.ragagent.session.dto.QaRequests.AttachmentUpload;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.dto.QaRequests.SearchKnowledgeRequest;
import com.ragagent.session.controller.KnowledgeQaController.Base64Support;

/**
 * {@code KnowledgeQaController} 的**静态解析助手簇**（§14.9c 刀 4a）：请求体绑定
 * （`ShouldBindJSON` 对应物，Go binding:required 文案逐字对齐）、绑定错误文案、附件上传的
 * 解码与校验、以及仅解析簇使用的列表助手。全部静态、参数化、零字段依赖。
 *
 * <p>共享项留控制器（按调用点核过，§11.26）：{@code stringListOf}（附件簇 1407 也在用）、
 * {@code tenantServiceField}/{@code currentTenant}（{@code executeQA} 720 在用）。</p>
 */
final class QaRequestBinder {

    private QaRequestBinder() {}

    static final com.fasterxml.jackson.databind.ObjectMapper BIND_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    static CreateKnowledgeQARequest bindQaRequest(String rawBody) {
        CreateKnowledgeQARequest r = parseOrBindError(rawBody, CreateKnowledgeQARequest.class);
        if (r.query == null || r.query.isEmpty()) {
            throw BizException.badRequest(bindingError("CreateKnowledgeQARequest", "Query", "required"));
        }
        return r;
    }
    static SearchKnowledgeRequest bindSearchRequest(String rawBody) {
        SearchKnowledgeRequest r = parseOrBindError(rawBody, SearchKnowledgeRequest.class);
        if (r.query == null || r.query.isEmpty()) {
            throw BizException.badRequest(bindingError("SearchKnowledgeRequest", "Query", "required"));
        }
        return r;
    }
    static <T> T parseOrBindError(String rawBody, Class<T> type) {
        String msg = com.ragagent.common.web.GoJsonBindError.message(rawBody, null);
        if (msg != null) {
            throw BizException.badRequest(msg);
        }
        try {
            return BIND_JSON.readValue(rawBody, type);
        } catch (Exception e) {
            // 字段级类型错误 → Go 的 unmarshal 措辞（golden 驱动登记，w5q-kch-badsource）
            if (e instanceof com.fasterxml.jackson.databind.JsonMappingException jme
                    && !jme.getPath().isEmpty() && jme.getPath().get(0).getFieldName() != null) {
                String field = jme.getPath().get(0).getFieldName();
                String kind = "?";
                try {
                    kind = com.ragagent.common.web.GoJsonBindError.valueKind(
                            BIND_JSON.readTree(rawBody).get(field));
                } catch (Exception ignore) {
                    // rawBody 本身坏掉时回落 Jackson 措辞
                }
                String goMsg = com.ragagent.common.web.GoJsonBindError.fieldTypeError(
                        type.getSimpleName(), field, kind);
                if (goMsg != null) {
                    throw BizException.badRequest(goMsg);
                }
            }
            throw BizException.badRequest(com.ragagent.common.web.GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }
    static String bindingError(String structName, String field, String tag) {
        String key = structName == null || structName.isEmpty() ? field : structName + "." + field;
        return "Key: '" + key + "' Error:Field validation for '" + field
                + "' failed on the '" + tag + "' tag";
    }
    static List<String> appendAll(List<String> base, List<String> extra) {
        List<String> out = new ArrayList<>(base == null ? List.of() : base);
        out.addAll(extra == null ? List.of() : extra);
        return out;
    }
    /** 对照 decodeAndValidateAttachmentUploads（qa.go L431-457）的校验段。 */
    static void decodeAndValidateAttachmentUploads(List<AttachmentUpload> uploads,
            int maxCount, long maxFileBytes, long maxTotalBytes) {
        if (uploads.size() > maxCount) {
            throw new IllegalArgumentException(
                    "at most " + maxCount + " attachments are allowed per request");
        }
        long total = 0;
        int i = 1;
        for (AttachmentUpload upload : uploads) {
            byte[] data;
            try {
                data = Base64Support.decode(upload.data);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("attachment " + i + " decode failed: " + e.getMessage());
            }
            if (data.length > maxFileBytes) {
                throw new IllegalArgumentException(
                        "attachment " + i + " exceeds size limit of " + maxFileBytes + " bytes");
            }
            total += data.length;
            if (total > maxTotalBytes) {
                throw new IllegalArgumentException(
                        "attachments exceed total request limit of " + maxTotalBytes + " bytes");
            }
            i++;
        }
    }
}
