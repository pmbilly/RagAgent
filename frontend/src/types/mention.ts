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

export interface MentionRequestItem {
  id: string;
  name: string;
  type: MentionItemType;
  kb_type?: 'document' | 'faq';
  kb_id?: string;
  kb_name?: string;
  service_id?: string;
  skill_name?: string;
}

/**
 * 消息对象里的提及项形状：与后端 `MentionedItem` 的线格式（camelCase）一致。
 *
 * 上送载荷（{@link MentionRequestItem}）仍是下划线键（请求面属 S4 批次），
 * 但本地构造的用户消息会被历史加载/对账拿到的 REST 消息替换，两处形状必须相同，
 * 否则同一行在刷新前后渲染不一致。
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

/** 上送项 → 消息对象元素（本地乐观消息与 steer 预览用）。 */
export function fromMentionRequest(item: MentionRequestItem): MentionedItem {
  const { kb_type, kb_id, kb_name, service_id, skill_name } = item;
  return {
    id: item.id,
    name: item.name,
    type: item.type,
    kbType: kb_type,
    kbId: kb_id,
    kbName: kb_name,
    serviceId: service_id,
    skillName: skill_name,
  };
}
