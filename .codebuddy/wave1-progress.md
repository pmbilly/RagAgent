# 波 1 执行进度（G1-G3 完成）

## 已完成
- G1 session CRUD+pin（8 条）— aaec588，A/B 34/34
- G2 消息面（5 条）— 2b11e4d，A/B 25/25
- G3 追问建议（3 条）— cfb8038，A/B 18/18
- 全量 3028 测试绿；端点覆盖 126/412（约 31%）

## 关键沉淀（conventions §9）
- G1: BizException 二次包装、渠道 source 500 双前缀、page=0 omitempty、
  queryPaged is_pinned 映射缺陷（真 bug）
- G2: search match_type=hybrid 之谜（partner 空 matchType 合并）、
  keyword 线性分值、两个 404 文案、psql 直插消息造数
- G3: writeError 子串分派、Ensure ContentLength 语义、ready 集合幂等复用、
  LLM 生成步降级（failed/generation_error）

## 待做（波 1 剩余）
- G5 临时文档 attachments ×5（temporary_document.go 29KB，asynq 异步解析需 Java 等价方案）
- G6 artifacts ×3 + generate_title + stop（artifact_download.go + title.go + stream.go）
- G4 steer ×4（steer.go 38KB，streamManager API 已就绪）

## 环境
- Go :8080 / Java :8082 仍在后台运行；dev PG :15432；两仓干净
- 脚本：record-{session,message,suggestion}-golden.sh、ab-{session,message,suggestion}.sh
