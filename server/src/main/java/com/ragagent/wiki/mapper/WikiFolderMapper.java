package com.ragagent.wiki.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.wiki.domain.WikiFolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * wiki_folders 仓储语句（对照 Go repository/wiki_page.go 的 "--- Folder tree ---" 段，
 * L635-758）。
 *
 * <p>GORM 隐式行为清单（约定 §3）：</p>
 * <ul>
 *   <li><b>软删除</b>：每条查询显式 {@code deleted_at IS NULL}。</li>
 *   <li><b>排序</b>：{@code ListChildFolders → sort_order ASC, name ASC}（L687-688）、
 *       {@code ListAllFolders → depth ASC, path ASC}（L698-699），显式写出。</li>
 *   <li><b>原子删除</b>：Go 把"空判"和软删放在同一条 UPDATE 里（L731-742），
 *       避免 service 先检查、并发 move/create 插入后留下悬空 folder_id。Java 照抄。</li>
 * </ul>
 */
@Mapper
public interface WikiFolderMapper extends BaseMapper<WikiFolder> {

    @Results(id = "wikiFolderResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId"),
            @Result(column = "parent_id", property = "parentId"),
            @Result(column = "name", property = "name"),
            @Result(column = "path", property = "path"),
            @Result(column = "depth", property = "depth"),
            @Result(column = "sort_order", property = "sortOrder"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "deleted_at", property = "deletedAt"),
    })
    @Select("SELECT * FROM wiki_folders WHERE knowledge_base_id = #{kbId} AND id = #{id} "
            + "AND deleted_at IS NULL LIMIT 1")
    WikiFolder selectFolderById(@Param("kbId") String kbId, @Param("id") String id);

    /** 对照 Go GetChildFolderByName（L665-678） */
    @ResultMap("wikiFolderResult")
    @Select("SELECT * FROM wiki_folders WHERE knowledge_base_id = #{kbId} AND parent_id = #{parentId} "
            + "AND name = #{name} AND deleted_at IS NULL LIMIT 1")
    WikiFolder selectChildByName(@Param("kbId") String kbId,
                                 @Param("parentId") String parentId,
                                 @Param("name") String name);

    /** 对照 Go ListChildFolders（L680-692） */
    @ResultMap("wikiFolderResult")
    @Select("SELECT * FROM wiki_folders WHERE knowledge_base_id = #{kbId} AND parent_id = #{parentId} "
            + "AND deleted_at IS NULL ORDER BY sort_order ASC, name ASC")
    List<WikiFolder> listChildFolders(@Param("kbId") String kbId, @Param("parentId") String parentId);

    /** 对照 Go ListAllFolders（L694-704） */
    @ResultMap("wikiFolderResult")
    @Select("SELECT * FROM wiki_folders WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL "
            + "ORDER BY depth ASC, path ASC")
    List<WikiFolder> listAllFolders(@Param("kbId") String kbId);

    /**
     * 对照 Go UpdateFolder（L706-725）：显式列更新（Go 用 map + Updates 绕开零值省略，
     * 于是重命名成空串 / 移动成根 {@code ""} 都能落库）。
     */
    @Update("UPDATE wiki_folders SET parent_id = #{parentId}, name = #{name}, path = #{path}, "
            + "depth = #{depth}, sort_order = #{sortOrder}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int updateFolder(WikiFolder folder);

    /**
     * 对照 Go DeleteFolder（L727-758）：把"空判"和软删放进同一条 UPDATE。
     *
     * <p>service 层的先前检查可能被并发的页面移动 / 子目录创建抢跑；
     * "先查后删"会留下悬空的 folder_id，所以两个 NOT EXISTS 必须在同一语句里。</p>
     *
     * <p>返回 0 行时调用方再查一次是否存在，以区分"不存在"与"非空"。</p>
     */
    @Update("UPDATE wiki_folders SET deleted_at = #{deletedAt} "
            + "WHERE knowledge_base_id = #{kbId} AND id = #{id} AND deleted_at IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM wiki_pages "
            + "                WHERE knowledge_base_id = #{kbId} AND folder_id = #{id} "
            + "                AND deleted_at IS NULL) "
            + "AND NOT EXISTS (SELECT 1 FROM wiki_folders AS child "
            + "                WHERE child.knowledge_base_id = #{kbId} AND child.parent_id = #{id} "
            + "                AND child.deleted_at IS NULL)")
    int softDeleteIfEmpty(@Param("kbId") String kbId, @Param("id") String id,
                          @Param("deletedAt") OffsetDateTime deletedAt);

    /** 对照 Go DeleteFolder 的兜底计数（L748-749）：判定"不存在"而非"非空" */
    @Select("SELECT COUNT(*) FROM wiki_folders WHERE knowledge_base_id = #{kbId} AND id = #{id} "
            + "AND deleted_at IS NULL")
    long countLiveById(@Param("kbId") String kbId, @Param("id") String id);

    /**
     * 对照 Go ListDistinctCategoryPaths（L609-633）：取物化路径，按 path 排序，
     * 截断到 maxPaths。folder 树是唯一真相来源，因此这里<b>不再扫页面行</b>。
     */
    @Select("SELECT path FROM wiki_folders WHERE knowledge_base_id = #{kbId} AND path <> '' "
            + "AND deleted_at IS NULL ORDER BY path ASC LIMIT #{maxPaths}")
    List<String> listDistinctPaths(@Param("kbId") String kbId, @Param("maxPaths") int maxPaths);
}
