package com.ragagent.datasource.controller;

import com.ragagent.common.web.JsonMappers;
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
 * 数据源的凭据子资源（{@code PUT /datasource/:id/credentials} 与
 * {@code DELETE /datasource/:id/credentials/:field}）。
 *
 * <h2>为什么只有一个逻辑字段</h2>
 * <p>MCP / Model / WebSearch 的凭据是若干个<b>具名字段</b>，可以逐字段增删；数据源的
 * 凭据却是<b>按连接器而异的原子 map</b>（OAuth token 对、Confluence 的 email+token
 * 组合……）。拆成字段会造出"配了一半、根本认证不了"的中间态，所以这里只有一个
 * {@code "credentials"}：PUT 整张替换、DELETE 整张清空。</p>
 *
 * <h2>⚠️ 错误形态与 {@link DataSourceController} <b>不同</b></h2>
 * <p>本类全走全局 ErrorHandler 的<b>AppError 信封</b>：
 * {@code {"error":{"code":N,"details":null,"message":"..."},"success":false}}。
 * 而 {@code DataSourceController} 那批全是纯字符串 {@code {"error":"..."}}。
 * 两种形态并存是有意为之，别统一。</p>
 *
 * <h2>与 {@code DataSourceController} 那份归属判定的两处刻意差异</h2>
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

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 校验失败文案里的请求体类型名（保持线上原文）。 */
    private static final String REQUEST_TYPE_NAME = "dataSourceCredentialsPutRequest";

    private final DataSourceService service;
    private final KnowledgeBridge kbBridge;

    public DataSourceCredentialsController(DataSourceService service, KnowledgeBridge kbBridge) {
        this.service = service;
        this.kbBridge = kbBridge;
    }

    /**
     * 整体替换凭据并立刻做一次真实连接校验
     * ——用户当场就知道新 token 对不对，不必等下一次定时同步。
     *
     * <p>成功体是<b>裸对象</b> {@code {"fields":{"credentials":{"configured":bool}}}}
     * （不包 data/success 信封）。</p>
     */
    @PutMapping("/api/v1/datasource/{id}/credentials")
    public ResponseEntity<?> put(@PathVariable("id") String id,
                                 @RequestBody(required = false) String rawBody) {
        DataSource ds = ownDataSource(id);
        PutRequest req = parsePutBody(rawBody);
        // 字段校验（map 判 null）+ 紧随其后的非空校验
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
            // 解析失败或结果为 null 都按"未配置"处理
        }
        return ResponseEntity.ok(CredentialsResponse.credentials(configured));
    }

    /**
     * 只认 {@code "credentials"} 这一个字段名。
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
     * 归属判定：与 {@code DataSourceController} 那份是<b>同型</b>，
     * 但错误映射不同（见类注释）。
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
            // 查询失败按"库不存在"处理
        }
        // ⚠️ 必须用 Objects.equals：两边都是包装类型 Long，`!=` 比的是**引用**——
        // 租户 id 10002 超出 Long 缓存区间（-128..127），装箱后的两个实例恒不相等，
        // 于是每个请求都落 404。这是最容易无声踩到的一条。
        if (kb == null || !java.util.Objects.equals(kb.getTenantId(), tenantId)) {
            throw new BizException(AppError.notFound("data source not found"));
        }
        return ds;
    }

    // ══════════════════════════ 请求体 ══════════════════════════

    /**
     * 解析 PUT 的请求体。
     *
     * <p>三态：空 body → {@code "No content to map due to end-of-input"}；JSON 语法错误 → 回解析器原文；
     * JSON 合法但字段缺失 → {@code null}，由调用方换成校验文案。</p>
     */
    private static PutRequest parsePutBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("No content to map due to end-of-input"));
        }
        try {
            return MAPPER.readValue(rawBody, PutRequest.class);
        } catch (JsonProcessingException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
    }

    /**
     * 请求体缺 {@code credentials} 字段时的校验失败文案（保持线上原文）。
     *
     * <p>它<b>会出现在线上</b>——契约测试逐字节比对时不能整条掩码掉。</p>
     */
    static String requiredFieldMessage() {
        return "Key: '" + REQUEST_TYPE_NAME + ".Credentials' Error:Field validation for "
                + "'Credentials' failed on the 'required' tag";
    }

    /** PUT 请求体。 */
    record PutRequest(Map<String, Object> credentials) {
    }
}
