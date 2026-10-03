package com.ragagent.datasource.connector.notion;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.FetchedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notion 抓取：单页正文与附件、数据库的整库与增量抓取、记录查询与块内容展开。
 *
 * <p>持有 {@link NotionConnector} 回引以复用其数据库信息解析与父节点判定；本类不得独立实例化。</p>
 */
final class NotionFetchOps {

    private static final Logger log = LoggerFactory.getLogger(NotionFetchOps.class);

    private final NotionConnector connector;

    NotionFetchOps(NotionConnector connector) {
        this.connector = connector;
    }

    /**
     * 抓单页的正文与附件；页面本身是数据库记录时
     * 走 {@code buildRecordItem}。
     */
    List<FetchedItem> fetchPage(NotionClient client, NotionPage page,
                                        Map<String, Boolean> visited) {
        List<FetchedItem> items = new ArrayList<>();
        if (page == null) {
            return items;
        }
        if (Boolean.TRUE.equals(visited.get(page.id()))) {
            return items;
        }
        visited.put(page.id(), true);

        if (page.inTrash) {
            return items;
        }

        // 数据库记录的内容在 properties 里而不是块里——交给 buildRecordItem，
        // 免得被当成"空页面"丢掉。记录父节点的 type 既可能是 database_id
        // （老的 GetPage 响应）也可能是 data_source_id（Search 与 2025-09-03+）。
        NotionParent parent = page.parent();
        if (NotionConstants.PARENT_TYPE_DATABASE_ID.equals(parent.type())
                || NotionConstants.PARENT_TYPE_DATA_SOURCE_ID.equals(parent.type())) {
            String dbTitle = "";
            try {
                NotionDatabaseInfo dbInfo = connector.getDatabaseOrDataSourceInfo(client, parent.parentId());
                dbTitle = dbInfo.page.title == null ? "" : dbInfo.page.title;
            } catch (ConnectorException e) {
                log.debug("[Notion] failed to resolve parent db title for record {}: {}",
                        page.id(), e.getMessage());
            }
            List<String> propNames = NotionProperties.extractPropertySchema(page);
            FetchedItem item = buildRecordItem(client, page, propNames, dbTitle);
            if (item != null) {
                items.add(item);
            }
            return items;
        }

        List<NotionBlock> blocks;
        try {
            blocks = client.getBlockChildrenAll(page.id());
        } catch (ConnectorException e) {
            log.warn("[Notion] failed to get blocks for page {}: {}", page.id(), e.getMessage());
            return items;
        }

        connector.resolveFileUploads(client, blocks);

        NotionMarkdown.Result markdown = NotionMarkdown.blocksToMarkdown(blocks);

        // 只跳过**完全**没有内容的页面
        String title = page.title == null ? "" : page.title;
        if (!NotionValues.trimSpace(markdown.markdown).isEmpty()) {
            String fileName = title + ".md";
            if (title.isEmpty()) {
                fileName = NotionConstants.DEFAULT_UNTITLED_NAME + ".md";
            }
            FetchedItem item = new FetchedItem();
            item.setExternalId(page.id());
            item.setTitle(title);
            item.setContent(markdown.markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            item.setContentType(NotionConstants.CONTENT_TYPE_MARKDOWN);
            item.setFileName(fileName);
            item.setUrl(page.url());
            item.setUpdatedAt(page.lastEditedTime);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("channel", NotionConstants.CHANNEL_NOTION);
            metadata.put("object_type", NotionConstants.OBJECT_TYPE_PAGE);
            item.setMetadata(metadata);
            items.add(item);
        }

        // 下载附件（PDF、文档等）。**跳过图片**——它们已经以 ![](url) 出现在
        // Markdown 里，单独下载需要 VLM 才能处理。
        for (NotionAttachment attachment : markdown.attachments) {
            if (attachment.url == null || attachment.url.isEmpty()
                    || "image".equals(attachment.type)) {
                continue;
            }
            byte[] data;
            try {
                data = client.downloadFile(attachment.url);
            } catch (ConnectorException e) {
                log.warn("[Notion] failed to download attachment {}: {}",
                        attachment.fileName, e.getMessage());
                continue;
            }
            FetchedItem item = new FetchedItem();
            item.setExternalId(page.id() + ":" + attachment.fileName);
            item.setTitle(attachment.fileName);
            item.setContent(data);
            item.setContentType(NotionMarkdown.mimeTypeForAttachment(attachment.type));
            item.setFileName(attachment.fileName);
            item.setSourceResourceId(page.id());
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("channel", NotionConstants.CHANNEL_NOTION);
            metadata.put("object_type", NotionConstants.OBJECT_TYPE_ATTACHMENT);
            item.setMetadata(metadata);
            items.add(item);
        }

        for (NotionBlock block : blocks) {
            switch (block.type()) {
                case "child_page": {
                    NotionPage childPage;
                    try {
                        childPage = client.getPage(block.id());
                    } catch (ConnectorException e) {
                        log.warn("[Notion] failed to get child page {}: {}",
                                block.id(), e.getMessage());
                        continue;
                    }
                    items.addAll(fetchPage(client, childPage, visited));
                    break;
                }
                case "child_database":
                    items.addAll(fetchDatabase(client, block.id(), visited));
                    break;
                default:
                    break;
            }
        }
        return items;
    }

    /**
     * 把整库同步成**一条**表格条目（全量）。
     * 接受 data_source_id（Search 给的）或 database_id（child_database 块给的）。
     */
    List<FetchedItem> fetchDatabase(NotionClient client, String id,
                                            Map<String, Boolean> visited) {
        List<FetchedItem> items = new ArrayList<>();
        if (Boolean.TRUE.equals(visited.get(id))) {
            return items;
        }
        visited.put(id, true);

        QueryDatabaseResult queried;
        try {
            queried = queryDatabaseRecords(client, id);
        } catch (ConnectorException e) {
            return items;
        }
        if (queried.records.isEmpty()) {
            return items;
        }
        String queryId = queried.queryId;
        if (!queryId.isEmpty() && !queryId.equals(id)) {
            if (Boolean.TRUE.equals(visited.get(queryId))) {
                return items;
            }
            visited.put(queryId, true);
        }

        // 记录标记成已访问，避免后续重复走 fetchPage
        for (NotionPage record : queried.records) {
            visited.put(record.id(), true);
        }

        FetchedItem item = buildDatabaseItem(client, id, queried.dbTitle, queried.records);
        if (item != null) {
            items.add(item);
        }
        return items;
    }

    /** 整库增量的结果：条目 + "记录 ID → 编辑时间"。 */
    static final class DatabaseIncremental {
        final List<FetchedItem> items;
        final Map<String, OffsetDateTime> recordEditTimes;

        DatabaseIncremental(List<FetchedItem> items, Map<String, OffsetDateTime> recordEditTimes) {
            this.items = items;
            this.recordEditTimes = recordEditTimes;
        }
    }

    /**
     * 只有变化的记录才重抓，
     * 返回"记录 ID → 编辑时间"供 cursor 合并。
     */
    DatabaseIncremental fetchDatabaseIncremental(NotionClient client, String id,
                                                         Map<String, OffsetDateTime> prevEditTimes,
                                                         Map<String, Boolean> visited) {
        Map<String, OffsetDateTime> empty = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(visited.get(id))) {
            return new DatabaseIncremental(new ArrayList<>(), empty);
        }
        visited.put(id, true);

        QueryDatabaseResult queried;
        try {
            queried = queryDatabaseRecords(client, id);
        } catch (ConnectorException e) {
            return new DatabaseIncremental(new ArrayList<>(), empty);
        }
        String queryId = queried.queryId;
        if (!queryId.isEmpty() && !queryId.equals(id)) {
            if (Boolean.TRUE.equals(visited.get(queryId))) {
                return new DatabaseIncremental(new ArrayList<>(), empty);
            }
            visited.put(queryId, true);
        }

        Map<String, OffsetDateTime> recordEditTimes = new LinkedHashMap<>();
        int changedCount = 0;

        for (NotionPage record : queried.records) {
            visited.put(record.id(), true);
            if (record.inTrash) {
                continue;
            }
            recordEditTimes.put(record.id(), record.lastEditedTime);

            OffsetDateTime prevTime = prevEditTimes.get(record.id());
            if (prevTime == null || !connector.equalInstants(record.lastEditedTime, prevTime)) {
                changedCount++;
            }
        }

        log.info("[Notion] database {} incremental: {} changed out of {} records",
                id, changedCount, queried.records.size());

        // 有任何记录变化（或没有上一次的时间）→ 整张表重建
        if (changedCount > 0 || prevEditTimes.isEmpty()) {
            FetchedItem item = buildDatabaseItem(client, id, queried.dbTitle, queried.records);
            if (item != null) {
                List<FetchedItem> items = new ArrayList<>();
                items.add(item);
                return new DatabaseIncremental(items, recordEditTimes);
            }
        }
        return new DatabaseIncremental(new ArrayList<>(), recordEditTimes);
    }

    /** 一次数据库查询的结果：记录 + 库标题 + 实际查询用的 ID。 */
    static final class QueryDatabaseResult {
        final List<NotionPage> records;
        final String dbTitle;
        final String queryId;

        QueryDatabaseResult(List<NotionPage> records, String dbTitle, String queryId) {
            this.records = records;
            this.dbTitle = dbTitle;
            this.queryId = queryId;
        }
    }

    /** 查询数据库的全部记录。 */
    QueryDatabaseResult queryDatabaseRecords(NotionClient client, String id) {
        NotionDatabaseInfo dbInfo;
        try {
            dbInfo = connector.getDatabaseOrDataSourceInfo(client, id);
        } catch (ConnectorException e) {
            log.warn("[Notion] failed to get database/data_source info {}: {}", id, e.getMessage());
            throw e;
        }

        String queryId = dbInfo.dataSourceId;
        if (queryId.isEmpty()) {
            queryId = id;
        }
        List<NotionPage> records;
        try {
            records = client.queryDatabaseAll(queryId);
        } catch (ConnectorException e) {
            log.warn("[Notion] failed to query database {}: {}", id, e.getMessage());
            throw e;
        }
        log.info("[Notion] database {} ({}): {} records",
                id, dbInfo.page.title == null ? "" : dbInfo.page.title, records.size());
        return new QueryDatabaseResult(records, dbInfo.page.title, queryId);
    }

    /**
     * 把一条数据库记录转成知识条目
     * （属性当抬头、块内容当正文）。
     *
     * <p>逐字符契约：</p>
     * <pre>
     *   "# Record One\n\n- **Status**: Done\nLine2|Pipe\n- **Tag**: X|Y"
     *   "# WithContent\n\n- **Status**: Deep\n\nrecord body"
     * </pre>
     * <p>注意属性值在这里**不做** {@code |} 转义、也**不**把换行换成 {@code <br>}
     * ——那是 {@code buildDatabaseItem} 的表格才有的处理。</p>
     */
    FetchedItem buildRecordItem(NotionClient client, NotionPage record,
                                List<String> propNames, String dbTitle) {
        StringBuilder content = new StringBuilder();
        String title = record.title == null ? "" : record.title;
        if (title.isEmpty()) {
            title = NotionConstants.DEFAULT_UNTITLED_NAME;
        }
        content.append("# ").append(title).append("\n\n");

        if (record.rawProperties != null) {
            for (String name : NotionProperties.orEmpty(propNames)) {
                JsonNode propMap = record.rawProperties.get(name);
                if (propMap != null && propMap.isObject()) {
                    String val = NotionProperties.propertyToString(propMap);
                    if (!val.isEmpty()) {
                        content.append("- **").append(name).append("**: ")
                                .append(val).append('\n');
                    }
                }
            }
        }

        // 数据库记录也可能有页面式的块内容
        List<NotionBlock> blocks = null;
        try {
            blocks = client.getBlockChildrenAll(record.id());
        } catch (ConnectorException e) {
            log.warn("[Notion] failed to get blocks for record {}: {}",
                    record.id(), e.getMessage());
        }
        if (blocks != null && !blocks.isEmpty()) {
            connector.resolveFileUploads(client, blocks);
            NotionMarkdown.Result markdown = NotionMarkdown.blocksToMarkdown(blocks);
            if (!NotionValues.trimSpace(markdown.markdown).isEmpty()) {
                content.append('\n').append(markdown.markdown);
            }
        }

        String bodyStr = NotionValues.trimSpace(content.toString());
        if (bodyStr.isEmpty()) {
            return null;
        }

        FetchedItem item = new FetchedItem();
        item.setExternalId(record.id());
        item.setTitle(title);
        item.setContent(bodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        item.setContentType(NotionConstants.CONTENT_TYPE_MARKDOWN);
        item.setFileName(title + ".md");
        item.setUrl(record.url());
        item.setUpdatedAt(record.lastEditedTime);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel", NotionConstants.CHANNEL_NOTION);
        metadata.put("object_type", NotionConstants.OBJECT_TYPE_PAGE);
        metadata.put("database", dbTitle == null ? "" : dbTitle);
        item.setMetadata(metadata);
        return item;
    }

    /**
     * 整库合成一条 Markdown 表格文档。
     *
     * <p>逐字符契约（含 {@code |} 转义与换行转 {@code <br>}）：</p>
     * <pre>
     * "# Test Database\n\n| Title | Status | Tag |\n|---|---|---|\n"
     * + "| Record One | Done&lt;br&gt;Line2\\|Pipe | X\\|Y |\n"
     * + "| Untitled | Open |  |\n"
     * + "\n\n## WithContent 内容\n\nrecord body"
     * </pre>
     * <p>几个坑：① 被 trash 的记录**跳过整行**（连它的 {@code ## 内容} 也不生成）；
     * ② 记录的额外内容攒在 {@code extraContent} 里、表格之后再补；
     * ③ 每个附加小节前面是 {@code "\n## X 内容\n\n"}、markdown 之后再一个
     * {@code "\n"}，最后整体再前置一个 {@code "\n"}；④ {@code updated_at} 取
     * <b>第一条记录</b>的编辑时间（不是 Now——那个只在 records 为空时兜底，
     * 而 records 为空早就 return nil 了）。</p>
     */
    FetchedItem buildDatabaseItem(NotionClient client, String id, String dbTitle,
                                  List<NotionPage> records) {
        if (records == null || records.isEmpty()) {
            return null;
        }

        List<String> propNames = NotionProperties.extractPropertySchema(records.get(0));

        StringBuilder content = new StringBuilder();
        String title = dbTitle == null ? "" : dbTitle;
        if (title.isEmpty()) {
            title = NotionConstants.DEFAULT_UNTITLED_NAME;
        }
        content.append("# ").append(title).append("\n\n");

        // 表头
        content.append("| Title ");
        for (String name : NotionProperties.orEmpty(propNames)) {
            content.append("| ").append(name.replace("|", "\\|")).append(' ');
        }
        content.append("|\n|");
        content.append("---|");
        for (int i = 0; i < NotionProperties.orEmpty(propNames).size(); i++) {
            content.append("---|");
        }
        content.append('\n');

        StringBuilder extraContent = new StringBuilder();

        for (NotionPage record : records) {
            if (record.inTrash) {
                continue;
            }

            String recordTitle = record.title == null ? "" : record.title;
            if (recordTitle.isEmpty()) {
                recordTitle = NotionConstants.DEFAULT_UNTITLED_NAME;
            }

            content.append("| ").append(recordTitle.replace("|", "\\|")).append(' ');

            if (record.rawProperties != null) {
                for (String name : NotionProperties.orEmpty(propNames)) {
                    String val = "";
                    JsonNode propMap = record.rawProperties.get(name);
                    if (propMap != null && propMap.isObject()) {
                        val = NotionProperties.propertyToString(propMap);
                    }
                    val = val.replace("\n", "<br>");
                    val = val.replace("|", "\\|");
                    content.append("| ").append(val).append(' ');
                }
            }
            content.append("|\n");

            // 记录可能还有页面正文
            List<NotionBlock> blocks = null;
            try {
                blocks = client.getBlockChildrenAll(record.id());
            } catch (ConnectorException e) {
                blocks = null;
            }
            if (blocks != null && !blocks.isEmpty()) {
                connector.resolveFileUploads(client, blocks);
                NotionMarkdown.Result markdown = NotionMarkdown.blocksToMarkdown(blocks);
                if (!NotionValues.trimSpace(markdown.markdown).isEmpty()) {
                    extraContent.append("\n## ").append(recordTitle).append(" 内容\n\n")
                            .append(markdown.markdown).append('\n');
                }
            }
        }

        if (extraContent.length() > 0) {
            content.append('\n').append(extraContent);
        }

        String bodyStr = NotionValues.trimSpace(content.toString());
        if (bodyStr.isEmpty()) {
            return null;
        }

        OffsetDateTime updatedAt = NotionValues.now();
        if (!records.isEmpty()) {
            updatedAt = records.get(0).lastEditedTime;
        }

        FetchedItem item = new FetchedItem();
        item.setExternalId(id);
        item.setTitle(title);
        item.setContent(bodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        item.setContentType(NotionConstants.CONTENT_TYPE_MARKDOWN);
        item.setFileName(title + ".md");
        item.setUrl("https://notion.so/" + id.replace("-", ""));
        item.setUpdatedAt(updatedAt);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel", NotionConstants.CHANNEL_NOTION);
        metadata.put("object_type", NotionConstants.OBJECT_TYPE_DATABASE);
        item.setMetadata(metadata);
        return item;
    }
}
