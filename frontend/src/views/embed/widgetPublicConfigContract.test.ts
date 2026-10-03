import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

/**
 * 源码扫描守卫：public/ 下的 widget 是独立发布物（纯 JS，不在 vue-tsc / 单测构建面内），
 * 公开 config 契约漂移不会被类型检查发现。
 *
 * 背景（2026-10-03 点检实锤）：公开 config 已换锚为「裸对象 + camelCase」（§14.9m E1），
 * 但 widget 仍按旧契约（{data} 信封 + snake 键）读 ⇒ 渠道配置的浮标图标静默不生效，
 * 按钮回落默认气泡，且不报任何错。
 */
const widgetSource = readFileSync(
  new URL('../../../public/weknora-widget.js', import.meta.url),
  'utf8',
)

test('widget 读取公开 config 用新契约键（camelCase）', () => {
  assert.match(widgetSource, /typeof cfg\.primaryColor === 'string'/)
  assert.match(widgetSource, /typeof cfg\.launcherIcon === 'string'/)
})

test('widget 不再残留旧契约读法（{data} 信封 / snake 键）', () => {
  assert.doesNotMatch(widgetSource, /payload\.data/)
  assert.doesNotMatch(widgetSource, /cfg\.launcher_icon/)
})
