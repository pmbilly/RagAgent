package com.ragagent.auth.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.crypto.CryptoService;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * {@code tenants.api_principal_config}（jsonb）的 TypeHandler。
 *
 * <ul>
 *   <li><b>写</b>：拷贝 → hmac_secret AES-256-GCM 加密（key 缺失时明文落库）→
 *       Jackson 序列化 →
 *       {@code setObject(i, json, Types.OTHER)}。⚠️ PG jsonb 必须 setObject(OTHER)，
 *       setString 会被服务端拒绝。</li>
 *   <li><b>读</b>：反序列化（未知键容忍在 {@link APIPrincipalConfig} 上）→
 *       hmac_secret 宽容解密——解密失败置空并视为未配置，行照常加载
 *       （日志 "[crypto] tenant api_principal_config.hmac_secret"）。</li>
 *   <li><b>NULL 列</b>：读回 null。</li>
 * </ul>
 */
@MappedTypes(APIPrincipalConfig.class)
public class APIPrincipalConfigTypeHandler extends BaseTypeHandler<APIPrincipalConfig> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CryptoService cryptoService;

    public APIPrincipalConfigTypeHandler() {
        this(new CryptoService());
    }

    public APIPrincipalConfigTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, APIPrincipalConfig parameter, JdbcType jdbcType)
            throws SQLException {
        APIPrincipalConfig cp = new APIPrincipalConfig();
        cp.mode = parameter.mode;
        cp.directHeaderName = parameter.directHeaderName;
        cp.signedTokenHeaderName = parameter.signedTokenHeaderName;
        cp.requireDirectHeader = parameter.requireDirectHeader;
        cp.hmacSecret = parameter.hmacSecret;
        byte[] key = cryptoService.getAESKey();
        if (key != null && cp.hmacSecret != null && !cp.hmacSecret.isEmpty()) {
            cp.hmacSecret = cryptoService.encryptAESGCM(cp.hmacSecret, key);
        }
        try {
            ps.setObject(i, MAPPER.writeValueAsString(cp), Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize api principal config failed", e);
        }
    }

    @Override
    public APIPrincipalConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public APIPrincipalConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public APIPrincipalConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private APIPrincipalConfig parse(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            APIPrincipalConfig cfg = MAPPER.readValue(json, APIPrincipalConfig.class);
            CryptoService.LenientResult secret = cryptoService.decryptStoredSecretLenient(cfg.hmacSecret);
            cfg.hmacSecret = secret.ok() ? secret.plaintext() : "";
            return cfg;
        } catch (Exception e) {
            throw new SQLException("deserialize api principal config failed", e);
        }
    }
}
