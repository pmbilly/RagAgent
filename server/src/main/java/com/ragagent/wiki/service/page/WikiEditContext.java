package com.ragagent.wiki.service.page;

import java.util.function.Supplier;

import com.ragagent.common.context.TenantContext;
import com.ragagent.wiki.domain.WikiConstants;

/**
 * wiki 写入的「编辑来源」作用域。
 *
 * <p>service 写入时从这里取出当前来源盖到页面上；用
 * ThreadLocal 承载作用域语义（不改动 handler 面的 service 签名）——与
 * {@link TenantContext} 的做法一致。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_USER, () -> service.createPage(p));
 * }</pre>
 *
 * <p><b>虚拟线程注意</b>：ThreadLocal 在虚拟线程下安全（虚拟线程也是线程），
 * 但跨线程/异步任务传递必须显式取值再传递；本类只负责单请求内的作用域。</p>
 *
 * <p><b>归一化</b>：空/未知来源一律归为
 * {@link WikiConstants#EDIT_SOURCE_PIPELINE}（"机器写的"），
 * 见 {@link WikiConstants#normalizeEditSource}。</p>
 */
public final class WikiEditContext {

    private WikiEditContext() {}

    private static final ThreadLocal<String> EDIT_SOURCE = new ThreadLocal<>();

    /**
     * 取当前作用域的编辑来源，
     * 缺席时返回 {@code "pipeline"}（<b>永不返回 null/空</b>）。
     */
    public static String currentEditSource() {
        return WikiConstants.normalizeEditSource(EDIT_SOURCE.get());
    }

    /**
     * 当前用户 id；取不到时返回 {@code ""}。
     */
    public static String currentEditorId() {
        String uid = TenantContext.currentUserId();
        return uid == null ? "" : uid;
    }

    /** 进入一个以 {@code source} 归属的作用域 */
    public static void set(String source) {
        EDIT_SOURCE.set(WikiConstants.normalizeEditSource(source));
    }

    /** 退出作用域；恢复到「pipeline」语义 */
    public static void clear() {
        EDIT_SOURCE.remove();
    }

    /** 包裹一段调用：期间编辑来源为 {@code source}，结束后恢复 */
    public static <T> T callWith(String source, Supplier<T> action) {
        String prev = EDIT_SOURCE.get();
        EDIT_SOURCE.set(WikiConstants.normalizeEditSource(source));
        try {
            return action.get();
        } finally {
            if (prev == null) {
                EDIT_SOURCE.remove();
            } else {
                EDIT_SOURCE.set(prev);
            }
        }
    }

    /** {@link #callWith} 的 void 版本 */
    public static void runWith(String source, Runnable action) {
        callWith(source, () -> {
            action.run();
            return null;
        });
    }
}
