# 翻译进度一览

> 详细台账：`docs/translation-conventions.md`（§8 翻译日志 / §9 已知细节与坑）、
> `docs/HANDOFF.md`（进度与交接）、`docs/known-issues/`（按批次分片的坑）。
> 本文件只是快速索引，**细节以上述文档为准**。

## 验收状态（2026-09-22 终核）

| 维度 | 状态 |
|---|---|
| HTTP 路由对账（route-recon） | 交集 387，无缺口（swagger 非翻译目标；models/{id}/debug 留阶段 7） |
| golden 契约测试 | 1,719+ 全绿（全部录自 Go 实行为准） |
| 全量测试 | 五批分批跑全绿（B1a/B1b/B2/B3/B4） |
| 双端 A/B | 九族 GET + 写路径 + HybridSearch 两场景逐字节 MATCH |

## 波次

| 波 | 内容 | 状态 |
|---|---|---|
| 0 | memory / datasource | ✅ |
| 1 | 会话/消息面（CRUD/附件/产物/追问/steer） | ✅ |
| 2 | chunk / knowledge / faq / infra-config / members / system / 扫尾 | ✅ |
| 3 | sandbox / 协作 / agents / browserskill | ✅ |
| 4 | agent 核心 + tools + chat_pipeline + 前置缺口（4.1–4.6d） | ✅ |
| 5 | 共享 agent 收口（W5α）/ tenant_skill verify（W5β）/ im 执行体（W5γ）/ provider 终端（W5δ）/ 检索引擎批 | ✅（γ3 平台出站传输与部分 provider 传输层见下） |

## 已知剩余（外部 provider 传输层，接缝与验收口径已备案）

- IM 九渠道出站客户端（SendReply/StartStream 的平台 HTTP/WS 调用）；验签/AES/解析核心已翻
- cube/e2b 终端 PTY 的 SDK 流传输（中性层已翻，W5d 接缝在）
- tenant_skill install 管线体（播种/installer agent 对话/快照构建/指针切换；需活沙箱+LLM）
- 外部向量店 driver（elasticsearch/milvus/qdrant/…；postgres 引擎已完整）
- ArtifactCollector 的沙箱文件源生产装配（seam 在，需活沙箱）
- models/{id}/debug（阶段 7，Owner 决策）
