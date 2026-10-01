<template>
  <div class="memory-workspace-settings">
    <div class="section-header">
      <h2>{{ t('memoryWorkspaceSettings.title') }}</h2>
      <p class="section-description">{{ t('memoryWorkspaceSettings.description') }}</p>
    </div>

    <!-- The switch defaults to off because memory retains what users say
         across sessions. That makes the feature easy to miss, so the intro
         states plainly what turning it on does. -->
    <div class="intro">
      <t-icon name="info-circle" class="intro-icon" />
      <div>
        <p class="intro-title">{{ t('memoryWorkspaceSettings.introTitle') }}</p>
        <p class="intro-desc">{{ t('memoryWorkspaceSettings.introDescription') }}</p>
      </div>
    </div>

    <div class="settings-group">
      <div class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.enableLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.enableDescription') }}</p>
        </div>
        <div class="setting-control">
          <t-switch v-model="config.enabled" :disabled="!canEdit" @change="debouncedSave" />
        </div>
      </div>

      <div v-if="config.enabled" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.writeModeLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.writeModeDescription') }}</p>
          <p class="desc hint">
            {{
              config.writeMode === 'auto'
                ? t('memoryWorkspaceSettings.writeModeAutoHint')
                : t('memoryWorkspaceSettings.writeModeExplicitHint')
            }}
          </p>
        </div>
        <div class="setting-control">
          <t-radio-group v-model="config.writeMode" :disabled="!canEdit" @change="debouncedSave">
            <t-radio-button value="explicit_only">
              {{ t('memoryWorkspaceSettings.writeModeExplicit') }}
            </t-radio-button>
            <t-radio-button value="auto">
              {{ t('memoryWorkspaceSettings.writeModeAuto') }}
            </t-radio-button>
          </t-radio-group>
        </div>
      </div>

      <div v-if="config.enabled && config.writeMode === 'auto'" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.extractModelLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.extractModelDescription') }}</p>
        </div>
        <div class="setting-control" style="min-width: 280px">
          <ModelSelector
            model-type="KnowledgeQA"
            :selected-model-id="config.extractModelId"
            :disabled="!canEdit"
            @update:selected-model-id="handleModelChange"
            @add-model="handleAddModel('chat')"
          />
        </div>
      </div>

      <div v-if="config.enabled && config.writeMode === 'auto'" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.extractDelayLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.extractDelayDescription') }}</p>
        </div>
        <div class="setting-control">
          <t-input-number
            v-model="config.extractDelaySeconds"
            :min="5"
            :max="3600"
            :step="15"
            suffix="s"
            :disabled="!canEdit"
            @change="debouncedSave"
          />
        </div>
      </div>

      <div v-if="config.enabled && config.writeMode === 'auto'" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.extractMinIntervalLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.extractMinIntervalDescription') }}</p>
        </div>
        <div class="setting-control">
          <t-input-number
            v-model="config.extractMinIntervalSeconds"
            :min="0"
            :max="86400"
            :step="60"
            suffix="s"
            :disabled="!canEdit"
            @change="debouncedSave"
          />
        </div>
      </div>

      <div v-if="config.enabled" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.vectorRecallLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.vectorRecallDescription') }}</p>
        </div>
        <div class="setting-control">
          <t-switch v-model="config.vectorRecall" :disabled="!canEdit" @change="debouncedSave" />
        </div>
      </div>

      <div v-if="config.enabled && config.vectorRecall" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.embeddingModelLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.embeddingModelDescription') }}</p>
        </div>
        <div class="setting-control" style="min-width: 280px">
          <ModelSelector
            model-type="Embedding"
            :selected-model-id="config.embeddingModelId"
            :disabled="!canEdit"
            :clearable="true"
            @update:selected-model-id="handleEmbeddingModelChange"
            @add-model="handleAddModel('embedding')"
          />
        </div>
      </div>

      <div v-if="config.enabled" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.conditioningLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.conditioningDescription') }}</p>
        </div>
        <div class="setting-control">
          <t-switch
            v-model="config.retrievalConditioning"
            :disabled="!canEdit"
            @change="debouncedSave"
          />
        </div>
      </div>

      <div v-if="config.enabled && config.writeMode === 'auto'" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.interestThresholdLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.interestThresholdDescription') }}</p>
        </div>
        <div class="setting-control">
          <t-input-number
            v-model="config.interestThreshold"
            :min="1"
            :max="20"
            :step="1"
            :disabled="!canEdit"
            @change="debouncedSave"
          />
        </div>
      </div>

      <div v-if="config.enabled && config.writeMode === 'auto'" class="setting-row instructions-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.instructionsLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.instructionsDescription') }}</p>
        </div>
        <div class="setting-control instructions-control">
          <t-textarea
            v-model="config.extractInstructions"
            :autosize="{ minRows: 3, maxRows: 8 }"
            :maxlength="1000"
            :disabled="!canEdit"
            :placeholder="t('memoryWorkspaceSettings.instructionsPlaceholder')"
            @blur="debouncedSave"
          />
        </div>
      </div>

      <div v-if="config.enabled" class="setting-row">
        <div class="setting-info">
          <label>{{ t('memoryWorkspaceSettings.maxItemsLabel') }}</label>
          <p class="desc">{{ t('memoryWorkspaceSettings.maxItemsDescription') }}</p>
        </div>
        <div class="setting-control">
          <t-input-number
            v-model="config.maxItems"
            :min="10"
            :max="2000"
            :step="10"
            :disabled="!canEdit"
            @change="debouncedSave"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import { useI18n } from 'vue-i18n'
import ModelSelector from '@/components/ModelSelector.vue'
import { useAuthStore } from '@/stores/auth'
import { useUIStore } from '@/stores/ui'
import { getTenantMemoryConfig, updateTenantMemoryConfig, type MemoryConfig } from '@/api/memory'

const { t } = useI18n()
const authStore = useAuthStore()
const uiStore = useUIStore()

const config = reactive<MemoryConfig>({
  enabled: false,
  writeMode: 'explicit_only',
  extractModelId: '',
  maxItems: 200,
  extractDelaySeconds: 90,
  extractMinIntervalSeconds: 300,
  extractInstructions: '',
  interestThreshold: 3,
  retrievalConditioning: true,
  embeddingModelId: '',
  vectorRecall: true,
})
const isInitializing = ref(true)

const canEdit = computed(() => authStore.hasRole('admin'))

const loadConfig = async () => {
  try {
    // The KV endpoint answers the bare config object (no {success, data} envelope).
    // Its inner keys are still the stored jsonb names and move to camelCase in M2.
    const stored = await getTenantMemoryConfig()
    if (stored) {
      config.enabled = stored.enabled ?? false
      config.writeMode = stored.writeMode === 'auto' ? 'auto' : 'explicit_only'
      config.extractModelId = stored.extractModelId || ''
      config.maxItems = stored.maxItems || 200
      config.extractDelaySeconds = stored.extractDelaySeconds || 90
      config.extractMinIntervalSeconds = stored.extractMinIntervalSeconds || 300
      config.extractInstructions = stored.extractInstructions || ''
      config.interestThreshold = stored.interestThreshold || 3
      config.retrievalConditioning = stored.retrievalConditioning !== false
      config.embeddingModelId = stored.embeddingModelId || ''
      config.vectorRecall = stored.vectorRecall !== false
    }
  } catch (error: any) {
    console.error('Failed to load memory config:', error)
  } finally {
    // Give the switches a tick to settle so binding the loaded values does not
    // immediately fire a save.
    setTimeout(() => {
      isInitializing.value = false
    }, 100)
  }
}

const saveConfig = async () => {
  try {
    await updateTenantMemoryConfig({ ...config })
    MessagePlugin.success(t('memoryWorkspaceSettings.toasts.saveSuccess'))
  } catch (error: any) {
    MessagePlugin.error(
      t('memoryWorkspaceSettings.toasts.saveFailed', { message: error?.message || '' }),
    )
  }
}

let saveTimer: number | null = null
const debouncedSave = () => {
  if (isInitializing.value || !canEdit.value) return
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => {
    saveConfig().catch(() => {})
  }, 500)
}

const handleModelChange = (modelId: string) => {
  config.extractModelId = modelId
  debouncedSave()
}

const handleEmbeddingModelChange = (modelId: string) => {
  config.embeddingModelId = modelId || ''
  debouncedSave()
}

const handleAddModel = (subSection: 'chat' | 'embedding') => {
  uiStore.openSettings('models', subSection)
  window.dispatchEvent(
    new CustomEvent('settings-nav', { detail: { section: 'models', subsection: subSection } }),
  )
}

onMounted(loadConfig)
</script>

<style lang="less" scoped>
.memory-workspace-settings {
  width: 100%;
}

.section-header {
  margin-bottom: 24px;

  h2 {
    font-size: 20px;
    font-weight: 600;
    color: var(--td-text-color-primary);
    margin: 0 0 8px 0;
  }

  .section-description {
    font-size: 14px;
    color: var(--td-text-color-secondary);
    margin: 0;
    line-height: 1.5;
  }
}

.intro {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: 8px;
  border-radius: 8px;
  background: var(--td-bg-color-secondarycontainer);
}

.intro-icon {
  color: var(--td-brand-color);
  margin-top: 2px;
  flex-shrink: 0;
}

.intro-title {
  margin: 0 0 4px 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--td-text-color-primary);
}

.intro-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--td-text-color-secondary);
}

.settings-group {
  display: flex;
  flex-direction: column;
}

.setting-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 0;
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

  .hint {
    margin-top: 4px !important;
    color: var(--td-text-color-placeholder);
  }
}

.setting-control {
  flex-shrink: 0;
  display: flex;
  justify-content: flex-end;
  align-items: center;
}

// The custom prompt needs room to read, so this row stacks instead of putting a
// paragraph of rules into a narrow right-hand column.
.instructions-row {
  flex-direction: column;
  align-items: stretch;

  .setting-info {
    max-width: 100%;
    padding-right: 0;
    margin-bottom: 10px;
  }
}

.instructions-control {
  width: 100%;
  justify-content: stretch;
}
</style>
