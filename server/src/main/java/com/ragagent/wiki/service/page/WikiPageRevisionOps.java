package com.ragagent.wiki.service.page;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiPageRevisionListResponse;
import com.ragagent.wiki.domain.WikiRevisionPruneRequest;
import com.ragagent.wiki.mapper.WikiPageRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 页面修订历史与页面问题协作者：快照构造、历史剪枝与查询、回滚，以及问题单的增查改。
 *
 * <p>持有 {@link WikiPageServiceImpl} 回引以访问仓储与工具;本类不得独立实例化。</p>
 */
final class WikiPageRevisionOps {

    private static final Logger log = LoggerFactory.getLogger(WikiPageRevisionOps.class);

    private final WikiPageServiceImpl service;

    WikiPageRevisionOps(WikiPageServiceImpl service) {
        this.service = service;
    }

    /**
     * 为给定页面状态构造不可变快照行。
     *
     * <p>快照上的 {@code editSource} 是<b>那个版本</b>的作者——该版本尚为当前版本时
     * 页面溯源列的值——而不是取代它的这次写入的作者。</p>
     */
    static WikiPageRevision revisionFromPage(WikiPage p) {
        WikiPageRevision rev = new WikiPageRevision();
        rev.setId(UUID.randomUUID().toString());
        rev.setTenantId(p.getTenantId());
        rev.setKnowledgeBaseId(p.getKnowledgeBaseId());
        rev.setPageId(p.getId());
        rev.setSlug(p.getSlug());
        rev.setVersion(p.getVersion());
        rev.setTitle(p.getTitle());
        rev.setPageType(p.getPageType());
        rev.setStatus(p.getStatus());
        rev.setContent(p.getContent());
        rev.setSummary(p.getSummary());
        rev.setAliases(new ArrayList<>(p.getAliases()));
        rev.setEditSource(WikiConstants.normalizeEditSource(p.getLastEditSource()));
        rev.setEditorId(p.getLastEditorId());
        rev.setEditedAt(p.getUpdatedAt());
        rev.setCreatedAt(OffsetDateTime.now());
        return rev;
    }

    /**
     * 页面推进到 currentVersion 之后限制其
     * 快照历史。机器作者的快照一旦滑出近期窗口就丢；人工/agent/回滚的快照活到硬上限
     * ——这样热页上的管道churn 挤不掉用户真正在意的编辑。
     */
    void pruneRevisions(String pageId, int currentVersion) {
        WikiRevisionPruneRequest req = new WikiRevisionPruneRequest(
                pageId,
                currentVersion - WikiConstants.MAX_REVISIONS_PER_PAGE,
                WikiConstants.PRUNABLE_EDIT_SOURCES,
                currentVersion - WikiConstants.MAX_REVISIONS_HARD_CAP);
        if (req.keepFromVersion() <= 0 && req.hardKeepFromVersion() <= 0) {
            return;
        }
        try {
            service.repo.pruneRevisions(req);
        } catch (RuntimeException e) {
            log.warn("prune wiki page revisions for {} failed: {}", pageId, e.toString());
        }
    }

    /**
     * 某页面存下来的历史快照
     * （最新在前，<b>省略 content</b>）+ 快照总数 + 页面当前版本。
     *
     * <p>当前版本本身<b>没有</b>快照行——它活在 wiki_pages 里。</p>
     */
    public WikiPageRevisionListResponse listRevisions(String kbId, String slug, int limit,
                                                      int offset) {
        WikiPage page = service.repo.getBySlug(kbId, slug);
        WikiPageRepository.RevisionList listed;
        try {
            listed = service.repo.listRevisions(kbId, page.getId(), limit, offset);
        } catch (RuntimeException e) {
            throw new WikiException("list wiki page revisions: " + e.getMessage(), e);
        }
        WikiPageRevisionListResponse resp = new WikiPageRevisionListResponse();
        resp.setRevisions(listed.revisions());
        resp.setTotal(listed.total());
        resp.setCurrentVersion(page.getVersion());
        return resp;
    }

    /** 单条历史快照，含 content */
    public WikiPageRevision getRevision(String kbId, String slug, int version) {
        WikiPage page = service.repo.getBySlug(kbId, slug);
        return service.repo.getRevision(kbId, page.getId(), version);
    }

    /**
     * 把页面回滚到某份快照的正文内容，
     * 并以<b>一次普通编辑</b>的形式应用它——回滚前的状态会被快照、版本号前进、链接重解析。
     *
     * <p>位置（文件夹、排序权重）与溯源引用保持当前值——回滚是关于内容的，
     * 不是撤销目录移动。</p>
     */
    public WikiPage revertPageToVersion(String kbId, String slug, int version) {
        WikiPage page = service.repo.getBySlug(kbId, slug);
        if (version == page.getVersion()) {
            throw new WikiRevertToCurrentVersionException();
        }
        WikiPageRevision rev = service.repo.getRevision(kbId, page.getId(), version);

        WikiPage target = WikiPageServiceImpl.copyPage(page);
        target.setTitle(rev.getTitle());
        target.setContent(rev.getContent());
        target.setSummary(rev.getSummary());
        target.setPageType(rev.getPageType());
        target.setStatus(rev.getStatus());
        target.setAliases(new ArrayList<>(rev.getAliases()));

        // 以"回滚"编辑来源包裹这次 UpdatePage，让新版本署名可辨
        return WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_REVERT,
                () -> service.updatePage(target));
    }

    public WikiPageIssue createIssue(WikiPageIssue issue) {
        if (issue.getId() == null || issue.getId().isEmpty()) {
            issue.setId(UUID.randomUUID().toString());
        }
        service.repo.createIssue(issue);
        return issue;
    }

    public List<WikiPageIssue> listIssues(String kbId, String slug, String status) {
        return service.repo.listIssues(kbId, slug, status);
    }

    public void updateIssueStatus(String issueID, String status) {
        service.repo.updateIssueStatus(issueID, status);
    }

    /**
     * 页面删除时一并丢掉快照历史：页面已从所有读路径消失，它们的快照是不可达的行，
     * 却占着完整正文。尽力而为——页面已经删了，回滚不了。
     */
    void deletePageRevisions(WikiPage page) {
        try {
            service.repo.deleteRevisionsByPage(page.getId());
        } catch (RuntimeException e) {
            log.warn("delete wiki page revisions for {} failed: {}", page.getId(), e.toString());
        }
    }
}
