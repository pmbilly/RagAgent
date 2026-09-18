package com.ragagent.datasource.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.dto.CredentialsResponse;
import com.ragagent.datasource.service.DataSourceService;
import com.ragagent.datasource.service.KnowledgeBridge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源的凭据子资源（对照 Go {@code internal/handler/datasource_credentials.go} 全文，
 * 路由 {@code PUT /datasource/:id/credentials} 与
 * {@code DELETE /datasource/:id/credentials/:field}）。
 *
 * <h2>为什么只有一个逻辑字段</h2>
 * <p>MCP / Model / WebSearch 的凭据是若干个<b>具名字段</b>，可以逐字段增删；数据源的
 * 凭据却是<b>按连接器而异的原子 map</b>（OAuth token 对、Confluence 的 email+token
 * 组合……）。拆成字段会造出"配了一半、根本认证不了"的中间态，所以这里只有一个
 * {@code "credentials"}：PUT 整张替换、DELETE 整张清空。</p>
 *
 * <h2>⚠️ 错误形态与 {@link DataSourceController} <b>不同</b></h2>
 * <p>这个文件全走 {@code c.Error(errors.NewXxxError(...))} —— 也就是全局 ErrorHandler 的
 * <b>AppError 信封</b>：
 * {@code {"error":{"code":N,"details":null,"message":"..."},"success":false}}。
 * 而 {@code datasource.go} 那批全是纯字符串 {@code {"error":"..."}}。两种形态并存是
 * Go 源码的事实，别统一。</p>
 *
 * <h2>与 datasource.go 复制的那份判定有两处刻意的差异（照抄 Go）</h2>
 * <ol>
 *   <li>租户缺失时这里是 <b>400</b> {@code Workspace ID cannot be empty}，
 *       那边是 <b>401</b> {@code unauthorized}；</li>
 *   <li>"知识库不存在"与"知识库不属于本租户"都折叠成同一个 <b>404</b>
 *       {@code data source not found}（那边是 404/403 两种）。</li>
 * </ol>
 */
@RestController
public class DataSourceCredentialsController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceCredentialsController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 对照 Go 的 {@code dataSourceCredentialsPutRequest}（validator 的 Key 前缀）。 */
    private static final String REQUEST_TYPE_NAME = "dataSourceCredentialsPutRequest";

    private final DataSourceService service;
    private final KnowledgeBridge kbBridge;

    public DataSourceCredentialsController(DataSourceService service, KnowledgeBridge kbBridge) {
        this.service = service;
        this.kbBridge = kbBridge;
    }

    /**
     * 对照 Go {@code Put}（L62-95）：整体替换凭据并立刻做一次真实连接校验
     * ——用户当场就知道新 token 对不对，不必等下一次定时同步。
     *
     * <p>成功体是
     * {@code {"data":{"fields":{"credentials":{"configured":bool}}},"success":true}}
     * ——{@code gin.H} 是 map，键按字母序（data &lt; success）。</p>
     */
    @PutMapping("/api/v1/datasource/{id}/credentials")
    public ResponseEntity<?> put(@PathVariable("id") String id,
                                 @RequestBody(required = false) String rawBody) {
        DataSource ds = ownDataSource(id);
        PutRequest req = parsePutBody(rawBody);
        // 对照 binding:"required"（map 判 nil）+ 紧随其后的非空校验
        if (req == null || req.credentials() == null) {
            throw new BizException(AppError.badRequest(requiredFieldMessage()));
        }
        if (req.credentials().isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "credentials map must be non-empty; to remove credentials use "
                            + "DELETE /credentials/credentials"));
        }
        DataSource updated;
        try {
            updated = service.updateDataSourceCredentials(ds.getId(), req.credentials());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to update credentials for data_source_id={}", ds.getId(), e);
            throw new BizException(AppError.badRequest(
                    "failed to update credentials: " + e.getMessage()));
        }
        boolean configured = false;
        try {
            DataSourceConfig parsed = updated.parseConfig();
            if (parsed != null) {
                configured = parsed.hasConfiguredCredentials(updated.getType());
            }
        } catch (RuntimeException ignored) {
            // 对照 Go 的 `if parsed, err := ...; err == nil && parsed != nil`
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", CredentialsResponse.credentials(configured));
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * 对照 Go {@code DeleteField}（L97-115）：只认 {@code "credentials"} 这一个字段名。
     *
     * <p>清空成功是 <b>204</b>；service 报错落 500（不是 400）——与 PUT 的映射刻意不同。</p>
     */
    @DeleteMapping("/api/v1/datasource/{id}/credentials/{field}")
    public ResponseEntity<?> deleteField(@PathVariable("id") String id,
                                         @PathVariable("field") String field) {
        DataSource ds = ownDataSource(id);
        if (!"credentials".equals(field)) {
            throw new BizException(AppError.badRequest("unknown credential field: " + field));
        }
        try {
            service.clearDataSourceCredentials(ds.getId());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to clear credentials for data_source_id={}", ds.getId(), e);
            throw new BizException(AppError.internal(
                    "failed to clear credentials: " + e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 归属判定 ══════════════════════════

    /**
     * 对照 Go {@code ownDataSource}（L37-56）：与 {@code datasource.go} 那份是
     * <b>复制关系</b>，但错误映射不同（见类注释）。
     */
    private DataSource ownDataSource(String id) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            throw new BizException(AppError.badRequest("Workspace ID cannot be empty"));
        }
        DataSource ds;
        try {
            ds = service.getDataSource(id);
        } catch (RuntimeException e) {
            throw new BizException(AppError.notFound("data source not found"));
        }
        if (ds == null) {
            throw new BizException(AppError.notFound("data source not found"));
        }
        KnowledgeBase kb = null;
        try {
            kb = kbBridge.findKnowledgeBase(ds.getKnowledgeBaseId());
        } catch (RuntimeException ignored) {
            // 对照 Go 的 `kb, err := h.kbService.GetKnowledgeBaseByID(...)`
        }
        // ⚠️ 必须用 Objects.equals：两边都是包装类型 Long，`!=` 比的是**引用**——
        // 租户 id 10002 超出 Long 缓存区间（-128..127），装箱后的两个实例恒不相等，
        // 于是每个请求都落 404。这是 Java 侧最容易无声踩到的一条
        //（Go 的 `kb.TenantID != tenantID` 是 uint64 的值比较）。
        if (kb == null || !java.util.Objects.equals(kb.getTenantId(), tenantId)) {
            throw new BizException(AppError.notFound("data source not found"));
        }
        return ds;
    }

    // ══════════════════════════ 请求体 ══════════════════════════

    /**
     * 解析 PUT 的请求体。
     *
     * <p>两态与 Go 一致：空 body → {@code "EOF"}（Go 的 json 层错误）；JSON 语法错误 →
     * {@code err.Error()}（Go 是 encoding/json 原文、Java 是 Jackson 的，措辞不同
     * ——已知差异族）；JSON 合法但字段缺失 → {@code null}，由调用方换成 validator 文案。</p>
     */
    private static PutRequest parsePutBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        try {
            return MAPPER.readValue(rawBody, PutRequest.class);
        } catch (JsonProcessingException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    /**
     * 对照 go-playground/validator v10 对
     * {@code dataSourceCredentialsPutRequest.Credentials} 的 required 失败文案。
     *
     * <p>它是 gin 的 {@code ShouldBindJSON} 在"JSON 合法但字段缺失"时的
     * {@code err.Error()}，Go 直接把它当 message 输出
     * （{@code NewBadRequestError(err.Error())}）。复刻它的理由是它<b>会出现在线上</b>
     * ——契约测试逐字节比对时不能整条掩码掉。</p>
     */
    static String requiredFieldMessage() {
        return "Key: '" + REQUEST_TYPE_NAME + ".Credentials' Error:Field validation for "
                + "'Credentials' failed on the 'required' tag";
    }

    /** 对照 Go 的 {@code dataSourceCredentialsPutRequest}。 */
    record PutRequest(Map<String, Object> credentials) {
    }
}
