package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * {@code messages} 表上 {@code List<MessageImage>} 型 jsonb 列的处理器。
 *
 * <p>存在的理由见 {@link AbstractJsonListTypeHandler}：泛型擦除会导致元素类型丢失。</p>
 */
public class MessageImageListTypeHandler extends AbstractJsonListTypeHandler<MessageImage> {

    @Override
    protected TypeReference<List<MessageImage>> typeReference() {
        return new TypeReference<>() {};
    }
}
