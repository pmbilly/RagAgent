import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

/**
 * 源码扫描守卫：MCP 认证方式的取值必须与后端枚举 `McpAuthType` 的线上值一致。
 *
 * 后端线上值（`@JsonValue`，契约 §1.7「枚举输出小写字符串」）：
 * `""`（none）/ `api_key` / `bearer` / `oauth` —— **不是 camel**。
 *
 * 2026-10-03 点检实锤（本守卫的由来）：前端曾发 `'apiKey'` → 后端 `fromValue`
 * 匹配不到就返回 null → 鉴权策略被静默写成空、凭据改以默认头 `X-API-Key` 发出，
 * 用户"配了 API Key 却不生效"。后端侧配套：`McpAuthType.parseStrict`（写侧未知值 400）
 * 与 `McpAuthTypeTest`。
 */
const dialog = readFileSync(new URL('./McpServiceDialog.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../../../api/mcp-service.ts', import.meta.url), 'utf8')

test('MCP 认证方式取值用后端枚举值 api_key（非 camel）', () => {
  assert.match(dialog, /value: 'api_key'/, '认证方式 pill 取值应为 api_key')
  assert.match(dialog, /authType = 'api_key'/, 'JSON 导入识别到 Authorization 头时应填 api_key')
  assert.match(dialog, /authType === 'api_key'/, '表单分支/凭证卡/保存条件应按 api_key 判断')
  assert.match(api, /'api_key'/, 'API 类型联合应含 api_key')
})

test('MCP 认证方式不再出现 camel 取值（apiKey）', () => {
  // 只查"authType 上下文里的 camel 取值"——凭证字段名（key: 'apiKey'）是另一层，合法。
  assert.doesNotMatch(dialog, /authType[^\n]*'apiKey'/)
  assert.doesNotMatch(api, /authType\?:[^\n]*'apiKey'/)
})
