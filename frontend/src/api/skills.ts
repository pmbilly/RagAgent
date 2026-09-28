import { get } from '@/utils/request'

/**
 * 指令型技能目录（技能降级选项 B）：宿主技能目录扫描结果。
 * 后端 agentm/SkillsCatalogController 提供，无安装管线。
 */
export interface InstructionalSkillInfo {
  name: string
  description: string
}

export function listSkills() {
  return get<{ data: InstructionalSkillInfo[]; skills_available: boolean }>('/api/v1/skills')
}
