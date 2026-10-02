package com.ragagent.agent.tools.wiki;

import java.util.List;

/** 待渲染的 wiki 页面（预算渲染输入）。 */
    public class PendingWikiPage {
        private final PageView page;
        private final String kbId;
        private final List<String> outLinks;
        private final List<String> inLinks;
        private final List<String> sources;
        private final String body;

        public PendingWikiPage(PageView page, String kbId, List<String> outLinks,
                List<String> inLinks, List<String> sources, String body) {
            this.page = page;
            this.kbId = kbId;
            this.outLinks = outLinks;
            this.inLinks = inLinks;
            this.sources = sources;
            this.body = body;
        }

        public PageView page() { return page; }
        public String body() { return body; }

        /** XML 渲染模板（字节级契约）。 */
        public String render(String body) {
            StringBuilder b = new StringBuilder();
            b.append("<wiki_page>\n");
            b.append("<metadata>\n");
            b.append("<knowledge_base_id>").append(kbId).append("</knowledge_base_id>\n");
            b.append("<link>[[").append(page.slug()).append('|').append(page.title()).append("]]</link>\n");
            b.append("<type>").append(page.pageType()).append("</type>\n");
            b.append("<aliases>").append(String.join(", ", page.aliases())).append("</aliases>\n");
            b.append("</metadata>\n");
            b.append("<relationships>\n");
            b.append("<links_to>").append(String.join(", ", outLinks)).append("</links_to>\n");
            b.append("<linked_from>").append(String.join(", ", inLinks)).append("</linked_from>\n");
            b.append("</relationships>\n");
            b.append("<sources>\n");
            b.append(String.join("\n", sources)).append('\n');
            b.append("</sources>\n");
            b.append("<summary>\n");
            b.append(page.summary()).append('\n');
            b.append("</summary>\n");
            b.append("<content>\n");
            b.append(body).append('\n');
            b.append("</content>\n");
            b.append("</wiki_page>");
            return b.toString();
        }
    }
