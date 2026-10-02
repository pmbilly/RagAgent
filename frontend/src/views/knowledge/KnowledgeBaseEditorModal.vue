<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="settings-overlay" @click.self="handleClose">
        <div class="settings-modal">
          <div v-if="loading" class="editor-initializing" role="status" :aria-label="$t('common.loading')">
            <t-loading size="medium" :text="$t('common.loading')" />
          </div>
          <!-- 关闭按钮 -->
          <button class="close-btn" @click="handleClose" :aria-label="$t('general.close')">
            <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
              <path d="M15 5L5 15M5 5L15 15" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </button>

          <div class="settings-container">
            <!-- 左侧导航 -->
            <div class="settings-sidebar">
              <div class="sidebar-header">
                <h2 class="sidebar-title">{{ editorMode === 'create' ? $t('knowledgeEditor.titleCreate') : $t('knowledgeEditor.titleEdit') }}</h2>
              </div>
              <div class="settings-nav" data-guide="kb-editor-sidebar">
                <template v-for="group in navGroups" :key="group.key">
                  <div class="nav-group-title">{{ group.label }}</div>
                  <div
                    v-for="(item, index) in group.items"
                    :key="index"
                    :class="['nav-item', { 'active': currentSection === item.key }]"
                    :data-guide="`kb-editor-nav-${item.key}`"
                    @click="currentSection = item.key"
                  >
                    <t-icon :name="item.icon" class="nav-icon" />
                    <span class="nav-label">{{ item.label }}</span>
                    <span v-if="item.badge" class="nav-badge">{{ item.badge }}</span>
                  </div>
                </template>
              </div>
            </div>

            <!-- 右侧内容区域 -->
            <div class="settings-content">
              <div class="content-wrapper">
                <!-- 基本信息 -->
                <div v-show="currentSection === 'basic'" class="section">
                  <div v-if="formData" class="section-content">
                    <div class="section-header">
                      <h3 class="section-title">{{ $t('knowledgeEditor.basic.title') }}</h3>
                      <p class="section-desc">{{ $t('knowledgeEditor.basic.description') }}</p>
                    </div>
                    <div class="section-body">
                      <div v-if="editorMode === 'edit' && activeKbId" class="form-item">
                        <label class="form-label">{{ $t('knowledgeEditor.basic.kbId') }}</label>
                        <p class="form-tip">{{ isPostCreateSession ? $t('knowledgeEditor.postCreateHint.followUpDesc') : $t('knowledgeEditor.basic.kbIdDesc') }}</p>
                        <div class="kb-id-field">
                          <code class="kb-id-value" :title="activeKbId">{{ activeKbId }}</code>
                          <t-tooltip :content="$t('common.copy')" placement="top">
                            <t-button theme="default" size="small" variant="text" class="kb-id-copy"
                              @click="copyKbId">
                              <t-icon name="file-copy" />
                            </t-button>
                          </t-tooltip>
                        </div>
                      </div>

                      <div class="form-item">
                        <label class="form-label required">{{ $t('knowledgeEditor.basic.typeLabel') }}</label>
                        <t-radio-group
                          v-model="formData.type"
                          :disabled="editorMode === 'edit'"
                          data-guide="kb-create-type"
                        >
                          <t-radio-button value="document">{{ $t('knowledgeEditor.basic.typeDocument') }}</t-radio-button>
                          <t-radio-button value="faq">{{ $t('knowledgeEditor.basic.typeFAQ') }}</t-radio-button>
                        </t-radio-group>
                        <p class="form-tip">{{ $t('knowledgeEditor.basic.typeDescription') }}</p>
                      </div>

                      <!-- 索引策略 (紧跟类型选择) -->
                      <div v-if="!isFAQ" class="form-item">
                        <label class="form-label required">{{ $t('knowledgeEditor.indexing.title') }}</label>
                        <p class="form-tip">{{ $t('knowledgeEditor.indexing.description') }}</p>
                        <div class="indexing-checks" :class="{ 'is-locked': isIndexingLocked }"
                          data-guide="kb-create-indexing">
                          <div
                            class="indexing-check-item"
                            :class="{ 'is-checked': formData.indexingStrategy.vectorEnabled, 'is-disabled': isIndexingLocked }"
                            @click="toggleVectorIndexing"
                          >
                            <t-checkbox
                              :checked="formData.indexingStrategy.vectorEnabled"
                              :disabled="isIndexingLocked"
                              class="indexing-check-box"
                            >{{ $t('knowledgeEditor.indexing.searchTitle') }}</t-checkbox>
                            <p class="indexing-check-desc">{{ $t('knowledgeEditor.indexing.searchDesc') }}</p>
                          </div>
                          <div
                            class="indexing-check-item"
                            :class="{ 'is-checked': formData.indexingStrategy.wikiEnabled, 'is-disabled': isIndexingLocked }"
                            @click="toggleWikiIndexing"
                          >
                            <t-checkbox
                              :checked="formData.indexingStrategy.wikiEnabled"
                              :disabled="isIndexingLocked"
                              class="indexing-check-box"
                            >
                              <span class="indexing-check-title">
                                {{ $t('knowledgeEditor.indexing.wikiTitle') }}
                                <span class="indexing-new-badge">NEW</span>
                              </span>
                            </t-checkbox>
                            <p class="indexing-check-desc">{{ $t('knowledgeEditor.indexing.wikiDesc') }}</p>
                          </div>
                        </div>
                        <p v-if="isIndexingLocked" class="form-tip locked-tip">
                          {{ $t('knowledgeEditor.indexing.lockedTip') }}
                        </p>
                      </div>

                      <!-- Wiki 提取粒度 (仅当 Wiki 启用时显示) -->
                      <div v-if="!isFAQ && formData.indexingStrategy.wikiEnabled" class="form-item">
                        <label class="form-label">{{ $t('knowledgeEditor.wiki.extractionGranularityLabel') }}</label>
                        <p class="form-tip">{{ $t('knowledgeEditor.wiki.extractionGranularityTip') }}</p>
                        <t-radio-group
                          :value="resolvedGranularity"
                          class="granularity-radio-group"
                          @change="handleGranularityChange"
                        >
                          <t-radio-button value="focused">
                            {{ $t('knowledgeEditor.wiki.granularityFocused') }}
                          </t-radio-button>
                          <t-radio-button value="standard">
                            {{ $t('knowledgeEditor.wiki.granularityStandard') }}
                          </t-radio-button>
                          <t-radio-button value="exhaustive">
                            {{ $t('knowledgeEditor.wiki.granularityExhaustive') }}
                          </t-radio-button>
                        </t-radio-group>
                        <p class="form-tip granularity-hint">{{ granularityHint }}</p>
                      </div>

                      <div v-if="!isFAQ && formData.indexingStrategy.wikiEnabled" class="form-item">
                        <label class="form-label">{{ $t('knowledgeEditor.wiki.contentInstructionsLabel') }}</label>
                        <p class="form-tip">{{ $t('knowledgeEditor.wiki.contentInstructionsTip') }}</p>
                        <t-textarea
                          v-model="formData.wikiConfig.contentInstructions"
                          :placeholder="$t('knowledgeEditor.wiki.contentInstructionsPlaceholder')"
                          :maxlength="4000"
                          :autosize="{ minRows: 3, maxRows: 8 }"
                        />
                      </div>

                      <div v-if="!isFAQ && formData.indexingStrategy.wikiEnabled" class="form-item">
                        <label class="form-label">{{ $t('knowledgeEditor.wiki.extractionInstructionsLabel') }}</label>
                        <p class="form-tip">{{ $t('knowledgeEditor.wiki.extractionInstructionsTip') }}</p>
                        <t-textarea
                          v-model="formData.wikiConfig.extractionInstructions"
                          :placeholder="$t('knowledgeEditor.wiki.extractionInstructionsPlaceholder')"
                          :maxlength="4000"
                          :autosize="{ minRows: 3, maxRows: 8 }"
                        />
                      </div>

                      <div class="form-item" data-guide="kb-create-name">
                        <label class="form-label required">{{ $t('knowledgeEditor.basic.nameLabel') }}</label>
                        <t-input 
                          v-model="formData.name" 
                          :placeholder="$t('knowledgeEditor.basic.namePlaceholder')"
                          :maxlength="50"
                        />
                      </div>
                      <div class="form-item">
                        <label class="form-label">{{ $t('knowledgeEditor.basic.descriptionLabel') }}</label>
                        <t-textarea
                          v-model="formData.description"
                          :placeholder="$t('knowledgeEditor.basic.descriptionPlaceholder')"
                          :maxlength="200"
                          :autosize="{ minRows: 3, maxRows: 6 }"
                        />
                      </div>

                      <!-- Wiki 合成模型移至模型配置页 -->
                    </div>
                  </div>
                </div>

                <!-- 模型配置 -->
                <div v-show="currentSection === 'models'" class="section">
                  <KBModelConfig
                    ref="modelConfigRef"
                    v-if="formData"
                    :config="formData.modelConfig"
                    :has-files="hasFiles"
                    :wiki-enabled="formData.indexingStrategy?.wikiEnabled"
                    :rag-enabled="formData.indexingStrategy?.vectorEnabled || formData.indexingStrategy?.keywordEnabled"
                    :all-models="allModels"
                    @update:config="handleModelConfigUpdate"
                  />
                </div>

                <!-- VectorStore 绑定 -->
                <div v-show="currentSection === 'vectorStore'" class="section">
                  <KBVectorStoreSettings
                    v-if="formData"
                    :mode="editorMode"
                    :vector-store-id="formData.vectorStoreId"
                    :bound-source="formData.vectorStoreInfo?.source"
                    :bound-name="formData.vectorStoreInfo?.name"
                    :bound-engine-type="formData.vectorStoreInfo?.engineType"
                    :bound-status="formData.vectorStoreInfo?.status"
                    @update:vector-store-id="handleVectorStoreIdUpdate"
                  />
                </div>

                <!-- FAQ 配置 -->
                <div v-if="isFAQ && formData" v-show="currentSection === 'faq'" class="section">
                  <div class="section-content">
                    <div class="section-header">
                      <h3 class="section-title">{{ $t('knowledgeEditor.faq.title') }}</h3>
                      <p class="section-desc">{{ $t('knowledgeEditor.faq.description') }}</p>
                    </div>
                    <div class="section-body">
                      <div class="form-item">
                        <label class="form-label required">{{ $t('knowledgeEditor.faq.indexModeLabel') }}</label>
                        <t-radio-group
                          v-model="formData.faqConfig.indexMode"
                        >
                          <t-radio-button value="question_only">{{ $t('knowledgeEditor.faq.modes.questionOnly') }}</t-radio-button>
                          <t-radio-button value="question_answer">{{ $t('knowledgeEditor.faq.modes.questionAnswer') }}</t-radio-button>
                        </t-radio-group>
                        <p class="form-tip">{{ $t('knowledgeEditor.faq.indexModeDescription') }}</p>
                      </div>
                      <div class="form-item">
                        <label class="form-label required">{{ $t('knowledgeEditor.faq.questionIndexModeLabel') }}</label>
                        <t-radio-group
                          v-model="formData.faqConfig.questionIndexMode"
                        >
                          <t-radio-button value="combined">{{ $t('knowledgeEditor.faq.modes.combined') }}</t-radio-button>
                          <t-radio-button value="separate">{{ $t('knowledgeEditor.faq.modes.separate') }}</t-radio-button>
                        </t-radio-group>
                        <p class="form-tip">{{ $t('knowledgeEditor.faq.questionIndexModeDescription') }}</p>
                      </div>
                      <div class="faq-guide">
                        <p>{{ $t('knowledgeEditor.faq.entryGuide') }}</p>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 解析引擎 -->
                <div v-if="!isFAQ && formData && currentSection === 'parser'" class="section">
                  <KBParserSettings
                    :parser-engine-rules="formData.chunkingConfig.parserEngineRules"
                    @update:parser-engine-rules="handleParserEngineRulesUpdate"
                  />
                </div>

                <!-- 存储引擎 -->
                <div v-if="!isFAQ && formData && currentSection === 'storage'" class="section">
                  <KBStorageSettings
                    :storage-backend-id="formData.storageBackendId"
                    :storage-provider="formData.storageProvider"
                    :has-files="editorMode === 'edit' && hasFiles"
                    @update:storage-backend-id="handleStorageBackendUpdate"
                    @update:storage-provider="handleStorageProviderUpdate"
                  />
                </div>

                <!-- 分块设置 -->
                <div v-if="!isFAQ" v-show="currentSection === 'chunking'" class="section">
                  <KBChunkingSettings
                    v-if="formData"
                    :config="formData.chunkingConfig"
                    @update:config="handleChunkingConfigUpdate"
                  />
                </div>

                <!-- 多模态配置 -->
                <div v-if="!isFAQ" v-show="currentSection === 'multimodal'" class="section">
                  <div v-if="formData" class="kb-multimodal-settings">
                    <div class="section-header">
                      <h2>{{ $t('knowledgeEditor.multimodal.title') }}</h2>
                      <p class="section-description">{{ $t('knowledgeEditor.multimodal.description') }}</p>
                    </div>

                    <div class="settings-group">
                      <!-- 多模态开关 -->
                      <div class="setting-row" data-guide="kb-create-multimodal-toggle">
                        <div class="setting-info">
                          <label>{{ $t('knowledgeEditor.advanced.multimodal.label') }}</label>
                          <p class="desc">{{ $t('knowledgeEditor.advanced.multimodal.description') }}</p>
                        </div>
                        <div class="setting-control">
                          <t-switch
                            v-model="formData.multimodalConfig.enabled"
                            @change="handleMultimodalToggle"
                            size="medium"
                          />
                        </div>
                      </div>

                      <!-- VLLM 模型选择（多模态启用时） -->
                      <div v-if="formData.multimodalConfig.enabled" class="setting-row"
                        data-guide="kb-create-multimodal-vllm">
                        <div class="setting-info">
                          <label>{{ $t('knowledgeEditor.advanced.multimodal.vllmLabel') }} <span class="required">*</span></label>
                          <p class="desc">{{ $t('knowledgeEditor.advanced.multimodal.vllmDescription') }}</p>
                        </div>
                        <div class="setting-control">
                          <ModelSelector
                            model-type="VLLM"
                            :selected-model-id="formData.multimodalConfig.vllmModelId"
                            :all-models="allModels"
                            @update:selected-model-id="handleMultimodalVLLMChange"
                            @add-model="handleAddVLLMModel"
                            :placeholder="$t('knowledgeEditor.advanced.multimodal.vllmPlaceholder')"
                          />
                        </div>
                      </div>

                      <div v-if="formData.multimodalConfig.enabled" class="setting-row">
                        <div class="setting-info">
                          <label>{{ $t('knowledgeEditor.advanced.multimodal.descriptionLanguageLabel') }}</label>
                          <p class="desc">{{ $t('knowledgeEditor.advanced.multimodal.descriptionLanguageDescription') }}</p>
                        </div>
                        <div class="setting-control">
                          <t-select v-model="formData.multimodalConfig.descriptionLanguage" clearable
                            :placeholder="$t('knowledgeEditor.advanced.multimodal.descriptionLanguageAuto')">
                            <t-option value="Chinese" :label="$t('language.zhCN')" />
                            <t-option value="English" :label="$t('language.enUS')" />
                            <t-option value="Korean" :label="$t('language.koKR')" />
                            <t-option value="Russian" :label="$t('language.ruRU')" />
                          </t-select>
                        </div>
                      </div>

                      <div v-if="formData.multimodalConfig.enabled" class="setting-row setting-row-vertical">
                        <div class="setting-info">
                          <label>{{ $t('knowledgeEditor.advanced.multimodal.customInstructionsLabel') }}</label>
                          <p class="desc">{{ $t('knowledgeEditor.advanced.multimodal.customInstructionsDescription') }}</p>
                        </div>
                        <div class="setting-control setting-control-full">
                          <t-textarea v-model="formData.multimodalConfig.customInstructions"
                            :placeholder="$t('knowledgeEditor.advanced.multimodal.customInstructionsPlaceholder')"
                            :maxlength="4000" :autosize="{ minRows: 3, maxRows: 8 }" />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 音频处理（ASR）设置 -->
                <div v-if="!isFAQ" v-show="currentSection === 'asr'" class="section">
                  <div v-if="formData" class="kb-multimodal-settings">
                    <div class="section-header">
                      <h2>{{ $t('knowledgeEditor.asr.title') }}</h2>
                      <p class="section-description">{{ $t('knowledgeEditor.asr.description') }}</p>
                    </div>

                    <div class="settings-group">
                      <!-- ASR 开关 -->
                      <div class="setting-row">
                        <div class="setting-info">
                          <label>{{ $t('knowledgeEditor.asr.label') }}</label>
                          <p class="desc">{{ $t('knowledgeEditor.asr.desc') }}</p>
                        </div>
                        <div class="setting-control">
                          <t-switch
                            v-model="formData.asrConfig.enabled"
                            size="medium"
                          />
                        </div>
                      </div>

                      <!-- ASR 模型选择 -->
                      <div v-if="formData.asrConfig.enabled" class="setting-row">
                        <div class="setting-info">
                          <label>{{ $t('knowledgeEditor.asr.modelLabel') }} <span class="required">*</span></label>
                          <p class="desc">{{ $t('knowledgeEditor.asr.modelDescription') }}</p>
                        </div>
                        <div class="setting-control">
                          <ModelSelector
                            model-type="ASR"
                            :selected-model-id="formData.asrConfig.modelId"
                            :all-models="allModels"
                            @update:selected-model-id="(val: string) => { if (formData) formData.asrConfig.modelId = val }"
                            @add-model="handleAddASRModel"
                            :placeholder="$t('knowledgeEditor.asr.modelPlaceholder')"
                          />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 知识图谱 -->
                <div v-if="!isFAQ && currentSection === 'graph'" class="section">
                  <GraphSettings
                    v-if="formData"
                    :graph-extract="formData.nodeExtractConfig"
                    :model-id="formData.modelConfig.llmModelId"
                    :all-models="allModels"
                    @update:graphExtract="handleNodeExtractUpdate"
                  />
                </div>

                <!-- 高级设置 -->
                <div v-if="!isFAQ" v-show="currentSection === 'advanced'" class="section">
                  <KBAdvancedSettings
                    ref="advancedSettingsRef"
                    v-if="formData"
                    :question-generation="formData.questionGenerationConfig"
                    :auto-tag="formData.autoTagConfig"
                    :rag-enabled="formData.indexingStrategy?.vectorEnabled || formData.indexingStrategy?.keywordEnabled"
                    :all-models="allModels"
                    :table-metadata-instructions="formData.chunkingConfig.tableMetadataInstructions"
                    @update:question-generation="handleQuestionGenerationUpdate"
                    @update:auto-tag="(value) => { if (formData) formData.autoTagConfig = value }"
                    @update:table-metadata-instructions="(value: string) => { if (formData) formData.chunkingConfig.tableMetadataInstructions = value }"
                  />
                </div>

                <!-- 数据源管理（仅编辑模式） -->
                <div v-if="editorMode === 'edit' && activeKbId && currentSection === 'datasource'" class="section">
                  <DataSourceSettings :kb-id="activeKbId" @count="dsCount = $event" />
                </div>

                <!-- 活动记录（仅编辑模式，KB 所属租户内 Owner/Admin） -->
                <div v-if="editorMode === 'edit' && activeKbId && canViewActivity && currentSection === 'activity'" class="section">
                  <KnowledgeBaseActivitySettings :kb-id="activeKbId" :active="currentSection === 'activity'" />
                </div>
              </div>

              <!-- 保存按钮 -->
              <div class="settings-footer">
                <p v-if="isPostCreateSession" class="settings-footer-note">
                  <t-icon name="check-circle-filled" class="settings-footer-note__icon" />
                  <span>
                    <strong>{{ $t('knowledgeEditor.postCreateHint.title') }}</strong>
                    {{ $t('knowledgeEditor.postCreateHint.footer') }}
                  </span>
                </p>
                <div class="settings-footer-actions">
                  <t-button theme="default" variant="outline" @click="handleClose">
                    {{ $t('common.cancel') }}
                  </t-button>
                  <t-button theme="primary" data-guide="kb-create-submit" @click="handleSubmit" :loading="saving"
                    :disabled="loading">
                    {{ saveButtonLabel }}
                  </t-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <KbCreateContextualGuide :when="visible && editorMode === 'create'" :is-faq="isFAQ"
    :needs-embedding="kbCreateNeedsEmbedding" />
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import KbCreateContextualGuide from '@/components/KbCreateContextualGuide.vue'
import { KB_EDITOR_FOCUS_SECTION_EVENT, markContextualGuideDone } from '@/config/contextualGuides'
import { MessagePlugin, DialogPlugin } from 'tdesign-vue-next'
import { createKnowledgeBase, getKnowledgeBaseById, listKnowledgeFiles, updateKnowledgeBase, rebuildKBIndex } from '@/api/knowledge-base'
import { updateKBConfig, type KBModelConfigRequest } from '@/api/initialization'
import { useChatResourcesStore } from '@/stores/chatResources'
import { selectInitialModelId } from '@/utils/modelDefaults'
import { copyWithToast } from '@/utils/clipboard'
import { useEditorResourcesStore } from '@/stores/editorResources'
import { useUIStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import KBModelConfig from './settings/KBModelConfig.vue'
import KBParserSettings from './settings/KBParserSettings.vue'
import KBStorageSettings from './settings/KBStorageSettings.vue'
import KBChunkingSettings from './settings/KBChunkingSettings.vue'
import KBVectorStoreSettings from './settings/KBVectorStoreSettings.vue'
import KBAdvancedSettings from './settings/KBAdvancedSettings.vue'
import ModelSelector from '@/components/ModelSelector.vue'
import GraphSettings from './settings/GraphSettings.vue'
import DataSourceSettings from './settings/DataSourceSettings.vue'
import KnowledgeBaseActivitySettings from './settings/KnowledgeBaseActivitySettings.vue'
import { useI18n } from 'vue-i18n'

const uiStore = useUIStore()
const authStore = useAuthStore()
const chatResources = useChatResourcesStore()
const editorResources = useEditorResourcesStore()
const { t } = useI18n()

// Props
const props = defineProps<{
  visible: boolean
  mode: 'create' | 'edit'
  kbId?: string
  initialType?: 'document' | 'faq'
}>()

// Emits
const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'success', kbId: string): void
}>()

/** 首次保存创建成功后留在弹窗内，继续配置共享等设置 */
const savedKbId = ref<string | null>(null)
const editorMode = computed(() => (savedKbId.value ? 'edit' : props.mode))
const activeKbId = computed(() => savedKbId.value ?? props.kbId)
const isPostCreateSession = computed(() => !!savedKbId.value)
const saveButtonLabel = computed(() =>
  editorMode.value === 'create'
    ? t('knowledgeEditor.buttons.create')
    : t('knowledgeEditor.buttons.saveAndClose')
)

const copyKbId = async () => {
  await copyWithToast(activeKbId.value, 'common.copied')
}

const currentSection = ref<string>('basic')

const onKbEditorFocusSection = (event: Event) => {
  const section = (event as CustomEvent<{ section?: string }>).detail?.section
  if (section) {
    currentSection.value = section
  }
}

onMounted(() => {
  window.addEventListener(KB_EDITOR_FOCUS_SECTION_EVENT, onKbEditorFocusSection)
})

onBeforeUnmount(() => {
  window.removeEventListener(KB_EDITOR_FOCUS_SECTION_EVENT, onKbEditorFocusSection)
})
const saving = ref(false)
const loading = ref(false)
const allModels = ref<any[]>([])
const hasFiles = ref(false)
const initialStorageProvider = ref<string>('')
/** Tenant-wide default from Settings → Storage engine (used when creating a KB). */
const tenantDefaultStorageProvider = ref('local')
const initialIndexingStrategy = ref<any>(null)
const dsCount = ref(0)
// Identifier of the user who created this KB. Empty for older rows
// that predate per-KB ownership tracking; those KBs are tenant-owned
// and only tenant Admin+ may manage them.
const kbCreatorId = ref<string>('')
const kbTenantId = ref<number>(0)

const isKbOwner = computed(() => {
  const userId = authStore.user?.id || ''
  return Boolean(kbCreatorId.value && userId && kbCreatorId.value === userId)
})

const canViewActivity = computed(() => {
  if (editorMode.value !== 'edit' || !activeKbId.value) return false
  if (Number(kbTenantId.value || 0) !== Number(authStore.currentTenantId || 0)) return false
  return isKbOwner.value || authStore.hasRole('admin')
})
// 用户是否在分块设置中手动改过任何值。一旦为 true，就不再根据索引策略自动调整默认分块参数。
const chunkingDirty = ref(false)

// 仅 Wiki 索引模式下的分块预设：更大 chunk、无 overlap、关闭父子分块。
// 该预设只在「创建模式」下、且用户尚未手动调整分块参数时生效，避免覆盖既有 KB 的配置。
const WIKI_ONLY_CHUNKING_PRESET = {
  chunkSize: 2048,
  chunkOverlap: 0,
  enableParentChild: false,
} as const

// Non-Wiki-only fallback. Mirrors chunker.DefaultChunkSize and
// DefaultChunkOverlap on the backend so a freshly created KB uses
// the same numbers whether the editor sets them or the splitter
// falls back to its package defaults.
const DEFAULT_CHUNKING_PRESET = {
  chunkSize: 512,
  chunkOverlap: 80,
  enableParentChild: true,
} as const

const navItems = computed(() => {
  const items: { key: string; icon: string; label: string; badge?: number }[] = [
    { key: 'basic', icon: 'info-circle', label: t('knowledgeEditor.sidebar.basic') },
    { key: 'models', icon: 'control-platform', label: t('knowledgeEditor.sidebar.models') },
    // VectorStore binding section — present in both create and edit
    // modes. Create mode shows a dropdown; edit mode shows the bound
    // store read-only with an immutability hint.
    { key: 'vectorStore', icon: 'data-base', label: t('knowledgeEditor.sidebar.vectorStore') }
  ]
  if (formData.value?.type === 'faq') {
    items.push({ key: 'faq', icon: 'help-circle', label: t('knowledgeEditor.sidebar.faq') })
  } else {
    items.push(
      { key: 'parser', icon: 'file-search', label: t('settings.parserEngine') },
      { key: 'multimodal', icon: 'image', label: t('knowledgeEditor.sidebar.multimodal') },
      { key: 'asr', icon: 'sound', label: t('knowledgeEditor.sidebar.asr') },
      { key: 'storage', icon: 'cloud', label: t('knowledgeEditor.sidebar.storage') },
      { key: 'chunking', icon: 'file-copy', label: t('knowledgeEditor.sidebar.chunking') },
      { key: 'graph', icon: 'chart-bubble', label: t('knowledgeEditor.sidebar.graph') },
      { key: 'advanced', icon: 'setting', label: t('knowledgeEditor.sidebar.advanced') }
    )
    if (editorMode.value === 'edit' && activeKbId.value) {
      items.push({ key: 'datasource', icon: 'cloud-download', label: t('knowledgeEditor.sidebar.datasource'), badge: dsCount.value || undefined })
    }
  }
  if (canViewActivity.value) {
    items.push({ key: 'activity', icon: 'history', label: t('knowledgeEditor.sidebar.activity') })
  }
  return items
})

// 左侧导航分组（与 AgentEditorModal 对齐）
const navGroups = computed(() => {
  const itemMap = new Map(navItems.value.map((item) => [item.key, item]))
  const pickItems = (keys: string[]) =>
    keys.map((key) => itemMap.get(key)).filter(Boolean) as typeof navItems.value
  return [
    {
      key: 'basic',
      label: t('knowledgeEditor.navGroups.basic'),
      items: pickItems(['basic', 'models', 'vectorStore', 'faq']),
    },
    {
      key: 'processing',
      label: t('knowledgeEditor.navGroups.processing'),
      items: pickItems(['parser', 'chunking', 'multimodal', 'asr', 'graph', 'advanced']),
    },
    {
      key: 'data',
      label: t('knowledgeEditor.navGroups.data'),
      items: pickItems(['storage', 'datasource']),
    },
    {
      key: 'management',
      label: t('knowledgeEditor.navGroups.management'),
      items: pickItems(['activity']),
    },
  ].filter((group) => group.items.length > 0)
})

// 模型配置引用
const modelConfigRef = ref<InstanceType<typeof KBModelConfig>>()
const advancedSettingsRef = ref<InstanceType<typeof KBAdvancedSettings>>()

// 表单数据
const formData = ref<any>(null)
const isFAQ = computed(() => formData.value?.type === 'faq')

const kbCreateNeedsEmbedding = computed(() => {
  if (!formData.value || formData.value.type === 'faq') return false
  const s = formData.value.indexingStrategy
  return Boolean(s?.vectorEnabled || s?.keywordEnabled)
})

const applyDefaultModelsIfEmpty = () => {
  if (!formData.value || editorMode.value !== 'create') return
  const chatModelId = selectInitialModelId(allModels.value, 'KnowledgeQA')
  const embeddingModelId = selectInitialModelId(allModels.value, 'Embedding')
  if (!formData.value.modelConfig.llmModelId && chatModelId) {
    formData.value.modelConfig.llmModelId = chatModelId
  }
  if (!formData.value.modelConfig.embeddingModelId && embeddingModelId) {
    formData.value.modelConfig.embeddingModelId = embeddingModelId
  }
}

watch(
  () => formData.value?.type,
  (newType, oldType) => {
    if (!formData.value) return
    if (newType === 'faq') {
      if (!formData.value.faqConfig) {
        formData.value.faqConfig = { indexMode: 'question_only', questionIndexMode: 'separate' }
      }
      if (!['basic', 'models', 'faq'].includes(currentSection.value)) {
        currentSection.value = 'faq'
      }
    } else if (oldType === 'faq' && currentSection.value === 'faq') {
      currentSection.value = 'basic'
    }
  }
)

// 初始化表单数据
const initFormData = (type: 'document' | 'faq' = 'document') => {
  return {
    type,
    name: '',
    description: '',
    faqConfig: {
      indexMode: 'question_only',
      questionIndexMode: 'separate'
    },
    modelConfig: {
      llmModelId: '',
      embeddingModelId: '',
      wikiSynthesisModelId: '',
    },
    chunkingConfig: {
      chunkSize: 512,
      // 80 ≈ 15% of chunkSize — community-recommended sweet spot.
      // Aligned with chunker.DefaultChunkOverlap on the backend.
      chunkOverlap: 80,
      separators: ['\n\n', '\n', '。', '！', '？', ';', '；'],
      parserEngineRules: undefined as any,
      enableParentChild: true,
      parentChunkSize: 4096,
      childChunkSize: 384,
      // New KBs default to the adaptive auto-strategy. User can change in the UI.
      strategy: 'auto' as string,
      tokenLimit: 0,
      languages: [] as string[],
      tableMetadataInstructions: ''
    },
    storageBackendId: '' as string,
    storageProvider: '' as string,
    multimodalConfig: {
      enabled: false,
      vllmModelId: '',
      descriptionLanguage: '',
      customInstructions: ''
    },
    asrConfig: {
      enabled: false,
      modelId: '',
      language: ''
    },
    nodeExtractConfig: {
      enabled: false,
      text: '',
      tags: [] as string[],
      nodes: [] as Array<{
        name: string
        attributes: string[]
      }>,
      relations: [] as Array<{
        node1: string
        node2: string
        type: string
      }>,
      customInstructions: ''
    },
    questionGenerationConfig: {
      enabled: true,
      questionCount: 3,
      customInstructions: ''
    },
    autoTagConfig: {
      enabled: false,
      modelId: '',
      maxTags: 3,
      skipIfTagged: true
    },
    wikiConfig: {
      synthesisModelId: '',
      maxPagesPerIngest: 0,
      extractionGranularity: 'standard' as 'focused' | 'standard' | 'exhaustive',
      contentInstructions: '',
      extractionInstructions: '',
    },
    indexingStrategy: {
      vectorEnabled: true,
      keywordEnabled: true,
      wikiEnabled: false,
      graphEnabled: false,
    },
    // Vector-store binding. Empty string means "use the env-configured
    // store"; create mode defaults to that, edit mode loads the
    // existing binding from the KB response below.
    vectorStoreId: '' as string,
    vectorStoreInfo: {
      source: undefined as string | undefined,
      name: undefined as string | undefined,
      engineType: undefined as string | undefined,
      status: undefined as string | undefined,
    },
  }
}

// 加载所有模型
const loadAllModels = async (force = false) => {
  try {
    await chatResources.ensureModels(force)
    allModels.value = chatResources.allModels || []
  } catch (error) {
    console.error('Failed to load model list:', error)
    MessagePlugin.error(t('knowledgeEditor.messages.loadModelsFailed'))
    allModels.value = []
  }
}

let kbEditorLoadGeneration = 0

const isCurrentKBLoad = (generation: number, kbId: string) => (
  generation === kbEditorLoadGeneration
  && props.visible
  && activeKbId.value === kbId
)

// 加载知识库数据（编辑模式）
const loadKBData = async (
  kbIdOverride?: string,
  generation = kbEditorLoadGeneration,
) => {
  const kbId = kbIdOverride ?? activeKbId.value
  if (editorMode.value !== 'edit' || !kbId) return
  
  loading.value = true
  try {
    const [kbInfo, filesResult] = await Promise.all([
      getKnowledgeBaseById(kbId),
      listKnowledgeFiles(kbId, { page: 1, pageSize: 1 })
    ])

    if (!isCurrentKBLoad(generation, kbId)) return
    
    if (!kbInfo || !kbInfo.data) {
      throw new Error(t('knowledgeEditor.messages.notFound'))
    }

    const kb = kbInfo.data
    hasFiles.value = (filesResult as any)?.total > 0
    kbCreatorId.value = (kb as any).creatorId || ''
    kbTenantId.value = Number((kb as any).tenant_id || 0)

    // 设置表单数据
    const kbType = (kb.type as 'document' | 'faq') || 'document'
    formData.value = {
      type: kbType,
      name: kb.name || '',
      description: kb.description || '',
      faqConfig: {
        indexMode: kb.faqConfig?.index_mode || 'question_only',
        questionIndexMode: kb.faqConfig?.question_index_mode || 'separate'
      },
      modelConfig: {
        llmModelId: kb.summaryModelId || '',
        embeddingModelId: kb.embeddingModelId || '',
        wikiSynthesisModelId: kb.wikiConfig?.synthesis_model_id || ''
      },
      chunkingConfig: {
        chunkSize: kb.chunkingConfig?.chunkSize || 512,
        // Fallback only used when the loaded KB has no chunk_overlap stored.
        // Aligned with chunker.DefaultChunkOverlap on the backend.
        chunkOverlap: kb.chunkingConfig?.chunkOverlap || 80,
        separators: kb.chunkingConfig?.separators || ['\n\n', '\n', '。', '！', '？', ';', '；'],
        parserEngineRules: kb.chunkingConfig?.parserEngineRules || undefined,
        enableParentChild: kb.chunkingConfig?.enableParentChild || false,
        parentChunkSize: kb.chunkingConfig?.parentChunkSize || 4096,
        childChunkSize: kb.chunkingConfig?.childChunkSize || 384,
        // Existing KBs without strategy field render as empty (= legacy behavior).
        // The user has to actively pick a value to opt in to the new tiers.
        strategy: kb.chunkingConfig?.strategy || '',
        tokenLimit: kb.chunkingConfig?.tokenLimit || 0,
        languages: kb.chunkingConfig?.languages || [],
        tableMetadataInstructions: kb.chunkingConfig?.tableMetadataInstructions || ''
      },
      storageBackendId: (kb.storageBackendId || '') as string,
      storageProvider: (kb.storageProvider || 'local') as string,
      multimodalConfig: {
        enabled: !!kb.vlmConfig?.enabled,
        vllmModelId: kb.vlmConfig?.modelId || '',
        descriptionLanguage: kb.vlmConfig?.descriptionLanguage || '',
        customInstructions: kb.vlmConfig?.customInstructions || ''
      },
      asrConfig: {
        enabled: !!kb.asrConfig?.enabled,
        modelId: kb.asrConfig?.modelId || '',
        language: kb.asrConfig?.language || ''
      },
      nodeExtractConfig: {
        enabled: kb.extractConfig?.enabled || false,
        text: kb.extractConfig?.text || '',
        tags: kb.extractConfig?.tags || [],
        nodes: (kb.extractConfig?.nodes || []).map((node: any) => ({
          name: node.name,
          attributes: node.attributes || []
        })),
        relations: kb.extractConfig?.relations || [],
        customInstructions: kb.extractConfig?.custom_instructions || ''
      },
      questionGenerationConfig: {
        enabled: kb.questionGenerationConfig?.enabled || false,
        questionCount: kb.questionGenerationConfig?.question_count || 3,
        customInstructions: kb.questionGenerationConfig?.custom_instructions || ''
      },
      autoTagConfig: {
        enabled: kb.autoTagConfig?.enabled || false,
        modelId: kb.autoTagConfig?.model_id || '',
        maxTags: kb.autoTagConfig?.max_tags || 3,
        // Absent on knowledge bases saved before the toggle existed; the
        // backend treats that as "skip", so mirror it here.
        skipIfTagged: kb.autoTagConfig?.skip_if_tagged ?? true
      },
      wikiConfig: {
        synthesisModelId: kb.wikiConfig?.synthesis_model_id || '',
        maxPagesPerIngest: kb.wikiConfig?.max_pages_per_ingest || 0,
        extractionGranularity: (
          kb.wikiConfig?.extraction_granularity === 'focused' ||
          kb.wikiConfig?.extraction_granularity === 'exhaustive'
            ? kb.wikiConfig.extraction_granularity
            : 'standard'
        ) as 'focused' | 'standard' | 'exhaustive',
        contentInstructions: kb.wikiConfig?.content_instructions || '',
        extractionInstructions: kb.wikiConfig?.extraction_instructions || '',
      },
      indexingStrategy: {
        vectorEnabled: kb.indexingStrategy?.vectorEnabled ?? true,
        keywordEnabled: kb.indexingStrategy?.keywordEnabled ?? true,
        wikiEnabled: kb.indexingStrategy?.wikiEnabled ?? false,
        graphEnabled: kb.indexingStrategy?.graphEnabled ?? false,
      },
      // Vector-store binding. vectorStoreId is editor-only state; it
      // is only included in the create request, never the update
      // request, because the binding is immutable after creation.
      // vectorStoreInfo carries the read-only display fields that the
      // edit view renders below; they come straight from the KB
      // response.
      vectorStoreId: '',
      vectorStoreInfo: {
        source: kb.vectorStore?.source,
        name: kb.vectorStore?.name,
        engineType: kb.vectorStore?.engineType,
        status: kb.vectorStore?.status,
      },
    }
    initialStorageProvider.value = formData.value.storageProvider
    initialIndexingStrategy.value = { ...formData.value.indexingStrategy }
  } catch (error) {
    if (!isCurrentKBLoad(generation, kbId)) return
    console.error('Failed to load knowledge base data:', error)
    MessagePlugin.error(t('knowledgeEditor.messages.loadDataFailed'))
    handleClose()
  } finally {
    if (isCurrentKBLoad(generation, kbId)) {
      loading.value = false
    }
  }
}

// 处理配置更新
const handleModelConfigUpdate = (config: any) => {
  if (formData.value) {
    formData.value.modelConfig = { ...config }
  }
}

// 粒度选择器：从 formData.wikiConfig 读出并规范化，未知值回退到 'standard'，
// 与后端 WikiExtractionGranularity.Normalize() 的契约保持一致。
const resolvedGranularity = computed<'focused' | 'standard' | 'exhaustive'>(() => {
  const g = formData.value?.wikiConfig?.extractionGranularity
  if (g === 'focused' || g === 'standard' || g === 'exhaustive') {
    return g
  }
  return 'standard'
})

const granularityHint = computed<string>(() => {
  switch (resolvedGranularity.value) {
    case 'focused':
      return t('knowledgeEditor.wiki.granularityFocusedHint')
    case 'exhaustive':
      return t('knowledgeEditor.wiki.granularityExhaustiveHint')
    default:
      return t('knowledgeEditor.wiki.granularityStandardHint')
  }
})

const handleGranularityChange = (value: string | number | boolean) => {
  if (!formData.value) return
  const next: 'focused' | 'standard' | 'exhaustive' =
    value === 'focused' || value === 'exhaustive'
      ? (value as 'focused' | 'exhaustive')
      : 'standard'
  formData.value.wikiConfig = {
    ...formData.value.wikiConfig,
    extractionGranularity: next,
  }
}

const isIndexingLocked = computed(() => editorMode.value === 'edit' && hasFiles.value)

const toggleVectorIndexing = () => {
  if (!formData.value) return
  if (isIndexingLocked.value) return
  const next = !formData.value.indexingStrategy.vectorEnabled
  formData.value.indexingStrategy.vectorEnabled = next
  formData.value.indexingStrategy.keywordEnabled = next
}

const toggleWikiIndexing = () => {
  if (!formData.value) return
  if (isIndexingLocked.value) return
  formData.value.indexingStrategy.wikiEnabled = !formData.value.indexingStrategy.wikiEnabled
}

const handleChunkingConfigUpdate = (config: any) => {
  if (formData.value) {
    formData.value.chunkingConfig = { ...config }
    // 用户已经手动触达分块设置，后续索引策略切换不再覆盖这些值
    chunkingDirty.value = true
  }
}

// 判断当前是否为「仅 Wiki 索引」：只开了 Wiki，关了向量/关键词检索
const isWikiOnlyStrategy = computed(() => {
  const s = formData.value?.indexingStrategy
  if (!s) return false
  return !!s.wikiEnabled && !s.vectorEnabled && !s.keywordEnabled
})

// 仅在创建模式、用户未改过分块设置时，随索引策略自动应用/撤销 Wiki-only 预设。
// 编辑模式严格保持后端已有配置不变，避免误改。
watch(isWikiOnlyStrategy, (wikiOnly) => {
  if (editorMode.value !== 'create') return
  if (!formData.value) return
  if (chunkingDirty.value) return
  const preset = wikiOnly ? WIKI_ONLY_CHUNKING_PRESET : DEFAULT_CHUNKING_PRESET
  formData.value.chunkingConfig = {
    ...formData.value.chunkingConfig,
    ...preset,
  }
})

const handleParserEngineRulesUpdate = (rules: any[]) => {
  if (formData.value) {
    formData.value.chunkingConfig.parserEngineRules = rules?.length ? rules : undefined
  }
}

const handleMultimodalToggle = () => {
  if (formData.value && !formData.value.multimodalConfig.enabled) {
    formData.value.multimodalConfig.vllmModelId = ''
  }
}

const handleMultimodalVLLMChange = (modelId: string) => {
  if (formData.value) {
    formData.value.multimodalConfig.vllmModelId = modelId
  }
}

const handleAddVLLMModel = () => {
  uiStore.openSettings('models', 'vllm')
}

const handleAddASRModel = () => {
  uiStore.openSettings('models', 'asr')
}

const handleAddWikiModel = () => {
  uiStore.openSettings('models', 'knowledgeqa')
}

const handleStorageProviderUpdate = (value: string) => {
  if (formData.value) {
    formData.value.storageProvider = editorMode.value === 'create'
      ? editorResources.resolveUsableStorageProvider(value || tenantDefaultStorageProvider.value)
      : (value || tenantDefaultStorageProvider.value || 'local')
  }
}

const handleStorageBackendUpdate = (value: string) => {
  if (formData.value) {
    formData.value.storageBackendId = value
  }
}

async function loadTenantDefaultStorageProvider(force = false) {
  try {
    await editorResources.ensureStorageEngine(force)
    tenantDefaultStorageProvider.value = editorResources.resolveUsableStorageProvider(
      editorResources.storageConfig?.default_provider,
    )
  } catch {
    tenantDefaultStorageProvider.value = editorResources.resolveUsableStorageProvider()
  }
}

/** Resolved storage provider for create payload (never silently default to local before tenant config loads). */
function resolvedStorageProvider(): string {
  const explicit = formData.value?.storageProvider?.trim()
  if (editorMode.value === 'create') {
    return editorResources.resolveUsableStorageProvider(explicit || tenantDefaultStorageProvider.value)
  }
  if (explicit) return explicit
  return tenantDefaultStorageProvider.value || 'local'
}

const handleVectorStoreIdUpdate = (id: string) => {
  if (formData.value) {
    // Empty string here means "use system default" (env-store fallback).
    // The create-payload assembly below converts this back to `omit` so
    // the backend stores NULL — keeping the wire shape identical to
    // pre-Phase-2 clients.
    formData.value.vectorStoreId = id || ''
  }
}

const handleQuestionGenerationUpdate = (config: any) => {
  if (formData.value) {
    formData.value.questionGenerationConfig = { ...config }
  }
}

const handleNodeExtractUpdate = (config: any) => {
  if (formData.value) {
    formData.value.nodeExtractConfig = { ...config }
  }
}

// 验证表单
const validateForm = (): boolean => {
  if (!formData.value) return false

  // 验证基本信息
  if (!formData.value.name || !formData.value.name.trim()) {
    MessagePlugin.warning(t('knowledgeEditor.messages.nameRequired'))
    currentSection.value = 'basic'
    return false
  }

  // 验证索引策略 — 文档类型至少需要开启一种
  if (formData.value.type !== 'faq') {
    const s = formData.value.indexingStrategy
    if (s && !s.vectorEnabled && !s.keywordEnabled && !s.wikiEnabled && !s.graphEnabled) {
      MessagePlugin.warning(t('knowledgeEditor.indexing.atLeastOne'))
      currentSection.value = 'basic'
      return false
    }
  }

  // 验证模型配置 - embedding 模型仅在检索索引启用时必须
  const needsEmbedding = formData.value.indexingStrategy?.vectorEnabled || formData.value.indexingStrategy?.keywordEnabled
  if (needsEmbedding && !formData.value.modelConfig.embeddingModelId) {
    MessagePlugin.warning(t('knowledgeEditor.indexing.embeddingRequired'))
    currentSection.value = 'models'
    return false
  }

  if (!formData.value.modelConfig.llmModelId) {
    MessagePlugin.warning(t('knowledgeEditor.messages.summaryRequired'))
    currentSection.value = 'models'
    return false
  }

  // 验证多模态配置（如果启用）
  if (formData.value.multimodalConfig.enabled && !formData.value.multimodalConfig.vllmModelId) {
    MessagePlugin.warning(t('knowledgeEditor.messages.multimodalInvalid'))
    currentSection.value = 'multimodal'
    return false
  }

  if (formData.value.type === 'faq' && !formData.value.faqConfig?.indexMode) {
    MessagePlugin.warning(t('knowledgeEditor.messages.indexModeRequired'))
    currentSection.value = 'faq'
    return false
  }

  return true
}

// 构建提交数据
const buildSubmitData = () => {
  if (!formData.value) return null

  // 新契约（docs/knowledge-api-contract-v1.md）：创建请求为 camelCase DTO，
  // 分块/索引/多模态/ASR 走类型化视图，JsonNode 透传配置（FAQ/Wiki/问题生成等）内键保持原样。
  const data: any = {
    name: formData.value.name,
    description: formData.value.description,
    type: formData.value.type,
    chunkingConfig: {
      chunkSize: formData.value.chunkingConfig.chunkSize,
      chunkOverlap: formData.value.chunkingConfig.chunkOverlap,
      separators: formData.value.chunkingConfig.separators,
      enableParentChild: formData.value.chunkingConfig.enableParentChild,
      parentChunkSize: formData.value.chunkingConfig.parentChunkSize,
      childChunkSize: formData.value.chunkingConfig.childChunkSize,
      // 自适应分块字段恒发送（含空值），用户可显式清空
      strategy: formData.value.chunkingConfig.strategy ?? '',
      tokenLimit: formData.value.chunkingConfig.tokenLimit ?? 0,
      languages: formData.value.chunkingConfig.languages ?? [],
      tableMetadataInstructions: formData.value.chunkingConfig.tableMetadataInstructions || '',
      parserEngineRules: formData.value.chunkingConfig.parserEngineRules?.length
        ? formData.value.chunkingConfig.parserEngineRules
        : null
    },
    embeddingModelId: formData.value.modelConfig.embeddingModelId,
    summaryModelId: formData.value.modelConfig.llmModelId
  }

  // Vector-store binding. Only attach the field when the user actively
  // selected a non-default store. The server treats an empty string as
  // NULL, but keeping the field absent on the wire matches what a
  // client that doesn't know about this binding would send — which
  // makes A/B response diffs easier to read.
  if (formData.value.vectorStoreId) {
    data.vectorStoreId = formData.value.vectorStoreId
  }

  // 添加多模态配置
  data.vlmConfig = {
    enabled: formData.value.multimodalConfig.enabled,
    modelId: formData.value.multimodalConfig.enabled
      ? (formData.value.multimodalConfig.vllmModelId || '')
      : '',
    descriptionLanguage: formData.value.multimodalConfig.descriptionLanguage || '',
    customInstructions: formData.value.multimodalConfig.customInstructions || ''
  }

  // 添加ASR语音识别配置
  data.asrConfig = {
    enabled: formData.value.asrConfig?.enabled || false,
    modelId: formData.value.asrConfig?.enabled
      ? (formData.value.asrConfig?.modelId || '')
      : '',
    language: formData.value.asrConfig?.language || ''
  }

  // storage_backend_id is authoritative. Keep provider projection for old clients
  // and for rolling upgrades where a node has not picked up the new schema yet.
  if (formData.value.storageBackendId) {
    data.storageBackendId = formData.value.storageBackendId
  }
  // 存储提供方改为字符串字段；storage_config / storage_provider_config 已退役
  data.storageProvider = resolvedStorageProvider()

  // 添加知识图谱配置 — now synced via indexingStrategy.graphEnabled
  // extract_config is sent below along with indexing_strategy

  // 添加问题生成配置
  if (formData.value.questionGenerationConfig?.enabled) {
    data.questionGenerationConfig = {
      enabled: true,
      question_count: formData.value.questionGenerationConfig.questionCount || 3,
      custom_instructions: formData.value.questionGenerationConfig.customInstructions || ''
    }
  } else {
    data.questionGenerationConfig = {
      enabled: false,
      question_count: 3,
      custom_instructions: formData.value.questionGenerationConfig?.customInstructions || ''
    }
  }

  data.autoTagConfig = {
    enabled: formData.value.autoTagConfig?.enabled || false,
    model_id: formData.value.autoTagConfig?.modelId || '',
    max_tags: formData.value.autoTagConfig?.maxTags || 3,
    skip_if_tagged: formData.value.autoTagConfig?.skipIfTagged ?? true
  }

  if (formData.value.type === 'faq') {
    data.faqConfig = {
      index_mode: formData.value.faqConfig?.indexMode || 'question_only',
      question_index_mode: formData.value.faqConfig?.questionIndexMode || 'separate'
    }
  }

  // Wiki enablement is carried solely by indexing_strategy.wikiEnabled.
  // wiki_config only holds wiki-specific tunables.
  if (formData.value.type !== 'faq') {
    data.wikiConfig = {
      synthesis_model_id: formData.value.modelConfig?.wikiSynthesisModelId || '',
      max_pages_per_ingest: formData.value.wikiConfig?.maxPagesPerIngest || 0,
      extraction_granularity: formData.value.wikiConfig?.extractionGranularity || 'standard',
      content_instructions: formData.value.wikiConfig?.contentInstructions || '',
      extraction_instructions: formData.value.wikiConfig?.extractionInstructions || '',
    }
  }

  // Send indexing strategy
  if (formData.value.type !== 'faq') {
    data.indexingStrategy = {
      vectorEnabled: formData.value.indexingStrategy?.vectorEnabled ?? true,
      keywordEnabled: formData.value.indexingStrategy?.keywordEnabled ?? true,
      wikiEnabled: formData.value.indexingStrategy?.wikiEnabled ?? false,
      graphEnabled: formData.value.indexingStrategy?.graphEnabled ?? false,
    }
  }

  // Always persist extract_config so the toggle state from GraphSettings is saved,
  // regardless of whether the graph indexing strategy is currently enabled.
  if (formData.value.nodeExtractConfig) {
    data.extractConfig = {
      enabled: !!formData.value.nodeExtractConfig.enabled,
      text: formData.value.nodeExtractConfig.text || '',
      tags: formData.value.nodeExtractConfig.tags || [],
      nodes: formData.value.nodeExtractConfig.nodes || [],
      relations: formData.value.nodeExtractConfig.relations || [],
      custom_instructions: formData.value.nodeExtractConfig.customInstructions || ''
    }
  }

  return data
}

// 提交表单
const handleSubmit = async () => {
  if (!validateForm()) {
    return
  }

  // 编辑模式下，若已有文件且存储引擎发生了变化，弹窗确认
  if (
    editorMode.value === 'edit' &&
    hasFiles.value &&
    formData.value &&
    initialStorageProvider.value &&
    formData.value.storageProvider !== initialStorageProvider.value
  ) {
    const dialog = DialogPlugin.confirm({
      header: t('common.confirm'),
      body: t('knowledgeEditor.messages.storageChangeConfirm'),
      confirmBtn: t('common.confirm'),
      cancelBtn: t('common.cancel'),
      onConfirm: () => {
        dialog.destroy()
        doSubmit()
      },
      onCancel: () => {
        dialog.destroy()
      },
    })
    return
  }

  doSubmit()
}

const doSubmit = async () => {
  saving.value = true
  try {
    const data = buildSubmitData()
    if (!data) {
      throw new Error(t('knowledgeEditor.messages.buildDataFailed'))
    }

    if (editorMode.value === 'create') {
      // 创建模式：一次性创建知识库及所有配置
      const created: any = await createKnowledgeBase(data)
      if (!created?.id) {
        throw new Error(created?.message || t('knowledgeEditor.messages.createFailed'))
      }
      const createdKbId = created.id as string
      savedKbId.value = createdKbId
      currentSection.value = 'basic'
      await loadKBData(createdKbId)
      MessagePlugin.success(t('knowledgeEditor.messages.createSuccess'))
      markContextualGuideDone('kbCreate')
      emit('success', createdKbId)
    } else {
      // 编辑模式：分别更新基本信息和配置
      const kbId = activeKbId.value
      if (!kbId) {
        throw new Error(t('knowledgeEditor.messages.missingId'))
      }

      // 1. 更新基本信息（名称、描述）和 FAQ/Wiki 配置
      const updateConfig: any = {}
      if (formData.value.type === 'faq' && formData.value.faqConfig) {
        updateConfig.faq_config = {
          index_mode: formData.value.faqConfig.indexMode || 'question_only',
          question_index_mode: formData.value.faqConfig.questionIndexMode || 'separate'
        }
      }
      if (formData.value.wikiConfig && formData.value.type !== 'faq') {
        updateConfig.wiki_config = {
          synthesis_model_id: formData.value.modelConfig?.wikiSynthesisModelId || '',
          max_pages_per_ingest: formData.value.wikiConfig.maxPagesPerIngest || 0,
          extraction_granularity: formData.value.wikiConfig.extractionGranularity || 'standard',
          content_instructions: formData.value.wikiConfig.contentInstructions || '',
          extraction_instructions: formData.value.wikiConfig.extractionInstructions || '',
        }
      }
      if (formData.value.type !== 'faq') {
        updateConfig.auto_tag_config = data.autoTagConfig
        updateConfig.indexing_strategy = {
          vector_enabled: formData.value.indexingStrategy?.vectorEnabled ?? true,
          keyword_enabled: formData.value.indexingStrategy?.keywordEnabled ?? true,
          wiki_enabled: formData.value.indexingStrategy?.wikiEnabled ?? false,
          graph_enabled: formData.value.indexingStrategy?.graphEnabled ?? false,
        }
      }
      await updateKnowledgeBase(kbId, {
        name: data.name,
        description: data.description,
        config: updateConfig
      })

      // 2. 更新完整配置（模型、分块、多模态、存储引擎、知识图谱等）
      const config: KBModelConfigRequest = {
        llmModelId: data.summaryModelId,
        embeddingModelId: data.embeddingModelId,
        vlmConfig: data.vlmConfig,
        asrConfig: data.asrConfig,
        documentSplitting: {
          chunkSize: data.chunkingConfig.chunkSize,
          chunkOverlap: data.chunkingConfig.chunkOverlap,
          separators: data.chunkingConfig.separators,
          parserEngineRules: data.chunkingConfig.parserEngineRules || undefined,
          enableParentChild: data.chunkingConfig.enableParentChild || false,
          parentChunkSize: data.chunkingConfig.parentChunkSize || 4096,
          childChunkSize: data.chunkingConfig.childChunkSize || 384,
          // Always send strategy / tokenLimit / languages — backend treats
          // empty/0/[] as a valid clear, so we must include them in the
          // payload to let users reset back to defaults.
          strategy: formData.value?.chunkingConfig.strategy ?? '',
          tokenLimit: formData.value?.chunkingConfig.tokenLimit ?? 0,
          languages: formData.value?.chunkingConfig.languages ?? [],
          tableMetadataInstructions: formData.value?.chunkingConfig.tableMetadataInstructions ?? ''
        },
        multimodal: {
          enabled: !!data.vlmConfig?.enabled
        },
        storageBackendId: formData.value?.storageBackendId || '',
        storageProvider: data.storageProvider || 'local',
        nodeExtract: {
          enabled: data.extractConfig?.enabled || false,
          text: data.extractConfig?.text || '',
          tags: data.extractConfig?.tags || [],
          nodes: data.extractConfig?.nodes || [],
          relations: data.extractConfig?.relations || [],
          customInstructions: data.extractConfig?.custom_instructions || ''
        },
        questionGeneration: {
          enabled: data.questionGenerationConfig?.enabled || false,
          questionCount: data.questionGenerationConfig?.question_count || 3,
          customInstructions: data.questionGenerationConfig?.custom_instructions || ''
        }
      }

      await updateKBConfig(kbId, config)
      MessagePlugin.success(t('knowledgeEditor.messages.updateSuccess'))

      // Check if indexing strategy changed and offer rebuild
      if (hasFiles.value && initialIndexingStrategy.value && formData.value) {
        const curr = formData.value.indexingStrategy
        const prev = initialIndexingStrategy.value
        const strategyChanged = (
          curr.vectorEnabled !== prev.vectorEnabled ||
          curr.keywordEnabled !== prev.keywordEnabled ||
          curr.wikiEnabled !== prev.wikiEnabled ||
          curr.graphEnabled !== prev.graphEnabled
        )
        if (strategyChanged) {
          const dialog = DialogPlugin.confirm({
            header: t('knowledgeEditor.indexing.rebuildConfirmTitle'),
            body: t('knowledgeEditor.indexing.rebuildConfirmBody', { count: '...' }),
            confirmBtn: t('common.confirm'),
            cancelBtn: t('common.cancel'),
            onConfirm: async () => {
              dialog.destroy()
              try {
                const result: any = await rebuildKBIndex(kbId)
                const count = result?.documentCount ?? 0
                MessagePlugin.success(t('knowledgeEditor.indexing.rebuildSuccess', { count }))
              } catch (e) {
                console.error('Rebuild index failed:', e)
              }
            },
            onCancel: () => {
              dialog.destroy()
              MessagePlugin.info(t('knowledgeEditor.indexing.rebuildSkip'))
            },
          })
        }
      }

      emit('success', kbId)
      handleClose()
    }
  } catch (error: any) {
    console.error('Knowledge base operation failed:', error)
    // Vector-store-binding error codes from the server. Both indicate
    // the selected store cannot be used: 2200 is "the binding itself
    // is invalid" (e.g. unknown id, foreign tenant), 2201 is "the
    // store is currently unreachable". For either, swap in a localized
    // message and jump the user back to the Vector Store section so
    // they can pick a different store or fall back to the system
    // default.
    const code = error?.response?.data?.error?.code ?? error?.code
    if (code === 2200) {
      MessagePlugin.error(t('knowledgeEditor.errors.vectorStoreBindingInvalid'))
      currentSection.value = 'vectorStore'
    } else if (code === 2201) {
      MessagePlugin.error(t('knowledgeEditor.errors.vectorStoreUnavailable'))
      currentSection.value = 'vectorStore'
    } else {
      MessagePlugin.error(error?.message || t('common.operationFailed'))
    }
  } finally {
    saving.value = false
  }
}

// 重置所有状态
const resetState = () => {
  savedKbId.value = null
  currentSection.value = 'basic'
  formData.value = null
  hasFiles.value = false
  initialStorageProvider.value = ''
  tenantDefaultStorageProvider.value = 'local'
  initialIndexingStrategy.value = null
  saving.value = false
  loading.value = false
  chunkingDirty.value = false
  kbCreatorId.value = ''
  kbTenantId.value = 0
}

// 关闭弹窗
const handleClose = () => {
  emit('update:visible', false)
  setTimeout(() => {
    if (props.visible) return
    resetState()
  }, 300)
}

// 监听弹窗打开/关闭
watch(() => props.visible, async (newVal) => {
  const generation = ++kbEditorLoadGeneration
  if (newVal) {
    // 打开弹窗时，先重置状态
    resetState()
    loading.value = true
    const targetKbId = props.kbId
    
    // 检查是否有初始 section，如果有则跳转
    if (uiStore.kbEditorInitialSection) {
      currentSection.value = uiStore.kbEditorInitialSection
    }
    
    // 加载模型列表与空间默认存储引擎（创建 KB 时即使用，不依赖是否打开「存储引擎」Tab）
    await Promise.all([loadAllModels(), loadTenantDefaultStorageProvider()])

    if (generation !== kbEditorLoadGeneration || !props.visible) return
    
    // 根据模式加载数据
    if (props.mode === 'edit' && targetKbId) {
      await loadKBData(targetKbId, generation)
    } else {
      // 创建模式：初始化空表单，并预填空间默认存储引擎
      formData.value = initFormData(props.initialType || 'document')
      formData.value.storageProvider = tenantDefaultStorageProvider.value
      hasFiles.value = false
      applyDefaultModelsIfEmpty()
      loading.value = false
    }
  } else {
    // 关闭弹窗时，延迟重置状态（等待动画结束）
    setTimeout(() => {
      if (props.visible) return
      resetState()
      currentSection.value = 'basic' // 重置为默认 section
    }, 300)
  }
})

// 监听全局设置弹窗关闭后刷新模型列表
watch(
  () => uiStore.showSettingsModal,
  async (visible, previous) => {
    if (!visible && previous && props.visible) {
      await loadAllModels(true)
    }
  }
)

watch(() => chatResources.allModels, (list) => {
  if (props.visible) {
    allModels.value = list || []
  }
})
</script>

<style scoped lang="less">
// 复用创建知识库的样式
.settings-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
}

.settings-modal {
  position: relative;
  width: 90vw;
  max-width: 1000px;
  height: 85vh;
  max-height: 750px;
  background: var(--td-bg-color-container);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.editor-initializing {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--td-bg-color-container);
}

.close-btn {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 32px;
  height: 32px;
  border: none;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--td-text-color-secondary);
  transition: all 0.2s ease;
  z-index: 10;

  &:hover {
    background: var(--td-bg-color-secondarycontainer);
    color: var(--td-text-color-primary);
  }
}

.settings-container {
  display: flex;
  height: 100%;
  width: 100%;
  overflow: hidden;
}

/* 左侧导航：与 AgentEditorModal 对齐 */
.settings-sidebar {
  width: 208px;
  background-color: var(--td-bg-color-settings-modal);
  border-right: 1px solid var(--td-component-stroke);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-header {
  padding: 16px 14px 12px;
  border-bottom: 1px solid var(--td-component-stroke);
  flex-shrink: 0;
}

.sidebar-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.settings-nav {
  flex: 1;
  padding: 8px 8px 12px;
  overflow-y: auto;
  min-height: 0;
}

.nav-group-title {
  padding: 6px 14px 2px;
  color: var(--td-text-color-placeholder);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;

  .settings-nav > &:first-child {
    padding-top: 2px;
  }

  .settings-nav > &:not(:first-child) {
    padding-top: 8px;
  }
}

.nav-item {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  margin-bottom: 2px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
  font-size: 14px;
  color: var(--td-text-color-primary);
  user-select: none;

  &:hover {
    background-color: var(--td-bg-color-container-hover);
    color: var(--td-text-color-primary);
  }

  &.active {
    background-color: var(--td-bg-color-secondarycontainer);
    color: var(--td-brand-color);
    font-weight: 500;
  }
}

.nav-icon {
  margin-right: 9px;
  font-size: 16px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: inherit;
}

.nav-label {
  flex: 1;
}

.nav-badge {
  flex-shrink: 0;
  margin-left: 2px;
  padding: 0 6px;
  border-radius: 8px;
  background: var(--td-bg-color-secondarycontainer);
  color: var(--td-text-color-secondary);
  font-size: 11px;
  line-height: 16px;
  font-weight: 500;
  text-align: center;
}

.settings-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.content-wrapper {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
}

.section {
  margin-bottom: 32px;

  &:last-child {
    margin-bottom: 0;
  }
}

.section-content {
  .section-header {
    margin-bottom: 16px;
  }

  .section-title {
    margin: 0 0 6px 0;
    font-family: var(--app-font-family);
    font-size: 20px;
    font-weight: 600;
    color: var(--td-text-color-primary);
  }

  .section-desc {
    margin: 0;
    font-family: var(--app-font-family);
    font-size: 14px;
    color: var(--td-text-color-placeholder);
    line-height: 22px;
  }

  .section-body {
    background: var(--td-bg-color-container);
  }
}

.form-item {
  margin-bottom: 16px;

  &:last-child {
    margin-bottom: 0;
  }
}

.form-label {
  display: block;
  margin-bottom: 8px;
  font-family: var(--app-font-family);
  font-size: 15px;
  font-weight: 500;
  color: var(--td-text-color-primary);

  &.required::after {
    content: '*';
    color: var(--td-error-color);
    margin-left: 4px;
  }
}

.form-tip {
  margin-top: 6px;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.kb-id-field {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  max-width: 480px;
  margin-top: 8px;
  padding: 6px 8px 6px 12px;
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--td-component-stroke);
  border-radius: 6px;

  .kb-id-value {
    flex: 1;
    min-width: 0;
    margin: 0;
    padding: 0;
    background: none;
    border: none;
    font-family: var(--app-font-family-mono);
    font-size: 13px;
    line-height: 1.5;
    color: var(--td-text-color-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .kb-id-copy {
    flex-shrink: 0;
    color: var(--td-text-color-secondary);

    &:hover {
      color: var(--td-brand-color);
    }
  }
}

.granularity-radio-group {
  margin-top: 4px;
}

.granularity-hint {
  margin-top: 8px;
  line-height: 1.6;
  color: var(--td-text-color-secondary);
  white-space: normal;
  word-break: break-word;
}

.indexing-checks {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin-top: 10px;
}

.indexing-check-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid var(--td-component-stroke);
  border-radius: 8px;
  background: var(--td-bg-color-container);
  cursor: pointer;
  user-select: none;
  transition: border-color 0.2s ease, background 0.2s ease;

  &:hover {
    border-color: var(--td-brand-color);
  }

  &.is-checked {
    border-color: var(--td-brand-color);
    background: var(--td-brand-color-light);
  }

  &.is-disabled {
    cursor: not-allowed;
    opacity: 0.7;

    &:hover {
      border-color: var(--td-component-stroke);
    }

    &.is-checked:hover {
      border-color: var(--td-brand-color);
    }
  }

  :deep(.t-checkbox__label) {
    font-weight: 500;
    color: var(--td-text-color-primary);
  }
}

.locked-tip {
  color: var(--td-warning-color);
  margin-top: 8px;
}

// 禁用内部 checkbox 自身的点击事件，统一由卡片处理
.indexing-check-box {
  pointer-events: none;
}

.indexing-check-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.indexing-new-badge {
  display: inline-flex;
  align-items: center;
  padding: 0 6px;
  height: 16px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.4px;
  color: var(--td-brand-color);
  background: var(--td-brand-color-light);
}

.indexing-check-desc {
  margin: 0;
  padding-left: 24px;
  font-size: 12px;
  line-height: 18px;
  color: var(--td-text-color-placeholder);
}

.faq-guide {
  margin-top: 20px;
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--td-bg-color-secondarycontainer);
  color: var(--td-text-color-secondary);
  font-size: 13px;
  line-height: 20px;
}

.settings-footer {
  padding: 12px 40px;
  border-top: 1px solid var(--td-component-stroke);
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  flex-shrink: 0;
}

.settings-footer-note {
  margin: 0;
  margin-right: auto;
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 13px;
  line-height: 20px;
  color: var(--td-text-color-secondary);

  strong {
    margin-right: 4px;
    color: var(--td-text-color-primary);
    font-weight: 500;
  }

  &__icon {
    flex-shrink: 0;
    margin-top: 2px;
    font-size: 14px;
    color: var(--td-success-color);
  }
}

.settings-footer-actions {
  display: flex;
  gap: 12px;
  flex-shrink: 0;
}

// 过渡动画
.modal-enter-active,
.modal-leave-active {
  transition: all 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;

  .settings-modal {
    transform: scale(0.95);
  }
}

// 多模态配置内联样式（与子组件 KBStorageSettings/KBAdvancedSettings 一致）
.kb-multimodal-settings {
  width: 100%;

  .section-header {
    margin-bottom: 20px;

    h2 {
      font-size: 20px;
      font-weight: 600;
      color: var(--td-text-color-primary);
      margin: 0 0 6px 0;
    }

    .section-description {
      font-size: 14px;
      color: var(--td-text-color-secondary);
      margin: 0;
      line-height: 1.5;
    }
  }

  .settings-group {
    display: flex;
    flex-direction: column;
  }

  .setting-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    padding: 16px 0;
    border-bottom: 1px solid var(--td-component-stroke);

    &:last-child {
      border-bottom: none;
    }
  }

  .setting-info {
    flex: 1;
    max-width: 65%;
    padding-right: 24px;

    label {
      font-size: 15px;
      font-weight: 500;
      color: var(--td-text-color-primary);
      display: block;
      margin-bottom: 4px;
    }

    .desc {
      font-size: 13px;
      color: var(--td-text-color-secondary);
      margin: 0;
      line-height: 1.5;
    }
  }

  .setting-control {
    flex-shrink: 0;
    min-width: 280px;
    display: flex;
    justify-content: flex-end;
    align-items: center;
  }

  .required {
    color: var(--td-error-color);
    margin-left: 2px;
    font-weight: 500;
  }
}
</style>
