package com.ragagent.sandbox.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.skills.Manager;
import com.ragagent.sandbox.domain.SkillEnvVar;
import com.ragagent.sandbox.domain.SkillEnvVars;
import com.ragagent.sandbox.service.SkillBundleParser.SkillBundle;

/**
 * installer 写出的环境变量声明面的纯函数族（对照 Go
 * internal/application/service/tenant_skill_env_declare.go 的
 * parseEnvDeclaration / validateEnvDeclarations / mergeEnvDeclaration 段）。
 *
 * <p>格式层与保留名层复用 {@link UserEnvService} 已翻的常量形态（同源同值）；
 * bundle 匹配层（幻觉过滤）是 install 管线专属，本文件首次落地。全部无状态，
 * 失败以 {@link IllegalArgumentException} 携带 Go 原文抛出。</p>
 */
public final class SkillEnvDeclaration {

    /** 对照 maxSkillEnvDeclarations：一个 skill 声明的上限。 */
    public static final int MAX_SKILL_ENV_DECLARATIONS = 20;

    /** 对照 maxSkillEnvDeclarationCandidates：约束的是 bundle 扫描成本，不是正确性。 */
    static final int MAX_SKILL_ENV_DECLARATION_CANDIDATES = 200;

    /** 对照 envNamePattern。 */
    static final Pattern ENV_NAME_PATTERN = Pattern.compile("^[A-Z_][A-Z0-9_]{0,127}$");

    /** 对照 reservedEnvNames（末四项 = InjectedSandboxEnvVars，与 UserEnvService 同表）。 */
    static final Set<String> RESERVED_ENV_NAMES = Set.of(
            "PATH", "HOME", "USER", "SHELL", "LD_PRELOAD", "LD_LIBRARY_PATH",
            "PYTHONPATH", "PYTHONHOME", "NODE_OPTIONS",
            "WEKNORA_SKILL_OUTPUT_DIR", "WEKNORA_SESSION_INPUT_DIR",
            "WEKNORA_SKILL_HISTORY_ROOT", "WEKNORA_SKILL_DIR", "NODE_PATH");

    /** 对照 reservedEnvPrefix。 */
    static final String RESERVED_ENV_PREFIX = "WEKNORA_SKILL_";

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SkillEnvDeclaration() {
    }

    /** 对照 declaredSkillEnv（value 只为让忽略提示词的 agent 产出可解析的文件）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class DeclaredSkillEnv {
        public String name = "";
        public String description = "";
        public boolean required;
        public String value = "";
    }

    /**
     * 对照 parseEnvDeclaration：解码 agent 留在沙箱里的文件。错误是调用方
     * "保持空声明继续" 的信号——写了句子而非 JSON 的健谈模型不得搞砸一次
 * 本已完成的安装（Go 注释原文）。
     */
    public static List<DeclaredSkillEnv> parseEnvDeclaration(byte[] raw) {
        Encoded encoded;
        try {
            encoded = JSON.readValue(raw, Encoded.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("parse skill env declaration: " + e.getMessage(), e);
        }
        // json.RawMessage → JsonNode：显式数组校验照 Go 的 bytes.Equal(TrimSpace, "null")——
        // 只命中裸 null 字面量（带引号的 "null" 落到下面的 env must be an array 分支，与 Go 同形）
        if (encoded.env == null || encoded.env.isNull()) {
            throw new IllegalArgumentException(
                    "parse skill env declaration: env must be an explicit JSON array");
        }
        List<DeclaredSkillEnv> declared;
        try {
            declared = JSON.readValue(encoded.env.toString(),
                    JSON.getTypeFactory().constructCollectionType(List.class, DeclaredSkillEnv.class));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "parse skill env declaration: env must be an array: " + e.getMessage(), e);
        }
        if (declared.size() > MAX_SKILL_ENV_DECLARATION_CANDIDATES) {
            throw new IllegalArgumentException(
                    "parse skill env declaration: too many entries (" + declared.size()
                            + " exceeds " + MAX_SKILL_ENV_DECLARATION_CANDIDATES + ")");
        }
        return declared;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Encoded {
        public com.fasterxml.jackson.databind.JsonNode env;
    }

    /**
     * 对照 validateEnvDeclarations：按序套三层——格式、名字必须逐字出现在 bundle
     * 某处、保留名——返回幸存者。被拒的条目单独丢弃（Go 注释原文）。
     */
    public static SkillEnvVars validateEnvDeclarations(List<DeclaredSkillEnv> declared,
            SkillBundle bundle) {
        SkillEnvVars out = new SkillEnvVars();
        Set<String> seen = new HashSet<>();
        if (declared == null) {
            return out;
        }
        for (DeclaredSkillEnv entry : declared) {
            if (out.size() >= MAX_SKILL_ENV_DECLARATIONS) {
                break;
            }
            String name = entry.name == null ? "" : entry.name.trim();
            if (seen.contains(name)) {
                continue;
            }
            if (!validateEnvNameFormat(name)) {
                continue;
            }
            if (!bundleMentionsEnvName(bundle, name)) {
                continue;
            }
            if (!validateEnvNameNotReserved(name)) {
                continue;
            }
            seen.add(name);
            // Value 刻意不复制：声明是元数据
            out.add(new SkillEnvVar(name,
                    entry.description == null ? "" : entry.description.trim(),
                    entry.required, ""));
        }
        return out;
    }

    static boolean validateEnvNameFormat(String name) {
        return ENV_NAME_PATTERN.matcher(name).matches();
    }

    static boolean validateEnvNameNotReserved(String name) {
        return !RESERVED_ENV_NAMES.contains(name) && !name.startsWith(RESERVED_ENV_PREFIX);
    }

    /**
     * 对照 bundleMentionsEnvName：过滤幻觉的一层。搜 bundle 的每个文件而不只是
     * SKILL.md——skill 真正读的变量出现在读它的脚本里（Go 注释原文）。
     */
    static boolean bundleMentionsEnvName(SkillBundle bundle, String name) {
        if (bundle == null) {
            return false;
        }
        for (byte[] content : bundle.files.values()) {
            if (indexOf(content, name.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static boolean indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 对照 mergeEnvDeclaration：把新声明整体折到存量上。声明本身（哪些变量、用途、
     * 是否必填）整体替换——它是对所装版本的陈述；管理员的 value 按名字携带——
     * 那是人输入的凭据，重装不是删除它的请求（Go 注释原文）。
     */
    public static SkillEnvVars mergeEnvDeclaration(SkillEnvVars previous,
            SkillEnvVars declared) {
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        SkillEnvVars out = new SkillEnvVars();
        for (SkillEnvVar entry : declared) {
            SkillEnvVar copy = entry.copy();
            if (previous != null) {
                SkillEnvVar old = previous.get(entry.getName());
                if (old != null && old.getValue() != null && !old.getValue().isEmpty()) {
                    copy.setValue(old.getValue());
                }
            }
            out.add(copy);
        }
        return out;
    }

    /** 供 Go init() 对齐说明：注入名单取自 {@link Manager#injectedSandboxEnvVars()}（已含于上表）。 */
    public static List<String> injectedSandboxEnvVars() {
        return Manager.injectedSandboxEnvVars();
    }
}
