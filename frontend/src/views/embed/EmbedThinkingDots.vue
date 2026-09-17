<template>
  <span class="embed-thinking-dots" role="status" :aria-label="t('chat.thinkingAlt')">
    <span v-for="n in 3" :key="n" class="embed-thinking-dots__dot" aria-hidden="true"
      :style="{ animationDelay: `${(n - 1) * 0.18}s` }" />
  </span>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<style scoped lang="less">
.embed-thinking-dots {
  // Block-level flex (not inline-flex): an inline-level box would sit on the
  // text baseline and inherit the line box's strut, so the dots never centered
  // vertically inside the bubble.
  display: flex;
  align-items: center;
  gap: 4px;
  width: fit-content;
  height: 14px;
  padding: 0 2px;
  font-size: 0;

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--td-brand-color);
    animation: embed-thinking-dot 1.2s ease-in-out infinite;
  }
}

@keyframes embed-thinking-dot {
  0%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(-2px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .embed-thinking-dots__dot {
    animation: none;
    opacity: 0.6;
  }
}
</style>
