<template>
  <div ref="sidebarRef" class="list-space-sidebar" :class="{ expanded: isExpanded, dragging: isDragging }"
    :style="{ width: isDragging ? `${dragWidth}px` : undefined }">
    <!-- Collapsed: icon strip -->
    <div v-if="!isExpanded" class="icon-strip">
      <t-tooltip v-if="!hideAll" :content="tooltipText($t('listSpaceSidebar.all'), countAll)" placement="right"
        :show-arrow="false">
        <div class="icon-item-labeled" :class="{ active: selected === 'all' }" @click="select('all')">
          <t-icon name="layers" size="16px" />
          <span class="icon-label">{{ $t('listSpaceSidebar.all') }}</span>
        </div>
      </t-tooltip>
      <t-tooltip v-if="showFavorites" :content="tooltipText($t('listSpaceSidebar.favorites'), countFavorites)"
        placement="right" :show-arrow="false">
        <div class="icon-item-labeled" :class="{ active: selected === 'favorites' }" @click="select('favorites')">
          <t-icon name="star" size="16px" />
          <span class="icon-label">{{ $t('listSpaceSidebar.favorites') }}</span>
        </div>
      </t-tooltip>
      <t-tooltip v-if="showRecents" :content="tooltipText($t('listSpaceSidebar.recents'), countRecents)"
        placement="right" :show-arrow="false">
        <div class="icon-item-labeled" :class="{ active: selected === 'recents' }" @click="select('recents')">
          <t-icon name="history" size="16px" />
          <span class="icon-label">{{ $t('listSpaceSidebar.recents') }}</span>
        </div>
      </t-tooltip>
      <t-tooltip :content="tooltipText(workspaceLabel, countMine)" placement="right" :show-arrow="false">
        <div class="icon-item-labeled workspace-item" :class="{ active: selected === 'mine' }"
          @click="select('mine')">
          <t-icon name="system-sum" size="16px" />
          <span class="icon-label">{{ workspaceLabel }}</span>
        </div>
      </t-tooltip>
    </div>

    <!-- Expanded: full nav panel -->
    <nav v-else class="expanded-panel">
      <div v-if="!hideAll" class="sidebar-item" :class="{ active: selected === 'all' }" @click="select('all')">
        <div class="item-left">
          <t-icon name="layers" class="item-icon" />
          <span class="item-label">{{ $t('listSpaceSidebar.all') }}</span>
        </div>
        <span v-if="countAll !== undefined" class="item-count">{{ countAll }}</span>
      </div>

      <div v-if="showFavorites" class="sidebar-item" :class="{ active: selected === 'favorites' }"
        @click="select('favorites')">
        <div class="item-left">
          <t-icon name="star" class="item-icon" />
          <span class="item-label">{{ $t('listSpaceSidebar.favorites') }}</span>
        </div>
        <span v-if="countFavorites > 0" class="item-count">{{ countFavorites }}</span>
      </div>
      <div v-if="showRecents" class="sidebar-item" :class="{ active: selected === 'recents' }"
        @click="select('recents')">
        <div class="item-left">
          <t-icon name="history" class="item-icon" />
          <span class="item-label">{{ $t('listSpaceSidebar.recents') }}</span>
        </div>
        <span v-if="countRecents > 0" class="item-count">{{ countRecents }}</span>
      </div>
      <div v-if="(showFavorites || showRecents)" class="sidebar-divider" />
      <div class="sidebar-item" :class="{ active: selected === 'mine' }" @click="select('mine')">
        <div class="item-left">
          <t-icon name="system-sum" class="item-icon" />
          <span class="item-label">{{ workspaceLabel }}</span>
        </div>
        <span v-if="countMine !== undefined" class="item-count">{{ countMine }}</span>
      </div>
    </nav>

    <!-- Drag handle on the right edge -->
    <div class="resize-handle" @mousedown.prevent="onDragStart">
      <div class="resize-handle-line" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { Icon as TIcon } from 'tdesign-vue-next'

const COLLAPSED_WIDTH = 56
const EXPANDED_WIDTH = 208
const SNAP_THRESHOLD = 120

const props = withDefaults(
  defineProps<{
    modelValue: string
    collapsedKey?: string
    countAll?: number
    countMine?: number
    hideAll?: boolean
    /** Favorites entry. */
    countFavorites?: number
    showFavorites?: boolean
    /** Recents entry. */
    countRecents?: number
    showRecents?: boolean
  }>(),
  {
    collapsedKey: 'sidebar-collapsed-list',
    countAll: undefined,
    countMine: undefined,
    hideAll: false,
    countFavorites: 0,
    showFavorites: true,
    countRecents: 0,
    showRecents: true,
  }
)

const storageKey = props.collapsedKey + '-expanded'
const sidebarRef = ref<HTMLElement | null>(null)
const isExpanded = ref(localStorage.getItem(storageKey) === 'true')
const isDragging = ref(false)
const dragWidth = ref(isExpanded.value ? EXPANDED_WIDTH : COLLAPSED_WIDTH)

let startX = 0
let startWidth = 0

function onDragStart(e: MouseEvent) {
  isDragging.value = true
  startX = e.clientX
  startWidth = isExpanded.value ? EXPANDED_WIDTH : COLLAPSED_WIDTH
  dragWidth.value = startWidth
  document.addEventListener('mousemove', onDragMove)
  document.addEventListener('mouseup', onDragEnd)
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
}

function onDragMove(e: MouseEvent) {
  const delta = e.clientX - startX
  const newWidth = Math.max(COLLAPSED_WIDTH, Math.min(EXPANDED_WIDTH + 20, startWidth + delta))
  dragWidth.value = newWidth
}

function onDragEnd() {
  document.removeEventListener('mousemove', onDragMove)
  document.removeEventListener('mouseup', onDragEnd)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''

  const shouldExpand = dragWidth.value >= SNAP_THRESHOLD
  isExpanded.value = shouldExpand
  localStorage.setItem(storageKey, String(shouldExpand))
  isDragging.value = false
  dragWidth.value = shouldExpand ? EXPANDED_WIDTH : COLLAPSED_WIDTH
}

function tooltipText(name: string, count?: number): string {
  return count !== undefined ? `${name} (${count})` : name
}

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()
const selected = computed({
  get: () => props.modelValue,
  set: (v: string) => emit('update:modelValue', v)
})

// workspaceLabel is the unified label for the tenant-owned bucket.
// The tenant identity is conveyed by the dedicated TenantSelector in the
// global header, so a constant i18n label loses nothing.
const workspaceLabel = computed(() => t('listSpaceSidebar.workspace'))

function select(value: string) {
  selected.value = value
}

onBeforeUnmount(() => {
  document.removeEventListener('mousemove', onDragMove)
  document.removeEventListener('mouseup', onDragEnd)
})
</script>

<style scoped lang="less">
.list-space-sidebar {
  width: 56px;
  flex-shrink: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  z-index: 10;
  transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);

  &.expanded {
    width: 208px;
    margin-right: 0;
  }

  &.dragging {
    transition: none;
  }
}

/* ========== Drag handle ========== */
.resize-handle {
  position: absolute;
  top: 0;
  right: -6px;
  bottom: 0;
  width: 12px;
  cursor: col-resize;
  z-index: 12;
  display: flex;
  align-items: center;
  justify-content: center;

  &:hover .resize-handle-line,
  .dragging & .resize-handle-line {
    opacity: 1;
    background: var(--td-brand-color);
  }
}

.resize-handle-line {
  width: 2px;
  height: 40px;
  border-radius: 1px;
  background: var(--td-bg-color-component-disabled);
  opacity: 0.45;
  transition: opacity 0.2s ease, background 0.2s ease;
}

/* ========== Icon strip (collapsed) ========== */
.icon-strip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 56px;
  padding: 12px 0 6px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.icon-item-labeled {
  width: 46px;
  padding: 5px 0 2px;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  cursor: pointer;
  color: var(--td-text-color-secondary);
  transition: all 0.15s ease;
  flex-shrink: 0;

  &:hover {
    background: var(--td-bg-color-container-hover);
    color: var(--td-text-color-primary);
  }

  &.active {
    background: var(--td-bg-color-secondarycontainer);
    color: var(--td-brand-color);

    &:hover {
      background: var(--td-bg-color-secondarycontainer);
    }

    .icon-label {
      color: var(--td-brand-color);
    }
  }

  :deep(.space-avatar) {
    width: 20px;
    height: 20px;
    font-size: 10px;
  }
}

.icon-label {
  font-size: 11px;
  line-height: 1.25;
  color: var(--td-text-color-secondary);
  max-width: 52px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: center;
  transition: color 0.15s ease;
}


.icon-strip-divider {
  width: 24px;
  height: 1px;
  background: var(--td-bg-color-secondarycontainer);
  margin: 3px 0;
  flex-shrink: 0;
}

/* ========== Expanded panel ========== */
.expanded-panel {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 8px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none;
  border-right: 1px solid var(--td-component-stroke);

  &::-webkit-scrollbar {
    display: none;
  }
}

/* ========== Nav items inside expanded panel ========== */
.sidebar-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  border-radius: 7px;
  color: var(--td-text-color-primary);
  cursor: pointer;
  transition: all 0.15s ease;
  font-family: var(--app-font-family);
  font-size: 14px;
  -webkit-font-smoothing: antialiased;

  .item-left {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    flex: 1;
  }

  .item-icon {
    flex-shrink: 0;
    color: var(--td-text-color-secondary);
    font-size: 14px;
    transition: color 0.15s ease;
  }

  .item-avatar {
    flex-shrink: 0;
  }

  .item-label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
    font-weight: 430;
    line-height: 1.4;
    letter-spacing: 0.01em;
  }

  .item-count {
    font-size: 12px;
    color: var(--td-text-color-secondary);
    font-weight: 500;
    padding: 2px 7px;
    border-radius: 8px;
    background: var(--td-bg-color-secondarycontainer);
    margin-left: 6px;
    flex-shrink: 0;
    transition: all 0.15s ease;
  }

  &:hover {
    background: var(--td-bg-color-container-hover);
    color: var(--td-text-color-primary);

    .item-icon {
      color: var(--td-text-color-primary);
    }

    .item-count {
      background: var(--td-bg-color-secondarycontainer);
      color: var(--td-text-color-primary);
    }
  }

  &.active {
    background: var(--td-bg-color-secondarycontainer);
    color: var(--td-brand-color);

    .item-icon {
      color: var(--td-brand-color);
    }

    .item-count {
      background: var(--td-bg-color-secondarycontainer);
      color: var(--td-brand-color);
    }

    &:hover {
      background: var(--td-bg-color-secondarycontainer);
    }
  }
}

.sidebar-divider {
  height: 1px;
  margin: 6px 4px;
  background: var(--td-component-stroke);
}

.sidebar-section {
  padding: 8px 6px 2px;
  margin-top: 2px;
  border-top: 1px solid var(--td-component-stroke);

  .section-title {
    font-size: 12px;
    color: var(--td-text-color-secondary);
    font-weight: 600;
    line-height: 1.4;
  }
}
</style>
