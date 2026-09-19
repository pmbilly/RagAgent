package com.ragagent.sandbox.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.crypto.CryptoService;
import org.apache.ibatis.type.BaseTypeHandler;import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code tenant_skills.envs} 列的 TypeHandler——对照 Go
 * {@code SkillEnvVars.Value()/Scan()}（internal/types/tenant_env_vars.go L63-122）。
 *
 * <h2>写路径（Value）</h2>
 * 接收者从不被修改：先拷贝，再对每个条目的 {@code value} 逐字段 AES-GCM 加密（Go 的
 * {@code encryptEnvValue}：明文为空或无 key → 原样；加密失败 → 中止写入，Go 注释原文：
 * 把明文写进可查询的 JSONB 列等于静默泄漏）。nil 列写 SQL NULL；非 nil 空列写 {@code []}
 * （Go 的 {@code json.Marshal(make([]row, 0))}）。
 *
 * <h2>读路径（Scan）</h2>
 * 宽容解密：解不开（SYSTEM_AES_KEY 缺失/轮换）的值置空并记日志，而不是拖垮整行加载
 * ——skill 必须保持可列出、可编辑，即使其存量凭据变得不可读（对照
 * TenantSandboxConfig.Scan 语义）。标签 {@code skill_env_vars.<name>}。
 *
 * <h2>列内 JSON 键序</h2>
 * {@code name, description, required, value}（Go {@code skillEnvVarRow} 声明序）；
 * description/required/value 带 omitempty（NON_EMPTY / NON_DEFAULT）。
 */
public class SkillEnvVarsTypeHandler extends BaseTypeHandler<SkillEnvVars> {

    private static final Logger log = LoggerFactory.getLogger(SkillEnvVarsTypeHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 对照 Go 的 {@code skillEnvVarRow}：声明在列上的行形态。 */
    @JsonPropertyOrder({"name", "description", "required", "value"})
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    static class Row {
        @JsonProperty("name")
        String name = "";
        @JsonProperty("description")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String description = "";
        @JsonProperty("required")
        boolean required;
        @JsonProperty("value")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String value = "";
    }

    private final CryptoService cryptoService;

    public SkillEnvVarsTypeHandler() {
        this(new CryptoService());
    }

    public SkillEnvVarsTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, SkillEnvVars parameter,
            JdbcType jdbcType) throws SQLException {
        byte[] key = cryptoService.getAESKey();
        try {
            List<Row> rows = new java.util.ArrayList<>(parameter.size());
            for (SkillEnvVar entry : parameter) {
                String encrypted;
                if (entry.getValue() == null || entry.getValue().isEmpty() || key == null) {
                    encrypted = entry.getValue();
                } else {
                    try {
                        encrypted = cryptoService.encryptAESGCM(entry.getValue(), key);
                    } catch (RuntimeException e) {
                        // 加密失败中止写入：把明文落进可查询的列是静默泄漏（Go 注释原文）
                        throw new SQLException("encrypt skill_env_vars." + entry.getName()
                                + ": " + e.getMessage(), e);
                    }
                }
                Row row = new Row();
                row.name = entry.getName();
                row.description = entry.getDescription();
                row.required = entry.isRequired();
                row.value = encrypted;
                rows.add(row);
            }
            ps.setObject(i, MAPPER.writeValueAsString(rows), Types.OTHER);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("serialize skill env vars failed", e);
        }
    }

    @Override
    public SkillEnvVars getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public SkillEnvVars getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public SkillEnvVars getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private SkillEnvVars parse(String raw) throws SQLException {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Row[] rows;
        try {
            rows = MAPPER.readValue(raw, Row[].class);
        } catch (Exception e) {
            throw new SQLException("deserialize skill env vars failed", e);
        }
        if (rows == null) {
            return null;
        }
        SkillEnvVars out = new SkillEnvVars();
        for (Row row : rows) {
            out.add(new SkillEnvVar(row.name, row.description, row.required,
                    decryptEnvValue(row.name, row.value)));
        }
        return out;
    }

    /**
     * 对照 Go 的 {@code decryptEnvValue}：解不开的密文按「未设置」报告而非失败，
     * 让 key 被轮换掉的行保持可列出（Go 注释原文）。
     */
    private String decryptEnvValue(String name, String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        CryptoService.LenientResult r = cryptoService.decryptStoredSecretLenient(stored);
        if (r.ok()) {
            return r.plaintext();
        }
        log.warn("[crypto] skill_env_vars.{}: decrypt failed "
                + "(SYSTEM_AES_KEY missing/rotated?), treating as unset", name);
        return "";
    }
}
