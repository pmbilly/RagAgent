package com.ragagent.datasource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.repository.KnowledgeTagRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeTagService;

/**
 * 自动标签生产实现（对照 Go tag.go FindOrCreateTagByName L475-506）：
 * 空参 400 文案、KB 缺失 404、同名直取不建、缺名走 createTag。
 */
class KnowledgeTagAutoTagProviderTest {

    private KnowledgeBaseService kbService;
    private KnowledgeTagRepository tagRepo;
    private KnowledgeTagService tagService;
    private KnowledgeTagAutoTagProvider provider;

    @BeforeEach
    void setUp() {
        kbService = mock(KnowledgeBaseService.class);
        tagRepo = mock(KnowledgeTagRepository.class);
        tagService = mock(KnowledgeTagService.class);
        provider = new KnowledgeTagAutoTagProvider(kbService, tagRepo, tagService);
    }

    @Test
    void blankArgsThrowBadRequestWithGoText() {
        assertThatThrownBy(() -> provider.findOrCreateTagId("kb-1", "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("知识库ID和标签名称不能为空");
        assertThatThrownBy(() -> provider.findOrCreateTagId("", "tag"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void missingKbThrowsNotFound() {
        when(kbService.getAllTenantById("kb-1")).thenReturn(null);
        assertThatThrownBy(() -> provider.findOrCreateTagId("kb-1", "tag"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("knowledge base not found");
    }

    @Test
    void existingTagIsReusedWithoutCreate() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setTenantId(10002L);
        when(kbService.getAllTenantById("kb-1")).thenReturn(kb);
        KnowledgeTag existing = new KnowledgeTag();
        existing.setId("tag-1");
        when(tagRepo.getByName(10002L, "kb-1", "tag")).thenReturn(existing);

        assertThat(provider.findOrCreateTagId("kb-1", " tag ")).isEqualTo("tag-1");
        verify(tagService, never()).createTag(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void missingTagIsCreatedViaCreateTag() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setTenantId(10002L);
        when(kbService.getAllTenantById("kb-1")).thenReturn(kb);
        when(tagRepo.getByName(10002L, "kb-1", "tag")).thenReturn(null);
        KnowledgeTag created = new KnowledgeTag();
        created.setId("tag-new");
        when(tagService.createTag("kb-1", "tag", null, 0)).thenReturn(created);

        assertThat(provider.findOrCreateTagId("kb-1", "tag")).isEqualTo("tag-new");
    }
}
