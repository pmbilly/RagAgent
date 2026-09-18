package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * {@code message_suggestion_sets.questions} 列（{@code List<SuggestionItem>}）的处理器。
 *
 * <p>存在的理由见 {@link AbstractJsonListTypeHandler}：泛型擦除会导致元素类型丢失。</p>
 */
public class SuggestionItemListTypeHandler extends AbstractJsonListTypeHandler<SuggestionItem> {

    @Override
    protected TypeReference<List<SuggestionItem>> typeReference() {
        return new TypeReference<>() {};
    }
}
