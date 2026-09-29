package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

/** wiki 链接突变：入站内容重写、失败回滚、错误聚合。 */
    public interface WikiContentRewrite {
        RewriteResult apply(String content);



    /**
     * 对照 applyIncomingWikiContentRewrite：更新机器维护的链接（不递增页面版本）。
     * 首个失败即停，返回已应用的变更供调用方补偿。
     */
    public static List<AppliedChange> applyIncomingWikiContentRewrite(
            WikiPages service, String kbId, List<String> inLinks, String editSource,
            WikiContentRewrite rewrite, List<String> updatedSlugsOut) {
        List<AppliedChange> changes = new ArrayList<>();
        for (String sourceSlug : SearchAuth.dedupNonEmptyStrings(inLinks)) {
            PageView page = service.getPageBySlug(kbId, sourceSlug);
            if (page == null) {
                throw new WikiRewriteException(
                        "load incoming page " + sourceSlug + ": empty result", changes);
            }
            RewriteResult result = rewrite.apply(page.content());
            if (!result.changed()) {
                continue;
            }
            String original = page.content();
            page.setContent(result.content());
            try {
                service.updateAutoLinkedContent(page, editSource);
            } catch (RuntimeException e) {
                page.setContent(original);
                throw new WikiRewriteException(
                        "update incoming page " + sourceSlug + ": " + e.getMessage(), changes);
            }
            changes.add(new AppliedChange(page, original));
            updatedSlugsOut.add(sourceSlug);
        }
        return changes;
    }

    /** 对照 rollbackWikiContentChanges：倒序回滚；失败聚合。 */
    public static void rollbackWikiContentChanges(WikiPages service, List<AppliedChange> changes, String editSource) {
        List<String> failures = new ArrayList<>();
        for (int i = changes.size() - 1; i >= 0; i--) {
            AppliedChange change = changes.get(i);
            change.page().setContent(change.originalContent());
            try {
                service.updateAutoLinkedContent(change.page(), editSource);
            } catch (RuntimeException e) {
                failures.add(change.page().slug() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("failed to roll back incoming pages: " + String.join("; ", failures));
        }
    }

    /** 对照 joinWikiMutationErrors。 */
    public static String joinWikiMutationErrors(String primary, String... extras) {
        List<String> parts = new ArrayList<>();
        parts.add(primary);
        for (String extra : extras) {
            if (extra != null && !extra.isEmpty()) {
                parts.add(extra);
            }
        }
        return String.join("; ", parts);
    }



/**
public final class WikiSupport {

    /** 对照 types.WikiEditSourceAgent。 */
    public static final String WIKI_EDIT_SOURCE_AGENT = "agent";
}
