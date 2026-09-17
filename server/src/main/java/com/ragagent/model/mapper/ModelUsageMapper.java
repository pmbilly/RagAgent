package com.ragagent.model.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 模型引用（usage）查询（对照 Go repository/model_usage.go + knowledgebase.go/custom_agent.go）。
 *
 * 方言策略：Go 在 SQL 层用 jsonb ->> 匹配；Java 改为拉取本租户行的相关列、
 * 在 JVM 内做绑定匹配（对照 Go ListModelUsages 的行解码 + bindings 计算路径）。
 * 语义等价：count = 绑定非空的行数；list = name,id 升序前 50 行。
 * 好处：H2 测试库零方言依赖；租户内 KB/agent 数量级小，成本可忽略。
 */
@Mapper
public interface ModelUsageMapper {

    @Select("SELECT id, name, embedding_model_id, summary_model_id, image_processing_config, "
            + "vlm_config, asr_config, wiki_config FROM knowledge_bases "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY name ASC, id ASC")
    List<Map<String, Object>> listKnowledgeBaseRows(@Param("tenantId") long tenantId);

    @Select("SELECT id, name, config FROM custom_agents "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY name ASC, id ASC")
    List<Map<String, Object>> listCustomAgentRows(@Param("tenantId") long tenantId);
}
