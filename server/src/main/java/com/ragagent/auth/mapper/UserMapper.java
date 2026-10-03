package com.ragagent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.auth.domain.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * tenantless 用户插入：SQL 列清单**不含 tenant_id** → 落 NULL。
     * 若走 MP 通用 insert（getTenantId 把 null 归一成 0）会写成 0，
     * 真 PG 会触发 fk_users_tenant（H2 无 FK 不暴露）。
     * tenantless 注册路径（register-by-invite / OIDC provisioning）专用。
     */
    @org.apache.ibatis.annotations.Insert(
            "INSERT INTO users (id, username, email, password_hash, avatar, is_active,"
                    + " can_access_all_tenants, is_system_admin, preferences, created_at, updated_at)"
                    + " VALUES (#{id}, #{username}, #{email}, #{passwordHash}, #{avatar}, #{isActive},"
                    + " #{canAccessAllTenants}, #{isSystemAdmin},"
                    + " #{preferences, typeHandler=com.ragagent.common.web.PgJsonTypeHandler},"
                    + " #{createdAt}, #{updatedAt})")
    int insertTenantless(User user);
}
