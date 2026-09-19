package com.ragagent.sandbox.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ragagent.common.crypto.CryptoService;

/**
 * envs 列的加解密往返（对照 Go {@code SkillEnvVars.Value()/Scan()}）：
 * value 字段 AES-GCM 加密落列（enc:v1:）、无 key 明文原样、解不开置空不拖垮加载、
 * {@code json:"-"} 恒不出现在任何 JSON 面。
 */
class SkillEnvVarsTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);

    /** 可注 key 的 handler（对照生产 TypeHandler 构造）。 */
    private static SkillEnvVarsTypeHandler handlerWith(CryptoService crypto) {
        return new SkillEnvVarsTypeHandler(crypto);
    }

    @Test
    void valueRoundTripsThroughEncryptedColumn() throws Exception {
        CryptoService crypto = new CryptoService() {
            @Override
            public byte[] getAESKey() {
                return KEY;
            }
        };
        SkillEnvVarsTypeHandler handler = handlerWith(crypto);

        SkillEnvVars envs = new SkillEnvVars();
        envs.add(new SkillEnvVar("PROBE_TOKEN", "token for probe", true, "sk-super-secret"));
        envs.add(new SkillEnvVar("OPTIONAL_KEY", "", false, ""));

        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        java.util.concurrent.atomic.AtomicReference<Object> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(ps).setObject(Mockito.anyInt(), Mockito.any(), Mockito.eq(Types.OTHER));
        handler.setNonNullParameter(ps, 1, envs, null);

        String columnJson = (String) stored.get();
        // 值以 enc:v1: 密文落列、明文不出现
        assertTrue(columnJson.contains("enc:v1:"), columnJson);
        assertFalse(columnJson.contains("sk-super-secret"));
        // 列内键序 = skillEnvVarRow 声明序 name,description,required,value
        assertTrue(columnJson.startsWith("[{\"name\":\"PROBE_TOKEN\",\"description\":"
                + "\"token for probe\",\"required\":true,\"value\":\"enc:v1:"),
                columnJson);
        // 空值条目：description/required/value 全 omitempty
        assertTrue(columnJson.endsWith(",{\"name\":\"OPTIONAL_KEY\"}]"), columnJson);

        // 回读：明文还原（strict 读回，未知键拒绝——列演进安全性）
        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("envs")).thenReturn(columnJson);
        SkillEnvVars back = handler.getNullableResult(rs, "envs");
        assertEquals(2, back.size());
        assertEquals("sk-super-secret", back.get("PROBE_TOKEN").getValue());
        assertTrue(back.get("PROBE_TOKEN").isRequired());
        assertEquals("token for probe", back.get("PROBE_TOKEN").getDescription());
        assertEquals("", back.get("OPTIONAL_KEY").getValue());
    }

    @Test
    void nilColumnReadsBackNullAndEmptyListWritesEmptyArray() throws Exception {
        SkillEnvVarsTypeHandler handler = handlerWith(new CryptoService());

        // SQL NULL → nil（对照 Go Scan 的 value == nil 分支）
        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("envs")).thenReturn(null);
        assertNull(handler.getNullableResult(rs, "envs"));

        // Go 的 Value()：非 nil 空列写 []，不是 NULL
        SkillEnvVars empty = new SkillEnvVars();
        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        java.util.concurrent.atomic.AtomicReference<Object> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(ps).setObject(Mockito.anyInt(), Mockito.any(), Mockito.eq(Types.OTHER));
        handler.setNonNullParameter(ps, 1, empty, null);
        assertEquals("[]", stored.get());
    }

    @Test
    void undecryptableStoredValueReadsAsUnset() throws Exception {
        SkillEnvVarsTypeHandler handler = handlerWith(new CryptoService());
        // 用别的 key 加密的密文（SYSTEM_AES_KEY 轮换后的存量行）
        CryptoService otherKey = new CryptoService() {
            @Override
            public byte[] getAESKey() {
                return "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8);
            }
        };
        SkillEnvVars encrypted = new SkillEnvVars();
        encrypted.add(new SkillEnvVar("TOK", "", true, "secret-value"));
        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        java.util.concurrent.atomic.AtomicReference<Object> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(ps).setObject(Mockito.anyInt(), Mockito.any(), Mockito.eq(Types.OTHER));
        handlerWith(otherKey).setNonNullParameter(ps, 1, encrypted, null);

        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("envs")).thenReturn((String) stored.get());
        SkillEnvVars back = handler.getNullableResult(rs, "envs");
        // 解不开 → 置空报告（skill 保持可列出、可编辑），不抛
        assertEquals("", back.get("TOK").getValue());
    }

    @Test
    void plaintextWithoutKeyStaysPlaintext() throws Exception {
        // 无 SYSTEM_AES_KEY 的部署降级：原样存（对照 Go encryptEnvValue 的 key==nil 分支）
        SkillEnvVarsTypeHandler handler = handlerWith(new CryptoService());
        SkillEnvVars envs = new SkillEnvVars();
        envs.add(new SkillEnvVar("TOK", "", false, "plain-value"));
        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        java.util.concurrent.atomic.AtomicReference<Object> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(ps).setObject(Mockito.anyInt(), Mockito.any(), Mockito.eq(Types.OTHER));
        handler.setNonNullParameter(ps, 1, envs, null);
        assertTrue(((String) stored.get()).contains("plain-value"));

        var rs = Mockito.mock(java.sql.ResultSet.class);
        Mockito.when(rs.getString("envs")).thenReturn((String) stored.get());
        assertEquals("plain-value",
                handler.getNullableResult(rs, "envs").get("TOK").getValue());
    }

    @Test
    void valueNeverLeaksThroughResponseProjection() throws Exception {
        // toSkillEnvResponses 的 is_set 语义 + value 恒缺席：响应形状在 controller 测，
        // 这里钉住类型面——SkillEnvVar.getValue 带 @JsonIgnore
        SkillEnvVar entry = new SkillEnvVar("PROBE_TOKEN", "token for probe", true, "secret");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(entry);
        assertFalse(json.contains("secret"), json);
        assertFalse(json.contains("value"), json);
        assertEquals("{\"name\":\"PROBE_TOKEN\",\"description\":\"token for probe\","
                + "\"required\":true}", json);
    }

    @Test
    void declaresFindsByName() {
        SkillEnvVars envs = new SkillEnvVars();
        envs.add(new SkillEnvVar("A", "", false, ""));
        envs.add(new SkillEnvVar("B", "", false, "set"));
        assertTrue(envs.declares("B"));
        assertFalse(envs.declares("C"));
        assertEquals("set", envs.get("B").getValue());
        assertNull(envs.get("C"));
    }
}
