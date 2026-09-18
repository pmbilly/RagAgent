package com.ragagent.datasource.connector.rss;

/**
 * {@link ArticleExtractor} 的<b>降级默认实现</b>：永远失败。
 *
 * <p>它存在的唯一理由是让 {@code resolveItem} 走 Go 已有的回落分支
 * （feed 内容），从而在没有 readability 依赖的情况下保持控制流等价。
 * 详细后果见 {@link ArticleExtractor} 的类注释。</p>
 *
 * <h2>为什么不返回整页 HTML 冒充"正文"</h2>
 * <p>那样看起来"更有内容"，但会把导航栏、页脚、广告一起灌进知识库，
 * 且与 Go 的输出<b>既不等价也不更差得可预期</b>——一个确定的失败比一个
 * 看起来成功的错误结果更容易被诊断。这也是任务书要求的处置。</p>
 */
public final class UnavailableArticleExtractor implements ArticleExtractor {

    /** 日志与测试里用的固定文案。 */
    public static final String MESSAGE =
            "readability extractor is not available in this build";

    @Override
    public ExtractedArticle extract(byte[] body, String pageUrl) {
        throw new ArticleExtractionException(MESSAGE);
    }
}
