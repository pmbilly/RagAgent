export type MentionItemType = 'kb' | 'file' | 'tag' | 'mcp' | 'skill';

/** TDesign icon for Skills in the agent editor, @-mention menu, and chat chips. */
export const SKILL_ICON = 'system-code';

export interface MentionItem {
  id: string;
  name: string;
  type: MentionItemType;
  group?: string;
  description?: string;
  kbType?: 'document' | 'faq';
  count?: number;
  kbName?: string;
  kbId?: string;
  serviceId?: string;
  serviceName?: string;
  skillName?: string;
  isAgentConfigured?: boolean;
  /** MCP catalog tool count; omitted when the service has never been synced. */
  toolCount?: number;
  catalogStale?: boolean;
  catalogSynced?: boolean;
}

/**
 * 提及项的**单一形状**：与后端 `MentionedItem` 的字段名一致（camelCase）。
 *
 * 请求面（chat 的 `mentioned_items` 元素 / steer 的 `mentionedItems`）、消息对象的
 * `mentionedItems`、以及落库 jsonb 都是它——三处同形，本地乐观消息与历史加载不会
 * 因形状不同而渲染不一致（S3 收口前请求元素曾是下划线形状，见 HANDOFF §14.9l）。
 */
export interface MentionedItem {
  id: string;
  name: string;
  type: MentionItemType;
  kbType?: 'document' | 'faq';
  kbId?: string;
  kbName?: string;
  serviceId?: string;
  skillName?: string;
}
