import { get, post, put, del } from "../../utils/request";

// 智能体配置
// 智能推理下的智能体类型预设 ID
// 'rag-qa'       : 经典文档/FAQ 分块 RAG
// 'wiki-qa'      : Wiki 图谱导航问答
// 'hybrid-rag-wiki': Wiki + 分块混合检索
// 'custom'       : 完全自定义（不应用预设）
export type AgentType = 'rag-qa' | 'wiki-qa' | 'hybrid-rag-wiki' | 'data-analysis' | 'custom';

export interface QuestionSuggestionConfig {
  starters: {
    enabled: boolean;
    mode: 'curated' | 'knowledge' | 'hybrid';
    items: string[];
    count: number;
  };
  follow_ups: {
    enabled: boolean;
    mode: 'generated' | 'knowledge' | 'hybrid';
    count: number;
    modelId?: string;
    additional_instruction?: string;
    categories: Array<'clarify' | 'deepen' | 'action'>;
    max_context_turns: number;
    suppress_on_fallback: boolean;
    suppress_when_answer_asks_question: boolean;
    knowledge_fallback: boolean;
    allow_regenerate: boolean;
  };
}

export interface CustomAgentConfig {
  // ===== 基础设置 =====
  agentMode?: 'quick-answer' | 'smart-reasoning';  // 运行模式：quick-answer=RAG模式, smart-reasoning=ReAct Agent模式
  // 智能推理模式下的类型预设，用于一键应用"系统提示词 + 工具 + KB 兼容性"组合
  // 仅在 agentMode === 'smart-reasoning' 时生效；quick-answer 模式忽略
  agentType?: AgentType;
  systemPrompt?: string;           // 统一系统提示词（使用 {{web_search_status}} 占位符动态控制行为）
  systemPromptId?: string;        // 引用的 prompt template ID（预设会填入此字段）
  contextTemplateId?: string;     // Inherit the referenced context template when text is empty
  contextTemplate?: string;        // 上下文模板（普通模式）

  // ===== 模型设置 =====
  modelId?: string;
  rerankModelId?: string;         // ReRank 模型 ID
  temperature?: number;
  maxCompletionTokens?: number;   // 0 = 跟随系统默认（快速问答 2048；智能推理 4096，绑沙箱可写文件时 24576）。大于 0 为自定义上限
  thinking?: boolean;                      // 是否启用思考模式（支持扩展思考的模型）
  citationEnabled?: boolean;        // 是否在最终回答中输出知识库/网页来源引用（默认开启）

  // ===== Agent模式设置 =====
  maxIterations?: number;          // 最大迭代次数；-1 表示不限制
  llmCallTimeout?: number;        // LLM调用超时时间（秒）
  allowedTools?: string[];         // 允许的工具
  reflection_enabled?: boolean;     // 是否启用反思
  // MCP服务选择模式：all=全部启用的MCP服务, selected=指定服务, none=不使用MCP
  mcpSelectionMode?: 'all' | 'selected' | 'none';
  mcpServices?: string[];          // 选择的MCP服务ID列表
  // 对话中触发 OAuth 授权时的等待超时（秒）：到点后自动跳过授权提示。
  // <=0 时使用服务端默认超时。仅对使用 OAuth 的 MCP 服务生效。
  mcpAuthWaitTimeout?: number;

  // ===== Skills设置（仅Agent模式）=====
  // Skills选择模式：all=全部预装, selected=指定, none=不使用
  skillsSelectionMode?: 'all' | 'selected' | 'none';
  selectedSkills?: string[];       // 选择的Skill名称列表

  // ===== 沙箱设置 =====
  // 该智能体的技能脚本运行在哪个沙箱配置上；为空表示不启用沙箱执行。
  // 指向逻辑配置而非某个具体版本，凭据轮换时无需重新指派每个智能体。
  sandbox_config_id?: string;

  // ===== 知识库设置 =====
  // 知识库选择模式：all=全部知识库, selected=指定知识库, none=不使用知识库
  kbSelectionMode?: 'all' | 'selected' | 'none';
  knowledgeBases?: string[];
  // 是否仅在显式 @ 提及时检索知识库（默认: false）
  // true: 只有用户通过 @ 明确提及知识库/文档时才检索
  // false: 根据 kbSelectionMode 自动检索知识库
  retrieveKbOnlyWhenMentioned?: boolean;

  // ===== 图片上传/多模态设置 =====
  imageUploadEnabled?: boolean;    // 是否启用图片上传（默认: false）
  vlmModelId?: string;            // VLM模型ID（图片分析用）
  imageStorageProvider?: string;   // 图片存储提供商
  audioUploadEnabled?: boolean;    // 是否启用音频上传/ASR转录（默认: false）
  asrModelId?: string;            // ASR模型ID（音频转录用）
  // 附件图片理解 / 扫描件 OCR 开关（默认: false，开启会增加解析耗时）
  attachmentImageUnderstanding?: boolean;
  // 扫描件 OCR 最大页数（0 = 使用全局默认 WEKNORA_CHAT_ATTACHMENT_OCR_MAX_PAGES）
  attachmentOcrMaxPages?: number;
  // 单轮问答等待附件解析完成的最长时间（秒，0 = 使用全局默认 WEKNORA_CHAT_ATTACHMENT_WAIT_TIMEOUT_SEC）
  attachmentParseWaitTimeoutSec?: number;

  // ===== 聊天附件解析引擎策略 =====
  // 按文件类型选择解析引擎；优先级：请求 parser_engine > 智能体规则 > 租户规则 > auto
  chatParserEngineRules?: { file_types: string[]; engine: string }[];

  // ===== 文件类型限制 =====
  // 支持的文件类型（如 ["csv", "xlsx", "xls"]）
  // 为空表示支持所有文件类型
  supportedFileTypes?: string[];

  // ===== 网络搜索设置 =====
  webSearchEnabled?: boolean;
  webSearchProviderId?: string;
  webSearchMaxResults?: number;

  // ===== 多轮对话设置 =====
  multiTurnEnabled?: boolean;     // 是否启用多轮对话
  historyTurns?: number;           // 保留历史轮数

  // ===== 长期记忆 =====
  // 该智能体是否可以读取用户的长期记忆。
  // 缺省（旧数据）等同于 true：这是一个只能"关"的开关，空间设置关闭时
  // 这里打开也不会生效。
  memoryEnabled?: boolean;

  // ===== 检索策略设置 =====
  embeddingTopK?: number;         // 向量召回TopK
  keywordThreshold?: number;       // 关键词召回阈值
  vectorThreshold?: number;        // 向量召回阈值
  rerankTopK?: number;            // 重排TopK
  rerankThreshold?: number;        // 重排阈值

  // ===== 高级设置（主要用于普通模式）=====
  enableQueryExpansion?: boolean; // 是否启用查询扩展
  enableRewrite?: boolean;         // 是否启用问题改写
  rewritePromptSystem?: string;   // 改写系统提示词
  rewritePromptUser?: string;     // 改写用户提示词模板
  fallbackStrategy?: 'fixed' | 'model'; // 兜底策略
  fallbackResponse?: string;       // 固定兜底回复
  fallbackPrompt?: string;         // 兜底提示词（模型生成时）
  // 意图提示词：非检索意图（问候、闲聊等）时覆盖主系统提示词
  intentPrompts?: Record<string, string>;

  // ===== 已废弃字段（保留兼容）=====
  welcome_message?: string;
  questionSuggestions?: QuestionSuggestionConfig;
}

// 智能体
export interface CustomAgent {
  id: string;
  name: string;
  description?: string;
  avatar?: string;
  builtin: boolean;
  tenantId?: number;
  createdBy?: string;
  // creatorName 由后端 list 接口批量回填，仅用于列表卡片来源徽章。
  creatorName?: string;
  config: CustomAgentConfig;
  createdAt?: string;
  updatedAt?: string;
}

// 创建智能体请求
export interface CreateAgentRequest {
  name: string;
  description?: string;
  avatar?: string;
  config?: CustomAgentConfig;
}

// 更新智能体请求
export interface UpdateAgentRequest {
  name: string;
  description?: string;
  avatar?: string;
  config?: CustomAgentConfig;
}

// 内置智能体 ID（常用的保留常量，便于代码引用）
export const BUILTIN_QUICK_ANSWER_ID = 'builtin-quick-answer';
export const BUILTIN_SMART_REASONING_ID = 'builtin-smart-reasoning';

// AgentMode 常量
export const AGENT_MODE_QUICK_ANSWER = 'quick-answer';
export const AGENT_MODE_SMART_REASONING = 'smart-reasoning';

// Deprecated: Use BUILTIN_QUICK_ANSWER_ID instead
export const BUILTIN_AGENT_NORMAL_ID = BUILTIN_QUICK_ANSWER_ID;
// Deprecated: Use BUILTIN_SMART_REASONING_ID instead
export const BUILTIN_AGENT_AGENT_ID = BUILTIN_SMART_REASONING_ID;

// 获取智能体列表（包括内置智能体）
// disabledOwnAgentIds: 当前空间在对话下拉中停用的「我的」智能体 ID，仅影响本空间
export function listAgents(params?: {
  /**
   * Optional creator filter; mirrors listKnowledgeBases. Built-in agents
   * (builtin=true) are always returned regardless of this filter so
   * the conversation dropdown never silently loses quick-answer /
   * smart-reasoning when a user picks "Created by me".
   */
  creator?: 'all' | 'mine' | 'others';
}) {
  const qs = params?.creator && params.creator !== 'all' ? `?creator=${params.creator}` : '';
  return get<{ agents: CustomAgent[]; disabledOwnAgentIds?: string[] }>(`/api/v1/agents${qs}`);
}

// 获取智能体详情
export function getAgentById(id: string) {
  return get<CustomAgent>(`/api/v1/agents/${id}`);
}

// 创建智能体
export function createAgent(data: CreateAgentRequest) {
  return post<CustomAgent>('/api/v1/agents', data);
}

// 更新智能体
export function updateAgent(id: string, data: UpdateAgentRequest) {
  return put<CustomAgent>(`/api/v1/agents/${id}`, data);
}

// 删除智能体
export function deleteAgent(id: string) {
  return del<void>(`/api/v1/agents/${id}`);
}

// 复制智能体
export function copyAgent(id: string) {
  return post<CustomAgent>(`/api/v1/agents/${id}/copy`);
}

// 判断是否为内置智能体（通过 agent.builtin 字段或 ID 前缀判断）
export function isBuiltinAgent(agentId: string): boolean {
  return agentId.startsWith('builtin-');
}

// 占位符定义
export interface PlaceholderDefinition {
  name: string;
  label: string;
  description: string;
}

// 占位符响应
export interface PlaceholdersResponse {
  all: PlaceholderDefinition[];
  systemPrompt: PlaceholderDefinition[];
  agent_system_prompt: PlaceholderDefinition[];
  contextTemplate: PlaceholderDefinition[];
  rewrite_system_prompt: PlaceholderDefinition[];
  rewrite_prompt: PlaceholderDefinition[];
  fallbackPrompt: PlaceholderDefinition[];
}

// 获取占位符定义
export function getPlaceholders() {
  return get<{ data: PlaceholdersResponse }>('/api/v1/agents/placeholders');
}

// ===== 智能体类型预设 =====

// 后端 kb_filter 结构（见 internal/types/agent_type_preset.go）
export interface AgentTypeKBFilter {
  any_of?: string[];   // KB 至少拥有其一
  all_of?: string[];   // KB 必须全部拥有
  none_of?: string[];  // KB 必须全部不拥有
}

// KB 能力标签（后端 types.KBCapabilities 的 JSON）
export interface KBCapabilities {
  vector: boolean;
  keyword: boolean;
  wiki: boolean;
  graph: boolean;
  faq: boolean;
}

// 预设的"自动填充"配置载荷：仅包含被预设覆盖的字段；其他字段不动
export interface AgentTypePresetConfig {
  systemPromptId?: string;
  temperature?: number;
  maxIterations?: number;
  allowedTools?: string[];
  retainRetrievalHistory?: boolean;
  faqPriorityEnabled?: boolean;
  webSearchEnabled?: boolean;
  supportedFileTypes?: string[];
  kbSelectionMode?: 'all' | 'selected' | 'none';
}

export interface AgentTypePresetI18n {
  label: string;
  description: string;
}

export interface AgentTypePreset {
  id: AgentType;
  i18n: Record<string, AgentTypePresetI18n>;
  config?: AgentTypePresetConfig;     // 为空表示"自定义"类型（无预设）
  kb_filter?: AgentTypeKBFilter;      // 为空表示所有 KB 可选
}

// 拉取类型预设列表（编辑器用）
export function getAgentTypePresets() {
  return get<{ data: AgentTypePreset[] }>('/api/v1/agents/type-presets');
}

// ===== IM渠道 =====

export interface IMChannel {
  id: string;
  tenantId?: number;
  agentId: string;
  // 'lark' is Feishu's international edition; it shares Feishu's credentials and modes.
  platform: 'wecom' | 'feishu' | 'lark' | 'slack' | 'telegram' | 'dingtalk' | 'mattermost' | 'wechat' | 'qqbot' | 'yunzhijia';
  name: string;
  enabled: boolean;
  mode: 'webhook' | 'websocket' | 'longpoll';
  outputMode: 'stream' | 'full';
  sessionMode?: 'user' | 'thread';
  knowledgeBaseId?: string;
  credentials: Record<string, any>;
  botIdentity?: string;
  createdAt?: string;
  updatedAt?: string;
  deletedAt?: string | null;
}

export function listIMChannels(agentId: string) {
  return get<IMChannel[]>(`/api/v1/agents/${agentId}/im-channels`);
}

// Tenant-wide overview row. Credentials are intentionally omitted — use
// listIMChannels(agentId) when you need to edit a specific channel.
export interface IMChannelOverview {
  id: string;
  tenantId: number;
  agentId: string;
  agentName: string; // localized built-in name when the agent is built-in
  platform: IMChannel['platform'];
  name: string;
  enabled: boolean;
  mode: IMChannel['mode'];
  outputMode: IMChannel['outputMode'];
  sessionMode?: IMChannel['sessionMode'];
  botIdentity: string;
  createdAt: string;
  updatedAt: string;
}

export function listAllIMChannels() {
  return get<IMChannelOverview[]>('/api/v1/im-channels');
}

export function createIMChannel(agentId: string, data: Partial<IMChannel>) {
  return post<IMChannel>(`/api/v1/agents/${agentId}/im-channels`, data);
}

export function updateIMChannel(id: string, data: Partial<IMChannel>) {
  return put<IMChannel>(`/api/v1/im-channels/${id}`, data);
}

export function deleteIMChannel(id: string) {
  return del<void>(`/api/v1/im-channels/${id}`);
}

export function toggleIMChannel(id: string) {
  return post<IMChannel>(`/api/v1/im-channels/${id}/toggle`);
}

// ===== 推荐问题 =====

// 推荐问题
export interface SuggestedQuestion {
  question: string;
  source: 'faq' | 'document' | 'agent_config' | 'wiki';
  knowledge_base_id?: string;
}

// 获取智能体推荐问题
// 根据智能体关联的知识库范围返回推荐问题，用于前端对话面板快捷提问
export function getSuggestedQuestions(
  agentId: string,
  params?: {
    knowledge_base_ids?: string[];
    knowledgeIds?: string[];
    tagScopes?: Array<{ knowledgeBaseId: string; tagIds: string[] }>;
    limit?: number;
  }
) {
  const query = new URLSearchParams();
  if (params?.knowledgeIds?.length) query.set('knowledgeIds', params.knowledgeIds.join(','));
  if (params?.tagScopes?.length) query.set('tagScopes', JSON.stringify(params.tagScopes));
  if (params?.limit) query.set('limit', String(params.limit));
  const qs = query.toString();
  return get<SuggestedQuestion[]>(`/api/v1/agents/${agentId}/suggested-questions${qs ? '?' + qs : ''}`);
}
// ===== WeChat QR Code Login =====

export interface WeChatQRCodeResult {
  qrcodeUrl: string;
  qrcode: string;
}

export interface WeChatQRCodeStatus {
  status: 'wait' | 'scaned' | 'confirmed' | 'expired';
  credentials: {
    botToken: string;
    ilinkBotId: string;
    ilinkUserId: string;
  } | null;
  baseUrl: string | null;
}

export function getWeChatQRCode() {
  return post<WeChatQRCodeResult>('/api/v1/wechat/qrcode');
}

export function pollWeChatQRCodeStatus(qrcode: string) {
  return post<WeChatQRCodeStatus>('/api/v1/wechat/qrcode/status', { qrcode });
}
