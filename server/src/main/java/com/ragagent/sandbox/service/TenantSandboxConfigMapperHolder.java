package com.ragagent.sandbox.service;

import java.time.OffsetDateTime;

import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.mapper.TenantSandboxConfigMapper;
import org.springframework.stereotype.Component;

/**
 * {@link TenantSkillService.TenantSandboxConfigMapperHolder} 的默认实现：委托子批 1 的
 * {@link TenantSandboxConfigMapper}（install/remove 入口只需要的两个配置读/写方法）。
 */
@Component
public class TenantSandboxConfigMapperHolder implements
        TenantSkillService.TenantSandboxConfigMapperHolder {

    private final TenantSandboxConfigMapper mapper;

    public TenantSandboxConfigMapperHolder(TenantSandboxConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TenantSandboxConfigEntity getByID(long tenantId, String id) {
        return mapper.getByID(tenantId, id);
    }

    @Override
    public void update(TenantSandboxConfigEntity entity, OffsetDateTime now) {
        mapper.update(entity, now);
    }
}
