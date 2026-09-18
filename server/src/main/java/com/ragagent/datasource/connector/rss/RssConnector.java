package com.ragagent.datasource.connector.rss;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * RSS / Atom 数据源连接器（对照 Go
 * {@code internal/datasource/connector/rss/connector.go}）。
 *
 * <h2>它做什么</h2>
 * <p>把一个或多个 feed 里的条目同步进知识库：每个 feed URL 是一个可选资源，
 * 每个条目是一个知识条目（正文是文章全文的 Markdown，取不到就回落 feed 内容）。</p>
 * <ul>
 *   <li><b>私有 feed</b>：自定义请求头（{@code Authorization: Bearer …} 之类）
 *       <b>只加在 feed 抓取上</b>，绝不发给第三方文章页。</li>
 *   <li><b>增量</b>：两级去重——feed 信号指纹没变就连文章页都不抓；
 *       抓完之后内容指纹仍没变就跳过灌入。</li>
 *   <li><b>不处理删除</b>：feed 本来就会自然丢弃旧条目，同步删除会造成误删。</li>
 * </ul>
 *
 * <h2>⚠️ 三块接缝与它们的降级后果（先读这段再看代码）</h2>
 * <table border="1">
 *   <caption>Go 依赖的三个第三方库在 Java 侧的处置</caption>
 *   <tr><th>Go 库</th><th>Java 侧</th><th>后果</th></tr>
 *   <tr>
 *     <td>{@code mmcdole/gofeed}（feed 解析）</td>
 *     <td>{@link FeedParser} 接缝 + {@link JdkXmlFeedParser}（JDK DOM，字段子集）</td>
 *     <td>支持 RSS 0.9x/1.0/2.0 与 Atom 1.0 的<b>连接器用到的字段</b>；
 *         <b>不支持 JSON Feed</b>；日期布局覆盖约 30/180；对格式不规范的 XML 更严格。
 *         详见 {@link JdkXmlFeedParser} 的类注释。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code readeck/go-readability}（正文抽取）</td>
 *     <td>{@link ArticleExtractor} 接缝 + {@link UnavailableArticleExtractor}（<b>永远失败</b>）</td>
 *     <td><b>灌入的是 feed 摘要而不是文章全文</b>；无标题时不再回落到页面标题。
 *         控制流与 Go 完全一致，只是恒走"全文抓取失败"那条回落分支。
 *         详见 {@link ArticleExtractor} 的类注释。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code JohannesKaufmann/html-to-markdown}（HTML→MD）</td>
 *     <td>{@link HtmlToMarkdown} 接缝 + {@link JdkHtmlToMarkdown}（有界实现）</td>
 *     <td>覆盖常用块级/行内标签；表格、嵌套列表、Markdown 转义的细节与 Go 不同。
 *         转换失败时与 Go 一样回落到 {@code TrimSpace(html)} 原文。
 *         详见 {@link JdkHtmlToMarkdown} 的类注释。</td>
 *   </tr>
 * </table>
 *
 * <h2>公开签名（给下一步 service 层）</h2>
 * <ul>
 *   <li>{@link #RssConnector()} —— 三块接缝都用默认（降级）实现；</li>
 *   <li>{@link #RssConnector(FeedParser, ArticleExtractor, HtmlToMarkdown)} —— 装配用；
 *       恢复任何一块能力只需换一个实现，连接器逻辑一行不动；</li>
 *   <li>{@link #fetchAll} / {@link #fetchIncremental} 在部分失败时抛
 *       {@link PartialFetchException}（带 items 与 cursor），全部失败时抛
 *       {@link AllFeedsFailedException}（带 cursor）。<b>先取结果、再判异常类型</b>
 *       —— 见 {@link RssFetchState} 的类注释。</li>
 * </ul>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子 / 软删除 / 自动时间戳 / 唯一索引 / 关联预加载 / 默认排序</b>：全无
 *       ——本连接器不碰数据库，只产出 {@link FetchedItem} 与 {@link SyncCursor}。</li>
 * </ol>
 */
public class RssConnector implements Connector {

    private static final Logger log = LoggerFactory.getLogger(RssConnector.class);

    /**
     * 对照 Go {@code types.ChannelRSS}（{@code internal/types/knowledge.go} 里的
     * {@code ChannelRSS = "rss"}）。它进 {@link FetchedItem#getMetadata()} 的
     * {@code channel} 键，最终落到知识条目的来源渠道列——是<b>存储契约</b>。
     */
    public static final String CHANNEL_RSS = "rss";

    /** 对照 Go {@code ListResources} 里那个常量字面量 {@code Type: "feed"}。 */
    public static final String RESOURCE_TYPE_FEED = "feed";

    private final FeedParser feedParser;
    private final ArticleExtractor articleExtractor;
    private final HtmlToMarkdown markdownConverter;

    /** 三块接缝都用降级实现——生产装配走这个。 */
    public RssConnector() {
        this(new JdkXmlFeedParser(), new UnavailableArticleExtractor(), new JdkHtmlToMarkdown());
    }

    /**
     * 完整装配（测试与将来的"恢复某块能力"用）。
     *
     * @param feedParser      feed 解析器，不可为 null
     * @param articleExtractor 正文抽取器，不可为 null（想保持 Go 行为就传
     *                         {@link UnavailableArticleExtractor}）
     * @param htmlToMarkdown  HTML→Markdown，不可为 null
     */
    public RssConnector(FeedParser feedParser, ArticleExtractor articleExtractor,
                        HtmlToMarkdown htmlToMarkdown) {
        this.feedParser = feedParser;
        this.articleExtractor = articleExtractor;
        this.markdownConverter = htmlToMarkdown;
    }

    // ── Connector 实现 ────────────────────────────────────────────────────

    @Override
    public String type() {
        return DataSourceConstants.CONNECTOR_TYPE_RSS;
    }

    /**
     * 对照 Go {@code Validate}：每个 feed URL 都要<b>抓得到且解析得动</b>，
     * 任意一个失败就整体失败。
     *
     * <p>失败文案逐字对齐 Go：抓取失败是 {@code "fetch feed <url>: <err>"}、
     * 解析失败是 {@code "parse feed <url>: <err>"}。两者都是<b>普通
     * {@link ConnectorException}</b>（不是 {@code InvalidConfig}/{@code InvalidCredentials}）
     * ——Go 那边也只是 {@code fmt.Errorf}，没有哨兵。</p>
     */
    @Override
    public void validate(DataSourceConfig config) {
        RssConfig cfg = RssConfig.parse(config);
        RssClient client = newClient(cfg);
        for (String feedUrl : cfg.feedUrlList()) {
            byte[] data;
            try {
                data = client.fetchFeed(feedUrl);
            } catch (ConnectorException e) {
                throw new ConnectorException("fetch feed " + feedUrl + ": " + e.getMessage(), e);
            }
            try {
                feedParser.parse(data);
            } catch (FeedParseException e) {
                throw new ConnectorException("parse feed " + feedUrl + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * 对照 Go {@code ResolveResourceAncestors}：feed 是扁平列表、没有层级，
     * 所以一个选择没有祖先要展开——恒回空列表（Go 也是 {@code []string{}}）。
     */
    @Override
    public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
        return List.of();
    }

    /**
     * 对照 Go {@code ListResources}：每个配置的 feed URL 一个资源。
     *
     * <ul>
     *   <li>{@code parentId} 非空 → 空列表（feed 是扁平的，惰性加载没有下一层）；</li>
     *   <li>抓取失败 → {@code Description = "fetch failed: " + err}，<b>仍然列出</b>
     *       （用户可以把它取消勾选，而不是整个列表失败）；</li>
     *   <li>解析失败 → {@code Description = "parse failed: " + err}，同样列出；</li>
     *   <li>成功 → {@code Name = TrimSpace(feed.Title)}（空则<b>保留 URL</b>）、
     *       {@code Description = TrimSpace(feed.Description)}、
     *       {@code URL = feed.Link}（非空时）、
     *       {@code ModifiedAt = feed.UpdatedParsed}（非 nil 时）、
     *       {@code Metadata = {"item_count": len(feed.Items)}}。</li>
     * </ul>
     */
    @Override
    public List<Resource> listResources(DataSourceConfig config, String parentId) {
        if (parentId != null && !parentId.isEmpty()) {
            return List.of();
        }
        RssConfig cfg = RssConfig.parse(config);
        RssClient client = newClient(cfg);

        List<String> feedUrls = cfg.feedUrlList();
        List<Resource> out = new ArrayList<>(feedUrls.size());
        for (String feedUrl : feedUrls) {
            Resource res = new Resource();
            res.setExternalId(feedUrl);
            res.setType(RESOURCE_TYPE_FEED);
            res.setName(feedUrl);
            res.setUrl(feedUrl);

            byte[] data;
            try {
                data = client.fetchFeed(feedUrl);
            } catch (ConnectorException e) {
                log.warn("[RSS] list: fetch {} failed: {}", feedUrl, e.getMessage());
                res.setDescription("fetch failed: " + e.getMessage());
                out.add(res);
                continue;
            }
            FeedParser.ParsedFeed feed;
            try {
                feed = feedParser.parse(data);
            } catch (FeedParseException e) {
                log.warn("[RSS] list: parse {} failed: {}", feedUrl, e.getMessage());
                res.setDescription("parse failed: " + e.getMessage());
                out.add(res);
                continue;
            }
            String title = RssUtil.goTrim(feed.title());
            if (!title.isEmpty()) {
                res.setName(title);
            }
            res.setDescription(RssUtil.goTrim(feed.description()));
            if (!RssUtil.nullToEmpty(feed.link()).isEmpty()) {
                res.setUrl(feed.link());
            }
            if (feed.updatedParsed() != null) {
                res.setModifiedAt(feed.updatedParsed());
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("item_count", feed.items().size());
            res.setMetadata(metadata);
            out.add(res);
        }
        return out;
    }

    /**
     * 对照 Go {@code FetchAll}：全量同步指定 feed（{@code resourceIds} 为空 → 全部已配置 feed）。
     *
     * <p>Go 是 {@code items, _, err := c.walk(...)} —— <b>cursor 被显式丢弃</b>，
     * 所以本方法抛出的 {@link PartialFetchException} 里 {@code cursor()} 恒为 {@code null}
     * （照抄那条 {@code _}）。</p>
     */
    @Override
    public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
        WalkOutcome outcome = walk(config, resourceIds, null, false);
        if (!outcome.feedErrors().isEmpty()) {
            throwFetchFailure(outcome, null);
        }
        return outcome.items();
    }

    /**
     * 对照 Go {@code FetchIncremental}：只返回内容指纹变过的条目；<b>不发删除</b>。
     *
     * <p>返回的 {@link FetchIncrementalResult} 里 items 与 cursor 都可能为 {@code null}
     * （"所有 feed 都失败"那条路径 items 为 nil、cursor 有值）。
     * 部分失败时抛 {@link PartialFetchException}，items 与 cursor 在异常上
     * ——见 {@link RssFetchState}。</p>
     */
    @Override
    public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
        RssCursor prev = parseConnectorCursor(cursor);

        List<String> resourceIds = config == null ? null : config.getResourceIds();
        WalkOutcome outcome = walk(config, resourceIds, prev, true);

        Map<String, Object> cursorMap = outcome.cursor().toMap();
        SyncCursor syncCursor = new SyncCursor();
        syncCursor.setLastSyncTime(outcome.cursor().getLastSyncTime());
        syncCursor.setConnectorCursor(cursorMap);

        if (!outcome.feedErrors().isEmpty()) {
            // 与 Go 的 `return items, syncCursor, err` 一致：结果已经构造好了，
            // 异常只是把"部分成功/全部失败"这件事额外告诉调用方。
            throwFetchFailure(outcome, syncCursor);
        }
        return new FetchIncrementalResult(outcome.items(), syncCursor);
    }

    // ── Go walk ───────────────────────────────────────────────────────────

    /**
     * {@code walk} 的返回值：条目 + 新游标 + 失败明细。
     *
     * <p>这不是 Go 的三返回值本身（Go 的第三个值是 {@code error}），而是它的
     * "先算完再分类"版本：{@code feedErrors} 为空表示全部成功；非空时由
     * {@link #throwFetchFailure} 按 Go 的两条判据决定抛哪个异常。
     * 这样 {@link #walk} 内部就没有半路抛异常的分支，三支汇总逻辑与 Go 的尾段一一对应。</p>
     *
     * @param items      抓到的条目（可能为空，<b>但恒非 null</b>——Java 侧用空列表表达 Go 的 nil slice）
     * @param cursor     新游标，恒非 null
     * @param feedErrors 每个失败 feed 的 {@code "<url>: <err>"}
     * @param feedTotal   本次要处理的 feed 总数（判"全部失败"要用）
     */
    private record WalkOutcome(List<FetchedItem> items, RssCursor cursor,
                               List<String> feedErrors, int feedTotal) {
    }

    /**
     * 对照 Go {@code walk}：{@code FetchAll} / {@code FetchIncremental} 的公共实现。
     *
     * <p>增量时的两级去重（照抄 Go）：</p>
     * <ol>
     *   <li><b>feed 信号没变 → 连文章页都不抓</b>（省网络）：
     *       {@code prevItems[itemID] != "" && feedSig == prevSig}；</li>
     *   <li>抓完之后<b>内容指纹仍相同 → 跳过灌入</b>（省写库）：
     *       {@code prevItems[itemID] == resolved.fingerprint}。</li>
     * </ol>
     * <p>无论跳过与否，新游标都反映<b>最新已知状态</b>，这样下一次同步才有比较基准。</p>
     */
    private WalkOutcome walk(DataSourceConfig config, List<String> resourceIds, RssCursor prev,
                             boolean incremental) {
        RssConfig cfg = RssConfig.parse(config);

        List<String> feedUrls = resourceIds;
        if (feedUrls == null || feedUrls.isEmpty()) {
            feedUrls = cfg.feedUrlList();
        }

        RssClient client = newClient(cfg);

        RssCursor newCursor = new RssCursor();
        newCursor.setLastSyncTime(OffsetDateTime.now(ZoneOffset.UTC));
        newCursor.setFeedItems(new LinkedHashMap<>());
        newCursor.setFeedSignals(new LinkedHashMap<>());

        List<FetchedItem> out = new ArrayList<>();
        List<String> feedErrors = new ArrayList<>();

        for (String feedUrl : feedUrls) {
            byte[] data;
            try {
                data = client.fetchFeed(feedUrl);
            } catch (ConnectorException e) {
                log.warn("[RSS] sync: fetch {} failed: {}", feedUrl, e.getMessage());
                feedErrors.add(feedUrl + ": " + e.getMessage());
                RssUtil.copyFeedCursor(newCursor, prev, feedUrl);
                continue;
            }
            FeedParser.ParsedFeed feed;
            try {
                feed = feedParser.parse(data);
            } catch (FeedParseException e) {
                log.warn("[RSS] sync: parse {} failed: {}", feedUrl, e.getMessage());
                feedErrors.add(feedUrl + ": " + e.getMessage());
                RssUtil.copyFeedCursor(newCursor, prev, feedUrl);
                continue;
            }

            Map<String, String> newItems = newCursor.bucketForItems(feedUrl);
            Map<String, String> newSignals = newCursor.bucketForSignals(feedUrl);

            Map<String, String> prevItems = null;
            Map<String, String> prevSignals = null;
            if (incremental && prev != null) {
                prevItems = prev.getFeedItems() == null ? null : prev.getFeedItems().get(feedUrl);
                if (prev.getFeedSignals() != null) {
                    prevSignals = prev.getFeedSignals().get(feedUrl);
                }
            }

            int kept = 0;
            int skipped = 0;
            for (FeedParser.ParsedItem item : feed.items()) {
                if (item == null) {
                    continue;
                }
                String itemId = RssUtil.firstNonEmpty(item.guid(), item.link(), item.title());
                if (itemId.isEmpty()) {
                    continue;
                }

                String feedContent = RssUtil.firstNonEmpty(item.content(), item.description());
                String feedSig = RssUtil.feedSignalFingerprint(item, feedContent);

                if (incremental && prevItems != null) {
                    String prevFp = RssUtil.mapGet(prevItems, itemId);
                    String prevSig = prevSignals == null ? "" : RssUtil.mapGet(prevSignals, itemId);
                    if (!prevFp.isEmpty() && feedSig.equals(prevSig)) {
                        newItems.put(itemId, prevFp);
                        newSignals.put(itemId, feedSig);
                        skipped++;
                        continue;
                    }
                }

                ResolvedItem resolved =
                        resolveItem(client, feed, item, feedUrl, itemId, feedContent);
                newItems.put(itemId, resolved.fingerprint());
                newSignals.put(itemId, feedSig);

                if (incremental && prevItems != null
                        && resolved.fingerprint().equals(RssUtil.mapGet(prevItems, itemId))) {
                    skipped++;
                    continue;
                }
                kept++;
                out.add(resolved.item());
            }

            log.info("[RSS] feed {}: items={} fetched={} skipped={}",
                    feedUrl, feed.items().size(), kept, skipped);
        }

        return new WalkOutcome(out, newCursor, feedErrors, feedUrls.size());
    }

    /**
     * 对照 Go {@code walk} 尾段的两条分支。
     *
     * <pre>
     *   if len(feedErrors) &gt; 0 {
     *       if len(out) == 0 &amp;&amp; len(feedErrors) == len(feedURLs) {
     *           return nil, newCursor, fmt.Errorf("all feeds failed: %s", join(feedErrors, "; "))
     *       }
     *       return out, newCursor, &amp;PartialFetchError{Details: feedErrors}
     *   }
     * </pre>
     *
     * @param syncCursor 给 {@code FetchIncremental} 用；{@code FetchAll} 传 {@code null}
     *                   （对照 Go 的 {@code _}）
     */
    private static void throwFetchFailure(WalkOutcome outcome, SyncCursor syncCursor) {
        if (outcome.items().isEmpty() && outcome.feedErrors().size() == outcome.feedTotal()) {
            throw new AllFeedsFailedException(
                    "all feeds failed: " + String.join("; ", outcome.feedErrors()), syncCursor);
        }
        throw new PartialFetchException(outcome.feedErrors(), outcome.items(), syncCursor);
    }

    // ── Go resolveItem ────────────────────────────────────────────────────

    /** 对照 Go 的 {@code resolvedFeedItem}。 */
    private record ResolvedItem(FetchedItem item, String fingerprint) {
    }

    /**
     * 对照 Go {@code resolveItem}：为单条 feed entry 组装一个 {@link FetchedItem}。
     *
     * <p>内容优先级：<b>文章全文 &gt; feed 内容</b>。文章抓取失败时打一条 warn
     * （文案逐字对齐 Go 的 {@code "[RSS] full-text fetch failed for %s (using feed content): %v"}）
     * 并使用 {@code firstNonEmpty(item.Content, item.Description)}。</p>
     *
     * <p>{@code updatedAt} 的回落链照抄 Go：
     * {@code UpdatedParsed} → {@code PublishedParsed} → {@code time.Now().UTC()}。
     * ⚠️ 只有 {@code <pubDate>} 的 RSS 条目在这里走的是第二档
     * （gofeed 把 {@code pubDate} 放进 {@code PublishedParsed}，{@code UpdatedParsed}
     * 只来自 {@code dc:date}）。</p>
     */
    private ResolvedItem resolveItem(RssClient client, FeedParser.ParsedFeed feed,
                                     FeedParser.ParsedItem item, String feedUrl, String itemId,
                                     String feedContent) {
        String title = RssUtil.firstNonEmpty(item.title(), "untitled");

        // 优先文章全文；失败就回落 feed 内容（Go 的同一分支）。
        String contentHtml = feedContent;
        if (!RssUtil.goTrim(item.link()).isEmpty()) {
            try {
                ArticleExtractor.ExtractedArticle article = client.extractArticle(item.link());
                contentHtml = article.contentHtml();
                if ((item.title() == null || item.title().isEmpty())
                        && article.title() != null && !article.title().isEmpty()) {
                    title = article.title();
                }
            } catch (RuntimeException e) {
                log.warn("[RSS] full-text fetch failed for {} (using feed content): {}",
                        item.link(), e.getMessage());
            }
        }

        String content = htmlToMarkdown(contentHtml);

        // 照抄 Go 的三档：UpdatedParsed -> PublishedParsed -> time.Now().UTC()，
        // 每一档都要求"非 nil 且非零值"（Go 写的是 !t.IsZero()）。
        OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (!RssUtil.isGoZeroTime(item.updatedParsed())) {
            updatedAt = item.updatedParsed();
        } else if (!RssUtil.isGoZeroTime(item.publishedParsed())) {
            updatedAt = item.publishedParsed();
        }

        String author = item.authorName() == null ? "" : item.authorName();

        FetchedItem fetched = new FetchedItem();
        fetched.setExternalId(RssUtil.itemExternalID(feedUrl, itemId));
        fetched.setTitle(title);
        fetched.setContent(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fetched.setContentType("text/markdown");
        fetched.setFileName(RssUtil.sanitizeFileName(title) + ".md");
        fetched.setUrl(RssUtil.nullToEmpty(item.link()));
        fetched.setUpdatedAt(updatedAt);
        fetched.setSourceResourceId(feedUrl);

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel", CHANNEL_RSS);
        metadata.put("feed_url", feedUrl);
        metadata.put("feed_title", RssUtil.nullToEmpty(feed.title()));
        metadata.put("guid", RssUtil.nullToEmpty(item.guid()));
        metadata.put("link", RssUtil.nullToEmpty(item.link()));
        metadata.put("author", author);
        fetched.setMetadata(metadata);

        return new ResolvedItem(fetched, RssUtil.contentFingerprint(content));
    }

    // ── Go 的包级 htmlToMarkdown ──────────────────────────────────────────

    /**
     * 对照 Go 的包级函数 {@code htmlToMarkdown}：
     * 空白输入回空串；转换失败<b>或</b>结果 TrimSpace 后为空，就回落
     * {@code strings.TrimSpace(html)}——<b>绝不静默丢内容</b>。
     */
    private String htmlToMarkdown(String html) {
        String raw = RssUtil.nullToEmpty(html);
        if (RssUtil.goTrim(raw).isEmpty()) {
            return "";
        }
        try {
            String md = markdownConverter.convert(raw);
            if (md == null || RssUtil.goTrim(md).isEmpty()) {
                return RssUtil.goTrim(raw);
            }
            return RssUtil.goTrim(md);
        } catch (RuntimeException e) {
            return RssUtil.goTrim(raw);
        }
    }

    // ── Go FetchIncremental 开头那段游标解码 ──────────────────────────────

    /**
     * 对照 Go {@code FetchIncremental} 开头那段：
     * 把 {@code cursor.ConnectorCursor} 经一次 JSON 往返解回 {@link RssCursor}；
     * 任何一步出错就<b>只记日志、当作没有上一轮</b>（{@code prev = nil}）——
     * 也就是退化成一次全量。
     */
    private RssCursor parseConnectorCursor(SyncCursor cursor) {
        if (cursor == null || cursor.getConnectorCursor() == null) {
            return null;
        }
        try {
            return RssCursor.fromMap(cursor.getConnectorCursor());
        } catch (RuntimeException e) {
            log.warn("[RSS] unmarshal connector cursor: {}", e.getMessage());
            return null;
        }
    }

    private RssClient newClient(RssConfig cfg) {
        return new RssClient(cfg.parseHeaders(), articleExtractor);
    }
}
