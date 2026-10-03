package com.ragagent.mcp.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.ragagent.common.crypto.CryptoService;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文本型秘密列的加密 TypeHandler（写前加密 + 读时宽容解密）。
 *
 * 作用于 mcp_oauth_clients.client_secret、mcp_oauth_tokens.access_token /
 * refresh_token 三个 TEXT 列。
 *
 * - 写：SYSTEM_AES_KEY 存在且值非空 → AES-256-GCM 加密（enc:v1: 前缀）；否则明文落库。
 * - 读：宽容解密——历史明文原样返回；密文解不开（密钥缺失/轮换）→ **置空并记日志**，
 *   绝不让密文冒充满值，也不报错。
 *
 * 刻意不加 {@code @MappedTypes(String.class)}：一旦被注册为全局 String handler，
 * 会污染所有文本列——本 handler 只在 MCP 的密钥列上**按列显式声明**。
 */
public class McpSecretTypeHandler extends BaseTypeHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(McpSecretTypeHandler.class);

    private final CryptoService cryptoService;

    public McpSecretTypeHandler() {
        this(new CryptoService());
    }

    public McpSecretTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        byte[] key = cryptoService.getAESKey();
        String stored = parameter;
        if (key != null && parameter != null && !parameter.isEmpty()) {
            stored = cryptoService.encryptAESGCM(parameter, key);
        }
        ps.setString(i, stored);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return lenient(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return lenient(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return lenient(cs.getString(columnIndex));
    }

    private String lenient(String stored) {
        if (stored == null) {
            return null;
        }
        CryptoService.LenientResult r = cryptoService.decryptStoredSecretLenient(stored);
        if (r.ok()) {
            return r.plaintext();
        }
        log.warn("[crypto] mcp oauth secret: decrypt failed (SYSTEM_AES_KEY missing/rotated?), "
                + "treating as unconfigured");
        return "";
    }
}
