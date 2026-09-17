package com.ragagent.apikey.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 租户 API Key 的**能力（capability）模型**——对照 Go {@code types.APIKeyCapability}
 * 常量表与 {@code NormalizeAPIKeyCapability} / {@code NormalizeAPIKeyCapabilities}
 * （internal/types/tenant_api_key.go L71-243）。
 *
 * <p><b>这是权限语义，不是配置项</b>：能力是"叠加式授权"（additive grant），
 * 与 JWT 的角色/所有权（{@link com.ragagent.common.web.RbacInterceptor}）是
 * <b>两套独立叠加的授权</b>。full-access Key 拥有全部能力且发 Key 时不允许再带细粒度能力；
 * scoped Key 只能做能力清单里列出的动作，且其 {@code knowledge_base_ids} 白名单
 * 在知识库相关路由上仍额外生效。</p>
 *
 * <p>逐条注释与 Go 的常量注释一一对应，改动前请先读 Go 侧注释——每条能力都写明了
 * "它不包含什么"，那才是划分边界的关键。</p>
 */
public final class APIKeyCapability {

    /** 读 / 检索知识库数据；不授予对话与内容写入。 */
    public static final String RETRIEVE = "retrieve";
    /** 走对话流程（会话 + agent 列表 + 自身身份）；不授予租户管理。 */
    public static final String CHAT = "chat";
    /** 列出/查看 agent；不允许起会话，也不允许编排 agent。 */
    public static final String READ_AGENTS = "read_agents";
    /** 向**自己白名单内**的知识库写内容（上传文档、改分块/FAQ/标签/wiki）。
     *  只放宽内容写路由：不允许建知识库/建 agent，也不允许破坏性的清空。 */
    public static final String INGEST = "ingest";
    /** 知识库全生命周期（建/复制/改/删）。<b>与 ingest 分离</b>：
     *  "能改内容"不等于"能管知识库本身"。 */
    public static final String MANAGE_KBS = "manage_kbs";
    /** agent 增删改查/复制。agent 配置可能携带模型/MCP 绑定，故默认关闭、须显式开启。 */
    public static final String MANAGE_AGENTS = "manage_agents";
    /** 检索/查看租户级聊天历史知识库；<b>与 chat 分离</b>——chat 只覆盖调用者自己的
     *  在线会话，message_history 可触达租户内历史消息。 */
    public static final String MESSAGE_HISTORY = "message_history";
    /** 租户模型定义、凭据、模型检查、WeKnoraCloud 凭据。 */
    public static final String MANAGE_MODELS = "manage_models";
    /** 租户 MCP 服务定义、凭据、工具策略、逐主体 OAuth 状态。 */
    public static final String MANAGE_MCP_SERVICES = "manage_mcp_services";
    /** 数据源连接器与同步任务；绑定到知识库的数据源仍受 KB 白名单约束。 */
    public static final String MANAGE_DATASOURCES = "manage_datasources";
    /** agent 的 embed / IM 渠道集成。 */
    public static final String MANAGE_CHANNELS = "manage_channels";
    /** 检索基础设施：向量库、解析引擎、存储连通性检查。 */
    public static final String MANAGE_VECTOR_STORES = "manage_vector_stores";
    /** 对象/文件存储后端实例（CRUD、连通性测试、租户默认选择）。
     *  <b>与 manage_vector_stores 分离</b>：存储后端是文件持久层（持有对象存储凭据
     *  与用户可控 endpoint），不是检索基础设施。 */
    public static final String MANAGE_STORAGE_BACKENDS = "manage_storage_backends";
    /** 租户级联网搜索供应商配置与凭据。 */
    public static final String MANAGE_WEB_SEARCH = "manage_web_search";
    /** 跑/看评测任务，无需完整租户所有权。 */
    public static final String RUN_EVALUATIONS = "run_evaluations";
    /** 租户成员与邀请的管理。<b>不含</b> API Key 管理、租户删除、所有权转移。 */
    public static final String MANAGE_MEMBERS = "manage_members";
    /** 组织/空间协作面（空间成员、加入流程）。
     *  <b>不授予</b> KB/agent 分享管理——分享管理保留给 full-access Key（与 JWT），
     *  scoped Key 保持 default-deny，本能力永远不放宽它。 */
    public static final String MANAGE_SPACES = "manage_spaces";
    /** /tenants 下的租户级集成设置（API 主体模式、请求头、租户 KV）。
     *  不含 API Key 管理、成员管理、租户删除、所有权转移。 */
    public static final String MANAGE_TENANT_SETTINGS = "manage_tenant_settings";

    // ── 平台级（PlatformOnly 路由专用；租户 Key 永不满足） ──
    public static final String SYSTEM_TENANTS_READ = "system_tenants_read";
    public static final String SYSTEM_TENANTS_MANAGE = "system_tenants_manage";
    public static final String SYSTEM_SETTINGS_READ = "system_settings_read";
    public static final String SYSTEM_SETTINGS_MANAGE = "system_settings_manage";
    public static final String SYSTEM_RUNTIME_READ = "system_runtime_read";
    public static final String SYSTEM_RUNTIME_MANAGE = "system_runtime_manage";
    public static final String SYSTEM_AUDIT_READ = "system_audit_read";

    /**
     * 全部已知能力，**顺序与 Go 的 switch 分支顺序一致**
     * （仅用于文档/调试，判定一律走 {@link #normalize(String)}）。
     */
    public static final List<String> ALL = List.of(
            RETRIEVE, CHAT, READ_AGENTS, INGEST, MANAGE_KBS, MANAGE_AGENTS, MESSAGE_HISTORY,
            MANAGE_MODELS, MANAGE_MCP_SERVICES, MANAGE_DATASOURCES, MANAGE_CHANNELS,
            MANAGE_VECTOR_STORES, MANAGE_STORAGE_BACKENDS, MANAGE_WEB_SEARCH,
            RUN_EVALUATIONS, MANAGE_MEMBERS, MANAGE_SPACES, MANAGE_TENANT_SETTINGS,
            SYSTEM_TENANTS_READ, SYSTEM_TENANTS_MANAGE, SYSTEM_SETTINGS_READ,
            SYSTEM_SETTINGS_MANAGE, SYSTEM_RUNTIME_READ, SYSTEM_RUNTIME_MANAGE,
            SYSTEM_AUDIT_READ);

    private static final Set<String> KNOWN = Set.copyOf(ALL);

    private APIKeyCapability() {
    }

    /**
     * 对照 {@code NormalizeAPIKeyCapability}（L169-224）：输入去空白 + 转小写后
     * 命中已知能力则返回归一后的字符串，**无法识别返回 {@code null}**，
     * 让调用方显式丢弃（Go 返回 {@code ""}）。
     */
    public static String normalize(String capability) {
        if (capability == null) {
            return null;
        }
        String norm = capability.trim().toLowerCase(Locale.ROOT);
        return KNOWN.contains(norm) ? norm : null;
    }

    /**
     * 对照 {@code NormalizeAPIKeyCapabilities}（L227-243）：去重 + 丢弃未知能力，
     * 保持首次出现顺序。
     *
     * <p><b>返回值恒为可变列表且从不为 null</b>——Go 的
     * {@code out := make(StringArray, 0, len(in))} 保证空输入产出空切片（{@code []}）
     * 而非 nil。这一点有外部契约后果：{@code tenantAPIKeyResponse.capabilities}
     * 走本函数归一化后，**永远是数组**（full-access Key 也是 {@code []}），
     * 而 {@code knowledge_base_ids} 没有这一步，nil 时输出 {@code null}。</p>
     */
    public static List<String> normalizeAll(List<String> in) {
        List<String> out = new ArrayList<>(in == null ? 0 : in.size());
        Set<String> seen = new LinkedHashSet<>();
        if (in == null) {
            return out;
        }
        for (String item : in) {
            String norm = normalize(item);
            if (norm == null) {
                continue;
            }
            if (seen.add(norm)) {
                out.add(norm);
            }
        }
        return out;
    }
}
