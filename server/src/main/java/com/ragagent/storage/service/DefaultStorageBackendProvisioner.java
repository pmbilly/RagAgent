package com.ragagent.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.storage.StorageBackendProvisioner;
import com.ragagent.storage.config.StorageProviderEnv;
import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.storage.mapper.StorageBackendRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link StorageBackendProvisioner} 的实现——「env 快照 → 存储后端实体/config JSON → 落库」。
 *
 * <p>本类整体由 {@code auth/service/TenantService} 搬来（含 Java↔Go 对照注释与键序契约），
 * 只做了一处形态变化：原先私有的 {@code createDefaultStorageBackend} 被拆成
 * "端口方法 {@link #provisionForTenant} + 事务编排留在 auth"——编排里的租户行回写
 * 不是存储域的职责。</p>
 *
 * <p>env 取值走 {@link StorageProviderEnv} 的 {@code @ConfigurationProperties} 绑定：
 * 「按 provider 取环境变量族」；变量名未变，落库形状＝<b>行面 camel</b>。</p>
 */
@Component
public class DefaultStorageBackendProvisioner implements StorageBackendProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackendRepository repository;
    private final StorageConfigCodec codec;
    private final StorageProviderEnv.StorageType storageType;
    private final Map<String, StorageProviderEnv.ProviderEnvFamily> providerEnvs;

    public DefaultStorageBackendProvisioner(StorageBackendRepository repository,
            StorageProviderEnv.StorageType storageType,
            List<StorageProviderEnv.ProviderEnvFamily> providerEnvs,
            StorageConfigCodec codec) {
        this.repository = repository;
        this.codec = codec;
        this.storageType = storageType;
        this.providerEnvs = new LinkedHashMap<>();
        for (StorageProviderEnv.ProviderEnvFamily env : providerEnvs) {
            this.providerEnvs.put(env.provider(), env);
        }
    }

    @Override
    public String provisionForTenant(long tenantId) {
        StorageBackend backend = envDefaultBackend(tenantId);
        if (backend == null) {
            throw new IllegalStateException("no supported default storage backend is configured");
        }
        backend.setLegacyAlias(true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        backend.setCreatedAt(now);
        backend.setUpdatedAt(now);
        // 落库走唯一读写口：凭据在此加密（"私钥落库即密文"）
        repository.create(backend, backend.getConfig() == null
                ? "{}" : codec.encode(backend.getConfig()));
        return backend.getId();
    }

    @Override
    public void deleteForTenant(long tenantId, String backendId) {
        repository.delete(tenantId, backendId);
    }

    /**
     * 进程级 env 快照为该空间落一行只读后端。键序＝
     * {@code writeConfig} 调用序；空串/假值整键省略。STORAGE_TYPE 缺省 "local"；未知 provider → null。
     */
    private StorageBackend envDefaultBackend(long tenantId) {
        String provider = storageType.provider();
        StorageProviderEnv.ProviderEnvFamily env = providerEnvs.get(provider);
        if (env == null) {
            return null;
        }
        StorageBackend b = new StorageBackend();
        b.setId(java.util.UUID.randomUUID().toString());
        b.setTenantId(tenantId);
        b.setName("System " + provider.toUpperCase(java.util.Locale.ROOT));
        b.setProvider(provider);
        b.setSource("env");
        b.setStatus("active");
        ObjectNode c = MAPPER.createObjectNode();
        env.writeConfig(c);
        b.setConfig(c);
        return b;
    }
}
