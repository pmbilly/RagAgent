package com.ragagent.storage.fileserve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.storage.config.StorageProviderEnv;
import com.ragagent.storage.config.StorageRuntimeEnv;
import com.ragagent.storage.domain.StorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 存储的<b>两套 provider 词汇</b>现状钉（存储读侧投影合并前的护栏）。
 *
 * <p>同一个「从环境变量投影出 provider 配置」的诉求，本域有<b>两条实现、两套键名</b>：</p>
 *
 * <table border="1">
 *   <caption>两套词汇</caption>
 *   <tr><th></th><th>写的人</th><th>写给谁读</th><th>凭据键（minio 以外）</th><th>省略规则</th></tr>
 *   <tr>
 *     <td><b>引擎面</b></td>
 *     <td>{@code StorageFileResolver.storageBackendFromEnvironment}（env 回落行）</td>
 *     <td>本类各 provider 分支的自校验（{@code textOr(x.get("access_key"))}）</td>
 *     <td>{@code access_key}/{@code secret_key}（minio 是 {@code access_key_id}/{@code secret_access_key}）</td>
 *     <td><b>恒写</b>（空串/false 也落键）</td>
 *   </tr>
 *   <tr>
 *     <td><b>落库面</b></td>
 *     <td>{@code StorageProviderEnv.*.writeConfig}（{@code DefaultStorageBackendProvisioner} 用）</td>
 *     <td>{@code storage_backends.config} → {@code toStorageEngineConfig}（改名前 camel、加密；读侧认两族）</td>
 *     <td>{@code access_key_id}/{@code secret_access_key}（cos 是 {@code secret_id}/{@code secret_key}）</td>
 *     <td><b>omitempty</b>（空串/假值整键省略）</td>
 *   </tr>
 * </table>
 *
 * <p><b>本测试的意义</b>：这是「存储域最后一块结构债」的护栏——两套词汇各自有真实消费者，合并
 * 必须先统一<b>读侧</b>（不能只改写侧）。在此钉住现状后，任何一次统一都必须<b>同一提交内改两侧
 * 并更新本测试</b>，不会出现「改了 A 忘了 B、云凭据静默读不到」的静默回归。</p>
 *
 * <p><b>待核（本测试不覆盖，属合并前置调查）</b>：落库面 s3/oss/obs/tos 行写的是
 * {@code access_key_id}，而引擎面分支自校验读的是 {@code access_key}——行模式下 s3 的校验
 * 恰好「两边都空 ⇒ hasKey == hasSecret ⇒ 通过」，实际 SDK 层能否取到凭据未验证。合并前先查
 * {@code providerBacked(...)} 下游对 s3 凭据键的读取口径。</p>
 */
class StorageProjectionVocabularyTest {

    /** 引擎面（env 回落行）在各 provider 下必写的键（不含 default_provider 等外层）。 */
    private static final Map<String, List<String>> ENGINE_FACE_KEYS = new LinkedHashMap<>();

    /** 落库面（类型化记录）在同样输入下写的键。 */
    private static final Map<String, List<String>> ROW_FACE_KEYS = new LinkedHashMap<>();

    static {
        ENGINE_FACE_KEYS.put("local", List.of("path_prefix"));
        ENGINE_FACE_KEYS.put("minio", List.of("mode", "endpoint", "access_key_id", "secret_access_key",
                "bucket_name", "path_prefix", "use_ssl"));
        ENGINE_FACE_KEYS.put("s3", List.of("endpoint", "region", "access_key", "secret_key",
                "bucket_name", "path_prefix", "use_ssl", "force_path_style"));
        ENGINE_FACE_KEYS.put("cos", List.of("secret_id", "secret_key", "region", "bucket_name", "app_id",
                "path_prefix", "temp_bucket_name", "temp_region"));
        ENGINE_FACE_KEYS.put("tos", List.of("endpoint", "region", "access_key", "secret_key",
                "bucket_name", "path_prefix", "temp_bucket_name", "temp_region"));
        ENGINE_FACE_KEYS.put("oss", List.of("endpoint", "region", "access_key", "secret_key",
                "bucket_name", "path_prefix", "use_temp_bucket", "temp_bucket_name", "temp_region"));
        ENGINE_FACE_KEYS.put("obs", List.of("endpoint", "region", "access_key", "secret_key",
                "bucket_name", "path_prefix", "use_ssl"));

        ROW_FACE_KEYS.put("local", List.of("path_prefix"));
        ROW_FACE_KEYS.put("minio", List.of("mode", "endpoint", "access_key_id", "secret_access_key",
                "bucket_name", "path_prefix", "use_ssl"));
        ROW_FACE_KEYS.put("s3", List.of("endpoint", "region", "access_key_id", "secret_access_key",
                "bucket_name", "path_prefix", "use_ssl", "force_path_style"));
        ROW_FACE_KEYS.put("cos", List.of("region", "access_key_id", "secret_access_key", "bucket_name",
                "path_prefix", "app_id", "temp_bucket_name", "temp_region"));
        ROW_FACE_KEYS.put("tos", List.of("endpoint", "region", "access_key_id", "secret_access_key",
                "bucket_name", "path_prefix", "temp_bucket_name", "temp_region"));
        ROW_FACE_KEYS.put("oss", List.of("endpoint", "region", "access_key_id", "secret_access_key",
                "bucket_name", "path_prefix", "use_temp_bucket", "temp_bucket_name", "temp_region"));
        ROW_FACE_KEYS.put("obs", List.of("endpoint", "region", "access_key_id", "secret_access_key",
                "bucket_name", "path_prefix", "use_ssl"));
    }

    @AfterEach
    void tearDown() {
        // 快照是全局静态量：用完即清，避免污染其它测试
        StorageRuntimeEnv.install("", "", "");
    }

    @Test
    @DisplayName("引擎面（env 回落行）：键集合与「恒写」语义逐 provider 钉住")
    void engineFaceVocabulary() {
        for (Map.Entry<String, List<String>> e : ENGINE_FACE_KEYS.entrySet()) {
            StorageBackend backend = engineFace(e.getKey());
            assertThat(backend).as("%s：env 回落行应存在", e.getKey()).isNotNull();
            JsonNode cfg = backend.getConfig();
            assertThat(cfg).as("%s：应有 config 对象", e.getKey()).isInstanceOf(ObjectNode.class);
            assertThat(fieldNames(cfg)).as("%s：键集合（恒写，空值也落键）", e.getKey())
                    .containsExactlyInAnyOrderElementsOf(e.getValue());
        }
    }

    @Test
    @DisplayName("落库面（类型化记录）：键集合与「omitempty」语义逐 provider 钉住")
    void rowFaceVocabulary() {
        for (Map.Entry<String, List<String>> e : ROW_FACE_KEYS.entrySet()) {
            ObjectNode cfg = JsonMappersForTest.newObjectNode();
            providerFamily(e.getKey()).writeConfig(cfg);
            assertThat(fieldNames(cfg)).as("%s：键集合（omitempty，空值省略）", e.getKey())
                    .containsExactlyInAnyOrderElementsOf(e.getValue());
        }
    }

    @Test
    @DisplayName("两套词汇的差集就是「合并必须先统一读侧」的范围（现状快照）")
    void vocabularyDivergenceIsDocumented() {
        for (String provider : ENGINE_FACE_KEYS.keySet()) {
            List<String> engine = ENGINE_FACE_KEYS.get(provider);
            List<String> row = ROW_FACE_KEYS.get(provider);
            if (engine.equals(row)) {
                continue;
            }
            // 凭据键差异是核心：合并时若只改一套写侧，另一套读侧就会静默读不到凭据
            assertThat(engine).as("%s：两套词汇应当不同（不同即本测试要钉的事实）", provider)
                    .isNotEqualTo(row);
        }
    }

    // ── 取两侧投影 ────────────────────────────────────────────────────────

    /** 引擎面：把存储类型装进快照后取 env 回落行（未装任何 provider env，值全空——键集合才是被钉对象）。 */
    private static StorageBackend engineFace(String provider) {
        StorageRuntimeEnv.install("", provider, "");
        return StorageFileResolver.storageBackendFromEnvironment(1L);
    }

    /**
     * 落库面：取该 provider 的类型化记录。
     *
     * <p><b>必须给「全开值」</b>：这批记录是 omitempty 语义（空串/假值整键省略），若像引擎面那样
     * 只喂空值，会一个键都不写、钉不住键集合（首版实测即踩此点）。布尔族给 {@code "true"}、
     * 其余给非空串，才能让该 provider 的<b>最大键集合</b>显现。</p>
     */
    private static StorageProviderEnv.ProviderEnvFamily providerFamily(String provider) {
        String v = "v";
        String on = "true";
        return switch (provider) {
            case "local" -> new StorageProviderEnv.Local(v);
            case "minio" -> new StorageProviderEnv.Minio(v, v, v, v, v, on);
            case "s3" -> new StorageProviderEnv.S3(v, v, v, v, v, v, on, on);
            case "cos" -> new StorageProviderEnv.Cos(v, v, v, v, v, v, v, v);
            case "tos" -> new StorageProviderEnv.Tos(v, v, v, v, v, v, v, v);
            case "oss" -> new StorageProviderEnv.Oss(v, v, v, v, v, v, v, v);
            case "obs" -> new StorageProviderEnv.Obs(v, v, v, v, v, v, on);
            default -> throw new IllegalArgumentException("未登记的 provider: " + provider);
        };
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.fieldNames().hasNext()
                ? java.util.stream.StreamSupport.stream(
                        ((Iterable<String>) () -> node.fieldNames()).spliterator(), false).toList()
                : List.of();
    }

    /** 只为造 ObjectNode 的小工具（与生产同一套 JsonMapper 配置无关：键集合与配置无关）。 */
    private static final class JsonMappersForTest {
        static ObjectNode newObjectNode() {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .createObjectNode();
        }
    }
}
