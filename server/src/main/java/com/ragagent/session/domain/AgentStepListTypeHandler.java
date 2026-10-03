package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ragagent.agent.domain.AgentStep;

/**
 * {@code messages} 表上 {@code agent_steps} 列的处理器。
 *
 * <p>存在的理由见 {@link AbstractJsonListTypeHandler}：泛型擦除会让
 * {@code List<AgentStep>} 退化成 {@code List<LinkedHashMap>}，一取元素就
 * {@code ClassCastException}——而 {@code agent_steps} 是会出现在消息响应体里的。</p>
 *
 * <p>写路径：nil 与空列表都写成 {@code []}，不写 SQL NULL。</p>
 */
public class AgentStepListTypeHandler extends AbstractJsonListTypeHandler<AgentStep> {

    @Override
    protected TypeReference<List<AgentStep>> typeReference() {
        return new TypeReference<>() {};
    }
}
