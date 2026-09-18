package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * sessions 表实体（对照 Go {@code types.Session}，internal/types/session.go L76-139）。
 *
 * <p><b>响应形态</b>：GET /sessions/{id} 与 POST /sessions 都把本对象**直接**放进
 * {@code {"success":true,"data":...}} 的 data 里，所以下面是 **Go struct 声明序**，
 * 不是字母序（对照 §9 的 JSON 键序规则：struct 按声明序、map 按字母序）。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ul>
 *   <li><b>钩子 BeforeCreate</b>（L141-144）：无条件 {@code s.ID = uuid.New().String()}——
 *       注意它**覆盖**调用方传入的 ID，不是"为空才生成"。<br>
 *       等效 Java：{@code SessionRepository.create} 无条件用新 UUID 覆盖。</li>
 *   <li><b>软删除</b>：{@code gorm.DeletedAt}。按 §9 的既定做法**不用** {@code @TableLogic}，
 *       而是在每条查询里显式写 {@code deleted_at IS NULL}（datetime 逻辑删除值在 MP 各版本
 *       行为敏感，显式条件语义确定）。{@code Delete} 等效为 UPDATE deleted_at=now()。</li>
 *   <li><b>默认排序</b>：仓库层显式 {@code Order("updated_at DESC")}（L100/L129），
 *       列表另有 pin 优先的排序串（见 {@code SessionRepository.queryPaged}）。</li>
 *   <li><b>jsonb 列 {@code agent_config}</b>：Go 把它当成 {@code SessionLastRequestState} 存
 *       （字段注释说明了为什么复用这一列："避免新增迁移"）。<b>注意 Go 的
 *       {@code sessionRepository.Update} 不写这一列</b>，只有
 *       {@code UpdateLastRequestState} 单独写——Java 侧同样分开。</li>
 *   <li><b>未映射的遗留列</b>：迁移 000001 建的一批策略配置列（{@code knowledge_base_id} /
 *       {@code max_rounds} / {@code enable_rewrite} / … / {@code summary_parameters}）
 *       在 Go 的 struct 里是**注释掉的**，因此 GORM 不碰它们、保持 DB 默认值。
 *       Java 实体同样**不映射**——那批列上的 NOT NULL 由 DB 默认值兜住，别顺手补上。</li>
 * </ul>
 */
@TableName(value = "sessions", autoResultMap = true)
@JsonPropertyOrder({
        "id", "title", "description", "tenant_id", "user_id", "is_pinned", "pinned_at",
        "last_request_state", "sandbox_config_id", "created_at", "updated_at", "deleted_at",
        "im_platform"
})
public class Session {

    /** 对照 Go {@code SessionSourceAPI}：跨整租户的 API-Key 会话视图（仅 Admin+，且不做按人隔离）。 */
    public static final String SOURCE_API = "api";
    /** 对照 Go {@code SessionSourceWeb}：用户自己的 Web 控制台会话。 */
    public static final String SOURCE_WEB = "web";
    /** 对照 Go {@code types.EmbedSessionMarkerPrefix}（embed_channel.go L153）。 */
    public static final String EMBED_SESSION_MARKER_PREFIX = "embed_channel:";
    /** 对照 Go {@code types.SkillMaintenanceSessionMarker}（tenant_skill.go L40）。 */
    public static final String SKILL_MAINTENANCE_SESSION_MARKER = "skill_maintenance:";

    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title = "";

    @JsonProperty("description")
    private String description = "";

    @JsonProperty("tenant_id")
    private Long tenantId;

    /**
     * 会话的所有者范围。WeKnora 用户 UUID、API 外部用户主体、embed 访客主体**共用这一列**。
     *
     * <p>值为空的行是历史的/由 API 创建的租户级会话——可见性判定要把它们算进来
     * （见 {@code SessionRepository.applyUserScope}）。</p>
     */
    @JsonProperty("user_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String userId = "";

    /**
     * <b>字段名不带 {@code is} 前缀是刻意的</b>，两个地方都依赖这一点：
     * <ol>
     *   <li>Jackson：字段 {@code isPinned} 的隐式属性名是 "isPinned"，而 getter
     *       {@code isPinned()} 的隐式名是 "pinned"——两者对不上就会**各生成一个属性**，
     *       JSON 里同时冒出 {@code is_pinned} 和 {@code pinned} 两个键（真实踩过）。</li>
     *   <li>MyBatis-Plus 的 lambda：{@code Session::isPinned} 按 PropertyNamer 推成
     *       "pinned"，要能对上实体字段名才找得到列映射。</li>
     * </ol>
     * 列名由 {@code @TableField("is_pinned")} 显式给出。
     */
    @TableField("is_pinned")
    @JsonProperty("is_pinned")
    private boolean pinned;

    /** 置顶时刻；未置顶时为 null（Go 的 *time.Time）。 */
    @JsonProperty("pinned_at")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OffsetDateTime pinnedAt;

    /**
     * 上次发问时的输入栏状态（agent / 模型 / KB 范围 / 联网 / MCP）。
     *
     * <p>存进**遗留的 {@code agent_config} jsonb 列**以避免新增迁移。纯 UI 记忆，
     * 不驱动任何后端行为。</p>
     */
    @TableField(value = "agent_config", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("last_request_state")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SessionLastRequestState lastRequestState;

    /** 本会话**当前存活的** sandbox 建在哪个配置上；空表示没有存活 sandbox。 */
    @JsonProperty("sandbox_config_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String sandboxConfigId = "";

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * 会话绑定的 IM 平台（如 feishu / wecom）。**不是 sessions 表的列**——
     * 它存在 {@code im_channel_sessions} 里，读的时候才由 LEFT JOIN 填进来，
     * 好让 Web 控制台不必再查一次就能给会话分来源。</p>
     */
    @TableField(exist = false)
    @JsonProperty("im_platform")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String imPlatform;

    public Session() {
    }

    // ── 对照 types/session.go 的包级判定函数 ────────────────────────────────

    /**
     * 列表的来源筛选是否会暴露**租户级**渠道流量（API / IM / embed）。
     *
     * <p>对照 Go {@code SessionListSourceRequiresAdmin}（L157-163）：空串与 {@code web}
     * （忽略大小写）返回 false，**其余一切都返回 true**——包括未知的 source 值。</p>
     */
    public static boolean listSourceRequiresAdmin(String source) {
        String src = source == null ? "" : source.trim();
        if (src.isEmpty() || src.equalsIgnoreCase(SOURCE_WEB)) {
            return false;
        }
        return true;
    }

    /**
     * 该会话是否为「渠道托管流量」——非管理员的 Web 用户不该从控制台打开。
     *
     * <p>对照 Go {@code SessionRequiresAdminConsoleRead}（L167-185）。四条判定**逐条照抄**，
     * 顺序无影响但都要有：API owner 前缀、embed 标记（description 前缀**或** user_id 前缀）、
     * skill 维护标记、以及 IM 平台非空。</p>
     */
    public static boolean requiresAdminConsoleRead(Session session, String imPlatform) {
        if (session == null) {
            return false;
        }
        String owner = session.getUserId() == null ? "" : session.getUserId();
        if (SessionOwnerIds.isApiSessionOwnerId(owner)) {
            return true;
        }
        String description = session.getDescription() == null ? "" : session.getDescription();
        if (description.startsWith(EMBED_SESSION_MARKER_PREFIX)
                || owner.startsWith(SessionOwnerIds.EMBED_SESSION_PREFIX)) {
            return true;
        }
        // 纵深防御：列表本来就不显示 skill 维护会话，这条保证泄漏出去的 id 也打不开。
        if (isSkillMaintenanceDescription(description)) {
            return true;
        }
        return imPlatform != null && !imPlatform.trim().isEmpty();
    }

    /** 对照 Go {@code IsSkillMaintenanceDescription}：description 是否带隐藏标记。 */
    public static boolean isSkillMaintenanceDescription(String description) {
        return description != null && description.startsWith(SKILL_MAINTENANCE_SESSION_MARKER);
    }

    /**
     * 对照 Go {@code SanitizeClientSessionDescription}（L197-205）。
     *
     * <p>已经落库的维护会话**保留原 description**，这样一次 PUT 无法把它"洗白"再暴露出来；
     * 其余情况丢掉客户端塞进来的标记，而不是接受它。</p>
     */
    public static String sanitizeClientSessionDescription(String incoming, String existing) {
        if (isSkillMaintenanceDescription(existing)) {
            return existing;
        }
        if (isSkillMaintenanceDescription(incoming)) {
            return "";
        }
        return incoming;
    }

    // ── 访问器 ──────────────────────────────────────────────────────────────

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String v) {
        this.title = v == null ? "" : v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = v == null ? "" : v;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long v) {
        this.tenantId = v;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String v) {
        this.userId = v;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean v) {
        this.pinned = v;
    }

    public OffsetDateTime getPinnedAt() {
        return pinnedAt;
    }

    public void setPinnedAt(OffsetDateTime v) {
        this.pinnedAt = v;
    }

    public SessionLastRequestState getLastRequestState() {
        return lastRequestState;
    }

    public void setLastRequestState(SessionLastRequestState v) {
        this.lastRequestState = v;
    }

    public String getSandboxConfigId() {
        return sandboxConfigId;
    }

    public void setSandboxConfigId(String v) {
        this.sandboxConfigId = v;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime v) {
        this.createdAt = v;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime v) {
        this.updatedAt = v;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime v) {
        this.deletedAt = v;
    }

    public String getImPlatform() {
        return imPlatform;
    }

    public void setImPlatform(String v) {
        this.imPlatform = v;
    }
}
