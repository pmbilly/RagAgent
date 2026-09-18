package com.ragagent.datasource;

import java.util.List;

/**
 * 连接器层的错误（对照 Go {@code internal/datasource/errors.go} 里那一批
 * {@code errors.New(...)} 哨兵 + Go 的 {@code %w} 包装语义）。
 *
 * <h2>为什么用"一个基类 + 嵌套子类"而不是一堆顶层异常</h2>
 * <p>Go 侧这些哨兵靠 {@code errors.Is(err, ErrXxx)} 区分，而 {@code fmt.Errorf("%w: 细节", ErrXxx)}
 * 会把错误文本拼成 <b>{@code "哨兵原文: 细节"}</b>。Java 里与之等价的表达就是
 * <b>继承</b>（{@code instanceof} 对应 {@code errors.Is}）+ <b>构造函数拼前缀</b>
 * （对应 {@code %w}）。子类集中在一个文件里，是为了让"Go 里的 15 个哨兵在 Java 里
 * 长什么样"一眼可见——分散成 15 个文件反而看不出这层对应关系。这与
 * {@link com.ragagent.datasource.domain.DataSourceException} 的嵌套
 * {@code NotFoundException} 是同一处置。</p>
 *
 * <h2>message 逐字对齐 Go</h2>
 * <p>无参构造器产出的 {@code getMessage()} 就是 Go 哨兵的原文（例如
 * {@code "invalid credentials"}），带 detail 的构造器产出
 * {@code "invalid credentials: api_token is required"}——与
 * {@code fmt.Errorf("%w: api_token is required", ErrInvalidCredentials).Error()} 逐字一致。
 * 这让"Go 里 grep 得到的字符串在 Java 里也 grep 得到"。</p>
 *
 * <h2>为什么这些 message 不直接上线</h2>
 * <p>与 {@code DataSourceException} 一样：真正的 HTTP 文案由 handler 另写
 * （{@code datasource_service.go} 会把 {@code ErrInvalidCredentials} 认出来、
 * 把数据源置为 {@code error} 状态并停止排期）。所以这里的分类语义
 * （{@link InvalidCredentials} 这个**类型**）比 message 更重要。</p>
 */
public class ConnectorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }

    // ── 类型判定：对照 Go 的 errors.Is（要穿透 %w 包装链） ─────────────────

    /**
     * 沿 <b>cause 链</b>找到第一个给定类型的连接器异常，找不到回 {@code null}。
     *
     * <h2>为什么必须有这个工具，而不是直接 {@code instanceof}</h2>
     * <p>Go 侧连接器大量使用 {@code fmt.Errorf("%w: 细节", ErrXxx)}：它<b>既</b>拼错误文本
     * <b>又</b>保留哨兵类型，所以 {@code errors.Is(err, datasource.ErrInvalidCredentials)}
     * 能穿透任意层包装。Java 的异常继承做不到"文本 + 类型"双保真——连接器里
     * 形如 {@code throw new ConnectorException("list wiki nodes: " + msg, cause)} 的包装
     * 会把内层的 {@link InvalidCredentials} 挤到 cause 上。</p>
     * <p>所以 <b>service 层判型必须走这里</b>（等价于 Go 的
     * {@code errors.Is} 自己会 unwrap），不要写 {@code err instanceof InvalidCredentials}。</p>
     *
     * <p>另有两点与 Go 一致：<b>同一实例</b>也算命中（Go 的 {@code errors.Is} 先比相等再
     * unwrap）；链上任何一层命中即返回<b>最外层</b>的那个。</p>
     */
    public static ConnectorException find(Throwable err, Class<? extends ConnectorException> type) {
        for (Throwable current = err; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return (ConnectorException) current;
            }
            if (current.getCause() == current) {
                break; // 自引用防御（构造出环的异常链会死循环）
            }
        }
        return null;
    }

    /** 对照 Go 的 {@code errors.Is(err, ErrXxx)}：链上任意一层是给定类型即为真。 */
    public static boolean is(Throwable err, Class<? extends ConnectorException> type) {
        return find(err, type) != null;
    }

    /**
     * 对照 Go 里数据源服务最常用的那一次判定：
     * {@code errors.Is(err, datasource.ErrInvalidCredentials)}
     * ——决定"把数据源置为 error 状态并停止排期"（而不是重试）。
     */
    public static boolean isInvalidCredentials(Throwable err) {
        return is(err, InvalidCredentials.class);
    }

    /**
     * 对照 Go 的 {@code errors.As(err, &partial)}：
     * 沿 cause 链找"部分成功"这一层（连接器会把仍然有效的 items/cursor 挂在它上面）。
     */
    public static PartialFetch findPartialFetch(Throwable err) {
        ConnectorException found = find(err, PartialFetch.class);
        return found == null ? null : (PartialFetch) found;
    }

    // ── 注册表（connector.go 的三个哨兵） ────────────────────────────────

    /** 对照 Go {@code ErrConnectorNil}：{@code "connector is nil"}。 */
    public static class NilConnector extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public NilConnector() {
            super("connector is nil");
        }
    }

    /** 对照 Go {@code ErrConnectorTypeEmpty}：{@code "connector type is empty"}。 */
    public static class EmptyConnectorType extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public EmptyConnectorType() {
            super("connector type is empty");
        }
    }

    /**
     * 对照 Go {@code ErrConnectorNotFound}：{@code "connector type not found in registry"}。
     *
     * <p>注册表在 Go 侧**不拼任何细节**——{@code Get} 直接 {@code return nil, ErrConnectorNotFound}，
     * 所以 {@code getMessage()} 就是哨兵原文（连请求的那个 type 都不带）。照抄。</p>
     */
    public static class NotFound extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public NotFound() {
            super("connector type not found in registry");
        }
    }

    // ── 配置 / 凭据 ─────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ErrInvalidConfig}：{@code "invalid configuration"}。
     *
     * <p>注意 Go 里有两种用法：{@code fmt.Errorf("%w: settings.projects is required", ErrInvalidConfig)}
     * （带细节）与 gitlab 的 {@code return nil, datasource.ErrInvalidConfig}（裸哨兵）。
     * 两个构造器分别对应。</p>
     */
    public static class InvalidConfig extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public InvalidConfig() {
            super("invalid configuration");
        }

        public InvalidConfig(String detail) {
            super("invalid configuration: " + detail);
        }
    }

    /** 对照 Go {@code ErrInvalidCredentials}：{@code "invalid credentials"}。 */
    public static class InvalidCredentials extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public InvalidCredentials() {
            super("invalid credentials");
        }

        public InvalidCredentials(String detail) {
            super("invalid credentials: " + detail);
        }
    }

    // ── 抓取 ────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ErrFetchFailed}：{@code "failed to fetch items from source"}。
     * Notion 把它当作重试耗尽后的兜底（{@code fmt.Errorf("%w: %v", ErrFetchFailed, lastErr)}）。
     */
    public static class FetchFailed extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public FetchFailed(String detail) {
            super("failed to fetch items from source: " + detail);
        }

        public FetchFailed(String detail, Throwable cause) {
            super("failed to fetch items from source: " + detail, cause);
        }
    }

    /**
     * 对照 Go {@code ErrResourceNotFound}：{@code "resource not found in source system"}。
     *
     * <p>Notion 的 404 分支拼的是**路径**而不是别的细节：
     * {@code fmt.Errorf("%w: %s", ErrResourceNotFound, path)}。</p>
     */
    public static class ResourceNotFound extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public ResourceNotFound(String path) {
            super("resource not found in source system: " + path);
        }
    }

    // ── 部分失败 ────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code PartialFetchError}：一部分资源成功了、另一部分失败。
     *
     * <p>调用方（service 层）要把它认出来（Go 的 {@code errors.As}，Java 的
     * {@code instanceof}），照常处理 {@code items}、持久化更新后的 cursor，
     * 并把 {@link #getDetails()} 当作"部分同步"暴露给用户。</p>
     *
     * <h2>为什么它不是 {@code ConnectorException} 的"错误"而是一个控制流信号</h2>
     * <p>Go 侧 {@code walk} 在部分失败时返回的是
     * <b>{@code (out, newCursor, &PartialFetchError{...})}</b>——三个值同时非空。
     * 所以 Java 侧绝不能把它当成"什么都没抓到"：RSS 连接器在返回它之前
     * 已经把成功的条目放进 items、把 cursor 也建好了。调用方必须
     * <b>先读 items/cursor、再判异常类型</b>——这条语义由
     * {@link com.ragagent.datasource.Connector.FetchIncrementalResult} 承载。</p>
     */
    public static class PartialFetch extends ConnectorException {

        private static final long serialVersionUID = 1L;

        private final List<String> details;

        public PartialFetch(List<String> details) {
            super(buildMessage(details));
            this.details = details == null ? List.of() : List.copyOf(details);
        }

        /** 对照 Go 的 {@code PartialFetchError.Details}。 */
        public List<String> getDetails() {
            return details;
        }

        /**
         * 对照 Go {@code (*PartialFetchError).Error()}：
         * 无细节时是 {@code "partial fetch: some resources failed"}，
         * 否则是 {@code "partial fetch: " + strings.Join(details, "; ")}。
         */
        private static String buildMessage(List<String> details) {
            if (details == null || details.isEmpty()) {
                return "partial fetch: some resources failed";
            }
            return "partial fetch: " + String.join("; ", details);
        }
    }
}
