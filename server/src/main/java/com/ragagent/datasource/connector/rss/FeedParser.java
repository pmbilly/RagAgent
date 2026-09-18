package com.ragagent.datasource.connector.rss;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>接缝（seam）</b>：feed 解析器。
 *
 * <h2>Go 侧是什么</h2>
 * <p>Go 的实现是 {@code github.com/mmcdole/gofeed} 的
 * {@code gofeed.NewParser().Parse(reader)}，它支持 <b>RSS 0.9x / 1.0 / 2.0、Atom 1.0、
 * 以及 JSON Feed</b>，并且用一个非常宽松的、自研的 XML pull parser（goxpp）
 * 读文档——连未声明的 HTML 实体（{@code &nbsp;}）和裸 {@code &} 都能容忍。</p>
 *
 * <h2>Java 侧为什么是一个接口</h2>
 * <p>本项目<b>不允许为了翻译而新增依赖</b>，而 gofeed 没有任何等价的 Java 库。
 * 所以解析这一步被抽成接口，默认实现是
 * {@link JdkXmlFeedParser}——用 JDK 自带的 DOM 解析器（不算新依赖）
 * 实现 <b>字段子集</b>。将来若要引入 feed 库（或自己补齐），
 * 只需另写一个实现并在 {@link RssConnector} 的构造器里换上，连接器的其余逻辑一行不动。</p>
 *
 * <h2>接口只暴露连接器真正用到的字段</h2>
 * <p>Go 的 {@code gofeed.Feed}/{@code gofeed.Item} 有几十个字段，
 * 但 RSS 连接器只读下面这几个（见 {@code connector.go} 的 {@code ListResources} 与
 * {@code resolveItem}）：</p>
 * <pre>
 *   Feed : Title / Description / Link / UpdatedParsed / Items
 *   Item : GUID / Link / Title / Content / Description / UpdatedParsed / PublishedParsed / Author.Name
 * </pre>
 * <p>所以接缝也只有这些。<b>刻意不建模的</b>（因为连接器读了也不用）：
 * categories / enclosures / iTunes / media / links[] / feed 级 PublishedParsed / language …
 * ——它们一个都不在 {@code walk} 或 {@code resolveItem} 的输入里。</p>
 *
 * <h2>字段语义必须逐条对齐 gofeed，不能"看着像就行"</h2>
 * <ul>
 *   <li><b>RSS 的 {@code UpdatedParsed} 只来自 {@code dc:date}</b>，<b>不</b>来自
 *       {@code pubDate}——{@code pubDate} 进的是 {@code PublishedParsed}。
 *       所以一条只有 {@code pubDate} 的 RSS 条目，{@code updatedParsed()} 是 {@code null}。</li>
 *   <li><b>Atom 的 {@code PublishedParsed} 在缺 {@code published} 时回落 {@code updated}</b>
 *       （gofeed 的 {@code translateItemPublishedParsed} 就是这么写的），
 *       但 {@code UpdatedParsed} 仍然是 {@code updated} 本身。</li>
 *   <li><b>时间一律归一成 UTC</b>：gofeed 的解析器在赋 {@code *Parsed} 之前统一做了
 *       {@code date.UTC()}。{@code RssUtil.feedSignalFingerprint} 又会 {@code .UTC()}
 *       一次再按 RFC3339 输出，所以指纹里恒是 {@code Z} 结尾。</li>
 *   <li><b>Atom 条目的 link 只认 {@code rel="alternate"}</b>——gofeed 的
 *       {@code firstLinkWithType("alternate", …)} 是<b>精确匹配</b>，
 *       没有 {@code rel} 属性的 {@code <link href="…"/>} 取不到值。
 *       这个反直觉的行为照抄了（{@code JdkXmlFeedParser} 有对应测试钉住）。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 * <p>解析失败抛 {@link FeedParseException}，消息会原样进入
 * {@code "parse feed <url>: <msg>"} / {@code ListResources} 的
 * {@code "parse failed: <msg>"}。Go 的非 feed 文档报 {@code "Failed to detect feed type"}
 * ——Java 侧对"根元素不是 rss/rdf/feed"的情况也用这条文案。</p>
 */
public interface FeedParser {

    /**
     * 解析一份 feed 文档。
     *
     * @param data 原始响应体（已是 feed 文档本身，未经编码转换）
     * @throws FeedParseException 无法识别 / XML 结构非法 / 本实现不支持该格式
     */
    ParsedFeed parse(byte[] data);

    /**
     * 连接器用到的 feed 字段子集（对照 {@code gofeed.Feed} 的子集）。
     *
     * <p>{@code updatedParsed} 可为 {@code null}（RSS 没有 {@code lastBuildDate}/{@code dc:date}
     * 时就是这样）——{@code ListResources} 只有非 null 才回填 {@code modified_at}。</p>
     */
    record ParsedFeed(String title, String description, String link,
                      OffsetDateTime updatedParsed, List<ParsedItem> items) {

        public ParsedFeed {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 连接器用到的条目字段子集（对照 {@code gofeed.Item} 的子集）。
     *
     * <p>{@code authorName} 是 {@code item.Author == null ? "" : item.Author.Name} 里的
     * {@code Name}——{@code null} 与空串在连接器里等价（都写成 {@code ""}）。</p>
     *
     * <p>{@code content} 对应 {@code item.Content}：RSS 来自 {@code content:encoded}，
     * Atom 来自 {@code <content>}。<b>它不是 {@code description}</b>——
     * 连接器的 {@code firstNonEmpty(item.Content, item.Description)} 才决定用哪个。</p>
     */
    record ParsedItem(String guid, String link, String title, String content, String description,
                      OffsetDateTime updatedParsed, OffsetDateTime publishedParsed, String authorName) {
    }
}
