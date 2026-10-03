package com.ragagent.storage.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.common.retrieval.SearchResult;

/**
 * 把内部存储引用替换成客户端可直接加载的 HTTP URL。
 *
 * <h2>它处理什么</h2>
 * <p>WeKnora 用两种内部引用形态持久化文件：稳定的 {@code resource://<handle>} 应用身份，
 * 以及历史/规范化的 provider 路径（{@code local://…}、{@code minio://…}、
 * {@code storage://<backend-id>/cos://…}）。两者浏览器与第三方应用都取不到，
 * 只能为每张图去调认证过的 {@code /files} 代理。</p>
 *
 * <h2>记忆化</h2>
 * <p>解析结果按 {@code Rewriter} 的生命周期缓存。**每个请求或每条出站消息造一个**：
 * 一次 {@code resource://} 解析就要写一行 access-grant，而一条流式回答会在很多分片里
 * 反复提到同一张图。</p>
 *
 * <h2>并发</h2>
 * <p>可并发使用：{@code mu} 同时保护 memo 与 resolver（后者自身是单线程的）。
 * 解析因此跨线程串行化——这正是想要的：两个分片提到同一张图不该各付一次签名钱。</p>
 *
 * <h2>日志策略（照搬 Go）</h2>
 * <ul>
 *   <li>重写成功记源引用于 INFO；**签名后的 URL 只记 DEBUG**，免得日志聚合系统
 *       把匿名可读的链接发出去。运维验证可达性时把日志级别调高即可。</li>
 *   <li>失败或空操作记 WARN。空操作通常意味着 {@code APP_EXTERNAL_URL} 没配——
 *       那是"IM 渠道/我的应用里图片坏了"这类报告最常见的成因。</li>
 * </ul>
 */
public class Rewriter {

    private static final Logger log = LoggerFactory.getLogger(Rewriter.class);

    /**
     * 匹配**所有**内部存储引用形态：{@code resource://} 手柄、
     * 历史 {@code provider://} 路径、以及规范的 {@code storage://<backend-id>/provider://} 路径。
     *
     * <p>字符类在 Markdown/HTML 分隔符处停下，于是 {@code ![alt](…)} 或 {@code src="…"}
     * 里的引用能被匹配到而**不带**这些分隔符。匹配整段 token 也防止了路径/手柄前缀被误授权。</p>
     *
     * <p>已知边界：Java 默认的 {@code \s} 含 {@code \x0B}（垂直制表）。引用里若真出现
     * 这种不可能出现在 URL 中的字节，切分位置会偏——实际影响不存在，不为此收窄写法。</p>
     */
    public static final Pattern PATTERN = Pattern.compile(
            "\\b(?:resource://[0-9A-Za-z_-]+|(?:storage://[0-9A-Za-z_-]+/)?"
                    + "(?:local|minio|s3|cos|tos|oss|obs|ks3)://[^\\s)\\]>\"]+)");

    /**
     * 只有 http(s) 是外部客户端能取的形态，
     * 任何 provider scheme（{@code oss://}、{@code local://}…）都不是。
     * <b>scheme 大小写不敏感</b>（RFC 3986 §3.1）——后端可能输出运维配置的
     * 大写 scheme 主机（如 {@code OBS_PROXY_DOMAIN}）。
     */
    public static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        return s.length() >= 7 && s.regionMatches(true, 0, "http://", 0, 7)
                || s.length() >= 8 && s.regionMatches(true, 0, "https://", 0, 8);
    }

    private final Resolver resolver;
    private final String logPrefix;

    private final Object mu = new Object();
    private final Map<String, String> memo = new HashMap<>();

    /**
     * {@code logPrefix} 给日志行打上调用面标记
     * （例如 {@code "IM"} / {@code "API"}）。{@code resolver} 为 {@code null} 时得到的是
     * <b>不改变内容</b>的 Rewriter（{@code Enabled()} 为 false）。
     */
    public Rewriter(Resolver resolver, String logPrefix) {
        this.resolver = resolver;
        this.logPrefix = logPrefix;
    }

    /** resolver 为 null 即禁用。 */
    public boolean enabled() {
        return resolver != null;
    }

    /**
     * 把 content 里的每个存储引用换成 HTTP URL。
     *
     * <p>已经是 HTTP 的、没有后端认领的、或解析结果不是 HTTP 的引用<b>原样保留</b>——
     * 调用方因此降级到认证过的文件代理，而不是发出一个取不到的 URL。</p>
     */
    public String rewrite(String content) {
        if (!enabled() || content == null || content.isEmpty()) {
            return content;
        }
        Matcher matcher = PATTERN.matcher(content);
        StringBuilder out = new StringBuilder(content.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(ref(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 重写**本身就是一条存储引用**的值
     * （而不是"含引用的散文"）——例如 {@code MessageImage.URL}。
     */
    public String rewriteRef(String ref) {
        if (!enabled() || ref == null || ref.isEmpty()) {
            return ref;
        }
        if (!PATTERN.matcher(ref).find()) {
            return ref;
        }
        return rewrite(ref);
    }

    /**
     * 解析单条引用。
     *
     * <p>锁<b>横跨整个 resolve</b>——因为 Resolver 实现（尤其 {@link FileServiceResolver}）
     * 带着一份未同步的按 provider 缓存，而且这样能把并发重复请求合并成一次签名而不是两次。</p>
     */
    private String ref(String ref) {
        synchronized (mu) {
            String cached = memo.get(ref);
            if (cached != null) {
                return cached;
            }
            String resolved = resolve(ref);
            memo.put(ref, resolved);
            return resolved;
        }
    }

    private String resolve(String ref) {
        FileService fileService = resolver.resolveFileService(ref);
        if (fileService == null) {
            log.warn("[{}] storage URL rewrite: no file service for src={}", logPrefix, ref);
            return ref;
        }
        String httpUrl;
        try {
            httpUrl = fileService.getFileURL(ref);
        } catch (RuntimeException e) {
            log.warn("[{}] storage URL rewrite failed: src={} err={}", logPrefix, ref, e.toString());
            return ref;
        }
        // 非 http(s) 的结果客户端取不到——这同时覆盖"没改动"的空操作，
        // 和"resource:// 别名被留成内部 storage:// 路径"（APP_EXTERNAL_URL 未设置 /
        // nginx 没代理 /r/）两种情形。
        if (!isHttpUrl(httpUrl)) {
            log.warn("[{}] storage URL rewrite no-op (resolved to non-HTTP URL \"{}\"; for local/private "
                    + "storage set APP_EXTERNAL_URL and ensure nginx proxies /r/): src={}",
                    logPrefix, httpUrl, ref);
            return ref;
        }
        log.info("[{}] storage URL rewrite: src={}", logPrefix, ref);
        log.debug("[{}] storage URL rewrite dst={}", logPrefix, httpUrl);
        return httpUrl;
    }

    // ── 建一个"按请求"的 Rewriter ────────────────────────────────────────────

    /**
     * 为一次 API 请求或一条响应流构造 Rewriter。
     *
     * <p>{@link Mode#HANDLE} 得到的是**禁用**的 Rewriter——默认路径因此不解析任何东西、
     * 也不写任何 access-grant 行。租户从请求上下文取，因为一条引用可能位于
     * 租户自配的存储后端而非进程级默认后端上。</p>
     *
     * <p><b>与 {@code /files} 代理不同，这里没有额外的授权闸门</b>：那个闸门存在是因为
     * {@code /files} 接受调用方给的任意路径、无法绑定到 KB 白名单；而这里的引用来自
     * 调用方**本来就有权收到**的响应，所以是**服务端**而非客户端决定哪些资源拿到 URL。</p>
     */
    public static Rewriter forRequest(Mode mode, Tenant tenant,
                                      FileService defaultSvc,
                                      StorageBackendResolver storageResolver) {
        if (mode != Mode.PUBLIC) {
            return new Rewriter(null, "API");
        }
        return new Rewriter(new FileServiceResolver(tenant, defaultSvc, storageResolver), "API");
    }

    // ── 整块到达、不需要扣留的流字段 ────────────────────────────────────────

    /**
     * 返回检索结果的**重写副本**，覆盖 chunk 正文与结构化的 image_info。
     *
     * <p>复制而不是就地改：SSE 的 references 载荷与流的重放缓冲、以及正在落库的助手消息
     * **共享同一批 {@code SearchResult} 指针**，就地改会把那两处一起弄坏。</p>
     */
    public List<SearchResult> copyReferences(List<SearchResult> refs) {
        if (!enabled() || refs == null) {
            return refs;
        }
        List<SearchResult> out = new ArrayList<>(refs.size());
        for (SearchResult ref : refs) {
            if (ref == null) {
                out.add(null);
                continue;
            }
            SearchResult rewritten = ref.copy();
            rewritten.setContent(rewrite(ref.getContent()));
            rewritten.setMatchedContent(rewrite(ref.getMatchedContent()));
            rewritten.setImageInfo(rewrite(ref.getImageInfo()));
            out.add(rewritten);
        }
        return out;
    }

    /**
     * 返回 SSE 元数据 map 的重写副本；其中没有存储引用时原样返回 {@code data}。
     *
     * <p>agent 工具结果会把可渲染的 Markdown 放进这个 map，而它的形状由工具定义，
     * 所以**每个字符串叶子都要重写**。</p>
     */
    public Map<String, Object> copyData(Map<String, Object> data) {
        if (!enabled() || data == null) {
            return data;
        }
        Converted rewritten = copyValue(data, 0);
        if (!rewritten.changed()) {
            return data;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) rewritten.value();
        return out;
    }

    /** 递归返回：值 + "改过没有"。 */
    private record Converted(Object value, boolean changed) {
    }

    /**
     * 递归进工具定义的元数据时的深度上限。
     * 可渲染内容就在两三层之内；这个封顶只防病态嵌套的载荷。
     */
    private static final int MAX_DATA_DEPTH = 8;

    /**
     * 递归重写值。
     *
     * <p><b>检索结果列表是一支特判</b>：{@code List<SearchResult>} 整体走
     * {@link #copyReferences}，恒返回 {@code changed = true}（副本总归是新建的）；
     * 其余列表/映射走通用路径，逐个重写。</p>
     *
     * <p><b>⚠️ {@code changed} 必须按值判，不能按引用判</b>：
     * Java 的 {@code rewrite()} 每次都会 {@code toString()} 出一个新对象——
     * 用 {@code converted != item} 会让每个没改动的字符串都算"改过"，
     * 于是 {@code copyData} 永远返回副本，"无变化就原样返回"的优化直接失效。
     * 故用 {@code (值, changed)} 二元返回：字符串按 {@code equals} 判，
     * 容器用**子层递归出来的 changed**，不做深比较。</p>
     */
    private Converted copyValue(Object value, int depth) {
        if (depth > MAX_DATA_DEPTH) {
            return new Converted(value, false);
        }
        if (value instanceof String text) {
            String out = rewrite(text);
            return out.equals(text) ? new Converted(value, false) : new Converted(out, true);
        }
        if (value instanceof List<?> list) {
            // references 事件把检索结果带了两遍：一次在 StreamResponse.KnowledgeReferences，
            // 一次在 Data。内存流管理器下它们到达时是**有类型的切片**（而不是 Redis
            // 往返后解码出的 []interface{}），所以两种形态都要处理，
            // 否则 Data 那份会漏出调用方要求解析掉的手柄。
            if (isReferenceList(list)) {
                @SuppressWarnings("unchecked")
                List<SearchResult> typed = (List<SearchResult>) list;
                // Go 这一支恒返回 changed = true（副本总归是新建的）。
                return new Converted(copyReferences(typed), true);
            }
            List<Object> out = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object item : list) {
                Converted converted = copyValue(item, depth + 1);
                out.add(converted.value());
                changed = changed || converted.changed();
            }
            return new Converted(changed ? out : value, changed);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Converted converted = copyValue(entry.getValue(), depth + 1);
                out.put(String.valueOf(entry.getKey()), converted.value());
                changed = changed || converted.changed();
            }
            return new Converted(changed ? out : value, changed);
        }
        return new Converted(value, false);
    }

    /**
     * 泛型擦除后只能看元素类型判断。
     * 空列表按"不是检索结果"处理——两种走法在这个列表上产出的 JSON 完全一样（都是 {@code []}），
     * 区别只在 returned-reference 身份，不影响契约。
     */
    private static boolean isReferenceList(List<?> list) {
        return !list.isEmpty() && list.get(0) instanceof SearchResult;
    }
}
