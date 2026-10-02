package com.ragagent.common.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索驱动的部署配置（{@code RETRIEVE_DRIVER}，B6 批 3）。
 *
 * <p>env 名保持原样：{@code RETRIEVE_DRIVER} → {@code retrieve.driver}（Spring 松散绑定），
 * 部署侧 .env 不需要改。</p>
 *
 * <p>值是<b>逗号分隔的引擎名列表</b>（如 {@code postgres,opensearch}）。本类只承载
 * 原始串（含 {@code null} 语义——「未配置」与「配成全空白」在各读点含义不同）：
 * 切分、trim 与缺省值都留在各读点（知识库缺省 {@code postgres}、系统信息页显示
 * 「未配置」、有效引擎列表为空即检索全关、env-向量库为空列表）。</p>
 */
@ConfigurationProperties(prefix = "retrieve")
public record RetrievalDriverProperties(String driver) {
}
