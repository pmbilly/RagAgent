package com.ragagent.storage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.ragagent.common.storage.StorageBackendProvisioner;
import com.ragagent.storage.dto.StorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 存储后端默认装配（B6 批 1）：env → provider 环境变量族 → 落库 config 的形状钉子。
 *
 * <p>取值走 {@code @ConfigurationProperties} 绑定（本类用测试属性代替 env，松散绑定与 env 同一条路）。
 * 断言要点：键名＝落库面 camel（与 {@code dto/StorageConfig} 同族，B14 合并后）、空值整键省略、
 * {@code use_ssl} 在 {@code S3_USE_SSL} 未设置时缺省为真（对照 Go {@code !EqualFold(env,"false")}）、
 * {@code force_path_style} 只在恰为 "true" 时写出。</p>
 */
@SpringBootTest(properties = {
        "storage.type=s3",
        "s3.endpoint=https://s3.example.com",
        "s3.region=ap-east-1",
        "s3.access-key=AK0",
        "s3.secret-key=SK0",
        "s3.bucket-name=b0-bucket",
        "s3.path-prefix=b3/",
        "s3.force-path-style=true"
})
class DefaultStorageBackendProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StorageBackendProvisioner provisioner;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    @Test
    void provisionsS3BackendFromEnvFamily() throws Exception {
        String id = provisioner.provisionForTenant(TENANT);

        String configText = jdbc.queryForObject(
                "SELECT config FROM storage_backends WHERE id = ?", String.class, id);
        JsonNode config = MAPPER.readTree(configText);

        assertThat(config.path("endpoint").asText()).isEqualTo("https://s3.example.com");
        assertThat(config.path("region").asText()).isEqualTo("ap-east-1");
        assertThat(config.path("accessKeyId").asText()).isEqualTo("AK0");
        assertThat(config.path("secretAccessKey").asText()).isEqualTo("SK0");
        assertThat(config.path("bucketName").asText()).isEqualTo("b0-bucket");
        assertThat(config.path("pathPrefix").asText()).isEqualTo("b3/");
        // S3_USE_SSL 未设置 → 缺省真；S3_FORCE_PATH_STYLE="true" → 显式写出
        assertThat(config.path("useSsl").asBoolean()).isTrue();
        assertThat(config.path("forcePathStyle").asBoolean()).isTrue();

        // 消费者层断言（与 StorageBackendService.configOf/serializeConfig 同一种读法）：
        // 落库配置必须能绑进 StorageConfig——B14 前这里写的是 snake，会被忽略未知键静默丢空
        StorageConfig readBack = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .treeToValue(config, StorageConfig.class);
        assertThat(readBack.accessKeyId).isEqualTo("AK0");
        assertThat(readBack.secretAccessKey).isEqualTo("SK0");
        assertThat(readBack.bucketName).isEqualTo("b0-bucket");
        assertThat(readBack.pathPrefix).isEqualTo("b3/");
        assertThat(readBack.useSsl).isTrue();
        assertThat(readBack.forcePathStyle).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT provider FROM storage_backends WHERE id = ?", String.class, id))
                .isEqualTo("s3");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM storage_backends WHERE id = ?", String.class, id))
                .isEqualTo("System S3");
        assertThat(jdbc.queryForObject(
                "SELECT source FROM storage_backends WHERE id = ?", String.class, id))
                .isEqualTo("env");
    }
}
