package com.ragagent.wiki.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.wiki.domain.WikiActivityAudit;
import com.ragagent.wiki.domain.WikiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 把人工页面变更投影进知识库活动流：记账是尽力而为，埋点失败绝不反噬编辑。
 */
final class WikiActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(WikiActivityRecorder.class);

    private final ObjectProvider<WikiActivityAudit> activityAudit;

    WikiActivityRecorder(ObjectProvider<WikiActivityAudit> activityAudit) {
        this.activityAudit = activityAudit;
    }

    /**
     * 把人工页面变更投影进知识库活动流。
     *
     * <p>记账是<b>尽力而为</b>：绝不能让埋点失败反过来让编辑失败。</p>
     */
    void recordManualWikiActivity(WikiPage page, String action) {
        if (page == null) {
            return;
        }
        Map<String, Integer> actions = new LinkedHashMap<>();
        actions.put(action, 1);

        long tenantId = page.getTenantId() == null ? 0L : page.getTenantId();
        WikiActivityAudit audit = activityAudit.getIfAvailable();
        if (audit == null) {
            // audit 接缝缺位时的等价行为：什么也不写
            log.debug("wiki activity skipped (no WikiActivityAudit bean): kb={} actions={}",
                    page.getKnowledgeBaseId(), actions);
            return;
        }
        try {
            audit.wikiContentChanged(tenantId, page.getKnowledgeBaseId(), actions);
        } catch (RuntimeException e) {
            log.warn("record wiki activity failed: kb={} action={} err={}",
                    page.getKnowledgeBaseId(), action, WikiRequestSupport.errText(e));
        }
    }
}
