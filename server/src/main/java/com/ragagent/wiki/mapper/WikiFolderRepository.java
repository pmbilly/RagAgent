package com.ragagent.wiki.mapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.wiki.domain.WikiCategoryPaths;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiFolderNotFoundException;
import com.ragagent.wiki.domain.WikiPage;
import org.springframework.stereotype.Repository;

/**
 * wiki 文件夹树仓储：wiki_folders 的读写，以及按文件夹的页面计数。
 *
 * <p>空判与软删在同一条 SQL 里；删 0 行时再判"压根不存在还是非空"，分别给不同异常。
 * 页面侧计数（{@code countPagesInFolder} / {@code countPagesByFolder} /
 * {@code listPagesByFolderIDs}）走 {@link WikiPageMapper}。</p>
 */
@Repository
public class WikiFolderRepository {

    private final WikiPageMapper pages;
    private final WikiFolderMapper folders;

    public WikiFolderRepository(WikiPageMapper pages, WikiFolderMapper folders) {
        this.pages = pages;
        this.folders = folders;
    }


    /**
     * 已有 wiki 文件夹的物化路径
     * （拆成段），按 path 排序、截断到 maxPaths。folder 树是唯一真相来源，
     * 因此不再扫页面行。
     */
    public List<List<String>> listDistinctCategoryPaths(String kbId, int maxPaths) {
        int cap = maxPaths <= 0 ? 150 : maxPaths;
        List<String> paths = folders.listDistinctPaths(kbId, cap);
        List<List<String>> out = new ArrayList<>(paths.size());
        for (String p : paths) {
            List<String> seg = WikiCategoryPaths.cleanCategoryPath(
                    List.of(p.split("/", -1)));
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }


    public void createFolder(WikiFolder folder) {
        OffsetDateTime now = OffsetDateTime.now();
        if (folder.getCreatedAt() == null) {
            folder.setCreatedAt(now);
        }
        if (folder.getUpdatedAt() == null) {
            folder.setUpdatedAt(now);
        }
        folders.insert(folder);
    }

    public WikiFolder getFolderByID(String kbId, String id) {
        WikiFolder folder = folders.selectFolderById(kbId, id);
        if (folder == null) {
            throw new WikiFolderNotFoundException();
        }
        return folder;
    }

    /**
     * 找不到时抛 {@link WikiFolderNotFoundException}（find-or-create 依赖这个判别）。
     */
    public WikiFolder getChildFolderByName(String kbId, String parentID, String name) {
        WikiFolder folder = folders.selectChildByName(kbId, parentID, name);
        if (folder == null) {
            throw new WikiFolderNotFoundException();
        }
        return folder;
    }

    /** sort_order ASC, name ASC */
    public List<WikiFolder> listChildFolders(String kbId, String parentID) {
        return folders.listChildFolders(kbId, parentID);
    }

    /** depth ASC, path ASC */
    public List<WikiFolder> listAllFolders(String kbId) {
        return folders.listAllFolders(kbId);
    }

    /** 0 行 → not found */
    public void updateFolder(WikiFolder folder) {
        if (folder.getUpdatedAt() == null) {
            folder.setUpdatedAt(OffsetDateTime.now());
        }
        if (folders.updateFolder(folder) == 0) {
            throw new WikiFolderNotFoundException();
        }
    }

    /**
     * 空判与软删在同一条 SQL 里；
     * 0 行时再判"是压根不存在还是非空"，分别给不同的 sentinel 错误。
     */
    public void deleteFolder(String kbId, String id) {
        int rows = folders.softDeleteIfEmpty(kbId, id, OffsetDateTime.now());
        if (rows == 0) {
            if (folders.countLiveById(kbId, id) == 0) {
                throw new WikiFolderNotFoundException();
            }
            throw new WikiFolderNotEmptyException();
        }
    }

    /** 直接挂在文件夹下的活跃页面数（排除归档） */
    public long countPagesInFolder(String kbId, String folderID) {
        return pages.countPagesInFolder(kbId, folderID, WikiConstants.STATUS_ARCHIVED);
    }

    /**
     * 按 folder_id 分组的活跃页面数。
     * 根目录页面用空串键。pageTypes 非空时只统计这些类型。
     */
    public Map<String, Long> countPagesByFolder(String kbId, List<String> pageTypes) {
        List<WikiPageMapper.FolderCount> rows = pages.countPagesByFolder(
                kbId, WikiConstants.STATUS_ARCHIVED,
                pageTypes == null ? List.of() : pageTypes);
        Map<String, Long> out = new LinkedHashMap<>(rows.size());
        for (WikiPageMapper.FolderCount row : rows) {
            out.put(row.getFolderId(), row.getCount());
        }
        return out;
    }

    /** 子树移动/重命名时重算缓存路径 */
    public List<WikiPage> listPagesByFolderIDs(String kbId, List<String> folderIDs) {
        if (folderIDs == null || folderIDs.isEmpty()) {
            return List.of();
        }
        return pages.listPagesByFolderIds(kbId, folderIDs);
    }

    /** 文件夹名冲突判定辅助（原实现由唯一索引 + service 层负责；此处保留最小入口） */
    public boolean folderNameExists(String kbId, String parentID, String name) {
        return folders.selectChildByName(kbId, parentID, name) != null;
    }
}
