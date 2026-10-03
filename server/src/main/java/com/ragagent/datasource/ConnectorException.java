package com.ragagent.datasource;

import java.util.List;

/**
 * 连接器层的错误体系：哨兵式错误类型 + "哨兵原文: 细节"前缀式 message。
 *
 * <h2>为什么用"一个基类 + 嵌套子类"而不是一堆顶层异常</h2>
 * <p>判型靠类型（{@code instanceof} / {@link #find}），文本由构造函数拼成
 * <b>{@code "哨兵原文: 细节"}</b>。子类集中在一个文件里，是为了让全部哨兵
 * 一眼可见——分散成十几个文件反而看不出这层分类。这与
 * {@link com.ragagent.datasource.domain.DataSourceException} 的嵌套
 * {@code NotFoundException} 是同一处置。</p>
 *
 * <h2>message 即哨兵原文</h2>
 * <p>无参构造器产出的 {@code getMessage()} 就是哨兵原文（例如
 * {@code "invalid credentials"}），带 detail 的构造器产出
 * {@code "invalid credentials: api_token is required"}——文本逐字稳定，便于日志检索。</p>
 *
 * <h2>为什么这些 message 不直接上线</h2>
 * <p>与 {@code DataSourceException} 一样：真正的 HTTP 文案由 handler 另写
 * （service 层会把 {@link InvalidCredentials} 认出来、
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

    // ── 类型判定（沿 cause 链穿透包装） ───────────────────────────────────

    /**
     * 沿 <b>cause 链</b>找到第一个给定类型的连接器异常，找不到回 {@code null}。
     *
     * <h2>为什么必须有这个工具，而不是直接 {@code instanceof}</h2>
     * <p>调用点上的 {@code instanceof} 只能看到最外层，而连接器里
     * 形如 {@code throw new ConnectorException("list wiki nodes: " + msg, cause)} 的包装
     * 会把内层的 {@link InvalidCredentials} 挤到 cause 上。</p>
     * <p>所以 <b>service 层判型必须走这里</b>，不要写 {@code err instanceof InvalidCredentials}。</p>
     *
     * <p>两点约定：<b>同一实例</b>也算命中；链上任何一层命中即返回<b>最外层</b>的那个。</p>
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

    /** 链上任意一层是给定类型即为真。 */
    public static boolean is(Throwable err, Class<? extends ConnectorException> type) {
        return find(err, type) != null;
    }

    /**
     * 判定"凭据无效"——service 层据此把数据源置为 error 状态并停止排期（而不是重试）。
     */
    public static boolean isInvalidCredentials(Throwable err) {
        return is(err, InvalidCredentials.class);
    }

    /**
     * 沿 cause 链找"部分成功"这一层（连接器会把仍然有效的 items/cursor 挂在它上面）。
     */
    public static PartialFetch findPartialFetch(Throwable err) {
        ConnectorException found = find(err, PartialFetch.class);
        return found == null ? null : (PartialFetch) found;
    }

    // ── 注册表相关哨兵 ──────────────────────────────────────────────────

    /** 连接器为 {@code null}（{@code "connector is nil"}）。 */
    public static class NilConnector extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public NilConnector() {
            super("connector is nil");
        }
    }

    /** {@code type()} 为空串（{@code "connector type is empty"}）。 */
    public static class EmptyConnectorType extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public EmptyConnectorType() {
            super("connector type is empty");
        }
    }

    /**
     * 请求的类型未注册（{@code "connector type not found in registry"}）。
     *
     * <p>注册表**不拼任何细节**——未命中直接抛本类型，
     * 所以 {@code getMessage()} 就是哨兵原文（连请求的那个 type 都不带）。</p>
     */
    public static class NotFound extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public NotFound() {
            super("connector type not found in registry");
        }
    }

    // ── 配置 / 凭据 ─────────────────────────────────────────────────────

    /**
     * 配置不合法（{@code "invalid configuration"}）。
     *
     * <p>两种用法：带细节（如 {@code "invalid configuration: settings.projects is required"}）
     * 与裸哨兵。两个构造器分别对应。</p>
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

    /** 凭据无效（{@code "invalid credentials"}）。 */
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
     * 抓取失败（{@code "failed to fetch items from source"}）。
     * Notion 把它当作重试耗尽后的兜底。
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
     * 资源在外部系统不存在（{@code "resource not found in source system"}）。
     *
     * <p>Notion 的 404 分支拼的是**路径**而不是别的细节。</p>
     */
    public static class ResourceNotFound extends ConnectorException {
        private static final long serialVersionUID = 1L;

        public ResourceNotFound(String path) {
            super("resource not found in source system: " + path);
        }
    }

    // ── 部分失败 ────────────────────────────────────────────────────────

    /**
     * 部分成功：一部分资源成功了、另一部分失败。
     *
     * <p>调用方（service 层）要把它认出来（{@code instanceof} 或
     * {@link #findPartialFetch}），照常处理 {@code items}、持久化更新后的 cursor，
     * 并把 {@link #getDetails()} 当作"部分同步"暴露给用户。</p>
     *
     * <h2>为什么它是控制流信号而不只是错误</h2>
     * <p>抛出它时<b>结果与异常同时有效</b>——成功的条目已在 items、cursor 也已建好。
     * 所以绝不能把它当成"什么都没抓到"。调用方必须
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

        /** 失败明细。 */
        public List<String> getDetails() {
            return details;
        }

        /**
         * 无细节时是 {@code "partial fetch: some resources failed"}，
         * 否则是 {@code "partial fetch: " + String.join("; ", details)}。
         */
        private static String buildMessage(List<String> details) {
            if (details == null || details.isEmpty()) {
                return "partial fetch: some resources failed";
            }
            return "partial fetch: " + String.join("; ", details);
        }
    }
}
