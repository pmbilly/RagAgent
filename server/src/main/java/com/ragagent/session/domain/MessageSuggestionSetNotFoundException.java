package com.ragagent.session.domain;

/**
 * 消息建议集合不存在（对照 Go 里直接透传的 {@code gorm.ErrRecordNotFound}）。
 *
 * <p>原先是 {@code MessageSuggestionRepository} 的嵌套类型——控制器要按它分派 404，
 * 于是形成了"controller → mapper"的直连。提成领域异常后，仓储抛出、控制器按类型分派。</p>
 */
public class MessageSuggestionSetNotFoundException extends RuntimeException {

    public MessageSuggestionSetNotFoundException() {
        super("message suggestion set not found");
    }
}
