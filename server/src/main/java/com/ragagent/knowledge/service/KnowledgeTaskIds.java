package com.ragagent.knowledge.service;

import java.util.UUID;

/**
 * 任务 ID 工具（对照 utils/taskid.go）。原为 KnowledgeService 内嵌段，
 * 阶段 2 拆分时独立成工具类（纯静态、零依赖）。
 */
public final class KnowledgeTaskIds {

    private KnowledgeTaskIds() {
    }

    /**
     * 对照 GenerateTaskID：{@code <type>_<tenant>_<millis>_<8hex>_<business12>}，
     * business 段取前 12 字符并剔除 - _ :。进度路由按嵌入的租户段做隔离校验。
     */
    public static String generateTaskId(String taskType, long tenantId, String businessId) {
        String type = taskType == null ? "" : taskType;
        type = type.replace(":", "_").replace("-", "_").replace(" ", "_").toLowerCase();
        String biz = businessId == null ? "" : businessId;
        if (biz.length() > 12) {
            biz = biz.substring(0, 12);
        }
        biz = biz.replace("-", "").replace("_", "").replace(":", "");
        String shortUuid = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String id = type + "_" + tenantId + "_" + System.currentTimeMillis() + "_" + shortUuid;
        if (!biz.isEmpty()) {
            id += "_" + biz;
        }
        return id;
    }

    /**
     * 对照 utils.ParseTaskID/TaskTenantID：从 {@code <type>_<tenant>_<ts>_<uuid>[_<biz>]}
     * 里定位 (tenant, timestamp) 对（type 段可含下划线）。解析失败返回 null → 调用方出
     * 400 "invalid task ID"。
     */
    public static Long taskTenantId(String taskId) {
        if (taskId == null) {
            return null;
        }
        String[] parts = taskId.split("_");
        if (parts.length < 4) {
            return null;
        }
        for (int i = 1; i < parts.length - 2; i++) {
            Long tenant = parseUint(parts[i]);
            if (tenant == null || tenant == 0) {
                continue;
            }
            Long ts = parseLong(parts[i + 1]);
            if (ts == null || ts < 1_000_000_000_000L) {
                continue;
            }
            return tenant;
        }
        return null;
    }

    private static Long parseUint(String s) {
        try {
            return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
