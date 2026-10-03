package com.ragagent.common.error;

/**
 * 路由守卫拒绝（403）——**纯字符串**错误形态。
 *
 * <h2>为什么需要单独一个异常</h2>
 * 系统里有两种 403，形态不同，前端按契约解析：
 * <ol>
 *   <li><b>路由中间件</b>（{@code middleware.RequireRole} / {@code RequireOwnershipOrRole}）：
 *       直接写 {@code {"error":"Forbidden: <message>"}}——**不带 success/code/details 信封**。
 *       例：{@code {"error":"Forbidden: insufficient workspace role"}}、
 *       {@code {"error":"Forbidden: must own the resource or have the required role"}}</li>
 *   <li><b>handler 内的 AppError</b>：走全局错误处理器，输出
 *       {@code {"error":{"code":N,...},"success":false}}</li>
 * </ol>
 *
 * Java 侧的角色下限由 {@link com.ragagent.web.RbacInterceptor} 拦截，形态自然一致；
 * 但**所有权判定**（如 Wiki 的 {@code OwnedWikiKBOrAdmin}）依赖具体资源，拦截器做不了，
 * 只能落到控制器里——若在那里抛 {@code BizException(AppError.forbidden(...))}，
 * 就会输出第 2 种形态，而非第 1 种守卫形态。
 *
 * 这个异常就是补上那条通路：控制器判定所有权失败时抛它，由全局处理器转成守卫格式。
 */
public class GuardForbiddenException extends RuntimeException {

    public GuardForbiddenException(String message) {
        super(message);
    }

    /** 守卫的固定文案。 */
    public static GuardForbiddenException mustOwnResourceOrHaveRole() {
        return new GuardForbiddenException("must own the resource or have the required role");
    }
}
