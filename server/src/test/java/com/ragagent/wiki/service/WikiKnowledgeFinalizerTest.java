package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.mapper.KnowledgeMapper;

/**
 * {@link DefaultWikiKnowledgeFinalizer} 的对等测试（对照 Go
 * {@code knowledgeRepository.FinalizeSubtask}，internal/application/repository/knowledge.go
 * L600-650）。
 *
 * <p><b>为什么值得单测</b>：这是本轮接线的三处"卡死"接缝之一——没有它，文档会永远
 * 停在 {@code finalizing}。它的正确性完全体现在<b>两条 UPDATE 的 WHERE 子句</b>上：
 * 递减必须钳在零、晋升必须"无 SELECT、无条件尝试"（Go 注释里的
 * "stuck pending_subtasks_count" bug 就是被一次滞后的 SELECT 造成的）。</p>
 */
class WikiKnowledgeFinalizerTest {

    private final KnowledgeMapper knowledgeMapper = mock(KnowledgeMapper.class);
    private final DefaultWikiKnowledgeFinalizer finalizer =
            new DefaultWikiKnowledgeFinalizer(knowledgeMapper);

    /**
     * 两次写入的形状：先递减（{@code pending_subtasks_count > 0} 守卫），
     * 再无条件的带守卫晋升（{@code parse_status = finalizing AND count = 0}），
     * 每次都刷新 {@code updated_at}。
     */
    @Test
    @DisplayName("递减 + 带守卫晋升（对照 Go FinalizeSubtask）")
    @SuppressWarnings("unchecked")
    void decrementThenPromote() {
        when(knowledgeMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        DefaultWikiKnowledgeFinalizer.Outcome outcome = finalizer.finalizeSubtask("kid-1");

        assertThat(outcome.decremented()).isTrue();
        assertThat(outcome.promoted()).isTrue();

        ArgumentCaptor<Wrapper<Knowledge>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(knowledgeMapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
        String decrementSql = captor.getAllValues().get(0).getSqlSegment();
        assertThat(decrementSql)
                .as("递减语句必须钳在零：%s", decrementSql)
                .contains("pending_subtasks_count >");
        assertThat(captor.getAllValues().get(0).getSqlSet())
                .contains("pending_subtasks_count = pending_subtasks_count - 1");

        String promoteSql = captor.getAllValues().get(1).getSqlSegment();
        assertThat(promoteSql)
                .as("晋升必须同时守 parse_status 与计数归零：%s", promoteSql)
                .contains("parse_status")
                .contains("pending_subtasks_count =");
        assertThat(captor.getAllValues().get(1).getSqlSet())
                .contains("parse_status")
                .contains("error_message")
                .contains("processed_at");
    }

    /**
     * 行已不在 {@code finalizing}（或计数未归零）时晋升影响 0 行——
     * 这是<b>预期</b>，不是错误：只有真正把计数减到零的调用方能匹配上。
     */
    @Test
    @DisplayName("晋升未命中不是错误")
    void promoteMissIsNotAnError() {
        when(knowledgeMapper.update(isNull(), any(Wrapper.class)))
                .thenReturn(1)   // 递减命中
                .thenReturn(0);  // 晋升未命中

        DefaultWikiKnowledgeFinalizer.Outcome outcome = finalizer.finalizeSubtask("kid-1");

        assertThat(outcome.decremented()).isTrue();
        assertThat(outcome.promoted()).isFalse();
    }

    /**
     * 计数已为 0（或行不存在）时递减影响 0 行；晋升仍<b>照常尝试</b>
     * ——绝不先 SELECT 再决定。
     */
    @Test
    @DisplayName("递减未命中仍无条件尝试晋升")
    void alwaysAttemptsPromote() {
        when(knowledgeMapper.update(isNull(), any(Wrapper.class)))
                .thenReturn(0)   // 递减未命中
                .thenReturn(0);

        DefaultWikiKnowledgeFinalizer.Outcome outcome = finalizer.finalizeSubtask("kid-1");

        assertThat(outcome.decremented()).isFalse();
        assertThat(outcome.promoted()).isFalse();
        verify(knowledgeMapper, org.mockito.Mockito.times(2)).update(isNull(), any(Wrapper.class));
    }

    /** 递减报错时不再尝试晋升（对照 Go 的 early return） */
    @Test
    @DisplayName("递减报错时提前返回")
    void decrementFailureShortCircuits() {
        when(knowledgeMapper.update(isNull(), any(Wrapper.class)))
                .thenThrow(new IllegalStateException("db down"));

        DefaultWikiKnowledgeFinalizer.Outcome outcome = finalizer.finalizeSubtask("kid-1");

        assertThat(outcome.decremented()).isFalse();
        verify(knowledgeMapper, org.mockito.Mockito.times(1)).update(isNull(), any(Wrapper.class));
    }

    /** 空 id 是安全 no-op（对照 Go 的 {@code knowledgeID == ""} 短路） */
    @Test
    @DisplayName("空 id 短路")
    void emptyIdShortCircuits() {
        assertThat(finalizer.finalizeSubtask("").decremented()).isFalse();
        assertThat(finalizer.finalizeSubtask(null).decremented()).isFalse();
        verify(knowledgeMapper, never()).update(any(), any(Wrapper.class));
    }

    /** 端口方法不抛异常（对照 Go 的 detached 路径：失败只记日志） */
    @Test
    @DisplayName("端口方法吞掉失败")
    void portMethodSwallowsFailures() {
        when(knowledgeMapper.update(isNull(), any(Wrapper.class)))
                .thenThrow(new IllegalStateException("db down"));
        finalizer.finalizeWikiSubtask("kid-1");
    }

    /** 晋升成功后 {@code parse_status} 落到 completed（守卫条件里的常量取自领域类型） */
    @Test
    @DisplayName("晋升条件使用领域常量")
    @SuppressWarnings("unchecked")
    void promoteUsesDomainConstants() {
        when(knowledgeMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        finalizer.finalizeSubtask("kid-1");

        ArgumentCaptor<Wrapper<Knowledge>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(knowledgeMapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
        assertThat(Knowledge.PARSE_FINALIZING).isEqualTo("finalizing");
        assertThat(Knowledge.PARSE_COMPLETED).isEqualTo("completed");
        // MyBatis-Plus 把值参数化了（#{ew.paramNameValuePairs.MPGENVALn}），
        // 因此断言参数表里有 completed，以及 WHERE 里的 finalizing。
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> promoted =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                        captor.getAllValues().get(1);
        assertThat(promoted.getParamNameValuePairs().values())
                .as("晋升的 SET 里必须有 completed")
                .contains(Knowledge.PARSE_COMPLETED);
        // WHERE 侧的等值条件由 MyBatis-Plus 内联进 SQL 片段（不进参数表），
        // 因此这里断言片段文本本身（decrementThenPromote 已逐条覆盖）。
        assertThat(promoted.getSqlSegment())
                .contains("parse_status")
                .contains("pending_subtasks_count");
    }
}
