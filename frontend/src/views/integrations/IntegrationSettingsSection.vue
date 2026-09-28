<template>
  <div class="integrations-settings">
    <div class="integrations-settings__body">
      <div v-if="tab === 'im'" class="section">
        <div class="section-header">
          <h2>{{ $t('agentEditor.im.title') }}</h2>
          <p class="section-description">
            {{ $t('agentEditor.im.description') }}
            <a
              href="https://github.com/Tencent/WeKnora/blob/main/docs/IM%E9%9B%86%E6%88%90%E5%BC%80%E5%8F%91%E6%96%87%E6%A1%A3.md"
              target="_blank"
              rel="noopener noreferrer"
              class="doc-link"
            >
              {{ $t('agentEditor.im.docLink') }}
              <t-icon name="link" class="link-icon" />
            </a>
          </p>
        </div>
        <IMChannelPanel v-model:filter-agent-id="filterAgentId" />
      </div>

      <div v-if="tab === 'embed'" class="section">
        <div class="section-header">
          <h2>{{ $t('agentEditor.embed.title') }}</h2>
          <p class="section-description">{{ $t('agentEditor.embed.description') }}</p>
        </div>
        <AgentEmbedChannelPanel v-model:filter-agent-id="filterAgentId" />
      </div>

      <div v-if="tab === 'api'" class="section">
        <div class="section-header">
          <h2>{{ $t('integrations.api.title') }}</h2>
          <p class="section-description">{{ $t('integrations.api.subtitle') }}</p>
        </div>
        <ApiIntegrationSettings />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import IMChannelPanel from '@/components/IMChannelPanel.vue'
import AgentEmbedChannelPanel from '@/components/AgentEmbedChannelPanel.vue'
import ApiIntegrationSettings from '@/views/integrations/ApiIntegrationSettings.vue'
import type { IntegrationTab } from '@/config/integrations'

const filterAgentId = ref('')

defineProps<{
  tab: IntegrationTab
}>()

const route = useRoute()

function applyAgentFilterFromRoute() {
  filterAgentId.value = (route.query.agentId as string) || ''
}

watch(
  () => route.query.agentId,
  applyAgentFilterFromRoute,
  { immediate: true },
)
</script>

<style scoped lang="less">
.integrations-settings {
  display: flex;
  flex-direction: column;
}

.integrations-settings__body {
  min-width: 0;
}

.section-header {
  margin-bottom: 18px;

  h2 {
    margin: 0 0 6px;
    color: var(--td-text-color-primary);
    font-size: 18px;
    font-weight: 600;
    line-height: 1.35;
  }
}

.section-description {
  margin: 0;
  color: var(--td-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.doc-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 6px;
  color: var(--td-brand-color);
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

.link-icon {
  font-size: 13px;
}
</style>
