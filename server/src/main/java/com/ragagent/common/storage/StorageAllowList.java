package com.ragagent.common.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 对照 Go {@code internal/storageallowlist}：STORAGE_ALLOW_LIST 控制的
 * provider 白名单（空 = 全部允许）。Supported 是**展示序**（契约：
 * /storage-backends/types 的输出顺序）。
 *
 * <p><b>为什么在 common</b>：这是**纯规则**（读 {@code STORAGE_ALLOW_LIST} 环境变量，无数据访问、
 * 无仓储依赖），却被 auth（建默认 KB 选 provider）、storage（后端列表/校验）、system（信息页）
 * 三处共用。留在 {@code com.ragagent.storage} 会让 auth/system 反向依赖存储域
 * （{@code auth ⇄ storage} 环的一半）；按"共享规则与配置搬 common"处理。</p>
 */
@Component
public class StorageAllowList {

    public static final String ALLOW_LIST_ENV = "STORAGE_ALLOW_LIST";

    private static final List<String> SUPPORTED =
            List.of("local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs");

    public List<String> supported() {
        return List.copyOf(SUPPORTED);
    }

    /** 对照 AllowedMap：分隔符 , ; | \n \t 空格；非法/未知条目丢弃 */
    public Set<String> allowedMap() {
        String raw = System.getenv(ALLOW_LIST_ENV);
        Set<String> allowed = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            allowed.addAll(SUPPORTED);
            return allowed;
        }
        for (String item : raw.split("[,;|\n\t ]")) {
            String provider = item.trim().toLowerCase(Locale.ROOT);
            if (provider.isEmpty()) {
                continue;
            }
            for (String name : SUPPORTED) {
                if (provider.equals(name)) {
                    allowed.add(provider);
                    break;
                }
            }
        }
        return allowed;
    }

    /** 对照 IsAllowed：空 provider 视为允许 */
    public boolean isAllowed(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) {
            return true;
        }
        return allowedMap().contains(p);
    }

    /** 对照 AllowedList：canonical 顺序输出允许项 */
    public List<String> allowedList() {
        Set<String> allowed = allowedMap();
        List<String> out = new ArrayList<>();
        for (String provider : SUPPORTED) {
            if (allowed.contains(provider)) {
                out.add(provider);
            }
        }
        return out;
    }

    /** 对照 isSupportedStorageBackendProvider（service 层第二道白名单） */
    public boolean isSupported(String provider) {
        return provider != null && SUPPORTED.contains(provider);
    }
}
