package com.ragagent.wiki.domain;


/**
 * 把页面回滚到某个已存修订的请求。JSON 键为 snake（前端按此解析）。
 *
 * <p>slug 走请求体而非路径，理由同 {@link WikiPageMoveRequest}：
 * 层级化 slug（"entity/acme"）放进路径会与 catch-all 通配路由冲突。</p>
 *
 * <p>本类刻意不加校验注解，必填性由 controller 层显式校验决定。</p>
 */
public record WikiPageRevertRequest( String slug, int version) {
}
