package com.ragagent.vectorstore.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.vectorstore.domain.ConnectionConfigTypeHandler;
import com.ragagent.vectorstore.domain.IndexConfigTypeHandler;
import com.ragagent.vectorstore.domain.VectorStore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对照 Go {@code repository.vectorStoreRepository}（internal/application/repository/vectorstore.go）。
 * List 排序 created_at DESC（与 wsp 的 ASC 相反，Go 原文如此）。
 */
@Mapper
public interface VectorStoreRepository {

    String COLS = "id, tenant_id, name, engine_type, connection_config, index_config, "
            + "created_at, updated_at, deleted_at";
    String CC_TH = "com.ragagent.vectorstore.domain.ConnectionConfigTypeHandler";
    String IC_TH = "com.ragagent.vectorstore.domain.IndexConfigTypeHandler";

    @Select("SELECT " + COLS + " FROM vector_stores "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    @Results(id = "vsRow", value = {
            @Result(column = "connection_config", property = "connectionConfig",
                    typeHandler = ConnectionConfigTypeHandler.class),
            @Result(column = "index_config", property = "indexConfig",
                    typeHandler = IndexConfigTypeHandler.class)
    })
    VectorStore getByID(@Param("tenantId") long tenantId, @Param("id") String id);

    /** Go List：created_at DESC（newest first） */
    @Select("SELECT " + COLS + " FROM vector_stores "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY created_at DESC")
    @Results(value = {
            @Result(column = "connection_config", property = "connectionConfig",
                    typeHandler = ConnectionConfigTypeHandler.class),
            @Result(column = "index_config", property = "indexConfig",
                    typeHandler = IndexConfigTypeHandler.class)
    })
    List<VectorStore> list(@Param("tenantId") long tenantId);

    @Insert("INSERT INTO vector_stores (" + COLS + ") VALUES ("
            + "#{s.id}, #{s.tenantId}, #{s.name}, #{s.engineType}, "
            + "#{s.connectionConfig,typeHandler=" + CC_TH + "}, "
            + "#{s.indexConfig,typeHandler=" + IC_TH + "}, #{now}, #{now}, NULL)")
    int create(@Param("s") VectorStore store, @Param("now") OffsetDateTime now);

    /** Go Update：Select("name") + GORM autoUpdateTime（engine/config/index 不可变） */
    @Update("UPDATE vector_stores SET name = #{s.name}, updated_at = #{now} "
            + "WHERE id = #{s.id} AND tenant_id = #{s.tenantId}")
    int updateName(@Param("s") VectorStore store, @Param("now") OffsetDateTime now);

    /** Go UpdateConnectionConfig：Select("connection_config") + autoUpdateTime 刷 updated_at */
    @Update("UPDATE vector_stores SET connection_config = #{s.connectionConfig,typeHandler=" + CC_TH + "}, "
            + "updated_at = NOW() "
            + "WHERE id = #{s.id} AND tenant_id = #{s.tenantId}")
    int updateConnectionConfig(@Param("s") VectorStore store);

    @Update("UPDATE vector_stores SET deleted_at = NOW() "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL")
    int delete(@Param("tenantId") long tenantId, @Param("id") String id);
}
