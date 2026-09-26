<template>
  <t-drawer :visible="visible" :header="title || $t('embedPublish.preview')" size="720px" :footer="false"
    :z-index="2600" attach="body" class="embed-preview-drawer" @close="emit('update:visible', false)">
    <div class="preview-body">
      <template v-if="mode === 'iframe'">
        <p class="preview-hint">{{ $t('embedPublish.previewIframeHint') }}</p>
        <div class="device-frame">
          <div class="device-frame__chrome">
            <span class="device-frame__dot" />
            <span class="device-frame__dot" />
            <span class="device-frame__dot" />
            <span class="device-frame__url">{{ previewUrlLabel }}</span>
          </div>
          <div class="device-frame__screen">
            <t-loading v-if="!iframeSrc || !iframeReady" size="small" :text="$t('embedPublish.previewLoading')" />
            <iframe v-else :key="iframeSrc" :src="iframeSrc" class="preview-iframe" allow="clipboard-write" />
          </div>
        </div>
      </template>

      <template v-else>
        <p class="preview-hint">{{ $t('embedPublish.previewWidgetHint') }}</p>
        <div class="widget-shell" :class="`pos-${position}`">
          <div class="widget-mock-page">
            <div class="widget-mock-page__title">{{ $t('embedPublish.previewMockPage') }}</div>
            <div class="widget-mock-page__line" />
            <div class="widget-mock-page__line widget-mock-page__line--short" />
          </div>
          <button type="button" class="widget-launcher" :style="{ background: primaryColor || 'var(--td-brand-color)' }"
            :aria-label="widgetOpen ? $t('common.close') : $t('embedPublish.preview')"
            @click="widgetOpen = !widgetOpen">
            <img v-if="!widgetOpen && launcherIcon" :src="launcherIcon" class="widget-launcher__img" alt="" />
            <!-- 与 loader 同一枚默认图标：未配置图片时用它（而不是 chat 通用图标） -->
            <svg v-else-if="!widgetOpen" class="widget-launcher__svg" viewBox="0 0 1024 1024" aria-hidden="true"
              style="width:22px;height:22px;display:block">
              <path fill="#ffffff" d="M781.69183326 208.58123803H242.36749291c-9.3092649 0-17.22930884 3.29754663-23.84417772 9.88769507-6.57531762 6.59509253-9.87780761 14.52502465-9.87780761 23.79473901v370.83801222c0 9.37847924 3.30743408 17.30841065 9.87780761 23.90350389 6.61486816 6.59014916 14.53491211 9.88769508 23.84417772 9.88769508h101.13629151v105.07159448l146.9311521-105.07159448h291.27667236c9.31420898 0 17.23425293-3.29754663 23.85900855-9.88769508 6.57037354-6.59509253 9.87780761-14.52502465 9.87780763-23.8985598V242.26367211c0-9.27465844-3.31237817-17.20458984-9.87780763-23.79968309-6.62475562-6.59014916-14.54479957-9.88769508-23.85900855-9.88769508h-0.01977562zM242.34771729 141.21142578h539.32434106c27.89318824 0 51.74725342 9.78881836 71.50286865 29.56420922 19.74572778 19.77539086 29.61364722 43.57507324 29.61364722 71.48803711v370.83801222c0 27.91790796-9.86792016 51.81646704-29.61364722 71.49298119-19.75561523 19.77539086-43.60968041 29.66308594-71.50286865 29.66308594h-269.68688989L276.049927 882.78857422v-168.52587867h-33.70220971c-27.91296386 0-51.74725342-9.88769508-71.50286866-29.66308594C151.08923339 664.9181521 141.21142578 641.0146482 141.21142578 613.10168433V242.26367211C141.21142578 214.34576416 151.08923339 190.55102516 170.84484863 170.775635 190.60046386 151.00024414 214.43475342 141.21142578 242.34771729 141.21142578z" />
            </svg>
            <t-icon v-else name="close" />
          </button>
          <transition name="widget-panel">
            <div v-show="widgetOpen" class="widget-panel">
              <iframe v-if="iframeSrc && iframeReady" :key="`widget-${iframeSrc}`" :src="iframeSrc"
                class="preview-iframe" :title="title || $t('embedPublish.preview')" allow="clipboard-write" />
            </div>
          </transition>
        </div>
      </template>
    </div>
  </t-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { buildEmbedURL, type WidgetPosition } from '@/api/embed'

const props = defineProps<{
  visible: boolean
  channelId: string
  token: string
  mode?: 'iframe' | 'widget'
  title?: string
  primaryColor?: string
  /** Custom launcher image as a data URL; empty falls back to the chat icon. */
  launcherIcon?: string
  position?: WidgetPosition
  /**
   * Bumped by the parent every time a preview is (re)opened. Folded into the
   * iframe URL so the embed page fully reloads and re-fetches the latest saved
   * config — otherwise re-previewing after an edit shows stale content until a
   * full page refresh.
   */
  refreshKey?: number
  /** Channel default locale — passed in the iframe URL so the first paint uses the right language. */
  locale?: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
}>()

const mode = computed(() => props.mode || 'iframe')
const widgetOpen = ref(true)
/** Defer iframe until the drawer has laid out — avoids embed autosize measuring a 0-size frame. */
const iframeReady = ref(false)

const iframeSrc = computed(() => {
  if (!props.channelId || !props.token) return ''
  return buildEmbedURL(props.channelId, props.token, {
    locale: props.locale,
    refreshKey: props.refreshKey,
  })
})

const previewUrlLabel = computed(() => {
  if (!props.channelId) return ''
  try {
    return new URL(buildEmbedURL(props.channelId)).pathname
  } catch {
    return `/embed/${props.channelId}`
  }
})

watch(() => props.visible, async (open) => {
  if (open) {
    widgetOpen.value = true
    iframeReady.value = false
    await nextTick()
    iframeReady.value = true
  } else {
    iframeReady.value = false
  }
}, { immediate: true })
</script>

<style scoped lang="less">
.preview-body {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 14px;
}

.preview-hint {
  flex-shrink: 0;
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--td-text-color-secondary);
  background: var(--td-bg-color-secondarycontainer);
  border: 1px solid var(--td-component-stroke);
}

.device-frame {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--td-component-stroke);
  border-radius: 12px;
  overflow: hidden;
  background: var(--td-bg-color-container);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);

  &__chrome {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 14px;
    background: var(--td-bg-color-secondarycontainer);
    border-bottom: 1px solid var(--td-component-stroke);
  }

  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--td-component-stroke);

    &:nth-child(1) {
      background: #ff5f57;
    }

    &:nth-child(2) {
      background: #febc2e;
    }

    &:nth-child(3) {
      background: #28c840;
    }
  }

  &__url {
    flex: 1;
    margin-left: 8px;
    padding: 4px 10px;
    border-radius: 6px;
    font-size: 12px;
    color: var(--td-text-color-placeholder);
    background: var(--td-bg-color-container);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__screen {
    flex: 1;
    min-height: 420px;
    background: #f5f7fa;
    display: flex;
    align-items: center;
    justify-content: center;
  }
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
  display: block;
}

.widget-shell {
  position: relative;
  flex: 1;
  min-height: 480px;
  border: 1px solid var(--td-component-stroke);
  border-radius: 12px;
  overflow: hidden;
  background: linear-gradient(180deg, #f8fafc 0%, #eef2f7 100%);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
}

.widget-mock-page {
  padding: 28px 32px;

  &__title {
    font-size: 14px;
    font-weight: 600;
    color: var(--td-text-color-placeholder);
    margin-bottom: 16px;
  }

  &__line {
    height: 10px;
    border-radius: 5px;
    background: rgba(0, 0, 0, 0.06);
    margin-bottom: 10px;
    max-width: 72%;

    &--short {
      max-width: 48%;
    }
  }
}

.widget-launcher {
  position: absolute;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border: none;
  border-radius: 50%;
  color: #fff;
  font-size: 20px;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.18);
  z-index: 3;
  overflow: hidden;
  transition: transform 0.15s ease;

  &:hover {
    transform: scale(1.04);
  }
}

.widget-launcher__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.widget-panel {
  position: absolute;
  width: 380px;
  max-width: calc(100% - 32px);
  height: 500px;
  max-height: calc(100% - 88px);
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.18);
  z-index: 2;
  border: 1px solid var(--td-component-stroke);
}

.widget-panel-enter-active,
.widget-panel-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.widget-panel-enter-from,
.widget-panel-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}

.pos-bottom-right {
  .widget-launcher {
    right: 20px;
    bottom: 20px;
  }

  .widget-panel {
    right: 20px;
    bottom: 80px;
  }
}

.pos-bottom-left {
  .widget-launcher {
    left: 20px;
    bottom: 20px;
  }

  .widget-panel {
    left: 20px;
    bottom: 80px;
  }
}

.pos-top-right {
  .widget-launcher {
    right: 20px;
    top: 20px;
  }

  .widget-panel {
    right: 20px;
    top: 80px;
  }
}

.pos-top-left {
  .widget-launcher {
    left: 20px;
    top: 20px;
  }

  .widget-panel {
    left: 20px;
    top: 80px;
  }
}
</style>

<!--
  Non-scoped: make the drawer body a full-height flex host so the device frame
  / widget shell can grow to fill the available space instead of sitting as a
  fixed-height island. Namespaced under .embed-preview-drawer.
-->
<style lang="less">
.embed-preview-drawer {
  .t-drawer__body {
    display: flex;
    flex-direction: column;
    padding: 16px 18px;
  }
}
</style>
