# 交接文档（新会话接手用）

> 最后更新：2026-09-21 · **基线：W5a 收尾批完成（WebConfig RBAC 漂移修复 + 13 条小散路由；受影响包回归绿 + A/B 三轮 56/56 ALL MATCH）** · golden 1395 个
> **端点覆盖：Go 412 条 → Java 已注册约 374 条（约 91%）——W5a ✅；下一步：收尾扫描批（见 §0「剩余缺口清单」）**
> **端点覆盖：Go 412 条 → Java 已注册约 361 条（约 88%）——波 4 ✅；下一步：波 5（im 执行体/skill 收口/shared-agent）+ 收尾扫描（见 §0「剩余缺口清单」）**

## 0. 接手状态（2026-09-21，波 4 已全部收官）

**波 4.6 四连批全部提交**：4.6a f7a2b98（modelcontext+skills+langfuse seam，193 实录）→
4.6b f345658（AgentEngine 引擎核心，74 实录 + MessageSanitizer 真缺陷修复）→
4.6c 755bff4（chatpipeline 43 文件，263 实录 + 三个 len/拼接真缺陷修复）→
4.6d 29b41b9（**chat 三入口 HTTP 面 + AgentStreamBridge SSE 桥 + PipelinePorts 11 seam
装配 + SteerSink/follow-up + stub LLM 全链路 A/B 15 场景 × 2 轮全 MATCH 零 DIFF**）。
验收基线：小包批 3,985 条全绿（session 257/agent+chatpipeline 404/apikey+auth 175/
common+event+audit 270/B2 955/B3 1066/B4 1115）；golden 1,339 个。

**W5a 收尾批（2026-09-21）**：A 部分 = WebConfig RBAC 漂移修复（拦截器 pattern 补
chunks/messages/faq/knowledge-chat/agent-chat/knowledge-search 六前缀 + sessions 23 条/
messages 4 条/chunks 写族 7 条规则——此前多为空转/缺席）；B 部分 = 13 条小散路由
（auth logout/refresh/switch-tenant + tenants CRUD 4 + KB 标签 4 + IM 回调 2）。
验收：56 条 w5a-* golden + W5aSundryRoutesContractTest + 真 PG A/B 三轮 56/56 ALL MATCH
（ab-w5a.sh）。台账见 conventions §8「W5a 收尾批」，坑见 §9「W5a 补充」
（tag.SeqID 回填、refresh 同秒 JWT 掷硬币、PathTenantMatch 死代码、mcp×storage
测试互踩为新发现）。

**⚠️ 批次教训（4.6d 复发确认）**：agent/chatpipeline 与 apikey/auth 等 @SpringBootTest
包同批 → Mockito attach 假红（110 条）；**B1 批拆两批跑**（agent/chatpipeline 一批、
Spring 包按 B1b~B4），分批即全绿。其余处置同 conventions §9「波 4.5b 补充」。

**剩余缺口清单（整体改造收尾，按批派）**：
1. **收尾扫描批（散条 HTTP 面）**：sessions/:id/local-browser ×2（BrowserSkillConnection，
   Go handler/session/browserskill.go）+ sandbox_terminal_ws.go(426)/bridge(340)
   （terminal-ticket + WebSocket）+ embed 公开 QA 委托面（4.3 遗留）+ models/{id}/debug
2. **波 5**：im 执行体（im service.go 3,453，/wechat/qrcode ×2 随此）+
   tenant_skill_* 收口 + shared_agent_access→tools + 共享 agent QA 解析
   （GetSharedAgentForTenant，4.6d 备案）+ 波 4.6d 其余移交缺口
3. **检索引擎批**：HybridSearch 执行面（向量/关键词检索实质执行——4.6d adapter 留
   空/1003 两形态，纯聊天路径不受影响）
4. **执行体批**：ArtifactCollector/rewriteArtifactReferences/VLM Predict 的生产装配
   （dev 部署两侧同形 no-op，真部署才需要）
5. **Owner 决策遗留**：⑱ MCP initialize 契约对齐、SkillEnvironment 位置、
   波 3 SkillFrontmatter snakeyaml 宽容类型、TenantService 占位是否变真、
   ConversationProperties 多环境接线

**重派模板（下一批=收尾扫描批 1）**："收尾批任务书——sessions/:id/local-browser ×2 +
sandbox_terminal_ws/bridge + embed QA 委托面 + models/{id}/debug；验收：golden +
A/B（WS/ticket 按部署态标 XDEP 或双端同打 stub）；先读 conventions §3/§6/§7.5/§8/§9；
golden 前缀先 ls contracts/；WebConfig/APIKeyRoutePolicies 显式授权；小包批测试"

## 0. 一句话背景

把 WeKnora 后端从 Go（Gin/GORM）**全面翻译**成 Java（Spring Boot 3 + JDK 21 + MyBatis-Plus）。
前端**零改动**，因此验收标准是「响应与 Go 实录**逐字节一致**（golden 契约测试）」，
而不是"代码看起来对"。

- Java 仓：`/Users/billy/ragagent-java`（可写）
- Go 仓：`/Users/billy/WeKnora`（**只读**对照，别改任何源文件；`scripts/go-server-up.sh` 会往
  `bin/` 写构建产物，那是对的，但跑完记得 `rm -rf bin` 让 Go 仓保持干净）
- 计划文件：`/Users/billy/.claude/plans/flickering-wishing-cat.md`

## 1. 开工前必读（按顺序，不要跳）

1. **`docs/translation-conventions.md`** —— 本项目最重要的资产。
   - §3 GORM 隐式行为清单
   - §4 错误与响应格式
   - §7.5 **派 agent 的十条强制约束**（每条都对应踩过的坑）
   - §8 翻译日志（每完成一个模块**必须**追加一行）
   - §9 **已确认的契约细节 + 已知差异 + 工具链坑 —— 动任何模块前逐条对照**，
     里面的每一条都是真实踩过的
2. `docs/HANDOFF.md`（本文）—— 进度、波次、下一步、协作方式
3. 需要时再查源码：`server/src/main/java/com/ragagent/`

## 2. 进度总览

### 2.1 已完成的模块

| 阶段 | 模块 | 状态 | 关键验证 |
|---|---|---|---|
| 0 | 骨架（路由/错误体系/分页/TenantContext） | ✅ | — |
| 1 | auth / 租户 | ✅ | 10 条 golden + e2e |
| 2 | 模型配置（SSRF / AES 凭证加密） | ✅ | 9 条 golden + 加密互操作 e2e |
| 3 | 知识库（KB CRUD + 文档→解析→chunk） | ✅ | 20 条 golden + e2e 连真 PG |
| 4.0 | LLM 调用客户端 | ✅ | 368 测试 |
| 4.1 | MCP 服务管理（自研协议 + OAuth 全链） | ✅ | 17 条 golden + 跨语言互操作 |
| 4.2 | Wiki（21 端点 + 生成管线） | ✅ | 13 条 golden + 5 端点 A/B MATCH |
| — | API Key 体系回补 | ✅ | 25 能力 + 数据面白名单收口 |
| — | audit 审计回补 | ✅ | 埋点接线 |
| 5.0 | `stream/` 流管理器 | ✅ | 35 测试（含真 redis） |
| 5.1 | 会话/消息 domain + 仓储 | ✅ | — |
| 5.2 | **`continue-stream` 端点 + SSE 契约层 + storageurl** | ✅ | **四条路径 A/B 逐字节 MATCH**（见 §9.3） |
| — | JSON 编码器全局对齐 + 系统性差分排查 | ✅ | 见 §9.2 |
| **波 0** | **`memory`（16 条路由）** | ✅ | 22 golden + **真 PG A/B 36 组 35 MATCH** |
| **波 0** | **`datasource`（17 条路由）** | ✅ | 39 golden + **真 PG A/B 39 组全 MATCH** |
| **波 1 G1** | **session CRUD + pin（8 条路由）** | ✅ | 34 golden + **真 PG A/B 34 组全 MATCH**（golden 实测纠正 3 处预实现，见 conventions §9「波 1 G1」） |
| **波 1 G2** | **消息面（load/search/stats/delete + 清空，5 条路由）** | ✅ | 23 golden + **真 PG A/B 25 组全 MATCH**（search 的 match_type=hybrid 之谜、matchType 空串合并等，见 conventions §9「波 1 G2」） |
| **波 1 G3** | **追问建议（ensure/get/events，3 条路由）** | ✅ | 17 golden + **真 PG A/B 18 组全 MATCH**（writeError 子串分派、LLM 生成步降级等，见 conventions §9「波 1 G3」） |
| **波 1 G6** | **产物 3 条 + generate_title + stop（5 条路由）** | ✅ | 22 golden + **真 PG A/B 22 组全 MATCH**（golden 抓回 stop 的 Long 引用比较、jsonb 处理器缺 JSR310 两个真缺陷，见 conventions §9「波 1 G6」） |
| **波 1 G4** | **steer（排队/列表/删除/提升，4 条路由）** | ✅ | 11 golden + 直种 streamManager 的排队路径单测 5 条 + **真 PG A/B 12 组全 MATCH**（引擎侧 PollSteer/follow-up 随波 4/5，见 conventions §9「波 1 G4」） |
| **波 1 G5** | **临时文档 attachments（5 条路由，波 1 收官）** | ✅ | 11 golden + **真 PG A/B 11 组全 MATCH**；纯文本解析管线（chunker+token）与 Go 逐字节一致；agent 门控/VLM/asynq 按已知差异收口（见 conventions §9「波 1 G5」） |
| **波 2 chunk** | **chunk 编辑面（10 条路由，波 2 开工）** | ✅ | 46 golden + **真 PG A/B 46 场景全 MATCH**（3161 全量绿）；ChunkAccessGuard（ownership+KB 访问分层）、修订历史/乐观锁、生成问题；clamp 误写被 golden 抓回（见 conventions §9「波 2 chunk」） |
| **波 2 knowledge 域** | **文档操作 16 条 + 搜索/移动/复制 8 条（24 条）** | ✅ | 197 golden + 真 PG A/B 189 项全 MATCH（3183 绿）；spans 合成时间线全量翻译、EnsureDefaults 钩子、file_path 零值归一（见 §9「波 2 knowledge」两节） |
| **波 2 FAQ** | **FAQ 12+1 条** | ✅ | 98 golden + A/B 全 MATCH（1 项预期差异=asynq 重试窗口）；textconv 繁简 vendor；**真缺陷：Kb*Config 七类补 @JsonIgnoreProperties（PG 列 DEFAULT 演进出 split_markers）**（见 §9「波 2 FAQ 补充」） |
| **波 2 基础设施配置** | **web-search-providers/vector-stores/storage-backends 30 条** | ✅ | 110 golden + A/B 全 MATCH（3215 绿）；**A/B 抓回三缺陷：jsonb TypeHandler setObject(Types.OTHER)、StoredResource.TableName()=resources、create 缺 AutoCreateTime 回写**（见 §9「波 2 基础设施配置三组补充」） |
| **波 2 成员/邀请** | **members/invitations/api-principal 17 条** | ✅ | 91 golden + A/B 全 MATCH（3238 绿）；**真缺陷：clearStaleHomeTenant 必须写 SQL NULL（写 0 炸 FK）**；B 的两种 token 形态是契约场景（见 §9「波 2 成员/邀请/api-principal 补充」） |
| **波 2 系统管理端** | **/system 7 条 + /system/admin 15 条 + evaluation 2 条** | ✅ | 53 golden + A/B 三轮稳定全 MATCH（3266 绿）；RequireSystemAdmin 文案纠正、UserKbPin 列映射真缺陷（kb_id/pinned_at）、sandbox-check 留波 3 占位（见 §9「波 2 系统管理端补充」） |
| **波 2 终扫批** | **用户收藏 3 条 + chunker 预览 1 条（波 2 全部收官）** | ✅ | 32 golden + 真 PG A/B 32 场景两轮 ALL MATCH（3298 绿，首轮即全对零缺陷）；GORM Find 空结果 `[]` 非 null、空 strategy=legacy 非 auto、preview 裸错误体、测试堆 2g→3g（见 conventions §9「波 2 终扫批」） |
| **波 3 sandbox 子批 1** | **/sandbox-configs 配置 CRUD 8 条** | ✅ | 27 golden + A/B 27 场景两轮 ALL MATCH（3358 绿，首轮即全对）；URL 守卫先于必填、Inventory 失败=200 固定形态、config 列字段级 AES、SandboxClientFactory 接缝占位（见 conventions §9「波 3 sandbox 子批 1」） |
| **波 3 sandbox 子批 2** | **/system/sandbox-check 转正 + templates/query provider 面** | ✅ | 8 golden + A/B 8 场景两轮 ALL MATCH（3361 绿，首轮即全对）；sandboxCheckReason 固定中文分类是字节稳定锚、plain-500 无 details 键（controller-local handler，FAQ 同款）、RemoteError 分类器落地（见 conventions §9「波 3 sandbox 子批 2」） |
| **波 3 sandbox 子批 3** | **/sandbox-configs/:id/skills* 12 条（sandbox HTTP 面收官）** | ✅ | 18 golden + A/B 18 场景两轮 ALL MATCH（3390 绿）；upload=202 异步受理、SSE 单帧、envs 列逐字段 AES、类级 NON_DEFAULT 吞 false 的坑（见 conventions §9「波 3 sandbox 子批 3」） |
| **波 3 sandbox 子批 4** | **/skills 家族 7 条 + /me/env-vars 5 条（skill 模块用户面收官）** | ✅ | 24 golden + A/B 24 场景两轮 ALL MATCH（3392 绿）；catalog 三段合并投影、install=202 installs 映射、删除钉住 409 1005、DELETE 吃 JSON body、bundle_sha256 掩码（见 conventions §9「波 3 sandbox 子批 4」） |
| **波 3 协作批** | **organizations 25 条 + KB/agent shares 7 条 + shared-* 3 条（协作面收官）** | ✅ | 117 golden（org-*/shr-*）+ A/B 两轮 116 场景 ALL MATCH（3394 绿）；com.ragagent.org 新包 20 文件；golden 纠正六处预实现（require_approval 不存在/shares 回填不对称/permission 恒 viewer/Go 文案错配真录 500/共享 KB raw 读无 EnsureDefaults）；**上报 emoji 转义跨横切缺陷待专项**（见 conventions §9「波 3 协作面」） |
| **波 3 agents 批** | **agents CRUD 8 条 + initialization 3 条** | ✅ | 60 golden（ag-*/init-*）+ A/B 两轮 60 场景 ALL MATCH（3396 绿）；**emoji 修复落地**（GoWriterJsonFactory 改道 Writer，root cause=Jackson UTF8 生成器硬编码，升级不可解；内建 avatar golden 钉住+全逐字节套件回归）；vendor yaml 装载；initialization 的 tenant_id=0 等既有行为照抄（见 conventions §9「波 3 agents 批」） |
| **波 3 browserskill 批** | **/me/browser 3 条 + local-browser 3 条（引擎级鉴权）** | ✅ | 44 golden（含 download 字节+headers）+ A/B 两轮 45 项 ALL MATCH 零 DIFF（3399 绿，测试堆 4g→5g）；跨语言互操作实测（Go authorize 兑换的设备行 Java WS 握手通过）；执行循环随波 4（见 conventions §9「波 3 browserskill 批」） |

### 2.2 波次路线（**2026-09-18 实测重排，已废弃原「阶段 6/7/8」**）

| 波 | 内容 | 规模 | 状态 |
|---|---|---|---|
| 0 | `memory`(7.9k) · `datasource`(14k) | 33 条路由 | ✅ **完成** |
| **1** | **会话/消息面剩余**（CRUD/附件/产物/追问建议/消息历史/steer） | 27 条 | ✅ **完成**（真 PG A/B 全 MATCH） |
| 2 | 其余未被 agent 阻塞的端点群（chunk/knowledge/faq/infra-config/members+invitations+api-principal/system/admin/evaluation + 扫尾 auth/OIDC/跨租户/favorites/chunker-预览） | ~140 条 | ✅ **全部收官（A/B 全 MATCH）** |
| 3 | **关键路径前置**：`sandbox` → `infrastructure` → `browserskill` → `modelcontext` | ~32k | ✅ **波 3 完成（sandbox/skill/协作/agents/browserskill，~92 条）**——emoji 专项已在 agents 批修复。剩余：sessions/:id/local-browser 2 条（随波 4 tools）、models/{id}/debug（阶段 7） |
| 4 | **agent 核心 + tools + chat_pipeline + 前置缺口** | ~40k | ⏳ **4.1/4.3/4.4/4.2/4.5a/4.5b/4.5c 完成并提交**（event 41 文件；embed/im 16 文件 85 golden；模型客户端+检索地基 68 文件 122 测试 + 30 请求体 stub A/B；纯逻辑件 24 文件 79 新测试 + 486 条 Go 实录；4.5a tools 基建 33 文件 + 208 条实录；4.5b 知识检索+wiki 24 文件 + 249 条实录；**4.5c 执行面+MCP 35 文件 + 265 条实录 + MCP stub A/B 双端逐字节收官 tools 全量**——见 §9）。剩 4.6 引擎+chat 收官 |
| 5 | **im 执行体 + skill 收口 + shared-agent 收口** | — | ⏳ im service.go 3,453 行执行体、tenant_skill_* 收口、shared_agent_access→tools：随 4.5/4.6 接缝 |
| 4 | **agent 核心** + `agent/tools`（实测待翻 ~27k 非测试行）+ chat_pipeline 6.8k + 前置缺口 6.5k | ~40k | ⏳ 作战计划见 §2.3 |
| 5 | `chat_pipeline` · `im` · skill · shared-agent 收口 | ~30k | ⏳ |

**为什么这么排（实测结论，别再照搬旧计划）**：

- **335 条待做路由里约 60% 现在就能做，不用等 agent 引擎。** 两条实测推翻了原前提：
  ① `agent/approval` **阶段 4.1 就翻译完了**——按**包名**做闭包判断会把 `mcp_service.go`(11)
  + `mcp_oauth.go`(6) 这 17 条误判成"被 agent 阻塞"；
  ② `session.go` 里的 `chat_pipeline` **只是个没被用到的字段**（全仓只有两处真正调
  `eventManager`，都在 `session_knowledge_qa.go`），所以 ~25 条会话路由**不**被阻塞。
  **教训：判依赖要看调用点，不要看包名闭包。**
- **agent 不是一块巨石，是三件平行的事**：`chat_pipeline` 与 `agent` 之间只有 1 处引用。
  真正的关键路径是 `sandbox → agent 核心 → agent/tools → {im, skill, chat_pipeline}`。
- **`embed`(28 条) 已从"阶段 6"移到波 4 之后**——它同样堵在 `agent/tools` 上。
- 五个真叶子（零未翻译前置）：`datasource` ✅、`memory` ✅、`sandbox`、`browserskill`、`infrastructure`。
  **`sandbox` 是最紧的前置**（`agent/skills` 硬依赖它，另解锁系统管理端与 skill）。


### 2.3 波 4 作战计划（2026-09-20 勘察 agent 实测产出）

**实测规模**：agent 根包 4,944（approval 1,163 已翻）+ compaction 863 + skills 1,858 + token 140 +
tools 非测试 20,112（69 文件）+ chat_pipeline 26 文件 6,763 + 事件契约 794 ≈ **待翻 ~34k**，
另有前置缺口 ~6.5k（models/embedding 3.8k、models/rerank 5.7k 含测试、searchutil 2,139、
web_fetch ~900、web_search 执行面 792、VLM/ASR）。HTTP 面 = qa.go(1,768) 的 chat 三兄弟 +
agent_stream_handler(896，17 种事件订阅 + superseded preamble 剔除) + session_agent_qa/
session_knowledge_qa(1,290) + agent_service 装配(1,485)。

**子批切法**（串行为主，4.3 可与 4.2 并行）：

| 子批 | 范围 | 行数 | 验收 |
|---|---|---|---|
| 4.1 事件契约（**第一子批**） | internal/event 整包 → com.ragagent.event + §6 的 24 个 emit 点落表（final_answer×7/tool_call×3/thought×2/mcp_oauth×2/其余各×1，payload 全在 event_data.go 317 行） | ~794 | 纯单测（Go 实录 payload JSON 逐字节）；零路由零 TestSchema |
| 4.2 纯逻辑件 | token estimator、compaction 全包、prompts 族、const/tool_images/context_debug | ~2,570 | 纯单测（Go 实录程序抄输出） |
| 4.3 embed/im 清单面（可并行） | embed_channel(426)+handler(847)、im channel CRUD+callback(537)、embed 公开面不堵 QA 的 ~14 条 | ~2,200 | golden + 真 PG A/B（wechat qrcode XDEP） |
| 4.4 模型客户端+检索地基 | models/embedding、models/rerank、searchutil、web_fetch、web_search 执行面 | ~6,500 | stub server A/B（双端同 stub 比请求体逐字节） |
| 4.5 tools 全量（可拆 a 确定性/b 知识/c 执行+MCP） | registry+基建 2,450 + 知识九件 5,700 + wiki 十件 2,870 + sandbox/shell/skill 十件 3,330 + MCP 五件 2,290 + web 四件 1,000 + todo/sequential 580 | ~20,100 | 知识/wiki/内存检索：真 PG golden A/B；MCP：双端同打 stub MCP server；web_search：stub |
| 4.6 引擎 + chat 三兄弟 | engine/think/act/observe/finalize/steer + skills 包 + agent_service 装配 + qa.go + agent_stream_handler + chat_pipeline | ~18,000 | **stub LLM 全链路 A/B**（双端同指脚本化 OpenAI 兼容 stub，复用 continue-stream MATCH 基建） |

**关键风险**：① SSE 时序（final_answer event-id 分片重组 + superseded preamble 剔除——最高危）；
② LLM 确定性（chat 链路 A/B 必须双端同指 stub LLM，真实链路只做骨架断言）；③ 虚拟线程显式
拷贝 TenantContext（Go ctx 传租户，ThreadLocal 不跨虚拟线程）+ Long 引用比较陷阱在工具并发
回调复发；④ 引擎装配 bean 走接口 seam 注入 stub，**不加 @SpringBootTest 上下文变体**（§5.15）。
**Java 已有基础（勿重建）**：llm 流式客户端（阶段 4.0）、StreamManager+steer 队列（阶段 5.0）、
session.sse 契约层+continue-stream（5.2）、agent.approval、sandbox 客户端切片、SkillFrontmatter/
SkillBundleParser/TenantSkillService、agentm BuiltinAgentRegistry。

## 3. 下一步：波 4.6（引擎 + chat 三兄弟，波 4 收官批）

**新会话开场动作**（按序）：
1. `git status` + 读 §0（4.5c 已收官，直接派 4.6；4.5c 报告的装配签名清单与
   四个决策点已抄录在 §0「4.6 待办输入」）
2. 派 4.6（engine/think/act/observe/finalize/steer + skills 包 + agent_service 装配 +
   qa.go 1,768 + agent_stream_handler 896 + session_agent_qa/knowledge_qa +
   chat_pipeline 6,763，~18k）——验收：**stub LLM 全链路 A/B**（双端同指脚本化
   OpenAI 兼容 stub，复用 continue-stream MATCH 基建）；**SSE 时序最高危**
   （final_answer event-id 分片重组 + superseded preamble 剔除，见 §2.3 风险清单；
   线格式契约见 §9.3）
3. 主会话全量复核（**按小包批次跑**，见 §0 测试基建坑；4.6 会动 session 域——
   agent 任务书要显式授权跨模块文件，且跑批期间不派别的 agent）
   → 更新 conventions §8/§9 → 提交
4. 4.6 后收尾：models/{id}/debug（阶段 7）+ sessions/:id/local-browser 2 条
   （随引擎）+ ⑱ initialize 契约是否对齐（Owner 决策）

### 3.0 波 2 扫尾清单（✅ 全部完成，留档备查）

最终对账（Go/Java 路由程序化对账 + 逐批核对）确认波 2 命名模块全部落地后，
剩下的"非命名模块但同样不被 agent 阻塞"的散条，已全部完成：

| 组 | 路由 | 说明 |
|---|---|---|
| ~~auth 注册族（~9）~~ | ✅ **已完成（2026-09-19）**：9 端点全落地，46 reg-* golden + 8 契约测试 + 真 PG A/B 46 组两轮 ALL MATCH。台账见 conventions §8「auth 注册族（波 2 扫尾批 1）」，坑见 §9 同名小节 | logout/refresh 阶段 1 已翻 |
| ~~OIDC（4）~~ | ✅ **已完成（2026-09-19）**：4 端点全落地（路由实为 4 条，config/url/start/callback），13 oidc-* golden（302 用合成信封约定）+ 5 契约测试 + 真 PG A/B 两轮 ALL MATCH。只翻了未配置=disabled 确定性分支；enabled 后的 discovery/code 交换/provisioning 整体推迟（§9「波 2 扫尾批 2」deferral）。将来做 provisioning 时 tenantless 建号必须用 UserMapper.insertTenantless（§9「波 2 扫尾批 1」的 FK 坑） | logout/refresh 阶段 1 已翻 |
| ~~跨租户租户管理（4~5）~~ | ✅ **已完成（2026-09-19）**：5 端点全落地（GET /tenants/all、/tenants/search、POST /tenants、GET/PUT /tenants/kv/{key}——注意 KV 是 /kv/{key} 不是 /{id}/kv/{key}，目标租户走 X-Tenant-ID 头）。65 ct-* golden（63 flag-on + 2 flag-off）+ 11 契约测试 + 真 PG A/B 62 MATCH + 1 EXPECTED-DIFF（prompt-templates GET 推迟，Go 独有 vendor yaml）。台账见 conventions §8「跨空间租户目录 + KV 配置（波 2 扫尾批 3）」，坑见 §9 同名小节 | logout/refresh 阶段 1 已翻 |
| ~~用户收藏（标 4，实为 3）~~ | ✅ **已完成（2026-09-19）**：GET/POST /user/favorites + DELETE /user/favorites/{type}/{id}（routes_agent.go 实际只注册 3 条，HANDOFF 旧写 4 条是笔误已纠正）。20 fav-* golden + 5 契约测试 + A/B 两轮 ALL MATCH。表无外键、纯 SQL mapper、幽灵删除 200。台账见 conventions §8「用户收藏 + chunker 预览（波 2 终扫批）」 | — |
| ~~chunker 预览（1）~~ | ✅ **已完成（2026-09-19）**：POST /chunker/preview，12 cprev-* golden（响应全确定零掩码）+ 2 契约测试 + A/B 两轮 ALL MATCH。chunker 补诊断层（SplitWithDiagnostics/splitParentChildWithDiagnostics），核心切分零改动 | — |

**明确推迟（有依赖，别现在做）**：/me/browser + /local-browser（波 3 browserskill）、
/me/env-vars/{skill,sandbox}（波 3/5）、/wechat/qrcode ×2（波 5 im）、
knowledge-chat/agent-chat/knowledge-search（波 4）、models/{id}/debug（阶段 7）、
/system/sandbox-check（波 3，Java 已 404 占位）。

### 3.1 波 3 起点（HANDOFF §2.2 波表已排）

**sandbox 是最紧的前置**（agent/skills 硬依赖，另解锁系统管理端 sandbox-check 与
skill 模块）。顺序：`sandbox → infrastructure → browserskill → modelcontext`。
波 3 的 A/B 直接复用本会话沉淀的脚本族（ab-chunk/ab-knowledge/ab-faq/ab-infra-config/
ab-members/ab-system）与录制脚本参数化模式（XXX_TARGET_PORT/XXX_OUT_DIR）。

### 3.2 已经就位的组件（直接用，别重造）

- **共享守卫**：`ChunkAccessGuard`（ownership+KB 访问分层，403 纯字符串 vs 信封按层分布）
  / `KnowledgeAccessGuard`（含 envelope 形态）/ `requireOwnedKb` 族——知识库域路由照此分层
- **chunk 模块起就位的响应契约**：`Chunk`/`ChunkRevision`/`DocumentChunkMetadata`/
  `GeneratedQuestion`/`KbChunkingConfig`（@JsonIgnoreProperties 全家桶）
- **基础设施**：`PlainErrorException(status,msg)`（任意 handler 直写纯字符串错误的通用出口）、
  `KnowledgeService.generateTaskId`（任务 id 契约）、`KnowledgeTaskProgressStore`
  （进程内进度存储）、`SystemSettingService`（运行时调谐统一入口）、
  `WebSearchProviderService.constructProvider`、`VectorStoreConfigService.testConnection`
- **A/B 脚本族**（全部真 PG、掩码后逐字节）：ab-chunk / ab-knowledge / ab-faq /
  ab-infra-config / ab-members / ab-system / ab-reg；录制脚本统一支持 XXX_TARGET_PORT/XXX_OUT_DIR
  参数化重放（新模块照此模式写）
- 既有：session/message/memory/datasource/wiki/mcp/model/audit/apikey/stream/storageurl 各域
  （见 §2.1 与 conventions §8）

### 3.3 执行纪律（本会话验证有效）

- 派 agent 前先 `lsof -ti :8082` 杀旧 Java server（**旧进程占端口会让 wait_for_port 打到
  旧代码，新路由表现为 404**——本会话 chunk 与 infra 两批都踩过）
- agent 任务书必带：conventions §9 对应小节、golden 前缀防冲突（先 ls contracts/）、
  幂等清理含 chunk_revisions 等衍生表、固定种子 id 纯十六进制
- agent 报"全绿"后主会话必须独立跑全量 + 真 PG A/B——本会话各批里 A/B 抓回了
  **10 个 H2 绿/PG 红或实现缺陷**（clamp 误写、setString→setObject、TableName 影子表、
  AutoCreateTime 回写、UserKbPin 列映射、clearStaleHomeTenant FK、business 零值、
  anydoc 文案、Kb*Config 容忍性、**扫尾批 1 的 tenantless 注册 getter 归一化写 0 炸 FK**）
- agent 可能撞用量上限中断（本会话 members 批中断一次）：中断后主会话直接接力修
  （编译错误→测试失败逐个排），比重新派 agent 快

## 4. 标准验收流程（每个模块）

```bash
# 0) 环境（换了 shell 一定要先设 JDK，否则 ./gradlew 报 "Unable to locate a Java Runtime"）
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
cd /Users/billy/ragagent-java

# 1) 起 Go server 录 golden（同时起 Java 做 A/B 对比）
scripts/go-server-up.sh            # 注意：必须从 WeKnora 目录调！见 §5 第 11 条
scripts/java-server-up.sh          # Java 默认 :8082，Go :8080
# 要共享 Redis 键空间时两个都要带：STREAM_MANAGER_TYPE=redis REDIS_PREFIX=stream:

# 2) 录 golden（务必用 curl -o，不要用 echo >，zsh 会解释转义）
TOKEN=$(scripts/token.sh 8080)
curl -s -o server/src/test/resources/contracts/xxx.json \
  -X POST http://localhost:8080/api/v1/... -H "Authorization: Bearer $TOKEN" ...

# 3) 写契约测试（掩码 UUID/时间戳后逐字节比对；中文用 content().bytes）
# 4) 定向测试 → 最后必须全量
./gradlew :server:test --tests "com.ragagent.<你的包>.*"
./gradlew test                     # ⚠️ 必须跑一次；约 15 分钟（测试堆 4g；全量时长随
                                   #    @SpringBootTest 上下文变体数增长，见 §5 补充）

# 5) e2e / A/B：Java 连真 PG 跑通，并与 Go 逐字节对比
# 6) 更新 docs/translation-conventions.md 的 §8（日志行）+ §9（新细节/差异）
# 7) 提交（结尾带 Co-Authored-By: Claude <noreply@anthropic.com>）
```

**环境**：dev PG `localhost:15432`（密码 `postgres123!@#`，库 `WeKnora`）、
Redis `localhost:16379`（密码 `redis123!@#`）、docreader `localhost:50051`；
测试账号 `java-phase1*`（租户 10002）；**dev DB 含真实数据，只动测试租户**。

## 5. 陷阱清单（按复发率排序）

> 完整版在 `docs/translation-conventions.md` §9。这里是最高频的几条。

1. **领域对象的 `isXxx()` 派生方法必须 `@JsonIgnore`** —— 复发率最高，阶段 3、4.1、波 0 各踩过。
   漏了会把多余的键写进 jsonb，回读抛 `UnrecognizedPropertyException` 让**整列不可用**。
   **防线**：`JsonContractRoundTripTest` 里加 `assertRoundTrips(...)`。
   ⚠️ **不要给字段取名 `isXxx`**（`private boolean isPinned` 会多吐一个键）——字段名去掉 `is` 前缀。
2. **JSON 键名逐字段对照 Go 的 json tag** —— 蛇形漏 `@JsonProperty` 就接不住前端请求。
   **map 响应字段必须挂 `GoMapSerializer`（或模块内子类）、double 字段必须挂 `GoDoubleSerializer`**
   —— §9 有专门说明，这两类**全局解不了**，新增响应类型时逐个检查。
3. **Go 零值语义** —— string 默认 `""`、计数器用原始类型、`omitempty` 的 0/空/false 要省略、
   无 `omitempty` 的恒输出（含 `null`）。**三态 `*bool` 必须是可空 `Boolean`**，压成 `boolean`
   等于替用户做决定。
4. **带 `DEFAULT` 的 jsonb 列**：MyBatis-Plus 对 null 字段**省略该列** → 落到 DB 默认值，
   而 Go 显式写 NULL。需要 `FieldStrategy.ALWAYS`（wiki 踩过）。
   ⚠️ **反过来也成立**：列**没有** DEFAULT 时不要加 ALWAYS（datasource 的三个 jsonb 列就是）。
   **逐个查迁移里有没有 DEFAULT，别一刀切。**
5. **中间件分层会改变错误文案** —— 写契约测试前先确认拒绝发生在哪一层，
   Go 的 handler 里常有**不可达的死代码**。
6. **`Long != Long` 是引用比较** —— 租户 id 10002 超出 `Long` 缓存区间（-128..127），
   用 `!=` 比会让整组子资源 404，**只在真请求下暴露**（波 0 踩到）。比 `Long` 一律用 `equals` 或先拆箱。
7. **自定义 `@Select` 的结果映射不套实体的 `@TableField(typeHandler=…)`** ——
   要写**方法级** `@Results`，否则 jsonb 列静默读成 null（"库里有值、读出来是 null"）。
8. **测试禁止依赖真实网络** —— 用 stub server（`com.sun.net.httpserver.HttpServer` 就够，
   记得 `setExecutor(...)` 否则挂死）；SSRF 白名单要在 `@AfterAll` 还原。
9. **不要写靠墙钟造时间的测试** —— 「1ms 窗口断言已过期」这类单跑绿、全量红（JIT/GC 下
   两次调用落在同一毫秒）。要造"时间已过去"就直接改 DB 里的时间戳。
10. **日志断言必须显式 `setLevel` 再还原** —— 级别过滤发生在 appender **之前**，
    断言 INFO/DEBUG 的测试单跑绿、与 `@SpringBootTest` 同批跑就红。这是"单跑绿全量红"的另一变种。
11. **起 Go server 的两个坑**（都实际踩过）：必须**从 WeKnora 目录**调用（viper 找
    `config/config.yaml`），且 `DB_DRIVER/DB_USER/DB_PASSWORD/DB_NAME/REDIS_ADDR/REDIS_PASSWORD`
    要显式导出（否则 panic `unsupported database driver:` 或 `连接Redis失败`）。
12. **保存 curl 输出用 `-o`**，别用 `echo "$X" > f`（zsh 的 echo 会解释 `\n`，golden 会坏）。
13. **`MyBatisSystemException: null`** 的根因在 `Caused by:` 链深处，别在业务代码里瞎找。
14. **H2 绿、PG 炸** —— NOT NULL 约束、jsonb 键序、DDL 默认值只在真 PG 上暴露。e2e 必须连真 PG。
15. **全量时长/堆的退化信号**（波 3 实测）：全量从 6.7min 涨到 13-15min 且伴随
    「Gradle Test Executor N failed」OOM → 先怀疑 **GC 死亡螺旋**（@SpringBootTest
    上下文变体又变多了），提堆（现 4g）只是买时间，治本是收敛变体数。
    墙钟脆弱测试的第三变种（TTL 续期断言）见 conventions §9「波 3 sandbox 子批 1」。

## 6. 协作方式（已验证有效）

- **主会话做**：共享契约（domain 类型）、跨模块装配（`WebConfig` 路由/过滤器、
  `APIKeyRoutePolicies`）、**`TestSchema` 的 DDL**、golden 录制 / A-B 对比、
  真实缺陷的排查与修复、文档与提交
- **agent 做**：单模块的机械翻译 + 对等测试。任务书必须带 §7.5 的十条约束
- **⚠️ 最重要的一条：agent 报"全绿"之后，主会话必须自己跑一次全量 `./gradlew test` 复核。**
  本轮就靠这个抓到 agent 自己写的一个墙钟不稳测试（单跑绿、全量红）。
  **只跑自己的包会漏掉这类问题。**
- **并发**：多个 agent 同时跑**全量** `./gradlew test` 会争抢 build 目录（OOM / 假失败）。
  任务书里要明确「只跑 `--tests "com.ragagent.<你的包>.*"`」，且**尽量串行**。
- **如果一个 agent 需要跨模块改动**（比如要动 `session` 包、`TestSchema`），
  在它跑的期间**不要派别的 agent**，并在任务书里显式授权那几个文件。
- 派 agent 时**第一句**永远是：「先读 `docs/translation-conventions.md` 的 §3/§7.5/§8/§9」
- **agent 撞用量上限中断（波 2 members 批实测）**：主会话直接接力修——先编译（该批
  遗留了测试变量遮蔽与 @PathVariable 模板名不一致），再逐个排契约测试失败（每修一轮
  重跑单包）。比重新派 agent 快，且上下文无损。接力时以 agent 留下的 golden 为准绳
  （预实现与 golden 冲突时以 golden 为准）。
- **A/B 掩码是逐步长出来的**：每批的 ab 脚本首轮跑完，把 DIFF 里的动态字段逐个加掩码
  （uuid/ts/epoch/task/seq/invite_url/JWT/generated_password/affected…），直到 ALL MATCH
  且连跑两轮稳定。掩码不是"放过差异"——**契约测试同时钉住 Java 自身确定性值**。
- **部署态文件**（capabilities/db_version/evaluation 执行态/搜索引擎列表连接态）在 A/B 里
  标 XDEP 跳过、按部署各自断言，契约测试负责 Java 侧形状。

## 7. 关键文件索引

| 用途 | 路径 |
|---|---|
| 翻译约定（必读） | `docs/translation-conventions.md` |
| 交接文档（本文） | `docs/HANDOFF.md` |
| 契约 golden（909 个） | `server/src/test/resources/contracts/` |
| H2 共享 DDL | `server/src/test/java/com/ragagent/TestSchema.java` |
| JSON 往返体检 | `server/src/test/java/com/ragagent/common/JsonContractRoundTripTest.java` |
| e2e 脚本 | `scripts/{dev-env,go-server-up,java-server-up,token}.sh` |
| golden 录制 / A-B 范例 | `scripts/{record-datasource-golden,ab-datasource}.sh` |
| 路由与过滤器装配 | `server/src/main/java/com/ragagent/config/WebConfig.java` |
| Go 的响应格式锚点 | `com.ragagent.common.web.{GoJsonEscapes,GoMapSerializer,GoDoubleSerializer,GoTimeSerializer}` |

## 8. 如果遇到不确定的

- **架构 / 范围 / 顺序决策**：问用户（这轮几次调整都是用户定的）
- **Go 行为不确定**：**实测**——起 Go server 打一发，**不要猜**。
  这轮发现的真实缺陷（403 两种形态、201 状态码、jsonb NULL 语义、`gorm` 的 `updated_at` 回写内存、
  `Long != Long`）**全部**是实测出来的
- **怀疑 Go 有 bug 时**：先实测再下结论。本轮有一次怀疑 GORM 的 AND/OR 优先级问题，
  用 DryRun 打印实际 SQL 后发现**是我错了**（GORM 会自己包括号），差点"修好"成偏离 Go。

## 9. 本轮（阶段 5.2 + 波 0）的经验总结 —— 新 agent 读这一节能少走弯路

### 9.1 最值钱的方法：**录 Go 实录**

不要靠读源码推断 Go 的行为。把 Go 的类型/函数**原样抄进一个独立 Go 程序**，
跑出真值，再把输出抄进 Java 断言。这条方法抓到过：

- `types.JSON` 漏抄 `MarshalJSON` 会退化成 base64（差点按错的行为写 Java）
- Java 的 `Double.toString` 在次正规数上比 Go 长（`4.9E-324` vs `5e-324`）
- Java 的 `$` **不等于** Go 的 `$`（Java 还匹配末尾换行符之前）——照抄会让流卡住
- gin 的 SSE 帧是 `event:message\ndata:…\n\n`（冒号后**没有空格**）+ Go 的 HTML 转义
- `scoreItems` 的分母口径、`selectResidentInterests` 会把 nil 条目也塞进 selected

**Go 程序要放到 `/tmp` 或复制一份 Go 仓到 `/tmp`**（原仓只读）。
需要调未导出函数时，用 `go test -overlay` 挂探针或复制整仓加同包测试文件。

### 9.2 JSON 编码器的类差异（已全局对齐，但要知道有哪些）

- **HTML 转义**（`< > &` → `<` 等）：**已全局装**在 `JacksonConfig`
- **控制字符小写十六进制、短转义**：`GoJsonEscapes`（注意是**整表替换**，
  `\b \t \n \f \r \" \\` 必须显式声明）
- **U+2028 / U+2029**：**已知差异，刻意保留**（Jackson 的 `CharacterEscapes` 够不到非 ASCII）
- **float64 格式**：**逐字段**，`GoDoubleSerializer`。**不要全局注册**——会污染发给 LLM provider 的请求体
- **map 键序**：**逐字段**，`GoMapSerializer`。注意它**只排键序**，
  map 里的 `Double` 值仍会被 Jackson 写成 `1.0`——值里可能有数字的字段要用模块内子类
  （见 `datasource.domain.DataSourceMapSerializer`）

### 9.3 SSE 线格式（A/B 已验，改动前先读）

四条路径逐字节 MATCH：错误路径 ×3、handle 模式回放、public 模式扣留冲发、public 模式跨分片重组。

- 帧：`event:message\ndata:<json>\n\n`；JSON 走 Go 的转义与 map 排序
- **Content-Type 被 SSE 渲染器无条件覆盖**成 `text/event-stream;charset=utf-8`
- 扣留键是 `类型 + NUL + 事件 id`：**同一流的增量分片共用一个 event id 才会重组**
- 客户端断开：Java 用**写失败**检测（Go 用 ctx 取消）——差异是**延迟**（有界）而非错误

### 9.4 一个模块的典型节奏（≈4 步，`memory` 与 `datasource` 都是这么走的）

1. **契约类型**（domain：实体 + 5~10 个响应/配置类型）—— 主会话做
2. **实体 + 仓储**（Mapper + Repository）—— 可派 agent，但 **`TestSchema` 由主会话加**
3. **service 层** —— 派 agent
4. **HTTP 层 + 路由**（Controller + `WebConfig` + `APIKeyRoutePolicies`）—— 派 agent

每步一个提交；每步都要求 agent 附**一次全量 `./gradlew test`** 的结果；
主会话再独立复核一次。一个模块大约 5 个提交 / 2000-6000 行 Java / 100-900 条测试。
