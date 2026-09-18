package com.ragagent.datasource.connector.yuque;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Doc;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2DocDetail;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Group;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Repo;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2User;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语雀（yuque.com）数据源连接器（对照 Go {@code yuque/connector.go} 全文）。
 *
 * <h2>只同步什么</h2>
 * <ul>
 *   <li>只同步 {@code type=Doc}（{@code Sheet} / {@code Thread} / {@code Board} /
 *       {@code Table} 都跳过）——<b>空 type 视为可接受</b>，这是对"API 变体省略该字段"
 *       的前向兼容；</li>
 *   <li>只同步 {@code status="1"}（已发布），草稿跳过——同样把空 status 视为可接受；</li>
 *   <li>{@code format} 只接受 {@code markdown} 与 {@code lake}（空也接受），
 *       其余（例如 {@code html}）产出带 {@code skip_reason} 的占位项。</li>
 * </ul>
 *
 * <h2>⚠️ 300ms 限速是可注入的</h2>
 * <p>{@code walk} 在**每个** {@code GetDocDetail} 之前睡
 * {@link #DEFAULT_DOC_FETCH_DELAY}（{@code 300ms}）以避开语雀的限流
 * （个人令牌大约 100 请求/5 分钟）。生产语义照抄不改；但<b>测试必须能把它压到 0</b>
 * ——否则每个含 N 篇文档的用例都要等 N×300ms，整套对等测试会从毫秒级涨到分钟级。
 * 所以它走构造参数（{@code docFetchDelay}），测试注入 {@link Duration#ZERO}。
 * 同理，客户端的重试退避走 {@link YuqueRetryPolicy#immediate()}。</p>
 *
 * <h2>刻意照抄、不要"修好"的两处</h2>
 * <ol>
 *   <li>{@code ListUserGroups} 失败被当成"没有团队"（404 是语雀对"没加入任何团队"
 *       的正常回答），个人仓库已经抓到了，继续；</li>
 *   <li>某个团队的 {@code ListGroupRepos} 失败只跳过该团队（例如受限团队 403），
 *       其余团队继续。</li>
 * </ol>
 *
 * <h2>与 Go 的一处刻意的确定性差异</h2>
 * <p>删除检测那段 Go 是 {@code for prevDocID := range prevTimes} ——<b>map 迭代
 * 顺序随机</b>，所以墓碑的相对顺序在 Go 侧本来就不可复现。Java 侧按 doc_id
 * 升序发出（{@code currentDocs} 用 {@link LinkedHashSet} 保持声明序，墓碑按键排序），
 * 集合内容与 Go 完全一致，只有顺序更稳定。</p>
 */
public class YuqueConnector implements Connector {

    private static final Logger log = LoggerFactory.getLogger(YuqueConnector.class);

    /**
     * 对照 Go {@code 300 * time.Millisecond}：每个文档详情请求前的限速间隔。
     * 见类注释——它是**默认值**，测试注入 0。
     */
    public static final Duration DEFAULT_DOC_FETCH_DELAY = Duration.ofMillis(300);

    private final YuqueRetryPolicy retryPolicy;
    private final Duration docFetchDelay;

    public YuqueConnector() {
        this(YuqueRetryPolicy.defaults(), DEFAULT_DOC_FETCH_DELAY);
    }

    /** 测试注入用：重试退避与文档限速都可压到 0。 */
    YuqueConnector(YuqueRetryPolicy retryPolicy, Duration docFetchDelay) {
        this.retryPolicy = retryPolicy == null ? YuqueRetryPolicy.defaults() : retryPolicy;
        this.docFetchDelay = docFetchDelay == null ? DEFAULT_DOC_FETCH_DELAY : docFetchDelay;
    }

    @Override
    public String type() {
        return DataSourceConstants.CONNECTOR_TYPE_YUQUE;
    }

    /**
     * 对照 Go {@code Validate}：调 {@code GET /api/v2/user} 验凭据。
     *
     * <p>外层 {@code "yuque connection failed: "} 与 Go 的
     * {@code fmt.Errorf("yuque connection failed: %w", err)} <b>文本逐字相同</b>；
     * 分类信息在异常链上（{@code getCause()}），理由同 {@code ImaConnector.validate}。</p>
     */
    @Override
    public void validate(DataSourceConfig config) {
        YuqueConfig cfg = YuqueConfig.parse(config);
        try {
            newClient(cfg).ping();
        } catch (ConnectorException e) {
            throw new ConnectorException("yuque connection failed: " + e.getMessage(), e);
        }
    }

    /**
     * 对照 Go {@code ResolveResourceAncestors}：语雀的仓库是**扁平**列表、
     * 没有嵌套，选择项没有祖先可揭示，回空列表。
     */
    @Override
    public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
        return new ArrayList<>();
    }

    /**
     * 对照 Go {@code ListResources}：返回该令牌可访问的全部仓库（个人 + 团队）。
     *
     * <p>团队令牌（{@code /api/v2/user} 返回 {@code type="Group"}）走"直接列本团队仓库"
     * 的分支——这个令牌代表的是团队而不是个人用户。
     * 否则走"个人仓库 + 加入的团队"两段流程。</p>
     *
     * <p>v1 是串行抓取（用户加入的团队通常 &lt; 10 个）。</p>
     */
    @Override
    public List<Resource> listResources(DataSourceConfig config, String parentId) {
        // 语雀资源是扁平的仓库列表（无嵌套），针对某个 parent 的惰性加载没有额外内容。
        if (parentId != null && !parentId.isEmpty()) {
            return new ArrayList<>();
        }

        YuqueConfig cfg = YuqueConfig.parse(config);
        YuqueClient cli = newClient(cfg);

        V2User me;
        try {
            me = cli.getCurrentUser();
        } catch (ConnectorException e) {
            throw new ConnectorException("get current user: " + e.getMessage(), e);
        }
        if (me == null) {
            me = new V2User();
        }

        Map<Long, V2Repo> repos = new LinkedHashMap<>();

        if ("Group".equals(me.getType())) {
            // 团队令牌：直接列本团队的仓库，不走"个人仓库 + 用户团队"流程。
            log.info("[Yuque] detected team token (type=Group, login={}), listing team repos directly",
                    me.getLogin());
            List<V2Repo> teamRepos;
            try {
                teamRepos = cli.listGroupRepos(me.getLogin());
            } catch (ConnectorException e) {
                throw new ConnectorException("list team repos: " + e.getMessage(), e);
            }
            if (teamRepos != null) {
                for (V2Repo r : teamRepos) {
                    repos.put(r.getId(), r);
                }
            }
        } else {
            // 个人令牌流程：自己的仓库 + 加入团队的仓库。
            List<V2Repo> personal;
            try {
                personal = cli.listUserRepos(me.getLogin());
            } catch (ConnectorException e) {
                throw new ConnectorException("list personal repos: " + e.getMessage(), e);
            }
            if (personal != null) {
                for (V2Repo r : personal) {
                    repos.putIfAbsent(r.getId(), r);
                }
            }

            List<V2Group> groups;
            try {
                groups = cli.listUserGroups(me.getId());
            } catch (ConnectorException e) {
                // 用户没加入任何团队（团队）时语雀返回 404 而不是空列表。
                // 把它当成"没有团队"继续——个人仓库上面已经抓到了。
                log.warn("[Yuque] list user groups failed (treating as empty): {}", e.getMessage());
                groups = null;
            }
            if (groups != null) {
                for (V2Group g : groups) {
                    List<V2Repo> teamRepos;
                    try {
                        teamRepos = cli.listGroupRepos(g.getLogin());
                    } catch (ConnectorException e) {
                        // 跳过这个团队但继续其它团队（例如受限团队的 403）。
                        log.warn("[Yuque] skip group {}: {}", g.getLogin(), e.getMessage());
                        continue;
                    }
                    if (teamRepos == null) {
                        continue;
                    }
                    for (V2Repo r : teamRepos) {
                        repos.putIfAbsent(r.getId(), r);
                    }
                }
            }
        }

        List<Resource> out = new ArrayList<>(repos.size());
        for (V2Repo r : repos.values()) {
            Resource res = new Resource();
            res.setExternalId(Long.toString(r.getId()));
            res.setName(r.getName());
            res.setType("book");
            res.setUrl(cfg.baseURL() + "/" + r.getNamespace());
            res.setDescription(r.getNamespace());
            res.setModifiedAt(YuqueFormats.parseContentUpdatedAt(r.getUpdatedAt()));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("public", r.getPublicValue());
            metadata.put("book_type", r.getType());
            res.setMetadata(metadata);
            out.add(res);
        }
        // 稳定的确定性顺序，供 UI 渲染与响应体缓存。
        // ⚠️ 注意这是**字符串**序（Go 的 out[i].ExternalID < out[j].ExternalID）：
        // "10" 排在 "9" 前面，不是数值序。照抄。
        out.sort(Comparator.comparing(Resource::getExternalId));
        return out;
    }

    /** 对照 Go {@code FetchAll}：全量同步（cursor 不参与）。 */
    @Override
    public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
        return walk(config, resourceIds, null, false).items();
    }

    /**
     * 对照 Go {@code FetchIncremental}：返回自上次 cursor 以来变更（或删除）的条目。
     *
     * <p>删除检测：上一轮 cursor 里有、当前列表里没有的文档，发出
     * {@code IsDeleted=true} 的占位项。</p>
     */
    @Override
    public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
        List<String> resourceIds = config == null ? null : config.getResourceIds();
        if (resourceIds == null || resourceIds.isEmpty()) {
            throw new ConnectorException("no resource IDs (book IDs) configured");
        }

        YuqueCursor prev = null;
        if (cursor != null && cursor.getConnectorCursor() != null) {
            prev = YuqueCursor.fromConnectorCursor(cursor.getConnectorCursor());
        }

        WalkResult result = walk(config, resourceIds, prev, true);

        SyncCursor newCursor = new SyncCursor();
        newCursor.setLastSyncTime(result.cursor().getLastSyncTime());
        newCursor.setConnectorCursor(result.cursor().toConnectorCursor());
        return new FetchIncrementalResult(result.items(), newCursor);
    }

    /** 对照 Go {@code walk} 的 {@code (items, *yuqueCursor, error)} 三返回值。 */
    private record WalkResult(List<FetchedItem> items, YuqueCursor cursor) {
    }

    /**
     * 对照 Go {@code walk}：{@code FetchAll} / {@code FetchIncremental} 的共享实现。
     * {@code incremental} 为 false 时 {@code prev} 被忽略、返回的 cursor 无意义。
     */
    private WalkResult walk(DataSourceConfig config, List<String> resourceIds,
                            YuqueCursor prev, boolean incremental) {
        YuqueConfig cfg = YuqueConfig.parse(config);
        YuqueClient cli = newClient(cfg);

        YuqueCursor newCursor = new YuqueCursor();
        newCursor.setLastSyncTime(OffsetDateTime.now());
        newCursor.setBookDocTimes(new LinkedHashMap<>());

        List<FetchedItem> out = new ArrayList<>();
        List<String> ids = resourceIds == null ? List.of() : resourceIds;

        for (String bookIdStr : ids) {
            long bookId = parseBookId(bookIdStr);

            List<V2Doc> docs;
            try {
                docs = cli.listBookDocs(bookId);
            } catch (ConnectorException e) {
                throw new ConnectorException("list docs for book " + bookId + ": " + e.getMessage(), e);
            }
            if (docs == null) {
                docs = new ArrayList<>();
            }

            Set<String> currentDocs = new LinkedHashSet<>();
            Map<String, String> docTimes = new LinkedHashMap<>();
            newCursor.getBookDocTimes().put(bookIdStr, docTimes);

            int skippedType = 0;
            int skippedDraft = 0;
            int kept = 0;
            String sampleSkipType = "";
            String sampleSkipDraft = "";
            for (V2Doc d : docs) {
                // 空的 type/status 视为可接受——对"API 变体省略该字段"的前向兼容。
                if (!d.getType().isEmpty() && !"Doc".equals(d.getType())) {
                    skippedType++;
                    if (sampleSkipType.isEmpty()) {
                        sampleSkipType = "id=" + d.getId() + " type=\"" + d.getType()
                                + "\" title=\"" + d.getTitle() + "\"";
                    }
                    continue;
                }
                if (!d.getStatus().value().isEmpty() && !"1".equals(d.getStatus().value())) {
                    skippedDraft++;
                    if (sampleSkipDraft.isEmpty()) {
                        sampleSkipDraft = "id=" + d.getId() + " status=\"" + d.getStatus().value()
                                + "\" title=\"" + d.getTitle() + "\"";
                    }
                    continue;
                }
                kept++;
                String docIdStr = Long.toString(d.getId());
                currentDocs.add(docIdStr);
                docTimes.put(docIdStr, d.getContentUpdatedAt());

                // 增量：内容没变就跳过。
                //
                // ⚠️ 这里**照抄** Go 的一个 map 零值细节：Go 写的是
                // `prevTimes[docIDStr] == d.ContentUpdatedAt`——键**缺失**时
                // 取到的是 ""，于是"上一轮没有这个 doc **且**它也恰好没有
                // content_updated_at"也会被判成"未变"而跳过。
                // 也就是说：语雀不返回 content_updated_at 的文档，
                // 只有**全量**同步才会被抓到（增量永远跳过它）。
                // 这看起来像 bug，但它是线上的实际行为，改掉会让 Java 多吐出
                // Go 不会吐的条目——所以逐字复刻，并用用例钉住。
                if (incremental && prev != null && prev.getBookDocTimes() != null) {
                    Map<String, String> prevTimes = prev.getBookDocTimes().get(bookIdStr);
                    if (prevTimes != null) {
                        String prevValue = prevTimes.get(docIdStr);
                        String prevNorm = prevValue == null ? "" : prevValue;
                        if (prevNorm.equals(d.getContentUpdatedAt())) {
                            continue;
                        }
                    }
                }

                // 限速：GetDocDetail 之间暂停，避开语雀的 API 限流
                //（个人令牌大约 100 请求/5 分钟）。默认 300ms，测试注入 0。
                Connector.sleep(docFetchDelay.toMillis());

                V2DocDetail detail;
                try {
                    detail = cli.getDocDetail(d.getId());
                } catch (ConnectorException e) {
                    // 记录失败但继续（带 error 元数据的占位项）。
                    // 保留 doc_id/book_id/slug 给按这些键做关联的观测管线用。
                    Map<String, String> metadata = new LinkedHashMap<>();
                    metadata.put("error", e.getMessage());
                    metadata.put("channel", "yuque");
                    metadata.put("doc_id", docIdStr);
                    metadata.put("book_id", bookIdStr);
                    metadata.put("slug", d.getSlug());
                    FetchedItem placeholder = new FetchedItem();
                    placeholder.setExternalId(docIdStr);
                    placeholder.setTitle(d.getTitle());
                    placeholder.setSourceResourceId(bookIdStr);
                    placeholder.setMetadata(metadata);
                    out.add(placeholder);
                    continue;
                }
                if (detail == null) {
                    detail = new V2DocDetail();
                }

                // 语雀在 format 为 "markdown" 或 "lake" 时把 `body` 序列化成 Markdown
                //（Lake XML 单独放在 `body_lake` 里）——已对 v2 API 实测确认。
                // 其它 format（例如 "html"）的 `body` 可能不是 Markdown，防御性跳过。
                if (!detail.getFormat().isEmpty()
                        && !"markdown".equals(detail.getFormat())
                        && !"lake".equals(detail.getFormat())) {
                    log.warn("[Yuque] skip doc {} (\"{}\"): unsupported format \"{}\"",
                            d.getId(), d.getTitle(), detail.getFormat());
                    Map<String, String> metadata = new LinkedHashMap<>();
                    metadata.put("channel", "yuque");
                    metadata.put("doc_id", docIdStr);
                    metadata.put("book_id", bookIdStr);
                    metadata.put("slug", d.getSlug());
                    metadata.put("skip_reason", "unsupported format: " + detail.getFormat());
                    FetchedItem placeholder = new FetchedItem();
                    placeholder.setExternalId(docIdStr);
                    placeholder.setTitle(d.getTitle());
                    placeholder.setSourceResourceId(bookIdStr);
                    placeholder.setMetadata(metadata);
                    out.add(placeholder);
                    continue;
                }

                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("doc_id", docIdStr);
                metadata.put("book_id", bookIdStr);
                metadata.put("slug", d.getSlug());
                metadata.put("creator", Long.toString(d.getUserId()));
                metadata.put("word_count", Integer.toString(d.getWordCount()));
                metadata.put("channel", "yuque");

                FetchedItem item = new FetchedItem();
                item.setExternalId(docIdStr);
                item.setTitle(d.getTitle());
                item.setContent(detail.getBody().getBytes(StandardCharsets.UTF_8));
                item.setContentType("text/markdown");
                item.setFileName(YuqueFormats.sanitizeFileName(d.getTitle()) + ".md");
                item.setUrl(YuqueFormats.buildDocURL(cfg.baseURL(),
                        detail.getBook() == null ? "" : detail.getBook().getNamespace(),
                        d.getSlug()));
                item.setUpdatedAt(YuqueFormats.parseContentUpdatedAt(d.getContentUpdatedAt()));
                item.setSourceResourceId(bookIdStr);
                item.setMetadata(metadata);
                out.add(item);
            }

            // ⚠️ SLF4J 的占位符是 `{}`，所以"给参数套一层花括号"要写成 `{{}}`
            // ——写成 `{{{}}}` 会输出 `{{值}}`（多一层，本轮已踩）。
            log.info("[Yuque] book {}: total={} kept={} skipped_non_doc={} skipped_draft={} "
                            + "non_doc_sample={{}} draft_sample={{}}",
                    bookId, docs.size(), kept, skippedType, skippedDraft,
                    sampleSkipType, sampleSkipDraft);

            // 删除检测（仅增量）：上一轮有、这一轮没有的 doc ID → IsDeleted=true
            if (incremental && prev != null && prev.getBookDocTimes() != null) {
                Map<String, String> prevTimes = prev.getBookDocTimes().get(bookIdStr);
                if (prevTimes != null) {
                    List<String> prevDocIds = new ArrayList<>(prevTimes.keySet());
                    prevDocIds.sort(Comparator.naturalOrder());
                    for (String prevDocId : prevDocIds) {
                        if (!currentDocs.contains(prevDocId)) {
                            FetchedItem tombstone = new FetchedItem();
                            tombstone.setExternalId(prevDocId);
                            tombstone.setDeleted(true);
                            tombstone.setSourceResourceId(bookIdStr);
                            out.add(tombstone);
                        }
                    }
                }
            }
        }

        if (!incremental) {
            return new WalkResult(out, null);
        }
        return new WalkResult(out, newCursor);
    }

    /**
     * 对照 Go 的 {@code strconv.ParseInt(bookIDStr, 10, 64)} 失败分支：
     * 错误文本逐字复刻 Go 的 {@code *strconv.NumError.Error()}
     * （{@code strconv.ParseInt: parsing "abc": invalid syntax} /
     * {@code ... : value out of range}），这样两边的日志与 error_message 能对上。
     */
    private static long parseBookId(String raw) {
        String s = raw == null ? "" : raw;
        if (s.isEmpty() || !s.matches("[+-]?[0-9]+")) {
            throw new ConnectorException("invalid book id \"" + s + "\": "
                    + "strconv.ParseInt: parsing \"" + s + "\": invalid syntax");
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ConnectorException("invalid book id \"" + s + "\": "
                    + "strconv.ParseInt: parsing \"" + s + "\": value out of range");
        }
    }

    /** 连接器构造客户端的唯一入口，测试可覆盖以注入重试预算。 */
    YuqueClient newClient(YuqueConfig cfg) {
        return new YuqueClient(cfg, retryPolicy);
    }
}
