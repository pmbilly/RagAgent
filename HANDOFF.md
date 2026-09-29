# ragagent 交接文档（新仓起步）

> 本文档写给在 `~/ragagent` 打开的新会话/新成员。新会话没有旧仓会话的记忆，**一切背景以本文为准**。
> 种子：自 `~/ragagent-java` @ `646aba7`（2026-09-28）分叉，git 历史完整保留（blame/log 可直接用）。
> **最近更新 2026-09-30**（本次会话）：§2 补第 11–14 条（落库格式 / 宽松读统一策略 / 包结构 / 命名政策，**均为已定，勿再重新讨论**）；§4 指标复核；§5 进度刷新；§7 当前状态重写（**接手先读这里**）；§9 补验证命令；新增 §11 执行记录 / §12 knowledge 包结构地图 / §13 操作经验备忘。
> **接手第一件事**：`git log --oneline -30` 看 `refactor/knowledge-java-idioms` 分支近提交；跑一次 §9 的三条验证命令确认基线全绿。

## 0. 总目标（2026-09-29 用户定稿）

**根据 Java 的标准和思想，全面提升代码的可读性。**

- 这是从翻译期（"忠实复刻 Go"）到 Java 本位期的目标切换：以 Java 生态的主流标准与惯用法为尺度——标准 Jackson 序列化、DTO + `@Valid` 请求绑定、`@ConfigurationProperties` 配置、Spring 装配惯例、常规类规模与命名——让代码读起来像一个原生 Java 项目，而不是 Go 的 Java 转写。
- 可读性是唯一主线：§5 路线图的各阶段（神类拆分、序列化换锚、DTO 化、注释清洗等）都是达成它的手段，规划优先级以"对可读性的收益"衡量。
- 行为不变仍是底线（4,600+ 测试是安全网）；可读性改造不得改变对外契约与业务语义，契约形态的显式变更走 §2 第 4 条。
- **下一步规划由用户在另一个新会话进行**——那个会话应以本文档为唯一背景，围绕本目标展开（候选工作面见 §5 阶段 2/3/4 与 §4 的存量数据）。

## 1. 仓库身份与分界

- 本仓 = 原 WeKnora Go 后端的 Java 翻译版（ragagent-java）的**后续演进线**。
- 自 seed 起：**不再承担与 Go 仓的任何契约对齐义务**——字节级一致、双端 A/B 对拍、Go 错误文案复刻、GORM 行为复刻注释等全部退役。
- 旧仓 `~/ragagent-java` 已冻结零修改，仅作考古参照（翻译方法论、坑史在那边，见 §10）。不要往旧仓推任何代码。
- 本仓 git origin 未设置（本地 clone 后已摘除，避免误推旧仓）；远端建好后：`git remote add origin <url> && git push -u origin main --tags`。

## 2. 已定决策（勿再重新讨论）

0. **总目标 = 按 Java 标准全面提升可读性**（见 §0）：后续所有重构规划的出发点与优先级尺度。
1. **旧仓零修改**：没有冻结过渡期、没有 fix 回流，本仓即唯一工作仓。
2. **产品未上线，无数据连续性负担**：schema 可直接做基线合并（见 §5 阶段 1）。
3. **前端随后端逐步调整**：改契约的后端 PR 同 PR 带前端修改，不设集中适配期。
4. **契约标准（2026-09-29 用户改定，取代本文档早前"保留 snake_case + RFC 7807"版本；细则见 `docs/knowledge-api-contract-v1.md` v1.0）**：
   - **字段命名 = camelCase，且 JSON 字段名 = Java 字段名**（禁止逐字段 `@JsonProperty`、禁止 `@JsonNaming` 下划线转换）；布尔字段不带 `is` 前缀（`pinned`/`enabled`）。
   - **成功响应不再包 `{data, success}` 信封**：单资源直接返回对象、列表直接返回数组；分页统一 `{"items","page","pageSize","total"}`；删除类接口返回 **HTTP 204**。
   - **错误响应统一** `{"error":{"code","message","details"}}`（保留数值 code，前端分支不变）。
   - 时间 ISO-8601 带时区；**可空字段显式输出 `null`**（不用 NON_NULL 省略、不用空串/0 代替）；不使用 `Problem Details`。
   - 落地范围（**2026-09-30 更新**）：知识库域**已完成**；并已按同标准扩展到 **检索域**（`SearchResult` + `hybrid-search`）、**会话/消息/附件/建议/steer/knowledge-search**、**chunker/preview**（均同批带前端）。其余域（wiki/agent/auth/memory/mcp 等）尚未跟进，按"域接域、同 PR 带前端"继续。
5. **功能裁剪（2026-09-28 用户定稿，第一批）**：移除「**浏览器连接、沙箱、CLI、Chrome 插件、Claw Skill**」五项——对应后端 `browserskill` + `sandbox` 两包（沙箱执行面：installer agent、镜像快照、shell_exec、沙箱文件四件套、PTY 终端；**技能体系保留但降级为指令型**，见第 9 条）与前端 integrations 设置的 `cli`/`chrome`/`claw` 三个纯展示 tab（精确清单见 §6.1）。裁完后**聚焦知识库与 Agent 两个域的重构**（§5 阶段 2）。以下为**待排期可选项**（不在第一批，勿主动动手）：`org`（共享空间/跨租户授予）、`im`（九渠道）、`datasource`（连接器，28.3k 行零耦合）、`evaluation`、`favorite`；多引擎检索是否裁到 postgres 单引擎待议。保留：mcp、memory、embed、wiki、知识库/检索/会话主链路。
6. **自研基础设施保留**：EventBus、StreamManager（Redis Stream）、chatpipeline 插件管线、ToolRegistry、各 Bridge——是架构不是技术债；阶段 4 只做多模块边界固化，不替换。
7. **Go 兼容序列化层退役（可读性主线的一环）**：`common/web` 下 `GoMapSerializer/GoDoubleSerializer/GoTimeSerializer/GoJsonEscapes` 等（**2026-09-30 实测：408 处引用 / 94 文件**）回归标准 Jackson；Controller 里大量手搓 `ObjectNode` 一并收敛为 DTO 序列化——DTO 化是 Java 本位可读性的核心工作面。**注意**：这是唯一被红线要求"一次性全仓完成"的动作（半删状态最危险），见 §3 与 §5 阶段 3。
8. **神类拆分有现成地图**：41 个千行大类的分段注释就是原 Go 文件边界，沿注释拆即可，不需要重新设计边界。
9. **Agent 能力取舍已接受**：裁沙箱与浏览器连接后，Agent 剩余工具面 = 知识检索族 + wiki 十件 + web 两件 + MCP + DuckDB 数据分析（DataAnalysisTool 走独立 DuckDB 会话，初步判断不依赖沙箱，动手时验证）；browser skill 工具随 `browserskill` 一并消失。
10. **技能降级为指令型（2026-09-28 定稿，选项 B）**：技能 = playbook——保留 SKILL.md 提示词注入路径（`agent/skills` 的 Skill/Loader/Manager + `AgentEngine.setSkillsManager` + agent config 的 `skills_selection_mode/selected_skills` 字段 + 前端技能选择器），模型凭指令执行；**删除**镜像源（TenantSkillSource）、安装管线、shell/文件注入（范围见 §6.1③）；执行型扩展需求引导走 MCP。
11. **落库格式（jsonb / 会话引用的存储）同样走 Java 字段名**（2026-09-29 用户改定）：产品未上线、**无历史包袱**，所以落库 JSON 不再保留 snake——直接去 `@JsonProperty`，**不加兼容别名**（曾加过的 `@JsonAlias` 已删）。范围：knowledge 落库类型（`Knowledge`/`KnowledgeBase`/`Chunk`/`ChunkRevision`/`GeneratedQuestion`/7 个 `KnowledgeBase*Config`/`*Metadata`/`Payload`/`FaqImportResult`）+ `retrieval.domain.SearchResult`。
12. **宽松读统一策略**（不要再逐类挂注解）：读落库/外部 JSON 一律忽略未知属性——`common/web/JsonMappers.lenient()` 是唯一工厂（jsonb 读写 `PgJsonTypeHandler`、chatpipeline 的 mapper、配置类型工厂都接它），Spring MVC 绑定侧由 `application.yml` 的 `spring.jackson.deserialization.fail-on-unknown-properties: false` 承担。**新增 mapper 必须接工厂**；旧域尚存的 44 个逐类 `@JsonIgnoreProperties` 属未接工厂的域，勿直接批量删。
13. **包结构约定（knowledge 包已示范，其余域照此靠拢）**：`mapper/` 只放 MyBatis-Plus 接口；`repository/` 放仓储门面（软删/乐观锁/方言分支）；`service/` 只放 Spring 服务（用例）与 `@Component`；`support/` 放无状态零依赖的算法与规则；`task/`、`client/`、`storage/`、`security/` 按角色。**一类型一文件**——禁 `*Dtos`/`*Enums`/`*Jsons`/`*Util` 这类复数容器与收集器类（已清理，别再造）。
14. **命名政策**：类型名**全词**（禁 `Kb`/`Ops` 等缩写；例外：实体类名可跟随表名，如 `UserKbPin` ↔ `user_kb_pins`）；请求侧布尔字段不带 `is` 前缀；Go 术语清零（`Runes`→`CodePoints`、`runeSlicesEqual`→`codePointSlicesEqual`）；抽象接口后缀用 `Gateway`（非旧 `Bridge`）。

## 3. 两条红线

1. **一次只动一个轴**：裁剪期不改结构、换锚期不拆类、拆类期不动架构；每阶段结束必须全绿可运行。一旦开始"顺手把 X 也重构了"，就退化成大爆炸重写。
2. **裁剪手术期单工作流**：缝合点文件高度重叠，PR 串行合入；阶段 3 起可按包分线并行。

## 4. 关键测量数据（**2026-09-30 复核**）

- main：**1,535 文件 / 27.9 万行**；test：**401 文件 / 12.6 万行 / 1,366 契约 fixture**；frontend：**465 文件 / 20.0 万行**。
- 后端测试：**4,670 用例全绿**（含 6 个 skip）；前端 `vue-tsc` 0 错误 + **690 用例全绿**（§9 有命令）。
- **≥800 行的类（main，全仓）**：AgentEngine 3,235、WikiIngestBatchHandler 2,268、WikiIngestService 2,182、InitializationController 1,981、DataSourceService 1,827、SessionKnowledgeQaService 1,764、MemoryService 1,660、OpenSearchRetrieveRepository 1,652、WikiPageServiceImpl 1,642、KnowledgeQaController 1,616……
- **knowledge 包（已整治，可作样板）**：**197 文件 / 25,264 行**；最大三个 = `FaqImportService` 1,234、`KnowledgeService` 850、`KnowledgeProcessWorker` 814；13 个子包见 §12；容器类/`*Util` 反模式命名已清零。
- **Go 遗留面（阶段 3 的存量，均为本仓 grep 口径）**：Go 兼容序列化器引用 **408 处 / 94 文件**；"对照 Go / GORM"类注释锚点 **6,157 处**（阶段 3 随触碰清洗，先摘不变量信息再删锚点，不搞专项大扫除）；裸 `System.getenv()` **151 处**（收敛进 `@ConfigurationProperties`）。
- 历史对照（2026-09-28 裁剪前）：main 1,608 文件 / 32.4 万行、test 448 / 14 万 / 1,783 fixture、frontend 533 / 23.6 万；千行大类 41 个（含 KnowledgeService 3,392 行 / 153 方法、FaqService 3,089、KnowledgeController 1,312——**这些数字均已过时**，knowledge 域已完成拆分）。

## 5. 转型路线图

> 各阶段均为 §0 总目标的手段；具体下一刀的取舍与排序由用户在新会话规划——下表是存量工作面的盘点，不是既定排期。

| 阶段 | 内容 | 量级 |
|---|---|---|
| 0 起步 | 建仓/环境隔离/CI 骨架/裁剪清单签字（本文档即阶段 0 产物） | 已完成 |
| 1 五功能移除 | 按 §6.1 清单逐 PR 拆除浏览器连接/沙箱(含技能体系)/CLI/Chrome插件/Claw Skill；schema dump → `V1__baseline.sql`（减裁剪表，196 个增量迁移退役）；测试对比器从字节对比改 **JSON 语义对比**（键序/转义归一化后再比）+ fixture 重录 | **已完成（2026-09-29）**：ba04157（CI+环境）→ f073c88（PR1 三 tab）→ 0f72b0f（PR2 浏览器连接）→ caef9d5（PR3 沙箱+技能降级）→ fba0e7a（PR4 基线+语义比较器）；累计净删 ~8.3 万行，4,685 后端测试全绿 |
| 2 **知识库 + Agent 聚焦重构** | knowledge 四神类 + agent 五神类拆分（沿注释边界）；Controller rawBody → DTO + `@Valid`；GORM 复刻层改写为自有数据访问契约 | **knowledge 域已完成**（2026-09-29/30，见 §11）：神类全拆（最大 1,234 行）、34 个 rawBody 端点 DTO 化、目录整治 13 子包。**剩 agent 域五神类**（AgentEngine 3,235 为首）与其余域的 GORM 复刻层 |
| 3 契约换锚（全仓一次性） | 删 Go 序列化层（408 处引用 / 94 文件）、Problem Details、jsr310、NON_NULL（§2 第 4 条）；每个端点改完同 PR 带前端 | **部分已执行**：knowledge / retrieval / 会话-消息-附件-建议 / chunker-preview 的前端可见契约已换锚（§11），**落库格式也已去 snake（§2 第 11 条）**；**Go 序列化器本体删除未动**——按红线仍须一次性全仓完成 |
| 4 其余域标准化 + 架构调整 | session/wiki/retrieval 等其余神类；getenv 收敛；注释清洗；可选裁剪（im/datasource，见 §6.2——org 已清账）；Gradle 多模块 + ArchUnit 边界规则进 CI | 2–3 人月（未开始） |

总量约 5–8 人月；2 人并行日历约 2.5–4 个月。阶段 2/3 顺序可对调（语义对比落地后换锚对已拆分代码同样安全），但**序列化层删除必须一次性全仓完成**——半删状态（一部分端点走 Go 格式、一部分走标准 Jackson）最危险。

## 6. 缝合点（精确文件清单，2026-09-28 import grep 实测）

> 注意：同包引用不产生 import，以下只列**跨包**缝合点；包内调用方（如 SessionAgentQaService 调 SessionSandboxExecutionService）在删服务类时编译器会全部指出。

### 6.1 第一批移除：五功能（§2 第 5 条）

**① CLI / Chrome 插件 / Claw Skill —— 纯前端集成页，无任何后端代码**：
- `frontend/src/config/integrations.ts`：`IntegrationTab`/`INTEGRATION_TABS`/`INTEGRATION_PREVIEW_ITEMS` 去掉 `cli`/`chrome`/`claw` 三项，删 `CHROME_EXTENSION_URL`（Chrome 商店外链）与 `CLAWHUB_SKILL_URL`（clawhub.ai 外链）两个常量
- 删三个 landing 视图：`views/integrations/CliIntegrationLanding.vue`、`ChromeExtensionLanding.vue`、`ClawSkillLanding.vue`（+ `cliIntegration.ts` 及其测试）
- i18n 五语言对应文案、`settingsRoute` 相关测试同步更新

**② 浏览器连接（browserskill，3.3k 行）**：
- 后端删整个 `browserskill/` 包：端点族为 `/api/v1/me/browser`（BrowserSkillAccountController）、`/api/v1/sessions/{id}/local-browser`（BrowserSkillSessionController）、BrowserSkillGatewayController；跨包引用仅 2 处——`agent/AgentConsts.java`（常量）、`session/controller/SessionController.java`
- 前端删：`views/settings/BrowserConnectionSettings.vue`、`BrowserSearchPreferences.vue`（+测试）、`stores/browserConnection.ts`（+测试）、chat 的 `BrowserTaskPreview.vue`/`BrowserToolDetails.vue`、`AgentStreamDisplay.vue` 内 browser 工具展示分支、`Settings.vue` 导航项

**③ 沙箱（sandbox，26.6k 行）——量最大，8 个跨包引用文件；技能按选项 B 降级（§2 第 10 条）**：
- `config/SandboxWiringConfig.java`（装配类，随沙箱整体删）
- `agent/skills/TenantSkillSource.java`（租户**镜像**技能源，删；但 `Skill/Loader/Manager` 提示词注入路径**保留**）
- `session/service/`：`SessionSandboxExecutionService`、`SessionTerminalService`、`TerminalBridge`、`InstallEngineFactoryImpl`、`SessionBoundArtifactSource`、`SessionAttachmentStagingService`
- 技能侧连带删除：安装管线（`sandbox/service/SkillInstallPipelineImpl` 及快照/镜像指针切换/reaper 的镜像部分）、`Manager.prepareShellEnvironment`（shell 环境注入）与技能 staging 进 `/workspace` 的路径、安装器专用工具 `WriteSkillFileTool`/`EditSkillFileTool`、前端技能的**上传/安装/install-events SSE/transcript/reinstall/stop** 页面与 API
- 技能侧保留：SKILL.md 加载与系统提示词注入、agent config 的 `skills_selection_mode/selected_skills`、前端技能**选择器**
- 沙箱侧连带清理：agent config 的 `sandboxConfigId` 字段；`agentm/builtin_agents.yaml` 的 `builtin-skill-installer` 角色；system_settings 的 `sandbox.docker_enabled` 键；`SessionAgentQaService` 的 `holdSandboxTurn`/沙箱工具注册调用点；docker-java/远程沙箱（Cube/E2B）相关依赖与配置；前端 `SandboxSettings.vue`
- **PR3 唯一设计项**：指令型技能的来源——内置静态 SKILL.md（classpath）或简化版 DB 目录（上传 bundle 只存档+注入，去掉"装依赖+验证+快照"步骤）；建议先做内置静态源跑通、DB 目录随后

### 6.2 可选裁剪项状态

- `org`（空间分享）：**已完成裁撤（2026-09-29，d4d63e0）**——org 包、KB/Agent shares 端点族、跨租户开关（enableCrossTenantAccess）、租户目录发现面（/tenants/all|search）、共享七表（V1__baseline 第二版）；保留面：同空间成员管理、/auth/invitations、个人多空间切换。AgentResolver 已改本租户直查；AgentResponses 内联 agentConfigMap。
- `datasource` / `evaluation` / `favorite`：**零外部引用**，随时可纯删（datasource 前端在 KB 设置面板 `views/knowledge/settings/DataSource*.vue`）。
- `im`（1 个跨包引用）：`config/ImAdapterWiringConfig.java`（+ im 包内回调 controller 自删）+ 前端渠道设置页（integrations 的 `im` tab）。

## 7. 执行记录（阶段 1 完成 / org 清账 / 阶段 2 开局）与当前状态

**阶段 1 已完成**（五个提交见 §5 表格；串行合入，红线 #2 全程遵守）。执行中的增量记录：

- 指令型技能数据源已定稿落地：宿主技能目录 `weknora.skills.host-dirs`（env `WEKNORA_SKILL_HOST_DIRS`，逗号分隔），`agentm/SkillsCatalogController` 提供 `GET /api/v1/skills`，前端技能选择器已切换；未配置目录时 `skills_available=false` 选择器隐藏。
- 测试对比器：`support/ContractJson`（键排序+数字归一+紧凑序列化）接入 33 个 golden()/27 个 raw() 出口与各 mask() 入口；fixture **无需重录**——语义等价即通过，本仓行为成为唯一契约。邻接键正则的存量断言已就地改 Jackson 树断言（逢触碰必改原则的既成事实清单见 PR4 提交）。
- 满负载测试暴露并修复了三个 seed 期潜伏缺陷（已随 PR2 提交）：ConcurrencyChatClient.drain 永动自旋（+30s 硬上限）、SsrfGuard static 白名单互踩（W5a 家族补快照/还原）、SSRF 契约用例的 fake-ip DNS 环境依赖（改确定性回环）。

**阶段 2 开局已完成（2026-09-29，8e9b7da）**：`KnowledgeService`（3,392 行/153 方法）沿注释边界拆为门面 + 7 服务——KnowledgeMoveService(405)/KnowledgeCloneService(486)/KnowledgeSearchService(198)/KnowledgeFolderService(409)/KnowledgeSpanService(269)/KnowledgeSummaryPipelineService(1,114)/KnowledgeBatchOpsService(158)/KnowledgeTaskIds(76)；门面保留全部公共方法委托（851 行），18+ 注入点与 Mockito 测试零改动。

**当前状态（2026-09-30，接手先读这一段）**：工作分支 **`refactor/knowledge-java-idioms`**（`main` 停在 `32a5354` = 本文档定稿点；工作区干净）。接手时用 `git log --oneline -20` 看实际顶端（本文档自身的提交就在顶端附近）。基线：**后端 4,670 用例全绿 + 前端 `vue-tsc` 0 错误 / 690 用例全绿**（命令见 §9）。

**自本文档定稿以来（70+ 提交）已完成三块**（细节与教训见 §11）：
1. **契约换锚（前端可见面）**：知识库域 → 检索域 → 会话/消息/附件/建议 → chunker-preview，按 §2 第 4 条全部落地（camelCase 且 JSON 名 = Java 字段名、去 `{data,success}` 信封、删除返 204、可空显式 `null`），后端与前端同批改完；
2. **落库格式去 snake（§2 第 11 条）**：知识库 18 个落库类型 + `SearchResult` 去 193 处 `@JsonProperty`，jsonb 内容改用 Java 字段名（无别名、无历史包袱）；
3. **knowledge 域目录整治（§2 第 13/14 条）**：`mapper/repository/service/support/task/client/storage/security` 分层、`dto/` 一类型一文件（59 个类型拆出）、容器类与缩写命名清零。

**下一步候选（按建议优先级）**：
1. **③ 错误文案 + 手写绑定器 DTO 化**（收益明确、风险低）：gin 风格校验文案（`Key: 'X' Error:Field validation for 'X' failed on the 'required' tag`、`json: cannot unmarshal … into Go struct field .tenant_id`）→ Java 惯用写法；连带把 **Go 复刻手写绑定器**（如 `AuthController.bindSwitchTenantRequest`）改成 DTO + `@Valid`。⚠️ 这类绑定器里藏着**真实缺陷**：请求侧早已 camelCase 但它们仍按旧键读 → 前端字段静默丢失（§11 已修一处，建议全仓 grep 同类）。
2. **agent 域五神类拆分**（`AgentEngine` 3,235 行，七段注释边界）——阶段 2 的另一半。
3. **Go 序列化层删除（阶段 3 收尾）**：408 处引用 / 94 文件回归标准 Jackson（`common/web` 的 `GoMapSerializer`/`GoDoubleSerializer`/`GoTimeSerializer`/`GoJsonEscapes`），Controller 手搓 `ObjectNode` 一并收敛——**红线要求一次性全仓完成**，不能按域分批。
4. 其余存量：`@JsonInclude`/NON_NULL 残留清理；wiki/session 神类；`System.getenv()` 151 处收敛 `@ConfigurationProperties`；Go 锚点注释随触碰清洗。
5. **已查清、勿再排查**：前端 `updateKBConfig` → `PUT /api/v1/initialization/config/{kbId}` 是**活端点**（agentm 域 `InitializationController` 自有契约、内层 snake 键，不在知识库契约范围）；其 legacy 装配块（`KnowledgeBaseEditorModal.vue` ~1415-1430）从 KB 响应里读 snake 键 → **一直在静默取默认值**（属 agentm 域改造面）。
6. **在途分支**：`wip/chat-sse-slice2`（`785cdc7`，会话域实体/控制器去 snake，**未并入**，等后续切片）；`wip/dego-storage-format` 与 `wip/knowledge-doc-contract` **已并入工作分支**（前者只剩历史意义）。另有一个 `stash@{0}` 是被取代的旧尝试（可删）。

## 8. 环境与运行

- **后端端口改 8083**（避开旧仓 8082 本地走查环境）。
- **PG 独立库名**（建议 `ragagent`）：基线合并会改 schema，不能与旧仓共用 dev 库；docker-compose 里 ParadeDB/Redis 实例可共用，建新库即可。
- 前端开发代理：`VITE_DEV_PROXY_TARGET=http://localhost:8083`。
- `.env` 已从旧仓原样复制（未入库，gitignore 正常），**待改** `SERVER_PORT` 与库名；`SYSTEM_AES_KEY` 可沿用。
- **`LOCAL_STORAGE_BASE_DIR` 必须放持久目录、严禁 /tmp**（旧环境实测踩坑 2026-09-28：放在 `/tmp/weknora-java-files`，macOS 定期清理 /tmp 导致已入库文档原始文件丢失——文档列表正常、检索可能正常，但 preview 全 500、重处理报 "failed to read file"，原始文件不可恢复只能重传）。建议 `~/ragagent-data/files` 之类仓库外持久路径。
- CI 起步三样：build、test、Spotless；ArchUnit 规则留到阶段 4。

## 9. 测试与安全网

- 401 个测试类 / 1,366 契约 fixture（**4,670 用例**）是重构回归网，**每一步（哪怕纯移动）结束都必须全绿**——近两轮的工作方式就是"改一步 → 全量验证 → 提交"。这是"种子 fork + 渐进转型"优于重写的全部意义。
- **三条验证命令（接手先跑一遍确认基线）**：
  ```bash
  # 后端全量（约 3 分钟；期望 BUILD SUCCESSFUL，4,670 用例 0 失败）
  cd ~/ragagent && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :server:test
  # 前端类型检查（期望 0 错误）
  cd ~/ragagent/frontend && npx vue-tsc --build --force
  # 前端单测（期望 pass 690 / fail 0）
  cd ~/ragagent/frontend && npm test
  ```
- 已知偶发：`WebToolsRecordingTest.searchWithContentFetchesLeadingPagesViaSharedFetchTool` 在全量并发下**偶发失败**（单独重跑通过，与代码改动无关）；遇到它单独重跑确认即可，别误判为回归。
- 裁剪功能的测试/fixture 随 PR 删除；阶段 1 末对比器改 **JSON 语义对比**后，fixture 锚定的是**本仓自己的行为**，与 Go 再无关系（键序/转义差异不算失败）。
- A/B 对拍脚本与 `artifacts/` 产物未带入本仓（留在旧仓）。

## 10. 考古指引（需要时去旧仓查）

- 某行为为什么是这样：旧仓 `docs/HANDOFF.md`（翻译约定正文）、`docs/known-issues/`（坑史，尤其 04 沙箱/技能卷、05 事件契约/工具/引擎卷）。
- 某行代码来历：直接在本仓 `git blame`（seed 前历史完整保留）。
- 架构总览（裁剪前状态）：`docs/site/` 门户、`architecture.html` / `agent-workflow.html` 交互图、`api/` 452 路由清单——阶段 1 后按新形态重生成。

### 7.1 存档：知识库域 Java 本位重构完成（2026-09-29）

**范围**：阶段 2 中 knowledge 域的完整改造（方案见 `docs/superpowers/plans/2026-09-29-knowledge-module-java-refactor.md`，分支 `refactor/knowledge-java-idioms`，17 任务 17 提交）。

**成果**：
- **神类拆分**：FaqService(3,086 行门面) → Guard/ChunkCodec/IndexWriter/ImportTaskStore/EntryCommand/EntryQuery/Import 七类删除门面；ChunkService(1,296) → ChunkEdit/ChunkQuestion + 写守卫并入 ChunkAccessGuard；KnowledgeSummaryPipelineService(1,114) → Summary/File/Parse 三服务；KnowledgeProcessWorker 阶段方法化。KnowledgeController(1,171) 拆为文档主面 + KnowledgeOpsController。
- **DTO 化**：知识库 7 个 controller 的 34 个 rawBody 端点 → `@Valid` DTO（30 个）+ 3 个固定文案兜底端点保留手绑 + 1 个 multipart；`FaqDtos` 分域为 Entry/Import/Search 三文件；请求 record 用 `@JsonNaming(SnakeCaseStrategy)`；响应统一 ApiResponse/MessageResponse/DataMessageResponse；`GlobalExceptionHandler` 增补四类校验异常 → 400 字段级中文 details（`NonNullBody`/`PageParams` 基建在 common/web）。
- **注释本位化**：知识库包 main 源码 Go/GORM/对照 锚点清零；ChunkRepository 类 javadoc 重写为本仓数据契约；goTrimSpace/goTimeString 等 go 前缀标识符改名；6 个 package-info 导览。
- **数据访问**：FAQ 仓储段独立为 FaqChunkRepository；ChunkRepository 死方法（saveChunks/deleteUnindexedChunks）删除。

**契约变化（唯一且已核实）**：参数校验错误的 `details` 从 Go validator/strconv 复刻文案换为字段级中文（message 类别文案统一"请求参数不合法/分页参数不合法"）；约 60 个错误 fixture 重录；200 路径 fixture 零改动（比较器语义化前置）。前端零改动（三绿验证）；`error.code` 字符串、半成功 200、信封形态均保持。

**量化**：knowledge 包 main ~26k 行；controller 层 3,780→2,940 行且全部 <500 行；>800 行仅剩 FaqImportService(1,243 导入状态机)/KnowledgeService(~830 门面聚合面) 两例外（javadoc 注明）。4,664 后端测试全绿 + 前端三绿。

**下一步候选**：agent 域五神类（阶段 2 另一半）、阶段 3 全局换锚（Go 序列化器在 DTO 上的注解仍保留）。

## 11. 执行记录（2026-09-29 ~ 09-30）：契约换锚 + 落库 de-Go + knowledge 目录整治

> 工作方式：**改一步 → 全量 4,670 用例验证 → 提交**；纯移动也走这一套。全部改动均已并入工作分支。

**① 前端可见契约换锚（阶段 3 的前置面，域接域做、同批带前端）**
- 请求侧 camelCase：8 个 DTO 去类级 `@JsonNaming`（请求体线格式 = Java 字段名）；请求布尔去 `is` 前缀；校验文案前缀同步（`standardQuestion: 不能为空`）+ `GlobalExceptionHandler` 的"消息是否自含字段名"判定放宽为 `^[a-z][A-Za-z0-9_]*: `；前端请求载荷同批适配（`9b9ea35` → `ac11090`，契约文档增补 §1.23–1.25）
- 端点收尾：`rebuild-index` 裸返回（`4a38815`）、`SearchResult` 命名契约化 + `hybrid-search` 去信封（`a454ce4`）、chunker/preview 契约化（`e46f250`）
- 会话/SSE 分片：消息检索（`0c29ad6`）、会话/消息/附件/建议去信封（`ae01174`）+ 前端解包（`701616f`）、steer 载荷（`509e73b`）、`/knowledge-search` 裸列表（`e40671b`）

**② 落库格式去 snake（§2 第 11 条，`ecbdf56` → `625780a` → `8cff849`）**
- 18 个知识库落库类型 + `SearchResult` 去 **193 处 `@JsonProperty` + 15 处失效 `@JsonPropertyOrder` + 18 处 `@JsonAlias`**；删 `SearchResultLegacyJsonTest`
- **连带揪出 4 处代码侧旧键读取（真实缺陷）**：`ChunkExtractService.buildTemplate`（customInstructions）、`PluginMerge.parseFaqMetadata`、`HybridSearchService.faqNegativeQuestions`、`AuthController.bindSwitchTenantRequest`（手写 Go 复刻绑定器 → 前端按新契约发 `tenantId` 时字段静默丢失）。**教训：改 JSON 键后必须 grep 全仓"按旧键读取"的代码，不能只改测试与 fixture**
- 边界（**仍是 snake、勿误改**）：租户配置 jsonb（`chat_parser_engine_rules`/`file_types`/`audio_upload_enabled`/`asr_model_id`——`ParserEngineRules.resolve`、`TemporaryDocument*` 按旧键读是**对的**）、auth 域、wiki 域实体、agent 域 fixture（`ag-*`）、chat/工具域手搓载荷与**工具输出自有 schema**（`faq_id`/`knowledge_title` 属性）、检索引擎索引文档（OpenSearch/Doris 的 `is_enabled`）

**③ 注解与列名对齐 Java 惯例（`672472d`）**
- 11 处逐类 `@JsonIgnoreProperties(ignoreUnknown=true)` → `JsonMappers.lenient()` + 全局配置（§2 第 12 条）
- 删死状态 `Knowledge.descriptionSpecified`（全仓无读取点）
- 列名对齐：`user_kb_pins.kb_id` → `knowledge_base_id`（列 + 主键）、`knowledge_bases.cos_config` → `storage_config`（Go 期 COS 专有名）、`UserKbPin.createdAt` → `pinnedAt`（照抄 Go 结构体的错名）；`chunk_revisions.is_enabled` **保留**（列名遵仓库 `is_*` 惯例，Java 侧 `enabled` 才对）
- **schema 两处同源**：`migrations/versioned/V1__baseline.sql` + `server/src/test/java/com/ragagent/TestSchema.java`——**改列名必须同改两处**，否则 H2 报 `Column ... not found`

**④ knowledge 目录整治（`10ac41f` → `8ca2fa0`，六个提交）**：见 §12 地图；操作经验见 §13。

## 12. knowledge 包结构地图（样板，其余域照此靠拢）

```
knowledge/
  controller/ (9)   只放 @RestController：Chunk / ChunkerPreview / Faq / KnowledgeBase /
                    KnowledgeBaseFileProxy / Knowledge / KnowledgeOperations / KnowledgeTag
  service/ (29)     Spring 服务（用例）与 @Component —— 含 FaqImportService(1,234)、
                    KnowledgeService(850 门面)、SpanTracker(700，knowledge/wiki 共用)
  support/ (5)      无状态零依赖的算法与规则：GraphChunkSelector / QuestionBatchPlanner /
                    KnowledgeIndexContent / ParserEngineRules
  task/ (10)        异步任务：队列接口 + InProcess 实现 + KnowledgeProcessWorker +
                    KnowledgeTaskExecutor / KnowledgeTaskIdCodec / 进度 store
  client/ (3)       出站依赖薄封装：DocReaderClient / EmbedderClient
  storage/ (4)      LocalStorageService / TenantFileStorage / TenantStorageService
  security/ (5)     KnowledgeRouteGuards + Knowledge/Chunk AccessGuard + FaqGuard
  repository/ (6)   仓储门面（软删三面孔/乐观锁/方言分支）：ChunkRepository /
                    FaqChunkRepository / KnowledgeTagRepository / KnowledgeSpanRepository
                    + ChunkTxTemplate
  mapper/ (8)       只放 MyBatis-Plus 接口（7 个 *Mapper）
  domain/ (27)      实体（@TableName 跟随表名）+ jsonb 值类型 + WireValued/ParseStatus/
                    EnableStatus/SummaryStatus
  dto/ (75)         一类型一文件（请求/响应/view 分离命名）
  chunker/ (15)     分块子域：Chunker 接口 + Heading/Heuristic/Legacy(Tier3) Splitter +
                    CodePoints/TextNormalizer/ChunkPatterns 等
```
**注**：`datasource/mapper`、`memory/mapper` 里仍各有 `XxxTxTemplate`（历史约定），其批次跟随 `repository/` 分层；`chunker/LegacySplitter` 的 "legacy" 指 **Tier 3 算法分档**（与 `HeadingSplitter`/`HeuristicSplitter` 同族），**不是**待删的遗留代码。

## 13. 操作经验 / 自动化脚本备忘（都是踩过坑换来的）

1. **跨包移动必然暴露 package-private 泄漏**：同包时侥幸能编译的成员（`FaqGuard.FaqFieldPlan` 的字段、`ChunkSearchUtil.trimSpace/toCodePoints`）移动后必须改 public——编译器会把它们全部列出来，逐个改即可。
2. **全局文本替换必须限定文件范围**（本轮**两次**翻车）：一次"会话域键的 keymap 扫全部测试源"（1448 处）、一次"Go 术语清理扫全仓"（把 83 个自有同名方法的类打挂）。正解：**先单文件 → 编译 → 再决定是否扩大**；只有**该类独有的方法名**（如 `toRunes`）才可安全全仓替换。
3. **脚本要幂等**：`git mv` 之后旧路径失效会让循环中断 → 路径集合**每次过滤存在性**；已移动则跳过、仍做引用修复。
4. **容器里的类型不一定同缩进**：`FaqEntryDtos` 里既有 4 空格缩进的成员、也有列 0 的顶层类型——按缩进提取必漏提（且容器已删）。正解：**按花括号深度解析**；漏提后可用"被 import 但文件不存在"反查补齐。
5. **拆容器前先盘点容器级成员**（非类型）：常量与私有 helper（`emptyToNull`、`UNTAGGED_TAG_NAME`）要各自安置；死常量直接删。
6. **测试随被测类同包迁移**（改了包就一起 `git mv` 测试），否则要补 import 且失去同包访问。
7. **每个包一份 `package-info.java`**（职责地图）——本仓惯例，新增包要补，移动类后要同步过时提及。
8. **翻车了就 `git stash` 无损回退重做**，比一边修一边补快得多（本轮用过一次，效果很好）。
9. 精细化的收尾手段：先用编译器列出全部错误 → 写"补 import/改引用"小脚本（含无 import 块文件的处理）→ 编译 → 再全量。
10. **移动/改名类之后必须 grep 全仓 **：Javadoc 链接不会被编译器发现（ 照样编译通过），会静默变成死链。
11. **别把 git 历史与批次代号写进注释**：、、 这类叙述读者无法解码；按 §4 的原则**摘出真实不变量、删掉阶段/批次代号**（本次已清 knowledge 包 8 处；新写注释也别再引入）。
