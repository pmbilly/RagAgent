<template>
  <div class="agent-list-container">
    <ListSpaceSidebar v-if="!authStore.isLiteMode" v-model="spaceSelection" :count-all="allAgentsCount"
      :count-mine="agents.length" :count-favorites="agentFavoritesCount"
      :count-recents="agentRecentsCount" />
    <div class="agent-list-content">
      <div class="header" style="--wails-draggable: drag">
        <div class="header-title" style="--wails-draggable: drag">
          <div class="title-row" style="--wails-draggable: drag">
            <h2 style="--wails-draggable: drag">{{ $t('agent.title') }}</h2>
            <t-tooltip v-if="authStore.hasRole('contributor')" :content="$t('agent.createAgent')" placement="bottom">
              <t-button variant="text" theme="default" size="small" class="header-action-btn"
                data-guide="agent-list-create" style="--wails-draggable: no-drag" @click="handleCreateAgent">
                <template #icon>
                  <span class="btn-icon-wrapper">
                    <svg class="sparkles-icon" width="19" height="19" viewBox="0 0 20 20" fill="none"
                      xmlns="http://www.w3.org/2000/svg">
                      <path
                        d="M10 3L10.8 6.2C10.9 6.7 11.3 7.1 11.8 7.2L15 8L11.8 8.8C11.3 8.9 10.9 9.3 10.8 9.8L10 13L9.2 9.8C9.1 9.3 8.7 8.9 8.2 8.8L5 8L8.2 7.2C8.7 7.1 9.1 6.7 9.2 6.2L10 3Z"
                        fill="currentColor" stroke="currentColor" stroke-width="0.8" stroke-linecap="round"
                        stroke-linejoin="round" />
                      <path
                        d="M15.5 4L15.8 5.2C15.85 5.45 16.05 5.65 16.3 5.7L17.5 6L16.3 6.3C16.05 6.35 15.85 6.55 15.8 6.8L15.5 8L15.2 6.8C15.15 6.55 14.95 6.35 14.7 6.3L13.5 6L14.7 5.7C14.95 5.65 15.15 5.45 15.2 5.2L15.5 4Z"
                        fill="currentColor" stroke="currentColor" stroke-width="0.6" stroke-linecap="round"
                        stroke-linejoin="round" />
                      <path
                        d="M4.5 13L4.8 14.2C4.85 14.45 5.05 14.65 5.3 14.7L6.5 15L5.3 15.3C5.05 15.35 4.85 15.55 4.8 15.8L4.5 17L4.2 15.8C4.15 15.55 3.95 15.35 3.7 15.3L2.5 15L3.7 14.7C3.95 14.65 4.15 14.45 4.2 14.2L4.5 13Z"
                        fill="currentColor" stroke="currentColor" stroke-width="0.6" stroke-linecap="round"
                        stroke-linejoin="round" />
                    </svg>
                  </span>
                </template>
              </t-button>
            </t-tooltip>
          </div>
          <p class="header-subtitle" style="--wails-draggable: drag">{{ $t('agent.subtitle') }}</p>
        </div>
      </div>
      <div class="agent-list-main">
        <!-- creator filter removed; see KnowledgeBaseList for rationale.
             Card-level creator display + URL-state field are retained. -->

        <!-- 骨架屏占位 -->
        <div v-if="loading && agents.length === 0" class="agent-card-wrap">
          <div v-for="n in 6" :key="'skel-' + n" class="agent-card agent-card-skeleton">
            <div class="card-header">
              <div class="card-header-left">
                <t-skeleton animation="gradient"
                  :row-col="[[{ width: '32px', height: '32px', type: 'circle' }, { width: '40%', height: '18px' }]]" />
              </div>
            </div>
            <div class="card-content">
              <t-skeleton animation="gradient"
                :row-col="[{ width: '100%', height: '14px' }, { width: '70%', height: '14px' }]" />
            </div>
            <div class="card-bottom">
              <t-skeleton animation="gradient"
                :row-col="[[{ width: '60px', height: '22px', type: 'rect' }, { width: '60px', height: '22px', type: 'rect' }]]" />
            </div>
          </div>
        </div>

        <!-- 全部 / 收藏 / 最近：共用同一份卡片模板 -->
        <div
          v-if="(spaceSelection === 'all' || spaceSelection === 'favorites' || spaceSelection === 'recents') && filteredAgents.length > 0"
          class="agent-card-wrap">
          <template v-for="(agent, index) in filteredAgents" :key="agent.id">
            <!-- 内置：始终置顶。filteredAgents 在 all 视图里已经把
                 builtin 排到最前；这里只在第一张 builtin 之前打一次标题。 -->
            <div v-if="showShareGroupHeaders
              && agent.isMine
              && agent.builtin
              && (index === 0
                || !filteredAgents[index - 1].isMine
                || !(filteredAgents[index - 1] as AgentWithUI).builtin)" class="agent-section-header" role="button"
              tabindex="0" @click="toggleAgentSection('builtin')"
              @keydown.enter.prevent="toggleAgentSection('builtin')"
              @keydown.space.prevent="toggleAgentSection('builtin')">
              <t-icon name="app" size="14px" />
              <span>{{ $t('agent.sections.builtin') }}</span>
              <span class="agent-section-count">{{ filteredAgentSectionCounts.builtin }}</span>
              <t-icon class="agent-section-toggle"
                :name="isAgentSectionCollapsed('builtin') ? 'chevron-right' : 'chevron-down'" size="14px" />
            </div>
            <!-- 我创建的：当前 agent 是本空间 + 非内置 + 我亲手创建，且前一张
                 要么不存在、要么不是本空间、要么是内置（builtin → mine 过渡）、
                 要么是同事创建。与 KB 列表对齐。 -->
            <div v-if="showShareGroupHeaders
              && agent.isMine
              && !agent.builtin
              && isMyAgent(agent)
              && (index === 0
                || !filteredAgents[index - 1].isMine
                || (filteredAgents[index - 1] as AgentWithUI).builtin
                || !isMyAgent(filteredAgents[index - 1] as AgentWithUI))" class="agent-section-header" role="button"
              tabindex="0" @click="toggleAgentSection('mine')"
              @keydown.enter.prevent="toggleAgentSection('mine')"
              @keydown.space.prevent="toggleAgentSection('mine')">
              <t-icon name="user" size="14px" />
              <span>{{ $t('agent.sections.mine') }}</span>
              <span class="agent-section-count">{{ filteredAgentSectionCounts.mine }}</span>
              <t-icon class="agent-section-toggle"
                :name="isAgentSectionCollapsed('mine') ? 'chevron-right' : 'chevron-down'" size="14px" />
            </div>
            <!-- 本空间 · 仅查看 / 其他成员：本空间里非内置且非我创建的同事 agent。 -->
            <div v-if="showShareGroupHeaders
              && agent.isMine
              && !agent.builtin
              && !isMyAgent(agent)
              && (index === 0
                || !filteredAgents[index - 1].isMine
                || (filteredAgents[index - 1] as AgentWithUI).builtin
                || isMyAgent(filteredAgents[index - 1] as AgentWithUI))" class="agent-section-header" role="button"
              tabindex="0" @click="toggleAgentSection('tenantOthers')"
              @keydown.enter.prevent="toggleAgentSection('tenantOthers')"
              @keydown.space.prevent="toggleAgentSection('tenantOthers')">
              <t-icon :name="tenantSectionIconName" size="14px" />
              <span>{{ $t(tenantSectionLabelKey) }}</span>
              <span class="agent-section-count">{{ filteredAgentSectionCounts.tenantOthers }}</span>
              <t-icon class="agent-section-toggle"
                :name="isAgentSectionCollapsed('tenantOthers') ? 'chevron-right' : 'chevron-down'" size="14px" />
            </div>
            <div v-show="!isAgentRowHidden(agent)" class="agent-card" :class="{
              'is-builtin': agent.builtin,
              'agent-mode-normal': agent.config?.agentMode === 'quick-answer',
              'agent-mode-agent': agent.config?.agentMode === 'smart-reasoning'
            }" @click="handleCardClick(agent)">
              <!-- 装饰星星 -->
              <div class="card-decoration">
                <svg class="star-icon" width="24" height="24" viewBox="0 0 20 20" fill="none"
                  xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M10 3L10.8 6.2C10.9 6.7 11.3 7.1 11.8 7.2L15 8L11.8 8.8C11.3 8.9 10.9 9.3 10.8 9.8L10 13L9.2 9.8C9.1 9.3 8.7 8.9 8.2 8.8L5 8L8.2 7.2C8.7 7.1 9.1 6.7 9.2 6.2L10 3Z"
                    stroke="currentColor" stroke-width="0.8" stroke-linecap="round" stroke-linejoin="round"
                    fill="currentColor" fill-opacity="0.15" />
                </svg>
                <svg class="star-icon small" width="14" height="14" viewBox="0 0 20 20" fill="none"
                  xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M10 3L10.8 6.2C10.9 6.7 11.3 7.1 11.8 7.2L15 8L11.8 8.8C11.3 8.9 10.9 9.3 10.8 9.8L10 13L9.2 9.8C9.1 9.3 8.7 8.9 8.2 8.8L5 8L8.2 7.2C8.7 7.1 9.1 6.7 9.2 6.2L10 3Z"
                    stroke="currentColor" stroke-width="0.8" stroke-linecap="round" stroke-linejoin="round"
                    fill="currentColor" fill-opacity="0.15" />
                </svg>
              </div>
              <!-- 收藏按钮：浮在卡片右上角；.card-header padding-right 已为
                   "更多"按钮腾出空间，避免重叠。 -->
              <button type="button" class="agent-favorite-star"
                :class="{ 'is-favorited': isAgentFavorited(agent.id) }"
                @click.stop="toggleFavoriteAgent(agent.id, $event)">
                <t-icon :name="isAgentFavorited(agent.id) ? 'star-filled' : 'star'" size="14px" />
              </button>
              <div class="card-header">
                <div class="card-header-left">
                  <div v-if="agent.builtin" class="builtin-avatar"
                    :class="agent.config?.agentMode === 'smart-reasoning' ? 'agent' : 'normal'">
                    <t-icon :name="agent.config?.agentMode === 'smart-reasoning' ? 'control-platform' : 'chat'"
                      size="18px" />
                  </div>
                  <div v-else-if="agent.avatar" class="builtin-avatar agent-emoji">{{ agent.avatar }}</div>
                  <AgentAvatar v-else :name="agent.name" size="small" />
                  <span class="card-title" :title="agent.name">{{ agent.name }}</span>
                </div>
                <t-popup
                  v-if="canManageAgent(agent) || authStore.hasRole('contributor') || authStore.hasRole('admin')"
                  :visible="openMoreAgentId === agent.id" trigger="hover" overlayClassName="card-more-popup"
                  destroy-on-close placement="bottom-right" @visible-change="onVisibleChange"
                  @update:visible="(v: boolean) => { if (!v) openMoreAgentId = null }">
                  <div class="more-wrap" :class="{ 'active-more': openMoreAgentId === agent.id }"
                    @click="toggleMore($event, agent.id)">
                    <img class="more-icon" src="@/assets/img/more.png" alt="" />
                  </div>
                  <template #content>
                    <div class="popup-menu">
                      <div v-if="canManageAgent(agent)" class="popup-menu-item" @click="handleEdit(agent)"><t-icon
                          class="menu-icon" name="edit" /><span>{{ $t('common.edit') }}</span></div>
                      <div v-if="authStore.hasRole('contributor')" class="popup-menu-item" @click="handleCopy(agent)">
                        <t-icon class="menu-icon" name="file-copy" /><span>{{ $t('common.copy') }}</span>
                      </div>
                      <div v-if="!agent.builtin && canManageAgent(agent)" class="popup-menu-item delete"
                        @click="handleDelete(agent)"><t-icon class="menu-icon" name="delete" /><span>{{
                          $t('common.delete') }}</span></div>
                    </div>
                  </template>
                </t-popup>
              </div>
              <div class="card-content">
                <div class="card-description">{{ agent.description || $t('agent.noDescription') }}</div>
              </div>
              <div class="card-bottom">
                <div class="bottom-left">
                  <div class="feature-badges">
                    <t-tag v-if="agent.disabled_by_me" theme="default" size="small" class="disabled-badge">{{
                      $t('agent.disabled') }}</t-tag>
                    <t-tooltip
                      :content="agent.config?.agentMode === 'smart-reasoning' ? $t('agent.mode.agent') : $t('agent.mode.normal')"
                      placement="top">
                      <div class="feature-badge"
                        :class="{ 'mode-normal': agent.config?.agentMode === 'quick-answer', 'mode-agent': agent.config?.agentMode === 'smart-reasoning' }">
                        <t-icon :name="agent.config?.agentMode === 'smart-reasoning' ? 'control-platform' : 'chat'"
                          size="14px" />
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.webSearchEnabled" :content="$t('agent.features.webSearch')"
                      placement="top">
                      <div class="feature-badge web-search">
                        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.2" fill="none" />
                          <ellipse cx="8" cy="8" rx="2.5" ry="6" stroke="currentColor" stroke-width="1.2" fill="none" />
                          <line x1="2" y1="6" x2="14" y2="6" stroke="currentColor" stroke-width="1.2" />
                          <line x1="2" y1="10" x2="14" y2="10" stroke="currentColor" stroke-width="1.2" />
                        </svg>
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.knowledgeBases?.length || agent.config?.kbSelectionMode === 'all'"
                      :content="$t('agent.features.knowledgeBase')" placement="top">
                      <div class="feature-badge knowledge">
                        <t-icon name="folder" size="16px" />
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.mcpServices?.length || agent.config?.mcpSelectionMode === 'all'"
                      :content="$t('agent.features.mcp')" placement="top">
                      <div class="feature-badge mcp">
                        <t-icon name="extension" size="16px" />
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.multiTurnEnabled" :content="$t('agent.features.multiTurn')"
                      placement="top">
                      <div class="feature-badge multi-turn">
                        <t-icon name="chat-bubble" size="16px" />
                      </div>
                    </t-tooltip>
                  </div>
                </div>
                <!-- 右下角：内置 / 来源徽章 -->
                <div v-if="showAgentBuiltinBadge(agent)" class="builtin-badge">
                  <t-icon name="lock-on" size="12px" />
                  <span>{{ $t('agent.builtin') }}</span>
                </div>
                <ResourceOriginBadge v-else-if="showAgentOriginBadge(agent)" :variant="agentOriginVariant(agent)"
                  :creator-name="(agent as any).creatorName" />
              </div>
            </div>
          </template>
        </div>

        <!-- 我的智能体 -->
        <div v-if="spaceSelection === 'mine' && sortedMineAgents.length > 0" class="agent-card-wrap">
          <template v-for="(agent, index) in sortedMineAgents" :key="agent.id">
            <!-- 内置：始终置顶。sortedMineAgents 已按 内置→我→同事 排序。 -->
            <div v-if="showShareGroupHeaders
              && agent.builtin
              && (index === 0 || !sortedMineAgents[index - 1].builtin)" class="agent-section-header" role="button"
              tabindex="0" @click="toggleAgentSection('builtin')"
              @keydown.enter.prevent="toggleAgentSection('builtin')"
              @keydown.space.prevent="toggleAgentSection('builtin')">
              <t-icon name="app" size="14px" />
              <span>{{ $t('agent.sections.builtin') }}</span>
              <span class="agent-section-count">{{ mineAgentSectionCounts.builtin }}</span>
              <t-icon class="agent-section-toggle"
                :name="isAgentSectionCollapsed('builtin') ? 'chevron-right' : 'chevron-down'" size="14px" />
            </div>
            <!-- 我创建的：第一张非内置且我亲手创建的卡片前打标题 -->
            <div v-if="showShareGroupHeaders
              && !agent.builtin
              && isMyAgent(agent)
              && (index === 0
                || sortedMineAgents[index - 1].builtin
                || !isMyAgent(sortedMineAgents[index - 1]))" class="agent-section-header" role="button"
              tabindex="0" @click="toggleAgentSection('mine')"
              @keydown.enter.prevent="toggleAgentSection('mine')"
              @keydown.space.prevent="toggleAgentSection('mine')">
              <t-icon name="user" size="14px" />
              <span>{{ $t('agent.sections.mine') }}</span>
              <span class="agent-section-count">{{ mineAgentSectionCounts.mine }}</span>
              <t-icon class="agent-section-toggle"
                :name="isAgentSectionCollapsed('mine') ? 'chevron-right' : 'chevron-down'" size="14px" />
            </div>
            <!-- 本空间 · 仅查看 / 其他成员：非内置且非我创建的同事 agent -->
            <div v-if="showShareGroupHeaders
              && !agent.builtin
              && !isMyAgent(agent)
              && (index === 0
                || sortedMineAgents[index - 1].builtin
                || isMyAgent(sortedMineAgents[index - 1]))" class="agent-section-header" role="button"
              tabindex="0" @click="toggleAgentSection('tenantOthers')"
              @keydown.enter.prevent="toggleAgentSection('tenantOthers')"
              @keydown.space.prevent="toggleAgentSection('tenantOthers')">
              <t-icon :name="tenantSectionIconName" size="14px" />
              <span>{{ $t(tenantSectionLabelKey) }}</span>
              <span class="agent-section-count">{{ mineAgentSectionCounts.tenantOthers }}</span>
              <t-icon class="agent-section-toggle"
                :name="isAgentSectionCollapsed('tenantOthers') ? 'chevron-right' : 'chevron-down'" size="14px" />
            </div>
            <div v-show="!isAgentRowHidden(agent)" class="agent-card" :class="{
              'is-builtin': agent.builtin,
              'agent-mode-normal': agent.config?.agentMode === 'quick-answer',
              'agent-mode-agent': agent.config?.agentMode === 'smart-reasoning'
            }" @click="handleCardClick(agent)">
              <!-- 装饰星星 -->
              <div class="card-decoration">
                <svg class="star-icon" width="24" height="24" viewBox="0 0 20 20" fill="none"
                  xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M10 3L10.8 6.2C10.9 6.7 11.3 7.1 11.8 7.2L15 8L11.8 8.8C11.3 8.9 10.9 9.3 10.8 9.8L10 13L9.2 9.8C9.1 9.3 8.7 8.9 8.2 8.8L5 8L8.2 7.2C8.7 7.1 9.1 6.7 9.2 6.2L10 3Z"
                    stroke="currentColor" stroke-width="0.8" stroke-linecap="round" stroke-linejoin="round"
                    fill="currentColor" fill-opacity="0.15" />
                </svg>
                <svg class="star-icon small" width="14" height="14" viewBox="0 0 20 20" fill="none"
                  xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M10 3L10.8 6.2C10.9 6.7 11.3 7.1 11.8 7.2L15 8L11.8 8.8C11.3 8.9 10.9 9.3 10.8 9.8L10 13L9.2 9.8C9.1 9.3 8.7 8.9 8.2 8.8L5 8L8.2 7.2C8.7 7.1 9.1 6.7 9.2 6.2L10 3Z"
                    stroke="currentColor" stroke-width="0.8" stroke-linecap="round" stroke-linejoin="round"
                    fill="currentColor" fill-opacity="0.15" />
                </svg>
              </div>

              <button type="button" class="agent-favorite-star"
                :class="{ 'is-favorited': isAgentFavorited(agent.id) }"
                @click.stop="toggleFavoriteAgent(agent.id, $event)">
                <t-icon :name="isAgentFavorited(agent.id) ? 'star-filled' : 'star'" size="14px" />
              </button>
              <!-- 卡片头部 -->
              <div class="card-header">
                <div class="card-header-left">
                  <!-- 内置智能体使用简洁图标 -->
                  <div v-if="agent.builtin" class="builtin-avatar"
                    :class="agent.config?.agentMode === 'smart-reasoning' ? 'agent' : 'normal'">
                    <t-icon :name="agent.config?.agentMode === 'smart-reasoning' ? 'control-platform' : 'chat'"
                      size="18px" />
                  </div>
                  <div v-else-if="agent.avatar" class="builtin-avatar agent-emoji">{{ agent.avatar }}</div>
                  <AgentAvatar v-else :name="agent.name" size="small" />
                  <span class="card-title" :title="agent.name">{{ agent.name }}</span>
                </div>
                <t-popup v-if="canManageAgent(agent) || authStore.hasRole('contributor') || authStore.hasRole('admin')"
                  :visible="openMoreAgentId === agent.id" trigger="hover" overlayClassName="card-more-popup"
                  destroy-on-close placement="bottom-right" @visible-change="onVisibleChange"
                  @update:visible="(v: boolean) => { if (!v) openMoreAgentId = null }">
                  <div class="more-wrap" :class="{ 'active-more': openMoreAgentId === agent.id }"
                    @click="toggleMore($event, agent.id)">
                    <img class="more-icon" src="@/assets/img/more.png" alt="" />
                  </div>
                  <template #content>
                    <div class="popup-menu">
                      <div v-if="canManageAgent(agent)" class="popup-menu-item" @click="handleEdit(agent)">
                        <t-icon class="menu-icon" name="edit" />
                        <span>{{ $t('common.edit') }}</span>
                      </div>
                      <div v-if="authStore.hasRole('contributor')" class="popup-menu-item" @click="handleCopy(agent)">
                        <t-icon class="menu-icon" name="file-copy" />
                        <span>{{ $t('common.copy') }}</span>
                      </div>
                      <div v-if="!agent.builtin && canManageAgent(agent)" class="popup-menu-item delete"
                        @click="handleDelete(agent)">
                        <t-icon class="menu-icon" name="delete" />
                        <span>{{ $t('common.delete') }}</span>
                      </div>
                    </div>
                  </template>
                </t-popup>
              </div>

              <!-- 卡片内容 -->
              <div class="card-content">
                <div class="card-description">
                  {{ agent.description || $t('agent.noDescription') }}
                </div>
              </div>

              <!-- 卡片底部 -->
              <div class="card-bottom">
                <div class="bottom-left">
                  <div class="feature-badges">
                    <t-tag v-if="agent.disabled_by_me" theme="default" size="small" class="disabled-badge">{{
                      $t('agent.disabled') }}</t-tag>
                    <t-tooltip
                      :content="agent.config?.agentMode === 'smart-reasoning' ? $t('agent.mode.agent') : $t('agent.mode.normal')"
                      placement="top">
                      <div class="feature-badge"
                        :class="{ 'mode-normal': agent.config?.agentMode === 'quick-answer', 'mode-agent': agent.config?.agentMode === 'smart-reasoning' }">
                        <t-icon :name="agent.config?.agentMode === 'smart-reasoning' ? 'control-platform' : 'chat'"
                          size="14px" />
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.webSearchEnabled" :content="$t('agent.features.webSearch')"
                      placement="top">
                      <div class="feature-badge web-search">
                        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.2" fill="none" />
                          <ellipse cx="8" cy="8" rx="2.5" ry="6" stroke="currentColor" stroke-width="1.2" fill="none" />
                          <line x1="2" y1="6" x2="14" y2="6" stroke="currentColor" stroke-width="1.2" />
                          <line x1="2" y1="10" x2="14" y2="10" stroke="currentColor" stroke-width="1.2" />
                        </svg>
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.knowledgeBases?.length || agent.config?.kbSelectionMode === 'all'"
                      :content="$t('agent.features.knowledgeBase')" placement="top">
                      <div class="feature-badge knowledge">
                        <t-icon name="folder" size="16px" />
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.mcpServices?.length || agent.config?.mcpSelectionMode === 'all'"
                      :content="$t('agent.features.mcp')" placement="top">
                      <div class="feature-badge mcp">
                        <t-icon name="extension" size="16px" />
                      </div>
                    </t-tooltip>
                    <t-tooltip v-if="agent.config?.multiTurnEnabled" :content="$t('agent.features.multiTurn')"
                      placement="top">
                      <div class="feature-badge multi-turn">
                        <t-icon name="chat-bubble" size="16px" />
                      </div>
                    </t-tooltip>
                  </div>
                </div>
                <!-- 右下角：内置 / 来源徽章（我创建 / 同空间其他成员） -->
                <div v-if="showAgentBuiltinBadge(agent)" class="builtin-badge">
                  <t-icon name="lock-on" size="12px" />
                  <span>{{ $t('agent.builtin') }}</span>
                </div>
                <ResourceOriginBadge v-else-if="showAgentOriginBadge(agent)" :variant="agentOriginVariant(agent)"
                  :creator-name="(agent as any).creatorName" />
              </div>
            </div>
          </template>
        </div>


        <!-- 空状态：全部（保留创建 CTA） -->
        <div v-if="spaceSelection === 'all' && filteredAgents.length === 0 && !loading" class="empty-state">
          <img class="empty-img" src="@/assets/img/upload.svg" alt="">
          <span class="empty-txt">{{ $t('agent.empty.title') }}</span>
          <span class="empty-desc">{{ $t('agent.empty.description') }}</span>
          <t-button v-if="authStore.hasRole('contributor')" class="agent-create-btn empty-state-btn"
            data-guide="agent-list-create" @click="handleCreateAgent">
            <template #icon>
              <span class="btn-icon-wrapper">
                <svg class="sparkles-icon" width="18" height="18" viewBox="0 0 20 20" fill="none"
                  xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M10 3L10.8 6.2C10.9 6.7 11.3 7.1 11.8 7.2L15 8L11.8 8.8C11.3 8.9 10.9 9.3 10.8 9.8L10 13L9.2 9.8C9.1 9.3 8.7 8.9 8.2 8.8L5 8L8.2 7.2C8.7 7.1 9.1 6.7 9.2 6.2L10 3Z"
                    fill="currentColor" stroke="currentColor" stroke-width="0.8" stroke-linecap="round"
                    stroke-linejoin="round" />
                  <path
                    d="M15.5 4L15.8 5.2C15.85 5.45 16.05 5.65 16.3 5.7L17.5 6L16.3 6.3C16.05 6.35 15.85 6.55 15.8 6.8L15.5 8L15.2 6.8C15.15 6.55 14.95 6.35 14.7 6.3L13.5 6L14.7 5.7C14.95 5.65 15.15 5.45 15.2 5.2L15.5 4Z"
                    fill="currentColor" stroke="currentColor" stroke-width="0.6" stroke-linecap="round"
                    stroke-linejoin="round" />
                  <path
                    d="M4.5 13L4.8 14.2C4.85 14.45 5.05 14.65 5.3 14.7L6.5 15L5.3 15.3C5.05 15.35 4.85 15.55 4.8 15.8L4.5 17L4.2 15.8C4.15 15.55 3.95 15.35 3.7 15.3L2.5 15L3.7 14.7C3.95 14.65 4.15 14.45 4.2 14.2L4.5 13Z"
                    fill="currentColor" stroke="currentColor" stroke-width="0.6" stroke-linecap="round"
                    stroke-linejoin="round" />
                </svg>
              </span>
            </template>
            <span>{{ $t('agent.createAgent') }}</span>
          </t-button>
        </div>

        <!-- 空状态：收藏 / 最近 — 不放创建按钮，参见 KnowledgeBaseList 的同处理由 -->
        <div v-if="spaceSelection === 'favorites' && filteredAgents.length === 0 && !loading" class="empty-state">
          <t-icon name="star" size="48px" class="empty-icon" />
          <span class="empty-txt">{{ $t('agent.empty.favoritesTitle') }}</span>
          <span class="empty-desc">{{ $t('agent.empty.favoritesDescription') }}</span>
        </div>
        <div v-if="spaceSelection === 'recents' && filteredAgents.length === 0 && !loading" class="empty-state">
          <t-icon name="history" size="48px" class="empty-icon" />
          <span class="empty-txt">{{ $t('agent.empty.recentsTitle') }}</span>
          <span class="empty-desc">{{ $t('agent.empty.recentsDescription') }}</span>
        </div>
        <!-- 空状态：我的 -->
        <div v-if="spaceSelection === 'mine' && agents.length === 0 && !loading" class="empty-state">
          <img class="empty-img" src="@/assets/img/upload.svg" alt="">
          <span class="empty-txt">{{ $t('agent.empty.title') }}</span>
          <span class="empty-desc">{{ $t('agent.empty.description') }}</span>
          <t-button v-if="authStore.hasRole('contributor')" class="agent-create-btn empty-state-btn"
            @click="handleCreateAgent">
            <template #icon>
              <span class="btn-icon-wrapper">
                <svg class="sparkles-icon" width="18" height="18" viewBox="0 0 20 20" fill="none"
                  xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M10 3L10.8 6.2C10.9 6.7 11.3 7.1 11.8 7.2L15 8L11.8 8.8C11.3 8.9 10.9 9.3 10.8 9.8L10 13L9.2 9.8C9.1 9.3 8.7 8.9 8.2 8.8L5 8L8.2 7.2C8.7 7.1 9.1 6.7 9.2 6.2L10 3Z"
                    fill="currentColor" stroke="currentColor" stroke-width="0.8" stroke-linecap="round"
                    stroke-linejoin="round" />
                  <path
                    d="M15.5 4L15.8 5.2C15.85 5.45 16.05 5.65 16.3 5.7L17.5 6L16.3 6.3C16.05 6.35 15.85 6.55 15.8 6.8L15.5 8L15.2 6.8C15.15 6.55 14.95 6.35 14.7 6.3L13.5 6L14.7 5.7C14.95 5.65 15.15 5.45 15.2 5.2L15.5 4Z"
                    fill="currentColor" stroke="currentColor" stroke-width="0.6" stroke-linecap="round"
                    stroke-linejoin="round" />
                  <path
                    d="M4.5 13L4.8 14.2C4.85 14.45 5.05 14.65 5.3 14.7L6.5 15L5.3 15.3C5.05 15.35 4.85 15.55 4.8 15.8L4.5 17L4.2 15.8C4.15 15.55 3.95 15.35 3.7 15.3L2.5 15L3.7 14.7C3.95 14.65 4.15 14.45 4.2 14.2L4.5 13Z"
                    fill="currentColor" stroke="currentColor" stroke-width="0.6" stroke-linecap="round"
                    stroke-linejoin="round" />
                </svg>
              </span>
            </template>
            <span>{{ $t('agent.createAgent') }}</span>
          </t-button>
        </div>
      </div>
    </div>

    <!-- 删除确认对话框 -->
    <t-dialog v-model:visible="deleteVisible" dialogClassName="del-agent-dialog" :closeBtn="false" :cancelBtn="null"
      :confirmBtn="null">
      <div class="circle-wrap">
        <div class="dialog-header">
          <img class="circle-img" src="@/assets/img/circle.png" alt="">
          <span class="circle-title">{{ $t('agent.delete.confirmTitle') }}</span>
        </div>
        <span class="del-circle-txt">
          {{ $t('agent.delete.confirmMessage', { name: deletingAgent?.name ?? '' }) }}
        </span>
        <div class="circle-btn">
          <span class="circle-btn-txt" @click="deleteVisible = false">{{ $t('common.cancel') }}</span>
          <span class="circle-btn-txt confirm" @click="confirmDelete">{{ $t('agent.delete.confirmButton') }}</span>
        </div>
      </div>
    </t-dialog>

    <!-- 智能体编辑器弹窗 -->
    <AgentEditorModal :visible="editorVisible" :mode="editorMode" :agent="editingAgent"
      :initialSection="editorInitialSection"
      :initialHighlightField="editorInitialHighlightField"
      :readOnly="editorMode === 'edit' && editingAgent != null && !canManageAgent(editingAgent as AgentWithUI)"
      @update:visible="editorVisible = $event" @success="handleEditorSuccess" />

    <TenantModelsGuide :when="showAgentTenantModelsGuide" variant="agent" />
    <ContextualGuide tour="agentList" :when="showAgentListContextualGuide" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MessagePlugin, Icon as TIcon } from 'tdesign-vue-next'
import { deleteAgent, copyAgent, type CustomAgent } from '@/api/agent'
import { useChatResourcesStore } from '@/stores/chatResources'
import { formatStringDate } from '@/utils/index'
import { useI18n } from 'vue-i18n'
import AgentEditorModal from './AgentEditorModal.vue'
import ContextualGuide from '@/components/ContextualGuide.vue'
import TenantModelsGuide from '@/components/TenantModelsGuide.vue'
import { focusAgentEditorSection, markContextualGuideDone } from '@/config/contextualGuides'
import { useTenantModelReadiness } from '@/composables/useTenantModelReadiness'
import { useUIStore } from '@/stores/ui'
import AgentAvatar from '@/components/AgentAvatar.vue'
import ListSpaceSidebar from '@/components/ListSpaceSidebar.vue'
import ResourceOriginBadge from '@/components/ResourceOriginBadge.vue'
import { useAuthStore } from '@/stores/auth'
import { useListUrlState } from '@/composables/useListUrlState'
import { useResourcePins } from '@/composables/useResourcePins'
import { integrationSectionKey } from '@/config/settingsRoute'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const uiStore = useUIStore()
const chatResources = useChatResourcesStore()
const { loaded: modelsReadyLoaded, isReadyForAgent } = useTenantModelReadiness()

interface AgentWithUI extends CustomAgent {
  showMore?: boolean
  /** 当前空间在对话下拉中停用（仅影响本空间；随空间分享裁撤，恒为 false） */
  disabled_by_me?: boolean
}

/** "all" / favorites / recents 视图里的条目形态：本空间自有 agent。 */
type DisplayAgent = AgentWithUI & { isMine: true }

// 左侧空间选择：默认根据当前角色决定。
// 与 KnowledgeBaseList 同款逻辑：Viewer 在当前空间里通常没有自建智能体，
// 默认落到 "all" 才能看到内置 + 共享给我的；Contributor 以上仍默认 "mine"。
// State synced to `?scope=` so links are shareable. The "mine" value is
// retained for back-compat with existing links; its display label is
// rebranded to the active tenant name inside ListSpaceSidebar.
const defaultScope: 'all' | 'mine' = authStore.hasRole('contributor') ? 'mine' : 'all'
const { scope: spaceSelection, creator: creatorFilter } = useListUrlState({
  defaultScope,
  defaultCreator: 'all',
})

// Per-user favorites + recents (localStorage-backed). See useResourcePins.
const pins = useResourcePins()
const agentFavoritesCount = computed(
  () => pins.favorites.value.filter((e) => e.type === 'agent').length
)
const agentRecentsCount = computed(
  () => pins.recents.value.filter((e) => e.type === 'agent').length
)
const agents = ref<AgentWithUI[]>([])
const allAgentsCount = computed(() => agents.value.length)

// Favorites / Recents view: hydrate pinned ids against own agents.
const agentResourceIndex = computed(() => {
  const map = new Map<string, AgentWithUI>()
  for (const a of agents.value) map.set(a.id, a)
  return map
})

const favoritesAgentList = computed<DisplayAgent[]>(() => {
  return pins.favorites.value
    .filter((e) => e.type === 'agent')
    .map((e) => agentResourceIndex.value.get(e.id))
    .filter((agent): agent is AgentWithUI => !!agent)
    .map((agent) => ({ ...agent, isMine: true as const, showMore: false }))
})

const recentsAgentList = computed<DisplayAgent[]>(() => {
  return pins.recents.value
    .filter((e) => e.type === 'agent')
    .map((e) => agentResourceIndex.value.get(e.id))
    .filter((agent): agent is AgentWithUI => !!agent)
    .map((agent) => ({ ...agent, isMine: true as const, showMore: false }))
})

const filteredAgents = computed<DisplayAgent[]>(() => {
  if (spaceSelection.value === 'favorites') return favoritesAgentList.value
  if (spaceSelection.value === 'recents') return recentsAgentList.value
  if (spaceSelection.value === 'mine') {
    return agents.value.map(a => ({ ...a, isMine: true as const }))
  }
  if (spaceSelection.value !== 'all') return []
  const list: DisplayAgent[] = []
  // 本空间内的 agent 拆成 内置 → 我创建 → 同事创建 三段。
  // 内置（builtin=true）和"个人所有权"是两个维度的概念，置顶为单独
  // 一段；它们的 createdBy 始终为空，跟在「同事/无创建者」桶里反而让
  // tenantOthers 段同时混入"系统内置 + 历史无 owner 的自定义"两类，
  // 语义不清。
  const builtin: AgentWithUI[] = []
  const ownMine: AgentWithUI[] = []
  const teammateMine: AgentWithUI[] = []
  agents.value.forEach(a => {
    if (a.builtin) builtin.push(a)
    else if (isMyAgent(a)) ownMine.push(a)
    else teammateMine.push(a)
  })
  builtin.forEach(a => list.push({ ...a, isMine: true as const }))
  ownMine.forEach(a => list.push({ ...a, isMine: true as const }))
  teammateMine.forEach(a => list.push({ ...a, isMine: true as const }))
  return list
})

// 「工作空间」视图下的稳定排序：本空间内「我创建」在前、「同事创建 / 内建」
// 在后。给 contributor 视图把「本空间 · 仅查看」分组标题正好插在过渡处。
const sortedMineAgents = computed(() => {
  // 内置 → 我创建 → 同事创建。与 filteredAgents 的"全部"视图保持同序。
  const builtin: AgentWithUI[] = []
  const own: AgentWithUI[] = []
  const teammate: AgentWithUI[] = []
  agents.value.forEach(a => {
    if (a.builtin) builtin.push(a)
    else if (isMyAgent(a)) own.push(a)
    else teammate.push(a)
  })
  return [...builtin, ...own, ...teammate]
})

const loading = ref(false)
const deleteVisible = ref(false)
const deletingAgent = ref<AgentWithUI | null>(null)
const editorVisible = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingAgent = ref<CustomAgent | null>(null)
const editorInitialSection = ref<string>('basic')
const editorInitialHighlightField = ref<string>('')
/** 当前打开三点菜单的卡片 agent.id（用于受控弹出层，避免 computed 项无持久引用导致菜单不响应） */
const openMoreAgentId = ref<string | null>(null)

const showAgentListEmpty = computed(() => {
  if (loading.value) return false
  if (!authStore.hasRole('contributor')) return false
  if (spaceSelection.value === 'all' && filteredAgents.value.length === 0) return true
  if (spaceSelection.value === 'mine' && agents.value.length === 0) return true
  return false
})

const showAgentTenantModelsGuide = computed(
  () => modelsReadyLoaded.value && showAgentListEmpty.value && !isReadyForAgent.value,
)

const showAgentListContextualGuide = computed(
  () => showAgentListEmpty.value && isReadyForAgent.value && !editorVisible.value,
)

const applyAgentListData = (res: { agents: CustomAgent[]; disabledOwnAgentIds: string[] }) => {
  const disabledOwnIds = res.disabledOwnAgentIds || []
  agents.value = (res.agents || []).map((agent: CustomAgent) => ({
    ...agent,
    showMore: false,
    disabled_by_me: disabledOwnIds.includes(agent.id)
  }))
  void checkAndOpenEditModal()
}

const fetchList = (force = false) => {
  loading.value = true
  return chatResources
    .fetchAgentsForList({ creator: creatorFilter.value }, force)
    .then(applyAgentListData)
    .finally(() => { loading.value = false })
    .then(() => {
      void checkAndOpenEditModal()
    })
}

// 检查 URL 参数并打开编辑模态框
const resolveAgentForEdit = (editId: string): CustomAgent | null => {
  return agents.value.find(a => a.id === editId) || null
}

let editOpenGeneration = 0

const checkAndOpenEditModal = async () => {
  const generation = ++editOpenGeneration
  const editId = route.query.edit as string
  const section = route.query.section as string
  if (editId && (section === 'im' || section === 'embed' || section === 'integrations' || section === 'integration-im' || section === 'integration-embed')) {
    const tab = section === 'embed' || section === 'integration-embed' ? 'embed' : 'im'
    router.replace({
      path: '/platform/settings',
      query: { section: integrationSectionKey(tab), agentId: editId },
    })
    return
  }
  if (editId) {
    const agent = resolveAgentForEdit(editId)
    // A route change can remove a creator filter before the corresponding
    // all-agent fetch completes. Keep the deep-link query intact so the list
    // refresh callback can resolve and open the target instead of losing it.
    if (!agent) return

    const requestedSection = section || 'basic'
    const requestedHighlight = (route.query.highlight as string) || ''
    if (
      editorVisible.value
      && editingAgent.value?.id === agent.id
      && !requestedHighlight
    ) {
      // Global Settings may be covering this exact editor. Preserve any
      // unsaved draft and focus the requested configuration section in place.
      editorInitialSection.value = requestedSection
      focusAgentEditorSection(requestedSection)
    } else {
      // A global Settings dialog can be opened on top of an existing agent
      // editor. Flush visible=false before loading the deep-linked target so
      // AgentEditorModal's visibility watcher rebuilds its form data.
      editorVisible.value = false
      await nextTick()
      if (generation !== editOpenGeneration) return
      editingAgent.value = agent
      editorMode.value = 'edit'
      editorInitialSection.value = requestedSection
      editorInitialHighlightField.value = requestedHighlight
      editorVisible.value = true
    }
    if (generation !== editOpenGeneration) return
    // Drop the transient edit/section params but preserve other filter
    // state (scope / creator / q) so refreshing doesn't reset the view.
    const { edit: _e, section: _s, highlight: _h, sourceTenantId: _st, ...rest } = route.query
    router.replace({ path: route.path, query: rest })
  }
}

// Also re-run when the query mutates while this view is already mounted —
// e.g. the IM overview dialog navigating here via router.push lands on the
// same route, so onMounted alone never fires and the editor would only open
// after a manual refresh.
watch(
  () => route.query.edit,
  (v) => {
    if (v && agents.value.length > 0) {
      void checkAndOpenEditModal()
    }
  },
)

// 监听菜单创建智能体事件
const handleOpenAgentEditor = (event: CustomEvent) => {
  if (event.detail?.mode === 'create') {
    openCreateModal()
  }
}

// Refetch when the creator filter flips so the server applies the
// predicate uniformly (also keeps built-in agents always present, see
// the matching block in custom_agent.go).
watch(creatorFilter, () => {
  fetchList(true)
})

onMounted(() => {
  fetchList()
  window.addEventListener('openAgentEditor', handleOpenAgentEditor as EventListener)
})

onUnmounted(() => {
  window.removeEventListener('openAgentEditor', handleOpenAgentEditor as EventListener)
})

const onVisibleChange = (visible: boolean) => {
  if (!visible) {
    openMoreAgentId.value = null
  }
}

const toggleMore = (e: Event, agentId: string) => {
  e.stopPropagation()
  openMoreAgentId.value = openMoreAgentId.value === agentId ? null : agentId
}

const handleCardClick = (agent: DisplayAgent | AgentWithUI) => {
  if (openMoreAgentId.value === agent.id) return
  // Track recency before any branch — Recents should reflect what the
  // user *looked at*, not only what they edited.
  pins.touchRecent('agent', agent.id)
  handleEdit(agent as AgentWithUI)
}

const toggleFavoriteAgent = (agentId: string, evt?: Event) => {
  evt?.stopPropagation()
  pins.toggleFavorite('agent', agentId)
}
const isAgentFavorited = (agentId: string) => pins.isFavorite('agent', agentId)

const handleEdit = (agent: AgentWithUI) => {
  openMoreAgentId.value = null
  editingAgent.value = agent
  editorMode.value = 'edit'
  editorInitialSection.value = 'basic'
  editorInitialHighlightField.value = ''
  editorVisible.value = true
}

// canManageAgent mirrors the server-side OwnedAgentOrAdmin guard
// (PR 5 #1303): the agent's creator may always edit / delete; otherwise
// Admin+ is required. Built-in agents have createdBy="" → only Admin+
// matches, which lines up with the "Admin can mutate tenant-owned
// agents" rule. The server still enforces the same matrix on every
// mutation; this gate just hides buttons the user has no authority
// to use.
function canManageAgent(agent: AgentWithUI): boolean {
  const userId = authStore.user?.id || ''
  const creatorId = (agent as any).createdBy || ''
  if (creatorId && userId && creatorId === userId) return true
  return authStore.hasRole('admin')
}

// isMyAgent 仅用于卡片来源徽章在「我创建」与「同空间其他成员创建」之间切换。
// 跟 canManageAgent 区别：管理权限有 admin 兜底；徽章纯粹按 createdBy 匹配。
// 内建 agent（createdBy=""）也归到非 mine 一档，由模板上的 builtin 分支
// 提前拦截，不会落到 ResourceOriginBadge。
function isMyAgent(agent: { createdBy?: string }): boolean {
  const userId = authStore.user?.id || ''
  return !!(agent.createdBy && userId && agent.createdBy === userId)
}

// agentOriginVariant 跟 kbOriginVariant 对齐：右下角徽章不再重复空间名
// （顶部 TenantSelector 已经标了空间身份），所有角色都用 creator 变体。
// 内建 agent 走 v-else 前的 builtin 分支，到不了这里。
function agentOriginVariant(agent: { createdBy?: string }): 'mine' | 'creator' {
  return isMyAgent(agent) ? 'mine' : 'creator'
}

// 分组标题可见时，隐藏与标题重复的角标（「我创建的」段内的 mine 徽章、
// 内置段、无创建者名的同事段），其余照常展示。
function showAgentOriginBadge(agent: { createdBy?: string; creatorName?: string }): boolean {
  if (!showShareGroupHeaders.value) return true
  const section = agentSectionOf(agent)
  const variant = agentOriginVariant(agent)
  if (section === 'mine' && variant === 'mine') return false
  if (section === 'builtin') return false
  if (section === 'tenantOthers' && variant === 'creator' && !agent.creatorName?.trim()) return false
  return true
}

function showAgentBuiltinBadge(agent: { builtin?: boolean }): boolean {
  if (!agent.builtin) return false
  if (!showShareGroupHeaders.value) return true
  return agentSectionOf(agent) !== 'builtin'
}

// 与 KnowledgeBaseList 同理：分组标题对所有角色生效，依据"创建者 + 来源"
// 这种客观信息分段，不再按当前用户的可写权限筛掉。
const showShareGroupHeaders = computed(() => true)

// 同空间、非当前用户创建的 Agent 分组标题。
// contributor / viewer 在本空间里对这些 Agent 没有写权限，所以打"仅查看"；
// admin / owner 对整个空间都有编辑权限，"仅查看"反而误导，统一改成
// "本空间 · 其他成员"——按所有权而非权限来标注。
const tenantSectionLabelKey = computed(() =>
  authStore.hasRole('admin')
    ? 'agent.sections.tenantOthers'
    : 'agent.sections.tenantReadonly'
)

// 与 KB 列表 .tenantSectionIconName 同理：admin/owner 看到"其他成员"配
// usergroup（多人）；contributor/viewer 看到"仅查看"配 browse（眼睛）。
const tenantSectionIconName = computed(() =>
  authStore.hasRole('admin') ? 'usergroup' : 'browse'
)

// 分组折叠：ephemeral，只在当前会话生效。和 KnowledgeBaseList 共用同一套
// 思路——空 Set = 全展开，避免新增分段还得维护默认值。
type AgentSectionKey = 'builtin' | 'mine' | 'tenantOthers'
const collapsedAgentSections = ref<Set<AgentSectionKey>>(new Set())
const isAgentSectionCollapsed = (key: AgentSectionKey) => collapsedAgentSections.value.has(key)
const toggleAgentSection = (key: AgentSectionKey) => {
  const next = new Set(collapsedAgentSections.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedAgentSections.value = next
}
// 根据 agent 数据形态判分组：filteredAgents 元素带 isMine；sortedMineAgents
// 是原始 agent（永远当作本空间）。
//
// 当前用户自己创建的 agent 在模板里**没有**独立分组标题（不像 KB 那边有
// "我创建的"段），所以这里返回 null——折叠任何分组都不会影响到它们。
const agentSectionOf = (item: any): AgentSectionKey | null => {
  // 内置 agent（builtin=true）单独成段，置顶展示——它们是空间共有的
  // 系统资源，跟"我 / 同事"几个所有权分类不在同一维度。
  if (item?.builtin === true) return 'builtin'
  // 本空间内：我亲手创建 → 'mine'；同事 / 非内置但无 createdBy → 'tenantOthers'。
  return isMyAgent(item as AgentWithUI) ? 'mine' : 'tenantOthers'
}
const isAgentRowHidden = (item: any): boolean => {
  const key = agentSectionOf(item)
  return key !== null && isAgentSectionCollapsed(key)
}

// 各分组卡片数量——和 KB 列表同思路，组标题上展示"(N)"，方便折叠后核对。
const emptyAgentCounts = (): Record<AgentSectionKey, number> => ({
  builtin: 0, mine: 0, tenantOthers: 0,
})
const filteredAgentSectionCounts = computed<Record<AgentSectionKey, number>>(() => {
  const c = emptyAgentCounts()
  filteredAgents.value.forEach(a => {
    const key = agentSectionOf(a)
    if (key) c[key]++
  })
  return c
})
const mineAgentSectionCounts = computed<Record<AgentSectionKey, number>>(() => {
  const c = emptyAgentCounts()
  sortedMineAgents.value.forEach(a => {
    const key = agentSectionOf(a)
    if (key) c[key]++
  })
  return c
})

const handleDelete = (agent: AgentWithUI) => {
  openMoreAgentId.value = null
  deletingAgent.value = agent
  deleteVisible.value = true
}

const handleCopy = (agent: AgentWithUI) => {
  openMoreAgentId.value = null
  copyAgent(agent.id).then((res: any) => {
    if (res.data) {
      MessagePlugin.success(t('agent.messages.copied'))
      fetchList(true)
    } else {
      MessagePlugin.error(res.message || t('agent.messages.copyFailed'))
    }
  }).catch((e: any) => {
    MessagePlugin.error(e?.message || t('agent.messages.copyFailed'))
  })
}

const confirmDelete = () => {
  if (!deletingAgent.value) return

  deleteAgent(deletingAgent.value.id).then((res: any) => {
    if (res.success) {
      MessagePlugin.success(t('agent.messages.deleted'))
      deleteVisible.value = false
      deletingAgent.value = null
      fetchList(true)
    } else {
      MessagePlugin.error(res.message || t('agent.messages.deleteFailed'))
    }
  }).catch((e: any) => {
    MessagePlugin.error(e?.message || t('agent.messages.deleteFailed'))
  })
}

const handleEditorSuccess = (agent?: CustomAgent) => {
  if (agent) {
    editingAgent.value = agent
    editorMode.value = 'edit'
  }
  fetchList(true)
}

const formatDate = (dateStr: string) => {
  if (!dateStr) return ''
  return formatStringDate(new Date(dateStr))
}

// 暴露创建方法供外部调用
const openCreateModal = () => {
  editingAgent.value = null
  editorMode.value = 'create'
  editorInitialSection.value = 'basic'
  editorInitialHighlightField.value = ''
  editorVisible.value = true
}

// 创建智能体
const handleCreateAgent = () => {
  if (!isReadyForAgent.value) {
    MessagePlugin.warning(t('contextualGuide.tenantModels.needChatModelFirst'))
    uiStore.openSettings('models')
    return
  }
  markContextualGuideDone('agentList')
  openCreateModal()
}

defineExpose({
  openCreateModal
})
</script>

<style scoped lang="less">
.agent-list-container {
  margin: 0;
  height: 100%;
  box-sizing: border-box;
  flex: 1;
  display: flex;
  position: relative;
  min-height: 0;
}

.agent-list-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  // 右侧不留 padding，让滚动条贴到内容区最右缘；内边距改到 header / main 内部
  padding: 20px 0 0 28px;
}

.agent-list-main {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  overflow-x: hidden;
  // 同 KB 列表：顶部去掉 padding，让 sticky 分组标题贴到容器最顶。
  padding: 0 28px 8px 0;
  scrollbar-width: auto;
  scrollbar-color: auto;
}

.agent-list-main-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  padding: 12px;
  background: var(--td-bg-color-container);
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-right: 28px;

  .header-title {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .title-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  h2 {
    margin: 0;
    color: var(--td-text-color-primary);
    font-family: var(--app-font-family);
    font-size: 24px;
    font-weight: 600;
    line-height: 32px;
  }
}

:deep(.agent-create-btn) {
  --ripple-color: rgba(118, 75, 162, 0.3) !important;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%) !important;
  border: none !important;
  color: var(--td-text-color-anti) !important;
  position: relative;
  overflow: hidden;

  &:hover,
  &:active,
  &:focus,
  &.t-is-active,
  &[data-state="active"] {
    background: linear-gradient(135deg, #5a6fd6 0%, #6a4190 100%) !important;
    border: none !important;
    color: var(--td-text-color-anti) !important;
  }

  --td-button-primary-bg-color: #667eea !important;
  --td-button-primary-border-color: #667eea !important;
  --td-button-primary-active-bg-color: #5a6fd6 !important;
  --td-button-primary-active-border-color: #5a6fd6 !important;

  .btn-icon-wrapper {
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .sparkles-icon {
    animation: twinkle 2s ease-in-out infinite;
  }

  &::before {
    content: '';
    position: absolute;
    top: -50%;
    left: -50%;
    width: 200%;
    height: 200%;
    background: linear-gradient(45deg,
        transparent 30%,
        rgba(255, 255, 255, 0.1) 50%,
        transparent 70%);
    transform: translateX(-100%);
    transition: transform 0.6s ease;
    z-index: 0;
  }

  &:hover::before {
    transform: translateX(100%);
  }
}

@keyframes twinkle {

  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }

  50% {
    opacity: 0.8;
    transform: scale(0.95);
  }
}

.header-subtitle {
  margin: 0;
  color: var(--td-text-color-placeholder);
  font-family: var(--app-font-family);
  font-size: 14px;
  font-weight: 400;
  line-height: 20px;
}

.header-action-btn {
  padding: 0 !important;
  min-width: 28px !important;
  width: 28px !important;
  height: 28px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  background: var(--td-bg-color-secondarycontainer) !important;
  border: 1px solid var(--td-component-stroke) !important;
  border-radius: 6px !important;
  color: var(--td-text-color-secondary);
  cursor: pointer;
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--td-bg-color-container) 72%, transparent);
  transition: background 0.2s, border-color 0.2s, color 0.2s;

  &:hover {
    background: var(--td-bg-color-secondarycontainer) !important;
    border-color: var(--td-component-stroke) !important;
    color: var(--td-text-color-primary);
  }

  :deep(.t-button__icon) {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    line-height: 1;
  }

  :deep(.t-icon),
  :deep(.btn-icon-wrapper) {
    color: var(--td-brand-color);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    line-height: 1;
  }
}

.agent-tabs {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--td-component-stroke);
  margin-bottom: 20px;

  .tab-item {
    padding: 12px 0;
    cursor: pointer;
    color: var(--td-text-color-secondary);
    font-family: var(--app-font-family);
    font-size: 14px;
    font-weight: 400;
    transition: color 0.2s;

    &:hover {
      color: var(--td-text-color-primary);
    }

    &.active {
      color: var(--td-brand-color);
      font-weight: 600;
      border-bottom: 2px solid var(--td-brand-color);
      margin-bottom: -1px;
    }
  }
}

.custom-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--td-bg-color-container-hover);
  color: var(--td-text-color-secondary);
  font-family: var(--app-font-family);
  font-size: 11px;
  font-weight: 500;
  flex-shrink: 0;
}

// 共享给我 · 可编辑 / 仅查看 分组标题，与 KB 列表 .kb-section-header 对齐。
.agent-section-header {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 6px;
  // 整行只用来铺背景；点击靠子元素冒泡，避免点到标题右侧空白误折叠。
  pointer-events: none;

  & > * {
    pointer-events: auto;
  }
  // 同 KB 列表：下滑到当前分组时标题吸顶到滚动容器顶部，box-shadow 向上/
  // 向下延伸背景以封掉 sticky 边缘的 subpixel 残缝。
  position: sticky;
  top: 0;
  z-index: 5;
  background: var(--td-bg-color-container);
  box-shadow: 0 -8px 0 0 var(--td-bg-color-container),
    0 4px 0 0 var(--td-bg-color-container);
  padding: 6px 4px 6px 0;
  color: var(--td-text-color-secondary);
  font-family: var(--app-font-family);
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
  cursor: pointer;
  user-select: none;
  outline: none;

  &:hover {
    color: var(--td-text-color-primary);
  }

  &:focus-visible {
    box-shadow: 0 0 0 2px var(--td-brand-color-focus, rgba(0, 82, 217, 0.2));
  }

  .t-icon {
    color: inherit;
  }

  .agent-section-toggle {
    margin-left: 4px;
    opacity: 0.7;
    transition: opacity 0.15s ease;
  }

  // 与 KB 列表口径一致：组里的卡片数量徽标。
  .agent-section-count {
    margin-left: 2px;
    padding: 0 6px;
    border-radius: 8px;
    background: var(--td-bg-color-secondarycontainer);
    color: var(--td-text-color-secondary);
    font-size: 11px;
    line-height: 16px;
    font-weight: 500;
  }

  &:hover .agent-section-toggle {
    opacity: 1;
  }
}


@keyframes contentFadeIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.agent-card-wrap {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr;
  animation: contentFadeIn 0.32s ease-out;
}

.agent-card-skeleton {
  cursor: default;

  .card-header {
    margin-bottom: 12px;
  }

  .card-content {
    flex: 1;
  }

  .card-bottom {
    margin-top: auto;
  }
}

/* 与知识库列表卡片统一尺寸：紧凑行高、148px 卡片高 */
.agent-card {
  border: 1px solid var(--td-component-stroke);
  border-radius: 8px;
  overflow: hidden;
  box-sizing: border-box;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  background: var(--td-bg-color-container);
  position: relative;
  cursor: pointer;
  transition: all 0.25s ease;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  height: 136px;
  min-height: 136px;

  &:hover {
    border-color: var(--td-brand-color);
    box-shadow: 0 4px 12px rgba(7, 192, 95, 0.12);
  }

  .agent-favorite-star {
    // 浮在卡片右上角顶角。卡片自身有 padding，"更多"按钮在 header flex
    // 末端自然落在 padding 内部，与零位的 star 错开。
    position: absolute;
    top: 0;
    right: 0;
    z-index: 3;
    width: 24px;
    height: 24px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: none;
    border-radius: 6px;
    color: var(--td-text-color-secondary);
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.15s ease, background 0.15s ease, color 0.15s ease;

    &:hover {
      background: var(--td-bg-color-secondarycontainer);
      color: var(--td-warning-color, #e37318);
    }

    &.is-favorited {
      opacity: 1;
      color: var(--td-warning-color, #e37318);
    }
  }

  &:hover .agent-favorite-star {
    opacity: 1;
  }

  // 普通模式样式
  &.agent-mode-normal {
    background: linear-gradient(135deg, var(--td-bg-color-container) 0%, rgba(7, 192, 95, 0.04) 100%);

    &:hover {
      border-color: var(--td-brand-color);
      background: linear-gradient(135deg, var(--td-bg-color-container) 0%, rgba(7, 192, 95, 0.08) 100%);
    }

    .card-decoration {
      color: rgba(7, 192, 95, 0.35);
    }

    &:hover .card-decoration {
      color: rgba(7, 192, 95, 0.5);
    }
  }

  // Agent 模式样式
  &.agent-mode-agent {
    background: linear-gradient(135deg, var(--td-bg-color-container) 0%, rgba(124, 77, 255, 0.04) 100%);

    &:hover {
      border-color: var(--td-brand-color);
      box-shadow: 0 4px 12px rgba(124, 77, 255, 0.12);
      background: linear-gradient(135deg, var(--td-bg-color-container) 0%, rgba(124, 77, 255, 0.08) 100%);
    }

    .card-decoration {
      color: rgba(124, 77, 255, 0.35);
    }

    &:hover .card-decoration {
      color: rgba(124, 77, 255, 0.5);
    }
  }

  // 确保内容在装饰之上
  .card-header,
  .card-content,
  .card-bottom {
    position: relative;
    z-index: 1;
  }

  .card-header {
    margin-bottom: 6px;
  }

  .card-title {
    font-size: 15px;
    line-height: 22px;
  }

  .card-content {
    margin-bottom: 6px;
  }

  .card-description {
    font-size: 12px;
    line-height: 17px;
  }

  .card-bottom {
    padding-top: 6px;
  }

  .more-wrap {
    width: 28px;
    height: 28px;

    .more-icon {
      width: 16px;
      height: 16px;
    }
  }

  .builtin-avatar {
    width: 32px;
    height: 32px;
    border-radius: 8px;
  }

  .edit-btn {
    width: 32px;
    height: 32px;
    border-radius: 8px;
  }
}

.card-decoration {
  position: absolute;
  top: 12px;
  right: 44px;
  display: flex;
  align-items: flex-start;
  gap: 4px;
  pointer-events: none;
  z-index: 0;
  transition: color 0.25s ease;

  .star-icon {
    opacity: 0.9;

    &.small {
      margin-top: 10px;
      opacity: 0.7;
    }
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
}

.card-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.card-title {
  color: var(--td-text-color-primary);
  font-family: var(--app-font-family);
  font-size: 15px;
  font-weight: 600;
  line-height: 22px;
  letter-spacing: 0.01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.builtin-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--td-bg-color-container-hover);
  color: var(--td-text-color-secondary);
  font-family: var(--app-font-family);
  font-size: 11px;
  font-weight: 500;
  flex-shrink: 0;
}

.builtin-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  flex-shrink: 0;

  &.agent-emoji {
    font-size: 18px;
    line-height: 1;
    background: var(--td-bg-color-container-hover);
  }

  &.normal {
    background: linear-gradient(135deg, rgba(7, 192, 95, 0.15) 0%, rgba(7, 192, 95, 0.08) 100%);
    color: var(--td-brand-color-active);
  }

  &.agent {
    background: linear-gradient(135deg, rgba(124, 77, 255, 0.15) 0%, rgba(124, 77, 255, 0.08) 100%);
    color: var(--td-brand-color);
  }
}

.edit-btn {
  display: flex;
  width: 32px;
  height: 32px;
  justify-content: center;
  align-items: center;
  border-radius: 8px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s ease;
  color: var(--td-text-color-disabled);

  &:hover {
    background: var(--td-bg-color-container-hover);
    color: var(--td-brand-color);
  }
}

.more-wrap {
  display: flex;
  width: 28px;
  height: 28px;
  justify-content: center;
  align-items: center;
  border-radius: 8px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s ease;
  opacity: 0;

  .agent-card:hover & {
    opacity: 0.6;
  }

  &:hover {
    background: var(--td-bg-color-container-hover);
    opacity: 1 !important;
  }

  &.active-more {
    background: var(--td-bg-color-container-hover);
    opacity: 1 !important;
  }

  .more-icon {
    width: 16px;
    height: 16px;
  }
}

/* 与知识库卡片内容区一致 */
.card-content {
  flex: 1;
  min-height: 0;
  margin-bottom: 8px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 三个列表卡片统一：描述字体 */
.card-description {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  color: var(--td-text-color-secondary);
  font-family: var(--app-font-family);
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
}

.card-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 8px;
  border-top: .5px solid var(--td-component-stroke);
}

.bottom-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.feature-badges {
  display: flex;
  align-items: center;
  gap: 4px;
}

.feature-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  cursor: default;
  transition: background 0.2s ease;

  &.mode-normal {
    background: rgba(7, 192, 95, 0.08);
    color: var(--td-brand-color-active);

    &:hover {
      background: rgba(7, 192, 95, 0.12);
    }
  }

  &.mode-agent {
    background: rgba(124, 77, 255, 0.08);
    color: var(--td-brand-color);

    &:hover {
      background: rgba(124, 77, 255, 0.12);
    }
  }

  &.web-search {
    background: rgba(255, 152, 0, 0.08);
    color: var(--td-warning-color);

    &:hover {
      background: rgba(255, 152, 0, 0.12);
    }
  }

  &.knowledge {
    background: rgba(7, 192, 95, 0.08);
    color: var(--td-brand-color-active);

    &:hover {
      background: rgba(7, 192, 95, 0.12);
    }
  }

  &.mcp {
    background: rgba(236, 72, 153, 0.08);
    color: var(--td-error-color);

    &:hover {
      background: rgba(236, 72, 153, 0.12);
    }
  }

  &.multi-turn {
    background: rgba(59, 130, 246, 0.08);
    color: var(--td-brand-color);

    &:hover {
      background: rgba(59, 130, 246, 0.12);
    }
  }
}

.card-time {
  color: var(--td-text-color-placeholder);
  font-family: var(--app-font-family);
  font-size: 12px;
  font-weight: 400;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 60px 20px;

  .empty-img {
    width: 162px;
    height: 162px;
    margin-bottom: 20px;
  }

  .empty-txt {
    color: var(--td-text-color-placeholder);
    font-family: var(--app-font-family);
    font-size: 16px;
    font-weight: 600;
    line-height: 26px;
    margin-bottom: 8px;
  }

  .empty-desc {
    color: var(--td-text-color-disabled);
    font-family: var(--app-font-family);
    font-size: 14px;
    font-weight: 400;
    line-height: 22px;
    margin-bottom: 0;
  }

  .empty-state-btn {
    margin-top: 20px;
  }
}

// 响应式布局
@media (min-width: 900px) {
  .agent-card-wrap {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (min-width: 1250px) {
  .agent-card-wrap {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (min-width: 1600px) {
  .agent-card-wrap {
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (min-width: 1900px) {
  .agent-card-wrap {
    grid-template-columns: repeat(5, 1fr);
  }
}

@media (min-width: 2200px) {
  .agent-card-wrap {
    grid-template-columns: repeat(6, 1fr);
  }
}

// 删除确认对话框样式
:deep(.del-agent-dialog) {
  padding: 0px !important;
  border-radius: 6px !important;

  .t-dialog__header {
    display: none;
  }

  .t-dialog__body {
    padding: 16px;
  }

  .t-dialog__footer {
    padding: 0;
  }
}

:deep(.t-dialog__position.t-dialog--top) {
  padding-top: 40vh !important;
}

.circle-wrap {
  .dialog-header {
    display: flex;
    align-items: center;
    margin-bottom: 8px;
  }

  .circle-img {
    width: 20px;
    height: 20px;
    margin-right: 8px;
  }

  .circle-title {
    color: var(--td-text-color-primary);
    font-family: var(--app-font-family);
    font-size: 16px;
    font-weight: 600;
    line-height: 24px;
  }

  .del-circle-txt {
    color: var(--td-text-color-placeholder);
    font-family: var(--app-font-family);
    font-size: 14px;
    font-weight: 400;
    line-height: 22px;
    display: inline-block;
    margin-left: 29px;
    margin-bottom: 21px;
  }

  .circle-btn {
    height: 22px;
    width: 100%;
    display: flex;
    justify-content: flex-end;
  }

  .circle-btn-txt {
    color: var(--td-text-color-primary);
    font-family: var(--app-font-family);
    font-size: 14px;
    font-weight: 400;
    line-height: 22px;
    cursor: pointer;

    &:hover {
      opacity: 0.8;
    }
  }

  .confirm {
    color: var(--td-error-color);
    margin-left: 40px;

    &:hover {
      opacity: 0.8;
    }
  }
}
</style>
