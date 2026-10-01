package com.ragagent.wiki.service.page;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ragagent.wiki.domain.WikiCategoryPaths;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderConflictException;
import com.ragagent.wiki.domain.WikiFolderNode;
import com.ragagent.wiki.domain.WikiFolderNotFoundException;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 文件夹树协作者:文件夹 CRUD、路径 find-or-create、页面归位与空链修剪。
 *
 * <p>持有 {@link WikiPageServiceImpl} 回引以访问仓储与工具;本类不得独立实例化。</p>
 */
final class WikiPageFolderSupport {

    private static final Logger log = LoggerFactory.getLogger(WikiPageFolderSupport.class);

    private final WikiPageServiceImpl service;

    WikiPageFolderSupport(WikiPageServiceImpl service) {
        this.service = service;
    }

    public WikiFolder getFolder(String kbId, String id) {
        return service.folderRepo.getFolderByID(kbId, id);
    }

    /**
     * 列出子文件夹树。
     *
     * <p>PageCount 是<b>递归</b>的（该文件夹的整棵子树），所以父节点反映其下所有内容。
     * 一个文件夹出现在结果里，当且仅当它的子树里有匹配 pageTypes 的页面；
     * 完全空的文件夹（子树里任何类型的页面都没有）只在请求多个类型时列出
     * ——即合并后的 knowledge 视图——这样单类型页签（如 summary）不会冒出空容器。</p>
     */
    public List<WikiFolderNode> listChildFolders(String kbId, String parentID,
                                                 List<String> pageTypes) {
        List<WikiFolder> all = service.folderRepo.listAllFolders(kbId);
        List<String> types = pageTypes == null ? List.of() : pageTypes;
        Map<String, Long> scopedDirect = service.folderRepo.countPagesByFolder(kbId, types);
        Map<String, Long> allDirect = scopedDirect;
        if (!types.isEmpty()) {
            allDirect = service.folderRepo.countPagesByFolder(kbId, null);
        }
        Map<String, Long> recScoped = service.recursiveFolderCounts(all, scopedDirect);
        Map<String, Long> recAll = service.recursiveFolderCounts(all, allDirect);
        boolean showEmptyFolders = types.size() > 1;
        // 一个文件夹属于本视图，当且仅当它（递归地）含有请求类型的页面，
        // 或者——只在合并视图里——它是一个任何类型页面都没有的完全空容器。
        java.util.function.Predicate<String> relevant = id -> {
            if (recScoped.getOrDefault(id, 0L) > 0) {
                return true;
            }
            if (showEmptyFolders) {
                return recAll.getOrDefault(id, 0L) == 0;
            }
            return false;
        };
        List<WikiFolderNode> out = new ArrayList<>();
        for (WikiFolder f : all) {
            if (!f.getParentId().equals(parentID) || !relevant.test(f.getId())) {
                continue;
            }
            boolean hasChildren = false;
            for (WikiFolder g : all) {
                if (g.getParentId().equals(f.getId()) && relevant.test(g.getId())) {
                    hasChildren = true;
                    break;
                }
            }
            out.add(new WikiFolderNode(f, recScoped.getOrDefault(f.getId(), 0L), hasChildren));
        }
        return out;
    }

    /** 创建文件夹（校验名字、解析父路径、防同级重名）。 */
    public WikiFolder createFolder(String kbId, Long tenantID, String parentID, String name) {
        String folderName = service.validateFolderName(name);
        String parentPath = "";
        int depth = 1;
        if (!WikiConstants.FOLDER_ROOT_ID.equals(parentID)) {
            WikiFolder parent = service.folderRepo.getFolderByID(kbId, parentID);
            parentPath = parent.getPath();
            depth = parent.getDepth() + 1;
        }
        if (service.folderRepo.folderNameExists(kbId, parentID, folderName)) {
            throw new WikiFolderConflictException();
        }
        String path = folderName;
        if (!parentPath.isEmpty()) {
            path = parentPath + "/" + folderName;
        }
        OffsetDateTime now = OffsetDateTime.now();
        WikiFolder folder = new WikiFolder();
        folder.setId(UUID.randomUUID().toString());
        folder.setTenantId(tenantID);
        folder.setKnowledgeBaseId(kbId);
        folder.setParentId(parentID);
        folder.setName(folderName);
        folder.setPath(path);
        folder.setDepth(depth);
        folder.setCreatedAt(now);
        folder.setUpdatedAt(now);
        // 先判冲突再写；唯一索引是最后一道防线
        service.folderRepo.createFolder(folder);
        return folder;
    }

    /**
     * 把分类路径解析到叶子文件夹 id，
     * 顺路补齐缺失的中间文件夹。对 (kb, parent, name) 唯一约束是并发安全的——
     * 创建冲突时重新拉取。
     */
    public WikiPageService.FindOrCreateResult findOrCreateFolderPath(String kbId, Long tenantID, List<String> path) {
        List<String> clean = WikiCategoryPaths.cleanCategoryPath(path);
        if (clean.isEmpty()) {
            return new WikiPageService.FindOrCreateResult(WikiConstants.FOLDER_ROOT_ID, null);
        }
        String parentID = WikiConstants.FOLDER_ROOT_ID;
        String parentPath = "";
        for (int depth = 0; depth < clean.size(); depth++) {
            String name = clean.get(depth);
            WikiFolder child;
            if (service.folderRepo.folderNameExists(kbId, parentID, name)) {
                child = service.folderRepo.getChildFolderByName(kbId, parentID, name);
            } else {
                String fp = name;
                if (!parentPath.isEmpty()) {
                    fp = parentPath + "/" + name;
                }
                OffsetDateTime now = OffsetDateTime.now();
                child = new WikiFolder();
                child.setId(UUID.randomUUID().toString());
                child.setTenantId(tenantID);
                child.setKnowledgeBaseId(kbId);
                child.setParentId(parentID);
                child.setName(name);
                child.setPath(fp);
                child.setDepth(depth + 1);
                child.setCreatedAt(now);
                child.setUpdatedAt(now);
                try {
                    service.folderRepo.createFolder(child);
                } catch (RuntimeException cerr) {
                    // 创建竞争（或唯一约束冲突）：同名兄弟此刻必然已存在——
                    // 重新拉取，而不是让整个 plan 失败
                    try {
                        child = service.folderRepo.getChildFolderByName(kbId, parentID, name);
                    } catch (RuntimeException e) {
                        throw new WikiException("create wiki folder \"" + fp + "\": "
                                + cerr.getMessage(), cerr);
                    }
                }
            }
            parentID = child.getId();
            parentPath = child.getPath();
        }
        return new WikiPageService.FindOrCreateResult(parentID, clean);
    }

    /** 移动页面到文件夹：纯记账写入，不动版本号 */
    public WikiPage movePage(String kbId, String slug, String folderID) {
        WikiPage page = service.repo.getBySlug(kbId, slug);
        page.setFolderId(folderID == null ? "" : folderID.trim());
        applyFolderToPage(page);
        page.setUpdatedAt(OffsetDateTime.now());
        service.normalizeWikiHierarchy(page);
        service.repo.updateMeta(page);
        return page;
    }

    /**
     * 改名和/或换父节点，然后重算
     * 整棵子树的物化 path/depth 及子树下每个页面的缓存分类路径。防成环（把文件夹
     * 移进自己或自己的后代）与同级重名。
     */
    public WikiFolder renameOrMoveFolder(String kbId, String id, String newName,
                                         String newParentID, boolean moveParent) {
        WikiFolder folder = service.folderRepo.getFolderByID(kbId, id);
        String name = folder.getName();
        if (newName != null && !newName.trim().isEmpty()) {
            name = service.validateFolderName(newName);
        }
        String targetParent = folder.getParentId();
        if (moveParent) {
            targetParent = newParentID == null ? "" : newParentID;
        }
        String parentPath = "";
        int depthBase = 0;
        if (!WikiConstants.FOLDER_ROOT_ID.equals(targetParent)) {
            if (targetParent.equals(folder.getId())) {
                throw new WikiException("cannot move a folder into itself");
            }
            WikiFolder parent = service.folderRepo.getFolderByID(kbId, targetParent);
            if (parent.getPath().equals(folder.getPath())
                    || parent.getPath().startsWith(folder.getPath() + "/")) {
                throw new WikiException("cannot move a folder into its own descendant");
            }
            parentPath = parent.getPath();
            depthBase = parent.getDepth();
        }
        if (service.folderRepo.folderNameExists(kbId, targetParent, name)) {
            WikiFolder existing = service.folderRepo.getChildFolderByName(kbId, targetParent, name);
            if (!existing.getId().equals(folder.getId())) {
                throw new WikiFolderConflictException();
            }
        }
        String oldPath = folder.getPath();
        String newPath = name;
        if (!parentPath.isEmpty()) {
            newPath = parentPath + "/" + name;
        }
        if (newPath.equals(oldPath) && targetParent.equals(folder.getParentId())) {
            return folder; // no-op
        }
        List<WikiFolder> all = service.folderRepo.listAllFolders(kbId);
        OffsetDateTime now = OffsetDateTime.now();
        List<String> affected = new ArrayList<>();
        WikiFolder updated = null;
        for (WikiFolder f : all) {
            boolean isSelf = f.getId().equals(folder.getId());
            boolean isDescendant = f.getPath().startsWith(oldPath + "/");
            if (!isSelf && !isDescendant) {
                continue;
            }
            if (isSelf) {
                f.setParentId(targetParent);
                f.setName(name);
                f.setPath(newPath);
                f.setDepth(depthBase + 1);
            } else {
                f.setPath(newPath + f.getPath().substring(oldPath.length()));
                f.setDepth(WikiCategoryPaths.folderPathSegments(f.getPath()).size());
            }
            f.setUpdatedAt(now);
            service.folderRepo.updateFolder(f);
            affected.add(f.getId());
            if (isSelf) {
                updated = f;
            }
        }
        recomputePagesForFolders(kbId, affected);
        return updated == null ? folder : updated;
    }

    /**
     * 刷新归在给定文件夹 id
     * 之下的每个页面的缓存 category_path/wiki_path/depth（用于文件夹子树被移动/改名之后）。
     * 纯记账写入，不动版本号。
     */
    void recomputePagesForFolders(String kbId, List<String> folderIDs) {
        if (folderIDs.isEmpty()) {
            return;
        }
        List<WikiPage> pages = service.folderRepo.listPagesByFolderIDs(kbId, folderIDs);
        for (WikiPage page : pages) {
            applyFolderToPage(page);
            page.setUpdatedAt(OffsetDateTime.now());
            service.normalizeWikiHierarchy(page);
            try {
                service.repo.updateMeta(page);
            } catch (RuntimeException e) {
                log.warn("wiki: recompute folder path for page {} failed: {}", page.getSlug(),
                        e.toString());
            }
        }
    }

    /**
     * 只能删既没有页面也没有子文件夹的
     * 文件夹。UI 必须先把内容移走；这让删除保持非破坏性。
     */
    public void deleteFolder(String kbId, String id) {
        service.folderRepo.getFolderByID(kbId, id);
        List<WikiFolder> children = service.folderRepo.listChildFolders(kbId, id);
        if (!children.isEmpty()) {
            throw new WikiFolderNotEmptyException();
        }
        List<WikiPage> pages = service.folderRepo.listPagesByFolderIDs(kbId, List.of(id));
        if (!pages.isEmpty()) {
            throw new WikiFolderNotEmptyException();
        }
        service.folderRepo.deleteFolder(kbId, id);
    }

    /**
     * 删掉文档回收之后变空的文件夹，
     * 再顺着被删除而变空的祖先往上继续。它<b>只</b>考虑传入的文件夹链，所以 wiki 里
     * 别处刻意留着的空文件夹会被保住。
     *
     * <p>调用方必须等该 KB 的摄取队列排空后再调：taxonomy 规划会先建文件夹，
     * reduce 之后才写入引用它的页面。</p>
     */
    public List<String> pruneEmptyFolderChains(String kbId, List<String> folderIDs) {
        if (folderIDs == null || folderIDs.isEmpty()) {
            return null;
        }
        List<WikiFolder> all = service.folderRepo.listAllFolders(kbId);
        Map<String, WikiFolder> byID = new LinkedHashMap<>(all.size());
        for (WikiFolder folder : all) {
            if (folder != null) {
                byID.put(folder.getId(), folder);
            }
        }
        Map<String, WikiFolder> candidates = new LinkedHashMap<>();
        for (String start : folderIDs) {
            String id = start;
            Set<String> seen = new LinkedHashSet<>();
            while (!WikiConstants.FOLDER_ROOT_ID.equals(id)) {
                if (!seen.add(id)) {
                    break; // 环
                }
                WikiFolder folder = byID.get(id);
                if (folder == null) {
                    break;
                }
                candidates.put(id, folder);
                id = folder.getParentId();
            }
        }
        List<WikiFolder> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator
                .comparingInt(WikiFolder::getDepth).reversed()
                .thenComparing(Comparator.comparing(WikiFolder::getPath).reversed()));
        List<String> deleted = new ArrayList<>(ordered.size());
        for (WikiFolder folder : ordered) {
            List<WikiFolder> children = service.folderRepo.listChildFolders(kbId, folder.getId());
            if (!children.isEmpty()) {
                continue;
            }
            List<WikiPage> pages = service.folderRepo.listPagesByFolderIDs(kbId, List.of(folder.getId()));
            if (!pages.isEmpty()) {
                continue;
            }
            try {
                service.folderRepo.deleteFolder(kbId, folder.getId());
            } catch (WikiFolderNotFoundException | WikiFolderNotEmptyException e) {
                continue;
            }
            deleted.add(folder.getId());
        }
        return deleted;
    }

    /**
     * 从权威的 FolderID 刷新页面的
     * 派生 category_path 缓存。根（""）清空路径。解析不到的文件夹 id 视为<b>硬错误</b>，
     * 这样我们永远不会静默地把页面放错地方。
     */
    void applyFolderToPage(WikiPage page) {
        if (page == null) {
            return;
        }
        if (page.getFolderId().trim().isEmpty()) {
            page.setFolderId("");
            page.setCategoryPath(new ArrayList<>());
            return;
        }
        WikiFolder folder;
        try {
            folder = service.folderRepo.getFolderByID(page.getKnowledgeBaseId(), page.getFolderId());
        } catch (WikiFolderNotFoundException e) {
            throw new WikiException("wiki page references unknown folder \""
                    + page.getFolderId() + "\"");
        }
        page.setCategoryPath(WikiCategoryPaths.folderPathSegments(folder.getPath()));
    }
}
