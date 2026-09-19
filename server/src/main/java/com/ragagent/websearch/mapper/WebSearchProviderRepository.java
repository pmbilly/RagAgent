package com.ragagent.websearch.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.websearch.domain.WebSearchProvider;
import com.ragagent.websearch.domain.WebSearchParamsTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对照 Go {@code repository.webSearchProviderRepository}（internal/application/repository/web_search_provider.go）。
 *
 * <p>软删走显式 {@code deleted_at IS NULL} 条件（约定 §9，不用 @TableLogic）。
 * List 排序 {@code created_at ASC}（vector/storage 是 DESC，Go 原文如此）。
 * 自定义 SQL 的 parameters 列必须显式声明 typeHandler（§9：注解 SQL 不套实体注解）。</p>
 */
@Mapper
public interface WebSearchProviderRepository {

    String COLS = "id, tenant_id, name, provider, description, parameters, is_default, "
            + "created_at, updated_at, deleted_at";

    String PARAMS_TH = "com.ragagent.websearch.domain.WebSearchParamsTypeHandler";

    @Select("SELECT " + COLS + " FROM web_search_providers "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    @Results(id = "wspRow", value = {
            @Result(column = "parameters", property = "parameters",
                    typeHandler = WebSearchParamsTypeHandler.class)
    })
    WebSearchProvider getByID(@Param("tenantId") long tenantId, @Param("id") String id);

    /** Go List：created_at ASC */
    @Select("SELECT " + COLS + " FROM web_search_providers "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY created_at ASC")
    @Results(value = {
            @Result(column = "parameters", property = "parameters",
                    typeHandler = WebSearchParamsTypeHandler.class)
    })
    List<WebSearchProvider> list(@Param("tenantId") long tenantId);

    @Insert("INSERT INTO web_search_providers (" + COLS + ") VALUES ("
            + "#{p.id}, #{p.tenantId}, #{p.name}, #{p.provider}, #{p.description}, "
            + "#{p.parameters,typeHandler=" + PARAMS_TH + "}, #{p.isDefault}, "
            + "#{now}, #{now}, NULL)")
    int create(@Param("p") WebSearchProvider provider, @Param("now") OffsetDateTime now);

    /**
     * 对照 Go {@code Update}：{@code Select("*").Updates(provider)} 写**全部**列——
     * 含零值 created_at（Go 侧写 year-1）与 deleted_at（NULL）。Java 侧 created_at
     * 写 SQL NULL（跨语言等价，见实体注释）、updated_at 写 now（GORM autoUpdateTime）。
     */
    @Update("UPDATE web_search_providers SET tenant_id = #{p.tenantId}, name = #{p.name}, "
            + "provider = #{p.provider}, description = #{p.description}, "
            + "parameters = #{p.parameters,typeHandler=" + PARAMS_TH + "}, "
            + "is_default = #{p.isDefault}, created_at = NULL, deleted_at = NULL, "
            + "updated_at = #{now} "
            + "WHERE id = #{p.id} AND tenant_id = #{p.tenantId}")
    int update(@Param("p") WebSearchProvider provider, @Param("now") OffsetDateTime now);

    @Update("UPDATE web_search_providers SET deleted_at = NOW() "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int delete(@Param("tenantId") long tenantId, @Param("id") String id);

    /** Go ClearDefault：清同租户全部默认（可排除一个 id）；GORM Update 自动刷 updated_at */
    @Update("UPDATE web_search_providers SET is_default = FALSE, updated_at = NOW() "
            + "WHERE tenant_id = #{tenantId} AND is_default = TRUE AND deleted_at IS NULL "
            + "AND (#{excludeId} = '' OR id != #{excludeId})")
    int clearDefault(@Param("tenantId") long tenantId, @Param("excludeId") String excludeId);
}
