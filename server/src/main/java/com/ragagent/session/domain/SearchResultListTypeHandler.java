package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ragagent.common.retrieval.SearchResult;

/**
 * {@code messages} 表上 {@code knowledge_references}（Go {@code types.References}）列的处理器。
 *
 * <p>存在的理由见 {@link AbstractJsonListTypeHandler}：泛型擦除会让
 * {@code List<SearchResult>} 退化成 {@code List<LinkedHashMap>}——
 * 而 {@code knowledge_references} 直接出现在消息响应体里，
 * 元素一旦退化成 map，键序就变成 PG jsonb 的规范化序，不再是 Go 的 struct 声明序。</p>
 *
 * <p><b>写路径与 wiki 那套相反</b>：Go 的 {@code References.Value()} 直接
 * {@code json.Marshal(c)}，nil 切片会得到 JSON {@code null}——但
 * {@code Message.BeforeCreate} 钩子已把 nil 置成 {@code References{}}，
 * 所以落库的实际是 {@code []}。基类的「空列表写 {@code []}」与此一致。</p>
 */
public class SearchResultListTypeHandler extends AbstractJsonListTypeHandler<SearchResult> {

    @Override
    protected TypeReference<List<SearchResult>> typeReference() {
        return new TypeReference<>() {};
    }
}
