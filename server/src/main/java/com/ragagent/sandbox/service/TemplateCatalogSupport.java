package com.ragagent.sandbox.service;

import com.ragagent.sandbox.runtime.RemoteTemplate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板目录的纯函数族（对照 Go tenant_sandbox_config.go L693-922 +
 * template_catalog.go 的判定助手）。全部无副作用，provider 无关——单测直接覆盖。
 */
public final class TemplateCatalogSupport {

    /** 对照 StandardTemplateName（template_catalog.go L8）。 */
    public static final String STANDARD_TEMPLATE_NAME = "weknora";

    private TemplateCatalogSupport() {
    }

    /** 对照 isStandardTemplate（template_catalog.go L58-64）：委托 RemoteTemplate。 */
    public static boolean isStandardTemplate(String name) {
        return RemoteTemplate.isStandardTemplate(name);
    }

    /** 对照 normalizeImageRepository：委托 RemoteTemplate。 */
    public static String normalizeImageRepository(String image) {
        return RemoteTemplate.normalizeImageRepository(image);
    }

    /** 对照 pickStandardTemplate（tenant_sandbox_config.go L846-856）。 */
    public static RemoteTemplate pickStandardTemplate(List<RemoteTemplate> items) {
        RemoteTemplate best = null;
        for (RemoteTemplate item : items) {
            if (!item.standard || RemoteTemplate.isTemplateBuildFailed(item.status)) {
                continue;
            }
            if (best == null
                    || RemoteTemplate.statusRank(item.status) > RemoteTemplate.statusRank(best.status)) {
                best = item;
            }
        }
        return best;
    }

    /** 对照 standardTemplateIDs（tenant_sandbox_config.go L705-715）。 */
    public static List<String> standardTemplateIDs(List<RemoteTemplate> items) {
        List<String> out = new ArrayList<>();
        for (RemoteTemplate item : items) {
            if (item.standard && item.id != null && !item.id.trim().isEmpty()) {
                out.add(item.id.trim());
            }
        }
        return out;
    }

    /** 对照 hideSupersededStandardTemplates（tenant_sandbox_config.go L693-703）。 */
    public static List<RemoteTemplate> hideSupersededStandardTemplates(
            List<RemoteTemplate> items, String keepId) {
        String keep = keepId == null ? "" : keepId.trim();
        List<RemoteTemplate> kept = new ArrayList<>();
        for (RemoteTemplate item : items) {
            if (item.standard && !item.id.trim().equals(keep)) {
                continue;
            }
            kept.add(item);
        }
        return kept;
    }

    /**
     * 对照 deduplicateSandboxTemplates（tenant_sandbox_config.go L868-903）：同 ID
     * 合并——standard 取或、status 按 rank 高者连同 version/updated_at/error 整组
     * 替换、名字让位给带路径的、image 补空。无 ID 的行恒保留。
     */
    public static List<RemoteTemplate> deduplicateSandboxTemplates(List<RemoteTemplate> items) {
        if (items.size() < 2) {
            return new ArrayList<>(items);
        }
        List<RemoteTemplate> result = new ArrayList<>();
        Map<String, Integer> indexById = new HashMap<>();
        for (RemoteTemplate item : items) {
            String id = item.id == null ? "" : item.id.trim();
            if (id.isEmpty()) {
                result.add(item);
                continue;
            }
            Integer idx = indexById.get(id);
            if (idx == null) {
                indexById.put(id, result.size());
                result.add(item);
                continue;
            }
            RemoteTemplate current = result.get(idx);
            current.standard = current.standard || item.standard;
            if (RemoteTemplate.statusRank(item.status) > RemoteTemplate.statusRank(current.status)) {
                current.status = item.status;
                current.version = item.version;
                current.updatedAt = item.updatedAt;
                current.error = item.error;
            }
            if (current.name == null || current.name.trim().isEmpty()
                    || (current.name.equalsIgnoreCase(STANDARD_TEMPLATE_NAME)
                            && item.name != null && item.name.contains("/"))) {
                current.name = item.name;
            }
            if (current.image == null || current.image.isEmpty()) {
                current.image = item.image;
            }
        }
        return result;
    }

    /**
     * 对照 QueryTemplates 尾部的稳定排序（tenant_sandbox_config.go L684-689）：
     * standard 在前，其余按 name 不区分大小写稳定排序。
     */
    public static final Comparator<RemoteTemplate> CATALOG_ORDER = (a, b) -> {
        if (a.standard != b.standard) {
            return a.standard ? -1 : 1;
        }
        return a.name.toLowerCase().compareTo(b.name.toLowerCase());
    };
}
