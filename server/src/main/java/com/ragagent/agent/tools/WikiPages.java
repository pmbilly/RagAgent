package com.ragagent.agent.tools;

import java.util.List;

/** wiki 页面服务 seam 接口（agent 工具对 wiki 域的最小依赖面）。 */

    /**
     * Wiki 页服务接缝（被用方法子集）。
     * 失败一律抛 RuntimeException；getPageBySlug 返回 null = 页不存在（ErrWikiPageNotFound）。
     */
    public interface WikiPages {
        PageView getPageBySlug(String kbId, String slug);

        PageView createPage(PageView page, String editSource);

        void updatePage(PageView page, String editSource);

        /** 机器维护链接更新（不递增版本）。 */
        void updateAutoLinkedContent(PageView page, String editSource);

        void deletePage(String kbId, String slug, String editSource);

        /** 修复内容链接；返回 null 表示服务不可用（调用方静默跳过）。 */
        RepairResult repairContentLinks(String kbId, String slug, String content);

        void injectCrossLinks(String kbId, List<String> slugs);

        /** 重建索引页；返回值恒被忽略。 */
        void rebuildIndexPage(String kbId);

        List<IssueView> listIssues(String kbId, String slug, String status);

        IssueView createIssue(IssueView issue);

        void updateIssueStatus(String issueId, String status);

        /**
         * 按关键词搜页（wiki_search 用）。失败抛 RuntimeException
         * （工具侧拼 "Wiki search %q failed in KB %s: %v"）。
         */
        default List<PageView> searchPages(String kbId, String query, int limit) {
            throw new UnsupportedOperationException("searchPages");
        }

        /**
         * 取索引页概览（wiki_read_page 的 index 特判用）。
         * 返回 null = 无 overview（调用方静默跳过）。
         */
        default IndexOverviewView getIndexView(String kbId, int topK) {
            return null;
        }
    }
