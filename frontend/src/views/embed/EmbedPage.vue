<template>
  <div class="embed-page" :style="pageStyle">
    <div v-if="loadError" class="embed-error">{{ loadError }}</div>
    <template v-else-if="config">
      <header v-if="sessionId" class="embed-header">
        <span class="embed-header__badge" :style="badgeStyle">
          <img v-if="headerAvatarImage" class="embed-header__avatar-img" :src="headerAvatarImage" alt="" />
          <span v-else-if="config.agentAvatar" class="embed-header__avatar">{{ config.agentAvatar }}</span>
          <t-icon v-else :name="headerIcon" size="18px" />
        </span>
        <div class="embed-header__text">
          <h1 class="embed-header__title">{{ headerTitle }}</h1>
          <p v-if="headerSubtitle" class="embed-header__subtitle">{{ headerSubtitle }}</p>
        </div>
        <t-button
          variant="text"
          shape="square"
          size="small"
          class="embed-header__action"
          :disabled="!chatHasMessages"
          :title="$t('embedPublish.newChat')"
          :aria-label="$t('embedPublish.newChat')"
          @click="handleNewChat"
        >
          <template #icon><t-icon name="add" /></template>
        </t-button>
      </header>

      <EmbedChatView
        v-if="sessionId"
        :session-id="sessionId"
        :session-sig="sessionSig"
        :visitor-id="visitorId"
        :channel-id="channelId"
        :token="token"
        :agent-id="config.agentId"
        :kb-ids="kbIds"
        :welcome-message="config.welcomeMessage"
        :show-suggested-questions="config.showSuggestedQuestions !== false"
        :show-thinking="config.showThinking === true"
        :allow-web-search="config.allowWebSearch === true"
        :agent-web-search-enabled="config.agentWebSearchEnabled === true"
        :allow-file-upload="config.allowFileUpload === true"
        :agent-image-upload-enabled="config.agentImageUploadEnabled === true"
        :use-session-header-title="useSessionHeaderTitle"
        :host-context="hostContext"
        @session-title="sessionTitle = $event"
        @messages-state="chatHasMessages = $event"
      />
      <div v-else class="embed-loading">{{ $t('embedPublish.loading') }}</div>
    </template>
    <div v-else-if="awaitingToken" class="embed-loading">{{ $t('embedPublish.awaitingToken') }}</div>
    <div v-else-if="bootstrapping" class="embed-loading">{{ $t('embedPublish.loading') }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import EmbedChatView from '@/views/embed/EmbedChatView.vue'
import { useEmbedBridge } from '@/composables/useEmbedBridge'
import { setDefaultProtectedFileAccess } from '@/utils/protectedFileAccess'

const { t } = useI18n()
const route = useRoute()
const channelId = ref(String(route.params.channelId || ''))
const sessionTitle = ref('')
const chatHasMessages = ref(false)

const {
  token,
  config,
  sessionId,
  sessionSig,
  visitorId,
  loadError,
  awaitingToken,
  bootstrapping,
  hostContext,
  startNewSession,
} = useEmbedBridge(channelId)

// An embed visitor has no Bearer/tenant credentials, so every protected file in
// this document must go through the channel-scoped proxy. Registering the plane
// once here keeps deeply nested renderers (agent stream, references, wiki
// drawer) from having to thread the channel/token down as props.
watchEffect(() => {
  setDefaultProtectedFileAccess(
    channelId.value && token.value
      ? { mode: 'embed', channelId: channelId.value, token: token.value }
      : null,
  )
})

onUnmounted(() => setDefaultProtectedFileAccess(null))

const handleNewChat = () => {
  // The current session is already empty — reuse it instead of spawning yet
  // another blank session (which would otherwise pile up server-side).
  if (!chatHasMessages.value) return
  sessionTitle.value = ''
  startNewSession()
}

const kbIds = computed(() => config.value?.knowledgeBaseIds ?? [])

const pageStyle = computed(() => {
  const color = config.value?.primaryColor
  if (!color) return {}
  return {
    '--embed-primary': color,
    '--td-brand-color': color,
    '--td-brand-color-hover': color,
    '--td-brand-color-active': color,
  } as Record<string, string>
})

const badgeStyle = computed(() => {
  const color = config.value?.primaryColor
  if (!color) return {}
  return {
    background: `color-mix(in srgb, ${color} 12%, transparent)`,
    color,
  } as Record<string, string>
})

const channelDisplayTitle = computed(() => {
  const cfg = config.value
  if (!cfg) return ''
  return (
    cfg.displayTitle?.trim()
    || cfg.pageTitle?.trim()
    || cfg.name?.trim()
    || cfg.agentName?.trim()
    || t('embedPublish.defaultChatTitle')
  )
})

const useSessionHeaderTitle = computed(
  () => config.value?.headerTitleMode === 'session',
)

const headerTitle = computed(() => {
  if (useSessionHeaderTitle.value && sessionTitle.value.trim()) {
    return sessionTitle.value.trim()
  }
  return channelDisplayTitle.value
})

const headerSubtitle = computed(() => {
  const cfg = config.value
  if (!cfg?.agentName) return ''
  if (useSessionHeaderTitle.value && sessionTitle.value.trim()) {
    const fallback = channelDisplayTitle.value
    if (fallback && fallback !== sessionTitle.value.trim()) {
      return fallback
    }
    return cfg.agentName
  }
  const channelName = cfg.name?.trim()
  if (!channelName || channelName === channelDisplayTitle.value) return ''
  return cfg.agentName
})

const headerIcon = computed(() => {
  const agentId = config.value?.agentId || ''
  return agentId && agentId !== 'builtin-quick-answer' ? 'control-platform' : 'chat'
})

/**
 * 头部徽标优先显示"渠道里配置的图片"（launcher_icon）；若 agent_avatar 本身就是图片地址
 * （data URL / http(s) / 站内路径）也用它。都没有时退回 emoji 文本或通用图标。
 */
const headerAvatarImage = computed(() => {
  const cfg = config.value
  const icon = typeof cfg?.launcherIcon === 'string' ? cfg.launcherIcon.trim() : ''
  if (icon) return icon
  const avatar = typeof cfg?.agentAvatar === 'string' ? cfg.agentAvatar.trim() : ''
  return /^(data:image\/|https?:\/\/|\/)/i.test(avatar) ? avatar : ''
})

watch(headerTitle, (title) => {
  if (title) document.title = title
}, { immediate: true })
</script>

<style scoped lang="less">
.embed-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--td-bg-color-container, #fff);
  overflow: hidden;
  /* 子组件（含 AgentStreamDisplay）内凡用 --td-brand-color 的 loading / 强调色均跟随渠道主题 */
  --td-brand-color: var(--embed-primary, var(--td-brand-color));
  --td-brand-color-hover: var(--embed-primary, var(--td-brand-color-hover));
  --td-brand-color-active: var(--embed-primary, var(--td-brand-color-active));

  :deep(.t-button--theme-primary) {
    --td-brand-color: var(--embed-primary, var(--td-brand-color));
    --td-brand-color-hover: var(--embed-primary, var(--td-brand-color-hover));
    --td-brand-color-active: var(--embed-primary, var(--td-brand-color-active));
  }

  :deep(.embed-input-box:focus-within) {
    border-color: var(--embed-primary, var(--td-brand-color));
  }

  :deep(.embed-send-btn:not(.disabled)) {
    background: var(--embed-primary, var(--td-brand-color));
  }
}

.embed-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--td-component-stroke);
  background: var(--td-bg-color-container);
  flex-shrink: 0;

  &__badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 10px;
    flex-shrink: 0;
    background: color-mix(in srgb, var(--td-brand-color) 10%, transparent);
    color: var(--td-brand-color);
  }

  &__avatar {
    font-size: 20px;
    line-height: 1;
  }

  &__avatar-img {
    width: 100%;
    height: 100%;
    border-radius: 10px;
    object-fit: cover;
    display: block;
  }

  &__text {
    min-width: 0;
    flex: 1;
  }

  &__action {
    flex-shrink: 0;
    color: var(--td-text-color-secondary);

    &:hover {
      color: var(--td-brand-color);
    }
  }

  &__title {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.35;
    color: var(--td-text-color-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__subtitle {
    margin: 2px 0 0;
    font-size: 12px;
    line-height: 1.4;
    color: var(--td-text-color-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.embed-error,
.embed-loading {
  padding: 24px;
  text-align: center;
  color: var(--td-text-color-placeholder);
}
</style>
