package com.ragagent.websearch.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.ragagent.common.crypto.CryptoService;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * web_search_providers.parameters jsonb 列的 TypeHandler。
 *
 * <ul>
 *   <li><b>写</b>：有 AES key 且 api_key 非空 → 加密后整体序列化；
 *       加密失败保留明文。</li>
 *   <li><b>读</b>：宽容解密——解密失败置空并记日志（行级加载不拖垮列表）；NULL 列返回零值对象。</li>
 * </ul>
 *
 * <p>读路径的 mapper 必须 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
 * （忽略未知键，列内新增字段不致读失败）。</p>
 */
public class WebSearchParamsTypeHandler extends BaseTypeHandler<WebSearchProviderParams> {

    private static final Logger log = LoggerFactory.getLogger(WebSearchParamsTypeHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CryptoService cryptoService;

    public WebSearchParamsTypeHandler() {
        this(new CryptoService());
    }

    public WebSearchParamsTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, WebSearchProviderParams parameter,
            JdbcType jdbcType) throws SQLException {
        WebSearchProviderParams out = new WebSearchProviderParams();
        out.setApiKey(parameter.getApiKey());
        out.setEngineId(parameter.getEngineId());
        out.setBaseUrl(parameter.getBaseUrl());
        out.setProxyUrl(parameter.getProxyUrl());
        out.setExtraConfig(parameter.getExtraConfig());
        byte[] key = cryptoService.getAESKey();
        if (key != null && out.getApiKey() != null && !out.getApiKey().isEmpty()) {
            try {
                out.setApiKey(cryptoService.encryptAESGCM(out.getApiKey(), key));
            } catch (RuntimeException e) {
                // 加密失败保留明文
                log.warn("[crypto] web search provider api_key encrypt failed, storing plaintext");
            }
        }
        try {
            // PG jsonb 列必须 setObject(Types.OTHER)（setString 报 "column ... is of type jsonb but expression is of type character varying"）
            ps.setObject(i, MAPPER.writeValueAsString(out), java.sql.Types.OTHER);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("serialize web search provider parameters failed", e);
        }
    }

    @Override
    public WebSearchProviderParams getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public WebSearchProviderParams getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public WebSearchProviderParams getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private WebSearchProviderParams parse(String raw) throws SQLException {
        if (raw == null || raw.isEmpty()) {
            return new WebSearchProviderParams();
        }
        WebSearchProviderParams p;
        try {
            p = MAPPER.readValue(raw, WebSearchProviderParams.class);
        } catch (Exception e) {
            throw new SQLException("deserialize web search provider parameters failed", e);
        }
        CryptoService.LenientResult r = cryptoService.decryptStoredSecretLenient(p.getApiKey());
        if (r.ok()) {
            p.setApiKey(r.plaintext());
        } else {
            log.warn("[crypto] web search provider api_key: decrypt failed (SYSTEM_AES_KEY missing/rotated?), "
                    + "treating as unconfigured");
            p.setApiKey("");
        }
        return p;
    }
}
