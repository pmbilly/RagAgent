package com.ragagent.session.domain;

/**
 * 会话不存在。
 *
 * <p>两个层面抛出，语义完全相同——"这个租户/这个 owner 看不到这个 id"：</p>
 * <ul>
 *   <li>仓储层：查询零行；</li>
 *   <li>服务层：{@code loadSessionForRead} 判定"渠道托管会话、调用方又非管理员"时
 *       **刻意返回同一个错误**，好让未授权者无法区分"不存在"与"存在但你看不到"。</li>
 * </ul>
 *
 * <p>handler 把它翻成 404 + AppError 信封（{@code errors.NewNotFoundError}）。</p>
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException() {
        super("session not found");
    }
}
