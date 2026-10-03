package com.ragagent.mcp.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.crypto.CryptoService;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * auth_config 的 jsonb TypeHandler（写前加密、读时宽容解密）。
 *
 * **写路径**：拷贝一份再加密 api_key / token（**避免污染调用方内存对象**——
 * 否则同一实例的后续读取会看到密文）。
 * SYSTEM_AES_KEY 缺失时明文落库。
 *
 * **读路径**：宽容解密——历史明文行（无 `enc:v1:` 前缀）原样返回；密文解密失败
 * （密钥缺失/轮换）**静默置空并记日志**，不让密文泄漏成 API key。
 */
@MappedTypes(McpAuthConfig.class)
public class McpAuthConfigTypeHandler extends BaseTypeHandler<McpAuthConfig> {

    private static final Logger log = LoggerFactory.getLogger(McpAuthConfigTypeHandler.class);
    /**
     * 读路径容忍未知属性：Jackson 默认对未知字段报错。
     * 历史行/未来新增字段都不该让整行读不出来。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CryptoService cryptoService;

    public McpAuthConfigTypeHandler() {
        this(new CryptoService());
    }

    public McpAuthConfigTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, McpAuthConfig parameter, JdbcType jdbcType)
            throws SQLException {
        McpAuthConfig out = copyOf(parameter);
        byte[] key = cryptoService.getAESKey();
        if (key != null) {
            if (out.getApiKey() != null && !out.getApiKey().isEmpty()) {
                out.setApiKey(cryptoService.encryptAESGCM(out.getApiKey(), key));
            }
            if (out.getToken() != null && !out.getToken().isEmpty()) {
                out.setToken(cryptoService.encryptAESGCM(out.getToken(), key));
            }
        }
        try {
            // PG jsonb：setObject(OTHER) 让服务端按列类型强转
            ps.setObject(i, MAPPER.writeValueAsString(out), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize mcp auth_config failed", e);
        }
    }

    @Override
    public McpAuthConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public McpAuthConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public McpAuthConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private McpAuthConfig parse(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        McpAuthConfig c;
        try {
            c = MAPPER.readValue(json, McpAuthConfig.class);
        } catch (Exception e) {
            throw new SQLException("parse mcp auth_config failed", e);
        }
        if (c == null) {
            return null;
        }
        CryptoService.LenientResult apiKey = cryptoService.decryptStoredSecretLenient(c.getApiKey());
        if (apiKey.ok()) {
            c.setApiKey(apiKey.plaintext());
        } else {
            log.warn("[crypto] mcp auth_config api_key: decrypt failed (SYSTEM_AES_KEY missing/rotated?), "
                    + "treating as unconfigured");
            c.setApiKey("");
        }
        CryptoService.LenientResult token = cryptoService.decryptStoredSecretLenient(c.getToken());
        if (token.ok()) {
            c.setToken(token.plaintext());
        } else {
            log.warn("[crypto] mcp auth_config token: decrypt failed (SYSTEM_AES_KEY missing/rotated?), "
                    + "treating as unconfigured");
            c.setToken("");
        }
        return c;
    }

    /** 浅拷贝——秘密字段是值类型，浅拷贝足以隔离加密副作用。 */
    private static McpAuthConfig copyOf(McpAuthConfig src) {
        McpAuthConfig c = new McpAuthConfig();
        c.setAuthType(src.getAuthType());
        c.setApiKey(src.getApiKey());
        c.setApiKeyHeader(src.getApiKeyHeader());
        c.setToken(src.getToken());
        c.setCustomHeaders(src.getCustomHeaders());
        c.setScopes(src.getScopes());
        c.setAuthServerMetadataUrl(src.getAuthServerMetadataUrl());
        return c;
    }
}
