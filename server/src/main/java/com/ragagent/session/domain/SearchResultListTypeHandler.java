package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ragagent.common.retrieval.SearchResult;

/**
 * {@code messages} 表上 {@code knowledge_references} 列的处理器。
 *
 * <p>存在的理由见 {@link AbstractJsonListTypeHandler}：泛型擦除会让
 * {@code List<SearchResult>} 退化成 {@code List<LinkedHashMap>}——
 * 而 {@code knowledge_references} 直接出现在消息响应体里，
 * 元素一旦退化成 map，键序就变成 PG jsonb 的规范化序。</p>
 *
 * <p><b>写路径</b>：nil 列表由实体的落库前兜底置成空列表，
 * 落库的实际是 {@code []}。基类的「空列表写 {@code []}」与此一致。</p>
 */
public class SearchResultListTypeHandler extends AbstractJsonListTypeHandler<SearchResult> {

    @Override
    protected TypeReference<List<SearchResult>> typeReference() {
        return new TypeReference<>() {};
    }
}
