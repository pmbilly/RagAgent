package com.ragagent.agent.tools.wiki;

import java.util.List;

/** wiki 内容重写失败异常（携带已完成变更清单）。 */

    /** 重写半途失败：携带已应用的变更供调用方回滚。 */
    public final class WikiRewriteException extends RuntimeException {
        private final List<AppliedChange> changes;

        public WikiRewriteException(String message, List<AppliedChange> changes) {
            super(message);
            this.changes = changes;
        }

        public List<AppliedChange> changes() {
            return changes;
        }
    }
