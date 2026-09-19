package com.ragagent.vectorstore.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.crypto.CryptoService;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * vector_stores.connection_config jsonb 列的 TypeHandler
 * （对照 Go {@code ConnectionConfig.Value()/Scan()}）。
 *
 * <ul>
 *   <li><b>写</b>：password / api_key 非空且有 key → AES-GCM 加密（失败保留明文）。</li>
 *   <li><b>读</b>：**严格**解密（DecryptStoredSecret）——失败抛错拖垮行加载
 *       （Go Scan 同语义，与 wsp 参数列的宽容策略刻意不同）。</li>
 * </ul>
 */
public class ConnectionConfigTypeHandler extends BaseTypeHandler<ConnectionConfig> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CryptoService cryptoService;

    public ConnectionConfigTypeHandler() {
        this(new CryptoService());
    }

    public ConnectionConfigTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, ConnectionConfig parameter,
            JdbcType jdbcType) throws SQLException {
        ConnectionConfig out = parameter.copy();
        byte[] key = cryptoService.getAESKey();
        if (key != null) {
            if (out.password != null && !out.password.isEmpty()) {
                try {
                    out.password = cryptoService.encryptAESGCM(out.password, key);
                } catch (RuntimeException e) {
                    // Go: err == nil 才替换
                }
            }
            if (out.apiKey != null && !out.apiKey.isEmpty()) {
                try {
                    out.apiKey = cryptoService.encryptAESGCM(out.apiKey, key);
                } catch (RuntimeException e) {
                    // 同上
                }
            }
        }
        try {
            // PG jsonb 列必须 setObject(Types.OTHER)（setString 报 "column ... is of type jsonb but expression is of type character varying"，§9 阶段 2）
            ps.setObject(i, MAPPER.writeValueAsString(out), java.sql.Types.OTHER);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("serialize connection_config failed", e);
        }
    }

    @Override
    public ConnectionConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public ConnectionConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public ConnectionConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private ConnectionConfig parse(String raw) throws SQLException {
        if (raw == null || raw.isEmpty()) {
            return new ConnectionConfig();
        }
        ConnectionConfig c;
        try {
            c = MAPPER.readValue(raw, ConnectionConfig.class);
        } catch (Exception e) {
            throw new SQLException("deserialize connection_config failed", e);
        }
        try {
            c.password = cryptoService.decryptStoredSecret(c.password);
        } catch (RuntimeException e) {
            throw new SQLException("decrypt vector store connection password", e);
        }
        try {
            c.apiKey = cryptoService.decryptStoredSecret(c.apiKey);
        } catch (RuntimeException e) {
            throw new SQLException("decrypt vector store connection api_key", e);
        }
        return c;
    }
}
