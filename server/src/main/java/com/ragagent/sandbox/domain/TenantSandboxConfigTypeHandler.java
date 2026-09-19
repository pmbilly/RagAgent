package com.ragagent.sandbox.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ragagent.common.crypto.CryptoService;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code tenant_sandbox_configs.config} jsonb 列的 TypeHandler——对照 Go
 * {@code TenantSandboxConfig.Value()/Scan()} driver 钩子（internal/types/tenant.go L862-953）。
 *
 * <h2>写路径（Value）</h2>
 * 每个携带密钥的字段（Cube.APIKey、E2B.APIKey、全部 EnvVars 值、Network 注入 header 值）
 * 在整体序列化前经 AES-256-GCM 加密（{@code enc:v1:} 前缀，复用阶段 2 已验证跨语言互操作的
 * {@link CryptoService}）。接收者不被修改：先拷贝再加密。明文为空或无 key → 原样保留；
 * 加密失败 → 保留明文（Go 的 {@code if err != nil return plain} 分支）。
 *
 * <h2>读路径（Scan）</h2>
 * 宽容解密：解不开（SYSTEM_AES_KEY 缺失/轮换）的密钥置空并记日志，而不是拖垮整个加载
 * ——租户必须保持可列出，即使其沙箱凭据变得不可读（对照 ModelParameters.Scan 语义）。
 * 标签与 Go 一致：{@code cube.api_key} / {@code e2b.api_key} / {@code env_vars.<name>} /
 * {@code network.injected_header}。
 *
 * <h2>mapper 配置</h2>
 * <ul>
 *   <li>写库必须 {@code setObject(Types.OTHER)}（setString 在 PG 报
 *       "column ... is of type jsonb but expression is of type character varying"，
 *       §9「波 2 基础设施」第 1 条）；</li>
 *   <li>读 mapper 必须 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}（Go json.Unmarshal 默认
 *       忽略未知键；§7.5 第 6 条 + §9 波 2 FAQ 的 PG DEFAULT 演进教训）并注册
 *       JavaTimeModule（SkillImageConfig.built_at 是 OffsetDateTime，§9 波 1 G6 第 2 条）。</li>
 * </ul>
 */
public class TenantSandboxConfigTypeHandler extends BaseTypeHandler<TenantSandboxConfig> {

    private static final Logger log = LoggerFactory.getLogger(TenantSandboxConfigTypeHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CryptoService cryptoService;

    public TenantSandboxConfigTypeHandler() {
        this(new CryptoService());
    }

    public TenantSandboxConfigTypeHandler(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, TenantSandboxConfig parameter,
            JdbcType jdbcType) throws SQLException {
        byte[] key = cryptoService.getAESKey();
        TenantSandboxConfig cp = parameter.shallowCopy();

        // 对照 Go encrypt 闭包：空串或无 key 原样；失败保留明文
        java.util.function.UnaryOperator<String> encrypt = plain -> {
            if (plain == null || plain.isEmpty() || key == null) {
                return plain;
            }
            try {
                return cryptoService.encryptAESGCM(plain, key);
            } catch (RuntimeException e) {
                return plain;
            }
        };

        if (parameter.getCube() != null) {
            CubeSandboxConfig cube = parameter.getCube().copy();
            cube.setApiKey(encrypt.apply(cube.getApiKey()));
            cp.setCube(cube);
        }
        if (parameter.getE2b() != null) {
            E2BSandboxConfig e2b = parameter.getE2b().copy();
            e2b.setApiKey(encrypt.apply(e2b.getApiKey()));
            cp.setE2b(e2b);
        }
        // 键保持可读，运维仍能看到设置了哪些变量（Go 注释原文）
        if (parameter.getEnvVars() != null && !parameter.getEnvVars().isEmpty()) {
            Map<String, String> envVars = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : parameter.getEnvVars().entrySet()) {
                envVars.put(e.getKey(), encrypt.apply(e.getValue()));
            }
            cp.setEnvVars(envVars);
        }
        // 注入 header 是不进沙箱就调 API 的凭据，值是密钥
        if (parameter.getNetwork() != null) {
            cp.setNetwork(parameter.getNetwork().cloneWithSecrets(encrypt));
        }

        try {
            // PG jsonb 必须 setObject(Types.OTHER)（§9 阶段 2 / 波 2 基础设施第 1 条）
            ps.setObject(i, MAPPER.writeValueAsString(cp), Types.OTHER);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("serialize tenant sandbox config failed", e);
        }
    }

    @Override
    public TenantSandboxConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public TenantSandboxConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public TenantSandboxConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private TenantSandboxConfig parse(String raw) throws SQLException {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        TenantSandboxConfig c;
        try {
            c = MAPPER.readValue(raw, TenantSandboxConfig.class);
        } catch (Exception e) {
            throw new SQLException("deserialize tenant sandbox config failed", e);
        }

        // 对照 Go decrypt 闭包：空串 → 空串；解不开 → 置空 + 日志（宽容）
        java.util.function.BiFunction<String, String, String> decrypt = (stored, label) -> {
            if (stored == null || stored.isEmpty()) {
                return "";
            }
            CryptoService.LenientResult r = cryptoService.decryptStoredSecretLenient(stored);
            if (r.ok()) {
                return r.plaintext();
            }
            log.warn("[crypto] tenant_sandbox_config.{}: decrypt failed "
                    + "(SYSTEM_AES_KEY missing/rotated?), treating as unconfigured", label);
            return "";
        };

        if (c.getCube() != null) {
            c.getCube().setApiKey(decrypt.apply(c.getCube().getApiKey(), "cube.api_key"));
        }
        if (c.getE2b() != null) {
            c.getE2b().setApiKey(decrypt.apply(c.getE2b().getApiKey(), "e2b.api_key"));
        }
        if (c.getEnvVars() != null) {
            for (Map.Entry<String, String> e : c.getEnvVars().entrySet()) {
                e.setValue(decrypt.apply(e.getValue(), "env_vars." + e.getKey()));
            }
        }
        if (c.getNetwork() != null) {
            // CloneWithSecrets 没有规则/header 上下文，轮换 key 的失败共用一个标签，
            // 而不是标识到具体凭据（Go 注释原文）
            c.setNetwork(c.getNetwork().cloneWithSecrets(stored ->
                    decrypt.apply(stored, "network.injected_header")));
        }
        return c;
    }
}
