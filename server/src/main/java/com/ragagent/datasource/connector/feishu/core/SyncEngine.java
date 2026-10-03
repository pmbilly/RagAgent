package com.ragagent.datasource.connector.feishu.core;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * wiki 与云盘<b>共用</b>的泛型流式同步引擎。
 *
 * <p>各连接器的差异（节点类型、列举 API、编辑时间字段、游标线格式、抓取派发、日志标签）
 * 全部隔离在 {@link NodeOps} 适配器后面。{@code fetchAll} / {@code fetchIncremental} 是
 * 同一引擎的薄封装：把 Emit 收进列表而不是流式外发。</p>
 *
 * <h2>三条刻意保留的行为</h2>
 * <ol>
 *   <li><b>续跑/增量快路径</b>：记录里的编辑时间与当前相同 → 跳过该节点，但<b>保留游标条目</b>。</li>
 *   <li><b>抓取失败不推进游标</b>：保留<b>旧的</b>编辑时间，下次跑 prev != current 于是重试该节点，
 *       而不是被"已同步"永久跳过（Tencent/WeKnora#2136）。这条对 FetchIncremental
 *       路径同样成立。</li>
 *   <li><b>删除检测只在完整列举时做</b>：部分列举没有枚举完整个子树，做删除检测会误报。</li>
 * </ol>
 *
 * <h2>取消与检查点</h2>
 * <ul>
 *   <li>取消 → 线程中断（{@code emit}/{@code checkpoint} 抛异常即中止）；</li>
 *   <li>节点间隔检查点（{@code processed % N == 0}）→ {@link #checkpointInterval}；</li>
 *   <li>墙钟间隔检查点（距上次检查点超过 max）→
 *       {@link #checkpointMaxInterval}。</li>
 * </ul>
 * <p>后两个做成<b>可覆盖的静态字段</b>：时间敏感项必须留出注入缝，
 * 测试不靠真实 sleep 推进。</p>
 */
public final class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    /**
     * 流式抓取中每处理多少个节点打一次
     * 游标检查点。小到"一次超时的同步不会丢太多进度"，大到"检查点的持久化（一次 DB 写）
     * 不会喧宾夺主"。
     */
    public static volatile int checkpointInterval = 50;

    /**
     * 检查点还受<b>墙钟时间</b>约束。
     *
     * <p>没有它，一次"节点数少于 {@link #checkpointInterval}、但每个都很慢（被限流）"的
     * 同步可能一直到 2 小时任务超时都没打过检查点，于是每次重试都从头再来——
     * 正是 #2136 那句"永远同步不完"。</p>
     */
    public static volatile Duration checkpointMaxInterval = Duration.ofSeconds(30);

    private SyncEngine() {
    }

    /**
     * 把某个连接器的节点类型适配到共享同步引擎。
     *
     * <p>每个方法都是纯访问器或薄封装——<b>引擎逻辑不在这里</b>。</p>
     */
    public interface NodeOps<N> {

        /**
         * 列举一个资源下的全部节点。
         *
         * <p>非 null 的 {@code partial}（且 {@code err == null}）表示<b>部分列举</b>：
         * nodes 仍然可用、同步继续，但调用方要经 {@link #listFailureItems} 把失败的子树
         * 暴露出来。非 null 的 {@code err} 是致命错误，直接中止同步。</p>
         */
        ListResult<N> list(FeishuClient client, String resourceId);

        /** 游标里存的是这个 token（wiki=node_token，drive=file token）。 */
        String token(N node);

        String title(N node);

        String objType(N node);

        /** 变更检测的时间戳字符串（游标比较用它）。 */
        String editTime(N node);

        /**
         * 抓取一个节点的内容；返回空列表表示"不支持的类型，没有条目"。
         */
        List<FetchedItem> fetch(FeishuClient client, N node, String resourceId, boolean multimodal);

        /** 把部分列举错误转成错误条目。 */
        List<FetchedItem> listFailureItems(String resourceId, RuntimeException partial);

        /** 用户可见错误文案里的名词：{@code "nodes"} / {@code "files"}。 */
        String resourceNoun();

        /** 没有配置任何资源 ID 时、连接器<b>各自</b>的文案（wiki/drive 不同，逐字保留）。 */
        String emptyResourceIdsError();

        /** 日志前缀：{@code "[Feishu]"} / {@code "[FeishuDrive]"}。 */
        String logTag();

        /**
         * 从持久化的 {@code ConnectorCursor} 里抽出"每资源 → 每节点 → 编辑时间"的映射
         * （nil 安全：缺席时返回 null）。
         */
        Map<String, Map<String, String>> decodeCursorTimes(Map<String, Object> connectorCursor);

        /**
         * 把引擎内部的 times 映射包装成连接器的线格式 {@code SyncCursor}
         * （JSON 序列化以实现快照隔离）。
         */
        SyncCursor encodeCursor(Map<String, Map<String, String>> times, OffsetDateTime lastSync);

        /** 列举结果三元组：nodes / 部分失败 / 致命错误。 */
        record ListResult<N>(List<N> nodes, RuntimeException partial, RuntimeException err) {

            public static <N> ListResult<N> ok(List<N> nodes) {
                return new ListResult<>(nodes, null, null);
            }

            public static <N> ListResult<N> partial(List<N> nodes, RuntimeException partial) {
                return new ListResult<>(nodes, partial, null);
            }

            public static <N> ListResult<N> failed(RuntimeException err) {
                return new ListResult<>(null, null, err);
            }
        }
    }

    /**
     * {@code FetchAll} / {@code FetchIncremental} 用它
     * 把每条条目收进列表而不是流式外发。{@code Checkpoint} 是 no-op——那两条路径
     * 最后一次性返回单个游标。
     */
    public static final class CollectHandler implements StreamHandler {

        private final List<FetchedItem> items = new ArrayList<>();

        @Override
        public void emit(FetchedItem item) {
            items.add(item);
        }

        @Override
        public void checkpoint(SyncCursor cursor) {
            // no-op
        }

        public List<FetchedItem> items() {
            return items;
        }
    }

    /**
     * {@code FetchStream / FetchAll / FetchIncremental} 背后
     * 唯一的实现。{@code cursor == null} 时抓全部（全量）；有游标时跳过编辑时间未变的
     * 节点（增量 + 续跑）。{@code resourceIds} 由调用方给：{@code fetchStream} /
     * {@code fetchIncremental} 传 {@code config.resourceIds}（先做非空检查），
     * {@code fetchAll} 传自己的参数、<b>不做</b>非空检查。
     */
    public static <N> SyncCursor runSync(FeishuClient client, DataSourceConfig config,
                                         List<String> resourceIds, SyncCursor cursor,
                                         StreamHandler handler, NodeOps<N> ops) {
        Map<String, Map<String, String>> prevTimes = null;
        if (cursor != null && cursor.getConnectorCursor() != null) {
            prevTimes = ops.decodeCursorTimes(cursor.getConnectorCursor());
        }

        Map<String, Map<String, String>> newTimes = new LinkedHashMap<>();
        OffsetDateTime lastSync = OffsetDateTime.now();

        int processed = 0;
        Instant lastCheckpoint = Instant.now();
        // null 按空列表处理，一次都不跑。
        List<String> ids = resourceIds == null ? List.of() : resourceIds;
        for (String resourceId : ids) {
            NodeOps.ListResult<N> listed = ops.list(client, resourceId);
            if (listed.err() != null) {
                throw new ConnectorException("list " + ops.resourceNoun() + " for resource "
                        + resourceId + ": " + messageOf(listed.err()), listed.err());
            }
            RuntimeException partial = listed.partial();
            List<N> nodes = listed.nodes() == null ? List.of() : listed.nodes();
            if (partial != null) {
                for (FetchedItem item : ops.listFailureItems(resourceId, partial)) {
                    handler.emit(item);
                }
            }

            Map<String, String> resourceTimes = new LinkedHashMap<>();
            newTimes.put(resourceId, resourceTimes);
            // 部分列举时把先前的编辑时间带过来，这样之后的一次完整列举仍能检测变更与删除。
            if (partial != null && prevTimes != null) {
                Map<String, String> prev = prevTimes.get(resourceId);
                if (prev != null) {
                    resourceTimes.putAll(prev);
                }
            }

            Map<String, Boolean> currentNodes = new LinkedHashMap<>();
            FetchTally tally = new FetchTally(nodes.size());
            for (int i = 0; i < nodes.size(); i++) {
                N node = nodes.get(i);
                String tok = ops.token(node);
                currentNodes.put(tok, Boolean.TRUE);
                String editTimeStr = ops.editTime(node);

                String prevEdit = null;
                boolean hadPrev = false;
                if (prevTimes != null) {
                    Map<String, String> prev = prevTimes.get(resourceId);
                    if (prev != null && prev.containsKey(tok)) {
                        prevEdit = prev.get(tok);
                        hadPrev = true;
                    }
                }

                // 续跑/增量快路径：记录里的编辑时间等于当前值 → 未变更（或本轮已同步过）——
                // 保留记录，跳过重新抓取。
                //
                // ⚠️ 这条快路径**不**递增 processed、**也**不打检查点：
                // 于是一次"全部未变更"的续跑不会打任何检查点，最终游标由
                // runSync 的返回值给出。
                if (hadPrev && java.util.Objects.equals(prevEdit, editTimeStr)) {
                    resourceTimes.put(tok, editTimeStr);
                    continue;
                }

                List<FetchedItem> items;
                RuntimeException ferr = null;
                try {
                    items = ops.fetch(client, node, resourceId, config.isMultimodalEnabled());
                } catch (RuntimeException e) {
                    items = null;
                    ferr = e;
                }
                if (ferr != null) {
                    tally.fail();
                    // 不推进游标：内容根本没抓到。保留旧的编辑时间（如果有），
                    // 于是下次 prev != current、该节点会被重试，而不是被一次瞬时导出失败
                    // 永久跳过（Tencent/WeKnora#2136）。
                    if (hadPrev) {
                        resourceTimes.put(tok, prevEdit);
                    }
                    FetchedItem failure = new FetchedItem();
                    failure.setExternalId(tok);
                    failure.setTitle(ops.title(node));
                    failure.setSourceResourceId(resourceId);
                    failure.setMetadata(FeishuErrors.feishuErrorItemMeta(ferr, null));
                    handler.emit(failure);
                } else {
                    // 抓到了，或是不支持的类型（没东西可抓）：记录当前编辑时间，
                    // 于是下次不会重复处理该节点。
                    resourceTimes.put(tok, editTimeStr);
                    if (items != null && !items.isEmpty()) {
                        tally.fetch();
                        for (FetchedItem it : items) {
                            handler.emit(it);
                        }
                    } else {
                        // 不支持的类型（mindnote/slides/…）：没有条目
                        tally.skip(ops.objType(node));
                    }
                }

                processed++;
                // lastCheckpoint 在检查点**尝试之后**无条件刷新
                // （即便 checkpoint 抛错也刷新）。
                if (checkpointIfDue(processed, lastCheckpoint, handler, ops, newTimes, lastSync)) {
                    lastCheckpoint = Instant.now();
                }
                if ((i + 1) % 100 == 0) {
                    log.info("{} stream progress resource={} {}/{} ({})",
                            ops.logTag(), resourceId, i + 1, nodes.size(), tally.summary());
                }
            }

            // 删除检测（只在整棵树都列举成功时）。部分列举没有枚举完整个子树，
            // 做删除检测会误报。
            if (partial == null && prevTimes != null) {
                Map<String, String> prev = prevTimes.get(resourceId);
                if (prev != null) {
                    for (String tok : prev.keySet()) {
                        if (!currentNodes.containsKey(tok)) {
                            FetchedItem deleted = new FetchedItem();
                            deleted.setExternalId(tok);
                            deleted.setDeleted(true);
                            deleted.setSourceResourceId(resourceId);
                            handler.emit(deleted);
                        }
                    }
                }
            }
            log.info("{} stream summary resource={} {}", ops.logTag(), resourceId, tally.summary());
        }

        return ops.encodeCursor(newTimes, lastSync);
    }

    /**
     * 打检查点（若到期）。返回是否真的打了，让调用方据此刷新
     * {@code lastCheckpoint}。
     */
    private static <N> boolean checkpointIfDue(int processed, Instant lastCheckpoint,
                                               StreamHandler handler, NodeOps<N> ops,
                                               Map<String, Map<String, String>> newTimes,
                                               OffsetDateTime lastSync) {
        boolean dueByCount = checkpointInterval > 0 && processed % checkpointInterval == 0;
        boolean dueByTime = Duration.between(lastCheckpoint, Instant.now())
                .compareTo(checkpointMaxInterval) >= 0;
        if (!dueByCount && !dueByTime) {
            return false;
        }
        // interval 为 0 时按数量触发会除零；
        // 这里把 0 当作"不按数量触发"（见 dueByCount），只有时间条件生效。
        try {
            handler.checkpoint(ops.encodeCursor(newTimes, lastSync));
        } catch (RuntimeException cerr) {
            log.warn("{} stream Checkpoint failed: {}", ops.logTag(), messageOf(cerr));
        }
        return true;
    }

    /**
     * 跑流式同步。{@code FetchStream} /
     * {@code FetchIncremental} 的外壳只差"传不传游标、怎么收集结果"，都路由到这里。
     */
    public static <N> SyncCursor fetchStreamEngine(FeishuClient client, DataSourceConfig config,
                                                   SyncCursor cursor, StreamHandler handler,
                                                   NodeOps<N> ops) {
        return runSync(client, config, config.getResourceIds(), cursor, handler, ops);
    }

    /**
     * 跑全量同步并把条目收进列表。把调用方给的
     * resourceIds 原样透传、<b>不做</b>非空检查（空列表被接受并
     * 返回 0 条目）。出错时已收集的条目被丢弃（一次致命列举错误
     * 会把之前收集的全部丢掉）。
     */
    public static <N> List<FetchedItem> fetchAllEngine(FeishuClient client, DataSourceConfig config,
                                                       List<String> resourceIds, NodeOps<N> ops) {
        CollectHandler ch = new CollectHandler();
        runSync(client, config, resourceIds, null, ch, ops);
        return ch.items();
    }

    /**
     * 对游标跑增量同步并收集条目。
     * resourceIDs 取自 {@code config.resourceIds}（非空检查是调用方的责任）。
     */
    public static <N> Connector.FetchIncrementalResult fetchIncrementalEngine(
            FeishuClient client, DataSourceConfig config, SyncCursor cursor, NodeOps<N> ops) {
        CollectHandler ch = new CollectHandler();
        SyncCursor next = runSync(client, config, config.getResourceIds(), cursor, ch, ops);
        return new Connector.FetchIncrementalResult(ch.items(), next);
    }

    private static String messageOf(RuntimeException e) {
        return e == null || e.getMessage() == null ? "" : e.getMessage();
    }
}
