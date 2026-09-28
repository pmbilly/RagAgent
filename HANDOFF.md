# ragagent 交接文档（新仓起步）

> 本文档写给在 `~/ragagent` 打开的新会话/新成员。新会话没有旧仓会话的记忆，**一切背景以本文为准**。
> 种子：自 `~/ragagent-java` @ `646aba7`（2026-09-28）分叉，git 历史完整保留（blame/log 可直接用）。

## 1. 仓库身份与分界

- 本仓 = 原 WeKnora Go 后端的 Java 翻译版（ragagent-java）的**后续演进线**。
- 自 seed 起：**不再承担与 Go 仓的任何契约对齐义务**——字节级一致、双端 A/B 对拍、Go 错误文案复刻、GORM 行为复刻注释等全部退役。
- 旧仓 `~/ragagent-java` 已冻结零修改，仅作考古参照（翻译方法论、坑史在那边，见 §10）。不要往旧仓推任何代码。
- 本仓 git origin 未设置（本地 clone 后已摘除，避免误推旧仓）；远端建好后：`git remote add origin <url> && git push -u origin main --tags`。

## 2. 已定决策（勿再重新讨论）

1. **旧仓零修改**：没有冻结过渡期、没有 fix 回流，本仓即唯一工作仓。
2. **产品未上线，无数据连续性负担**：schema 可直接做基线合并（见 §5 阶段 1）。
3. **前端随后端逐步调整**：改契约的后端 PR 同 PR 带前端修改，不设集中适配期。
4. **契约标准（阶段 2 目标形态）**：字段命名保留 snake_case（`@JsonNaming(SnakeCaseStrategy)` 是标准做法，不改 camelCase）；错误改 RFC 7807 Problem Details；时间用 jackson-datatype-jsr310（ISO-8601，`WRITE_DATES_AS_TIMESTAMPS=false`）；空值策略 `@JsonInclude(NON_NULL)`。
5. **功能裁剪清单**：裁 `sandbox` + `browserskill`（**连带技能体系**：installer agent、镜像快照、shell_exec、沙箱文件四件套、PTY 终端、browser skill）、`org`（共享空间/跨租户授予）、`im`（九渠道）、`datasource`（连接器）、`evaluation`、`favorite`。保留：mcp、memory、embed、wiki、知识库/检索/会话主链路；多引擎检索是否裁到 postgres 单引擎**待议**（裁则再省约 1.5 万行与 9 个驱动适配）。
6. **自研基础设施保留**：EventBus、StreamManager（Redis Stream）、chatpipeline 插件管线、ToolRegistry、各 Bridge——是架构不是技术债；阶段 4 只做多模块边界固化，不替换。
7. **Go 兼容序列化层在阶段 2 删除**：`common/web` 下 `GoMapSerializer/GoDoubleSerializer/GoTimeSerializer/GoJsonEscapes` 等（约 110 个引用点回归标准 Jackson；目前 Controller 里还有大量手搓 `ObjectNode`，一并在阶段 3 收敛为 DTO 序列化）。
8. **神类拆分有现成地图**：41 个千行大类的分段注释就是原 Go 文件边界，沿注释拆即可，不需要重新设计边界。
9. **Agent 能力取舍已接受**：裁沙箱后 Agent 剩余工具面 = 知识检索族 + wiki 十件 + web 两件 + MCP + DuckDB 数据分析（DataAnalysisTool 走独立 DuckDB 会话，初步判断不依赖沙箱，动手时验证）。

## 3. 两条红线

1. **一次只动一个轴**：裁剪期不改结构、换锚期不拆类、拆类期不动架构；每阶段结束必须全绿可运行。一旦开始"顺手把 X 也重构了"，就退化成大爆炸重写。
2. **裁剪手术期单工作流**：缝合点文件高度重叠，PR 串行合入；阶段 3 起可按包分线并行。

## 4. 关键测量数据（2026-09-28，裁剪前）

- main：1,608 文件 / 32.4 万行；test：448 文件 / 14 万行 / 1,783 份契约 fixture；frontend：533 文件 / 23.6 万行。
- 包 LOC Top：agent 35.4k、datasource 28.3k、sandbox 26.6k、knowledge 25.9k、session 25.4k、wiki 23.5k、retrieval 19.4k、im 15.9k、memory 13.1k、mcp 12.7k、llm 11.1k、auth 9.4k、chatpipeline 8.8k……（browserskill 3.3k、org 4.5k、embed 2.6k、evaluation 2.2k、favorite 0.3k）
- 裁剪全部候选后 main 约减 25%（~77k 行），对应测试等比例消失。
- 千行大类 41 个（裁掉 sandbox/org 相关后剩约 32）：KnowledgeService 3,392 行 / 153 方法、AgentEngine 3,266、FaqService 3,089、WikiIngestBatchHandler 2,268、WikiIngestService 2,182、KnowledgeController 1,312……
- "对照 Go"注释引用 6,145 处 / 1,511 文件：阶段 3 **随触碰清洗**（先摘出真实不变量信息再删 Go 锚点），不搞专项大扫除。
- 裸 `System.getenv()` 约 90 处：阶段 3 收敛进 `@ConfigurationProperties`。

## 5. 转型路线图

| 阶段 | 内容 | 量级 |
|---|---|---|
| 0 起步 | 建仓/环境隔离/CI 骨架/裁剪清单签字（本文档即阶段 0 产物） | 基本完成 |
| 1 裁剪 | 按 §6 缝合点逐 PR 拆除；schema dump → `V1__baseline.sql`（减裁剪表，196 个增量迁移退役）；测试对比器从字节对比改 **JSON 语义对比**（键序/转义归一化后再比）+ fixture 重录 | 3–4 周 |
| 2 契约换锚 | 删 Go 序列化层、Problem Details、jsr310、NON_NULL（§2 第 4 条）；每个端点改完同 PR 带前端 | 2–3 周 |
| 3 Java 标准化 | 拆神类（沿注释边界）；Controller rawBody 手工解析 → DTO + `@Valid`（如 knowledge 包 68 处手搓 ObjectNode）；ChunkRepository 等 "GORM 复刻层" 改写为本仓自己的数据访问契约（行为不变、文档重写）；getenv 收敛；注释清洗 | 3–5 人月 |
| 4 架构调整 | Gradle 多模块（platform-core / domain-knowledge / domain-agent / infra …）+ ArchUnit 边界规则进 CI | 1–2 人月 |

总量约 5–8 人月；2 人并行日历约 2.5–4 个月。

## 6. 裁剪缝合点（精确文件清单，2026-09-28 import grep 实测）

> 注意：同包引用不产生 import，以下只列**跨包**缝合点；包内调用方（如 SessionAgentQaService 调 SessionSandboxExecutionService）在删服务类时编译器会全部指出。

**datasource / evaluation / favorite：零外部引用**——直接删包 + 删对应 controller 路由、测试、fixture、前端页面。

**sandbox（8 个跨包引用文件）**：
- `config/SandboxWiringConfig.java`（装配类，随沙箱整体删）
- `agent/skills/TenantSkillSource.java`（技能的租户镜像源，技能体系随沙箱裁掉）
- `session/service/`：`SessionSandboxExecutionService`、`SessionTerminalService`、`TerminalBridge`、`InstallEngineFactoryImpl`、`SessionBoundArtifactSource`、`SessionAttachmentStagingService`
- browserskill（2 个）：`agent/AgentConsts.java`（常量引用）、`session/controller/SessionController.java`（终端/产物端点）
- 连带清理：agent config 的 `sandboxConfigId/skillsEnabled/skill_selection_mode` 字段；`agentm/builtin_agents.yaml` 的 `builtin-skill-installer` 角色；system_settings 的 `sandbox.docker_enabled` 键；`SessionAgentQaService` 的 `holdSandboxTurn`/沙箱工具注册调用点；docker-java/远程沙箱（Cube/E2B）相关依赖与配置。

**org（6 个）**：`agentm/service/CustomAgentService`、`agentm/dto/AgentResponses`、`session/service/AgentResolver`（共享优先回落改直查 own）、`session/controller/KnowledgeQaController`、`knowledge/service/SharedAgentAccessResolver`、`wiki/controller/WikiPageController`。连带：KB/Agent shares 端点族、跨租户开关（`TenantProperties.enableCrossTenantAccess`）、embed 渠道绑定共享 agent 的校验、org 相关 API Key 能力项。

**im（1 个）**：`config/ImAdapterWiringConfig.java`（+ im 包内回调 controller 自删）+ 前端渠道设置页。

**前端对称删除**（随各 PR，以路由/菜单为准；关键词粗测供参考）：skill ~61 文件、sandbox ~62、org/share ~74–86、datasource ~22、im-channel ~2。

## 7. 第一周任务（阶段 1 开局）

- PR1：删 `datasource` + `evaluation` + `favorite`（零耦合纯删）——顺便建立"裁一个功能 = 一个 PR（含测试/fixture/前端）"的节奏模板。
- PR2：删 `im`。
- PR3：拆 `sandbox` + `browserskill`（§6 清单）。
- PR4：拆 `org`。
- PR5：schema 基线合并（`V1__baseline.sql` = 当前 schema − 裁剪表，删 196 个增量迁移）+ 对比器语义化 + fixture 重录。
- 建议 PR1 合入后再动 PR3/PR4，减少缝合点冲突。

## 8. 环境与运行

- **后端端口改 8083**（避开旧仓 8082 本地走查环境）。
- **PG 独立库名**（建议 `ragagent`）：基线合并会改 schema，不能与旧仓共用 dev 库；docker-compose 里 ParadeDB/Redis 实例可共用，建新库即可。
- 前端开发代理：`VITE_DEV_PROXY_TARGET=http://localhost:8083`。
- `.env` 已从旧仓原样复制（未入库，gitignore 正常），**待改** `SERVER_PORT` 与库名；`SYSTEM_AES_KEY` 可沿用。
- CI 起步三样：build、test、Spotless；ArchUnit 规则留到阶段 4。

## 9. 测试与安全网

- 448 个测试 / 1,783 fixture 是重构回归网，**每阶段结束必须全绿**——这是"种子 fork + 渐进转型"优于重写的全部意义。
- 裁剪功能的测试/fixture 随 PR 删除；阶段 1 末对比器改 JSON 语义对比并重录后，fixture 锚定的是**本仓自己的行为**，与 Go 再无关系。
- A/B 对拍脚本与 `artifacts/` 产物未带入本仓（留在旧仓）。

## 10. 考古指引（需要时去旧仓查）

- 某行为为什么是这样：旧仓 `docs/HANDOFF.md`（翻译约定正文）、`docs/known-issues/`（坑史，尤其 04 沙箱/技能卷、05 事件契约/工具/引擎卷）。
- 某行代码来历：直接在本仓 `git blame`（seed 前历史完整保留）。
- 架构总览（裁剪前状态）：`docs/site/` 门户、`architecture.html` / `agent-workflow.html` 交互图、`api/` 452 路由清单——阶段 1 后按新形态重生成。
