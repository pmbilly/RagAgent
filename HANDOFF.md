# ragagent 交接文档（新仓起步）

> 本文档写给在 `~/ragagent` 打开的新会话/新成员。新会话没有旧仓会话的记忆，**一切背景以本文为准**。
> 种子：自 `~/ragagent-java` @ `646aba7`（2026-09-28）分叉，git 历史完整保留（blame/log 可直接用）。
> **最近更新 2026-09-30**（本次会话）：§2 补第 11–14 条（落库格式 / 宽松读统一策略 / 包结构 / 命名政策，**均为已定，勿再重新讨论**）；§4 指标复核；§5 进度刷新；§7 当前状态重写（**接手先读这里**）；§9 补验证命令；新增 §11 执行记录 / §12 knowledge 包结构地图 / §13 操作经验备忘。
> **接手第一件事**：`git log --oneline -30` 看 `main` 分支近提交；跑一次 §9 的三条验证命令确认基线全绿。

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
7. **Go 兼容序列化层退役（可读性主线的一环）——线上对齐面已于 2026-09-30（`0ac456e`）执行完毕**：摘除逐字段 Go 注解 **156 处 + 全限定写法 5 处**（42 文件），删除 `JacksonConfig`（4 个 bean：全局时区归一 / HTML 转义 / writer 工厂 / `float[]`）与 `GoTimeDeserializer`、`GoNaiveTimeSerializer`、`GoWriterJsonFactory`、`GoFloatArraySerializer`；**保留工具面**（仍是字节契约的 §11 边界路径）：`GoDoubleSerializer`/`GoTimeSerializer`/`GoMapSerializer`/`GoJsonEscapes`/`GoJson`——chatpipeline 手搓载荷、agent 工具输出、事件总线、Redis 流事件、provider 客户端仍依赖其 Go 字节形态，**别再当遗产删**。原描述：Controller 里大量手搓 `ObjectNode` 一并收敛为 DTO 序列化——DTO 化是 Java 本位可读性的核心工作面。**注意**：这是唯一被红线要求"一次性全仓完成"的动作（半删状态最危险），见 §3 与 §5 阶段 3。
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
- 后端测试：**4,681 用例全绿**（含 6 个 skip）；前端 `vue-tsc` 0 错误 + **690 用例全绿**（§9 有命令）。
- **≥800 行的类（main，全仓）**：AgentEngine 3,235、WikiIngestBatchHandler 2,268、WikiIngestService 2,182、InitializationController 1,981、DataSourceService 1,827、SessionKnowledgeQaService 1,764、MemoryService 1,660、OpenSearchRetrieveRepository 1,652、WikiPageServiceImpl 1,642、KnowledgeQaController 1,616……
- **knowledge 包（已整治，可作样板）**：**197 文件 / 25,264 行**；最大三个 = `FaqImportService` 1,234、`KnowledgeService` 850、`KnowledgeProcessWorker` 814；13 个子包见 §12；容器类/`*Util` 反模式命名已清零。
- **agent 域（2026-09-30 A/B/E 波后）**：agent 145 文件 / 24,438 行 + agentm 30 文件 / 5,614 行；**≥800 行类 0 个**（A 波前 8 个）；**Go 锚点 0**（479 处/186 文件已清扫,§13.11/13.13 判据,真实不变量改中性陈述保留）；12 个子包全有 package-info；`@JsonProperty` 余 30 处已随落库换锚清零（§11.1）。
- **Go 遗留面（阶段 3 的存量，均为本仓 grep 口径）**：Go 兼容序列化器**线上引用 0 处**（2026-09-30 退役完成，`0ac456e`）；**工具面保留 5 个类**（`GoDoubleSerializer`/`GoTimeSerializer`/`GoMapSerializer`/`GoJsonEscapes`/`GoJson`，服务于 §11 边界内仍按 Go 字节的手搓载荷与 provider 请求体）；"对照 Go / GORM"类注释锚点 **6,157 处**（阶段 3 随触碰清洗，先摘不变量信息再删锚点，不搞专项大扫除）；裸 `System.getenv()` **151 处**（收敛进 `@ConfigurationProperties`）。
- **注释卫生（knowledge 包实测，2026-09-30，可作其余域标准）**：Go 锚点注释 **0 处**、注释掉的代码 **0 处**、TODO **1 处**、注释占比 12.1%、13 个包全有 `package-info`；坏 `{@link}` 0 处。Javadoc 覆盖：**public 类型 91%**（201/221，未写的 20 处是纯 CRUD 请求体——有意留白，名字即语义）、public 方法 33%（**分布是对的**：逻辑密集类 90%+，POJO 访问器 7%）。
- **import 卫生（实测 2026-09-30）**：主干 11,557 条 import，Spotless 闸门清掉 **295 处未使用**（其中 276 处在 `knowledge/dto`——**抽类时继承原文件 import 列表**留下的）+ **13 处重复**；剩 64 处未使用在 `seed` 后未触碰过的文件里，改到即被闸门清掉（这是 ratchet 的设计，不是遗漏）。**死 logger（声明却未使用）**：主干 15 处 / 测试 0 处；knowledge 已清零（`88c8054`，-24 行），**agent/agentm 已清零**（2026-09-30，含死 `ObjectMapper` 3 处），余 **5 处**散在 `model/controller`（3）、`auth/service`（1）、`wiki/service`（1），随各自批次清。**死成员**：knowledge 已清零（logger 8 + MAPPER 5 + 死局部变量 4 + 死方法 1 + 死依赖 16 + 遮蔽 import 2）；全仓候选 **66 处**（字段 51 / 私有方法 14 / 遮蔽 import 1，`7bf963e` 口径，**含误报类**，需逐条人工确认；口径**不含未使用局部变量**——那类目前只有 IDE 能发现）；`agentm/ModelConnectivityTestService` 3 处死依赖**已清**（2026-09-30）；**agent/agentm 死成员亦清零**（死成员 7 = 死 `ObjectMapper` 3 / 死 logger 2 / 死私有方法 2，另重复 import 6 条——Spotless 不去重）。**注意一个已知口径缺口**：字段级扫描"构造函数里赋值算引用"，所以**只注入不读取的依赖**要用依赖级口径单独扫（`agentm` 那 3 处就是这么漏到后来的）。
- **`@JsonInclude` 处置完毕（2026-09-30，批次 A/B = `dc62ef7` + `7f1b2a7`）**：knowledge 原 38 处（Go `omitempty` 直译）→ **全域清零**；响应面 3 处按「可空显式 null」改（7 个 cprev fixture 同步），落库/LLM 载荷 35 处删注解（键恒输出；`path(x).asDefault()` 容错，全仓无「依赖键缺席」判断）。**当时暴露的缺口已补**：KB 配置 jsonb（`config` 列）形状原本无任何契约测试（改了 4 个 config 类型的输出却零 fixture 变化）→ 2026-09-30 新增 `knowledge/domain/KnowledgeBaseConfigJsonContractTest`（5 用例：键集合钉死、空值/假值必须显式输出、7 个配置类型 round-trip、读取容错与「旧 snake 键不再映射」防回流，`cd153c3`）。
- **全限定名注解**：knowledge 已清零（22 处 → import + 短名，`4735348`）；全仓余 **177 处**（jackson annotation 138 / databind 15 / spring 9+4+2+1 / mybatis-plus 3 …），随各域批次清理。
- 历史对照（2026-09-28 裁剪前）：main 1,608 文件 / 32.4 万行、test 448 / 14 万 / 1,783 fixture、frontend 533 / 23.6 万；千行大类 41 个（含 KnowledgeService 3,392 行 / 153 方法、FaqService 3,089、KnowledgeController 1,312——**这些数字均已过时**，knowledge 域已完成拆分）。

## 5. 转型路线图

> 各阶段均为 §0 总目标的手段；具体下一刀的取舍与排序由用户在新会话规划——下表是存量工作面的盘点，不是既定排期。

| 阶段 | 内容 | 量级 |
|---|---|---|
| 0 起步 | 建仓/环境隔离/CI 骨架/裁剪清单签字（本文档即阶段 0 产物） | 已完成 |
| 1 五功能移除 | 按 §6.1 清单逐 PR 拆除浏览器连接/沙箱(含技能体系)/CLI/Chrome插件/Claw Skill；schema dump → `V1__baseline.sql`（减裁剪表，196 个增量迁移退役）；测试对比器从字节对比改 **JSON 语义对比**（键序/转义归一化后再比）+ fixture 重录 | **已完成（2026-09-29）**：ba04157（CI+环境）→ f073c88（PR1 三 tab）→ 0f72b0f（PR2 浏览器连接）→ caef9d5（PR3 沙箱+技能降级）→ fba0e7a（PR4 基线+语义比较器）；累计净删 ~8.3 万行，4,685 后端测试全绿 |
| 2 **知识库 + Agent 聚焦重构** | knowledge 四神类 + agent 五神类拆分（沿注释边界）；Controller rawBody → DTO + `@Valid`；GORM 复刻层改写为自有数据访问契约 | **knowledge 域已完成**（2026-09-29/30，见 §11）：神类全拆（最大 1,234 行）、34 个 rawBody 端点 DTO 化、目录整治 13 子包。**剩 agent 域五神类**（AgentEngine 3,235 为首）与其余域的 GORM 复刻层 |
| 3 契约换锚（全仓一次性） | 删 Go 序列化层（408 处引用 / 94 文件）、Problem Details、jsr310、NON_NULL（§2 第 4 条）；每个端点改完同 PR 带前端 | **部分已执行**：knowledge / retrieval / 会话-消息-附件-建议 / chunker-preview 的前端可见契约已换锚（§11），**落库格式也已去 snake（§2 第 11 条）**；**Go 序列化器本体删除已执行（2026-09-30，`0ac456e`）**——按红线一次性全仓完成（线上对齐面清零，工具面按 §11 边界保留） |
| 4 其余域标准化 + 架构调整 | **按 §14 逐包重构范式推进**（knowledge 为范本）：session/wiki/retrieval/memory/llm 等其余神类；getenv 收敛；注释清洗；可选裁剪（im/datasource，见 §6.2——org 已清账）；Gradle 多模块 + ArchUnit 边界规则进 CI | 2–3 人月（未开始） |

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

**当前状态（2026-09-30，接手先读这一段）**：工作分支 = **`main`**（2026-09-30：把 `refactor/knowledge-java-idioms` 的 **140 个提交快进并入** 并删除该分支 —— 此后所有开发**直接在 main 上进行**；合并时状态：后端 4,670 + 前端 690 全绿、工作区干净）。接手时用 `git log --oneline -20` 看实际顶端（本文档自身的提交就在顶端附近）。基线：**后端 4,670 用例全绿 + 前端 `vue-tsc` 0 错误 / 690 用例全绿**（命令见 §9）。

**自本文档定稿以来（70+ 提交）已完成三块**（细节与教训见 §11）：
1. **契约换锚（前端可见面）**：知识库域 → 检索域 → 会话/消息/附件/建议 → chunker-preview，按 §2 第 4 条全部落地（camelCase 且 JSON 名 = Java 字段名、去 `{data,success}` 信封、删除返 204、可空显式 `null`），后端与前端同批改完；
2. **落库格式去 snake（§2 第 11 条）**：知识库 18 个落库类型 + `SearchResult` 去 193 处 `@JsonProperty`，jsonb 内容改用 Java 字段名（无别名、无历史包袱）；
3. **knowledge 域目录整治（§2 第 13/14 条）**：`mapper/repository/service/support/task/client/storage/security` 分层、`dto/` 一类型一文件（59 个类型拆出）、容器类与缩写命名清零。

**下一步候选（按建议优先级）**：

> **推进方式已定稿：§14 逐包重构范式（knowledge 为范本，逐步重构其他包）**——先读 §14.2 的七步 SOP 与 §14.3 的候选域盘点，再选下一个域。

1. **③ 错误文案 + 手写绑定器 DTO 化**（收益明确、风险低）：gin 风格校验文案（`Key: 'X' Error:Field validation for 'X' failed on the 'required' tag`、`json: cannot unmarshal … into Go struct field .tenant_id`）→ Java 惯用写法；连带把 **Go 复刻手写绑定器**（如 `AuthController.bindSwitchTenantRequest`）改成 DTO + `@Valid`。⚠️ 这类绑定器里藏着**真实缺陷**：请求侧早已 camelCase 但它们仍按旧键读 → 前端字段静默丢失（§11 已修一处，建议全仓 grep 同类）。
2. **agent 域五神类拆分**（`AgentEngine` 3,235 行，七段注释边界）——阶段 2 的另一半。
3. **Go 序列化层删除（阶段 3 收尾）**：408 处引用 / 94 文件回归标准 Jackson（`common/web` 的 `GoMapSerializer`/`GoDoubleSerializer`/`GoTimeSerializer`/`GoJsonEscapes`），Controller 手搓 `ObjectNode` 一并收敛——**红线要求一次性全仓完成**，不能按域分批。
4. 其余存量：wiki/session 神类；`System.getenv()` 151 处收敛 `@ConfigurationProperties`；Go 锚点注释随触碰清洗。（`@JsonInclude` 已于批次 A/B 清零，见 §4；KB 配置 jsonb 契约测试已补，`cd153c3`。）
5. **已修复（2026-09-30，`9c01242`）：同名防线两份实现、严格度不一致**（原为待决策）——`ChunkAccessGuard.rejectMovingKnowledge`（public static；null→404、形态异常→500）与 `KnowledgeFolderService.rejectMovingKnowledge`（package-private static；无 null 校验、形态异常**静默放行**）规则相同但严格度不同：同一份异常 metadata，走文件夹路由被放行、走编辑路由 500。**已统一到严格版**：删除 `KnowledgeFolderService` 的宽松副本，3 个调用点改指 `ChunkAccessGuard.rejectMovingKnowledge`（全仓只剩一份实现）；新增 `ChunkAccessGuardMoveGuardTest`（6 用例）钉死行为——含此前被**静默放行**的两类：transfer 非对象、operation/phase 非文本（现在都是 500）。行为变化仅限异常态（正是要修的洞）；全量 4,681 用例绿、**零 fixture 变化**（说明该异常路径此前无测试覆盖，这也是它能悄悄分叉的原因）。
6. **已查清、勿再排查**：前端 `updateKBConfig` → `PUT /api/v1/initialization/config/{kbId}` 是**活端点**（agentm 域 `InitializationController` 自有契约、内层 snake 键，不在知识库契约范围）；其 legacy 装配块（`KnowledgeBaseEditorModal.vue` ~1415-1430）从 KB 响应里读 snake 键 → **一直在静默取默认值**（属 agentm 域改造面）。
7. **在途分支**：`wip/chat-sse-slice2`（`785cdc7`，会话域实体/控制器去 snake，**未并入**，等后续切片）；`wip/dego-storage-format` 与 `wip/knowledge-doc-contract` **已并入工作分支**（前者只剩历史意义）。另有一个 `stash@{0}` 是被取代的旧尝试（可删）。
   另：`refactor/knowledge-java-idioms` 已于 2026-09-30 **合并进 main 并删除**（§5 表格、§7.1 存档、§11/§11.1 等**历史记录**中仍会提到它，那是当时的记录，不影响现在）；`wip/dego-storage-format` 与 `wip/knowledge-doc-contract` 的内容均已并入 main，只剩历史意义、可删；`wip/chat-sse-slice2` **未并入**（保留作工作清单参考）。

## 8. 环境与运行

- **后端端口改 8083**（避开旧仓 8082 本地走查环境）。
- **PG 独立库名**（建议 `ragagent`）：基线合并会改 schema，不能与旧仓共用 dev 库；docker-compose 里 ParadeDB/Redis 实例可共用，建新库即可。
- 前端开发代理：`VITE_DEV_PROXY_TARGET=http://localhost:8083`。
- `.env` 已从旧仓原样复制（未入库，gitignore 正常），**待改** `SERVER_PORT` 与库名；`SYSTEM_AES_KEY` 可沿用。
- **`LOCAL_STORAGE_BASE_DIR` 必须放持久目录、严禁 /tmp**（旧环境实测踩坑 2026-09-28：放在 `/tmp/weknora-java-files`，macOS 定期清理 /tmp 导致已入库文档原始文件丢失——文档列表正常、检索可能正常，但 preview 全 500、重处理报 "failed to read file"，原始文件不可恢复只能重传）。建议 `~/ragagent-data/files` 之类仓库外持久路径。
- CI 起步三样：build、test、Spotless；ArchUnit 规则留到阶段 4。
- **远程仓库（2026-09-30 起）**：`origin` = `https://github.com/pmbilly/ragagent.git`（**公开**）；首次推送只推了 `main`（`bbf7443`），**wip 分支与 tag 都留在本地**。此后本地提交若要同步，记得 `git push`（并行会话在同一仓库提交、同样落在 main，也需推送）。

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
- 已知偶发（第二个）：`EvaluationContractTest.getTerminalRunsExecution` 在**全量并发**下偶发失败
  （期望 `model ID cannot be empty`、实际空串；单独 `--tests "*EvaluationContractTest"` 重跑通过）。
  与代码改动无关，遇到时先单独重跑确认（2026-09-30 首次观察到）。
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


## 11.1 执行记录（2026-09-30 会话）：agent 域 A/B 波 + approval 换锚（方案 `docs/superpowers/plans/2026-09-30-agent-module-java-refactor.md`）

> 工作方式同 §11：改一步 → 全量 4,670+ 用例验证 → 提交。分支 `refactor/knowledge-java-idioms`，提交 cb835e4..6c0be66。

**A 波（神类拆分,七个 ≥800 类清零——拆后 agent+agentm 全域无 ≥800 行类）**：
- `AgentEngine` 3,235 → 门面 752 + 八个同包协作者（Think/Act/Observe/PromptAssembly/Finalize/SteerIntake/ContextDebugEmitter/ReActIteration,持 engine 回引,构造期装配）;门面保留 Execute/主循环/token 预算与 4 个包内 seam 委托桥（EngineRecordingTest 直引面,测试零改动）。
- `SqlGuard` 1,590 → 442 + SqlTokenizer/SqlSelectDeepChecker/SqlInjectionAnalyzer（全静态,公共面不动）。
- `KnowledgeSearchTool` 1,178 → 585 + KnowledgeSearchRanking/KnowledgeSearchOutputFormatter。
- `WikiSupport` 1,148 容器 → 19 个顶层类型（§2 第 13 条容器反模式清零;215 处消费方引用符号级改写,18 文件）。
- `McpCatalog` 923 → 744 + McpCatalogPagination;`GrepChunksTool` 897 → 675 + GrepChunksScoring。
- agentm:`InitializationController` 1,981 → 229 端点薄层 + 四服务（InitializationConfigService 754/OllamaManageService 363/ModelConnectivityTestService 659/TextExtractionTestService 245）+ InitializationRequests 绑定器族;`CustomAgentService` 882 → 425 + AgentSuggestedQuestions 516。

**B 波**：agent/agentm 12 个子包 package-info 职责地图;根包文件归位顺延（裁定:扰动/收益比不划算）。

**C 波（部分）**：approval 六个 Redis 内部报文去 56 处 `@JsonProperty` + 失效 `@JsonPropertyOrder`,JSON 键=Java 字段名,双侧同批;`ApprovalWireFormatTest` 五个 Go-tag 钉子重录为 camelCase wire 钉子。**event/ 的 SSE 同键类型保持 snake**（归 session 域切片,前端同批）。

**Task 11 已完成（39e3cb6）**：五落库类型去 30 处注解（键=Java 字段名,`is_complete`→`complete` 随字段名）;信封键 `agent_steps` 属 session 域 Message 未动;前端 `useChatStreamHandler`/`steerStreamFork` 内层读取同批适配(合成 SSE 事件键不动);`AgentStepsJsonTest` 重录 + `GoRecording46B` 逐常量上下文敏感重录(EVENTS 仅 agent_steps 跨度内,STATE 全量,SSE 信封键与事件类型值保持)并改为本仓行为契约。

**agent 域余下工作（Task 12 一项,原子任务,接手面已详注）**：
1. agentm 契约换锚（27 端点/17 fixture/前端三 api 模块;解包点、config jsonb 三方同批、fixture 清单、KnowledgeBaseEditorModal legacy 缺陷——详注见上文第 2 条,2026-09-30 已补）。
2. agentm 契约换锚 ✅ 已完成（9cb74b4,2026-09-30）。**新登记边界**:agent config jsonb 的内层键(agent_mode/system_prompt/kb_selection_mode/chat_parser_engine_rules 族)保持 snake——它是跨 agentm/engine(AgentConfig)/embed(EmbedChannelService)/session(QaAgentConfig) 五包共享的自洽 schema,前端设置表单同款写入;翻转=五包+表单三方同批,漏一个读者=静默默认值,独立切片任务另做(仿 chat SSE 先例)。占位符名(agent_system_prompt 等)=模板令牌({{...}}),同理保持。
3. **Task 13 已完成（f59e35b）**：Go 锚点 479→0、批次代号清零;终审顺延项(服务段横幅/控制器批次叙述)一并落地。经验:清扫注释时 javadoc 里的 `\u00XX` 会被 javac 当 unicode 转义处理(非法十六进制=编译错)——写"U+00XX"形态。
4. **范围外发现（终审抓出,动 session 域前必读）**：Gate 发出的 `agent.approval.*Data` 经 `ApprovalBridge.toEventBus` 上的真实 EventBus,而 `AgentStreamBridge` 四个 handler instanceof 的是 `com.ragagent.event.*Data`——类型永不匹配,审批/OAuth 事件的 SSE 流转链路疑似断裂（重构前即如此,本域改造未改变它）。session 域切片动 `AgentStreamBridge` 时必须先核实前端实际经哪条链路收到审批事件。

## 11.5 分支与残留清理（2026-09-30）

清理后**只保留 `main`**（`seed` tag 是 Spotless ratchet 基准，**永久保留**）。删除记录（sha 可经 `git reflog` / `git fsck --lost-found` 找回）：

| 旧 ref | sha | 判定依据 |
|---|---|---|
| `refactor/retire-go-json-layer` | `0ac456f` | 已并入 main（本批 Go 序列化层退役） |
| `wip/dego-storage-format` | `8cff849` | 已并入 main（0 独有提交） |
| `wip/knowledge-doc-contract` | `78e6705` | 已并入 main（0 独有提交） |
| `wip/chat-sse-slice2` | `785cdc7` | **被取代**：其做法是"落库兼容别名"，而 §2.11 定稿"不加兼容别名、曾加过的 `@JsonAlias` 已删"；会话域契约已由后续批次换锚（§2.4/§11） |
| `stash@{0}`（原属已删的 `refactor/knowledge-java-idioms`） | `775b42c` | 消息自述"over-broad rune rename"，该改名已按 §2.14 正规完成（`Runes`→`CodePoints`） |

**纪律**：分支合入后即删（不留"事后考古"分支）；`seed` 与 `origin/main` 除外。
## 11.6 agent/agentm 域 §14.5 复验与卫生清零（2026-09-30）

**复验结论：结构面已达标**——202 文件（agent 171 / agentm 31）、**≥800 行类 0**（最大 `ActPhase` 761）、
**Go 锚点 0**、**批次代号/计划号 0**、10 个子包 `package-info` 全覆盖、容器类反模式命名 0、
逐字段 `@JsonProperty` 仅 `agent/AgentConfig.java` 14 处（= §11.2 登记的 config jsonb 内层键边界）。
**本轮清零**：死成员 7（死 `ObjectMapper` 3、死 logger 2、死私有方法 2）+ 死依赖 3 + 重复 import 6 → 6 文件 / −31 行。

**108 处 snake 键读取的逐类判定（结论：全部是登记边界，勿改）**：

| 族 | 例 | 为何必须留 snake |
|---|---|---|
| agent config jsonb 内层键 | `question_suggestions`/`starters`/`follow_ups`/`agent_mode` | 跨 agentm/engine/embed/session 五包共享的自洽 schema，前端表单同款写入（§11.2 已登记） |
| **工具参数 schema** | `thought`/`next_thought_needed`/`knowledge_id`/`argument_resolution` | **模型侧按 schema 发参**——改名等于工具调不动（含各 `agent/tools/*`） |
| prompt 模板 YAML 键 | `templates`/`i18n`/`has_knowledge_base` | 数据资产（`resources` 下的 YAML）自有键 |
| span/实时载荷键 | `display_type`/`mentioned_items`/`channel` | 工具 span 与流式载荷的自有 schema（§11 边界族） |

**体检口径的两个误报（勿据此动手）**：①"注释掉的代码"启发式在 agent 域报 11 处，**实为 0**（全是说明性注释，
如 `// if / then / else` 段首、键格式说明）；②`{@link X}` 存在性检查报 42 处，绝大多数是 JDK/Jackson 类型与同文件嵌套类型。


## 11.7 顶层包归并与结构决策（2026-09-30）

**用户拍板三条**：① `apikey` **并入** `auth`（✅ 已完成）；② `agentm` **分拆**（智能体管理 / 初始化+模型能力，待执行）；
③ `datasource` / `im` **保留**（不再考虑删除，其结构可按 §14 正常重构）。

**① 已执行（`apikey` → `auth/apikey`）**：main 27 文件 + test 9 文件 `git mv` 搬迁，68 文件引用重写
（含 4 处 mapper 字符串里的 FQN `typeHandler=com.ragagent.apikey.domain.APIKeyRawJsonbTypeHandler`）；
`auth/package-info` 补子域说明、子包 `package-info` 改子域措辞。
**效果**：环 **34 → 32**（`apikey ⇄ auth`、`apikey ⇄ knowledge` 双消），L2→L3 直连 19 → 18；
全量 4,655 用例绿 + spotlessCheck 通过。基线已刷新（`scripts/package-cycles.baseline.json`）。

**② 已执行（`agentm` → `agentm` + `initialization`）**：main 12 文件 `git mv`（1 controller + 1 dto + 8 service + `AgentmWiring`→`InitializationWiring`）+ 2 个 init 专属资源随迁（`asr_test.wav`、`extract_config.yaml`，含路径字符串同步）；新建 `initialization` 四份 package-info，改写 `agentm` 四份只描述剩余职责。拆后：**agentm 19 文件 / 2607 行、initialization 16 文件 / 2945 行**。
**环影响**：`agentm ⇄ knowledge`/`agentm ⇄ model` 改名为 `initialization ⇄ knowledge`/`initialization ⇄ model`（净数仍 32）；`ExtractPrompts` 因引用 `chatpipeline` 未按预案下沉 `common/prompt`，留 init 半区（后续解环批次再处理）。
**③ `datasource`/`im` 保留**：进入正常重构队列（P1/P2 结构项照做）。

## 11.8 P0 批 1 执行记录：配置/工具类归位（2026-09-30）

**结果：环 32 → 24（消除 8 组）**，顶层包 34 → 31，依赖 `config` 的包 5 → 1，L2→L3 直连 18 → 14。

| 组 | 动作 | 消除的环 |
|---|---|---|
| 1 | `TenantRole`（auth/domain，30 文件在用）、`TenantProperties`（config）→ **`common/tenant/`** | `common⇄config`、`auth⇄config`、`common⇄auth`、`audit⇄auth` |
| 2 | `ConversationProperties`（config）→ **`common/settings/`** | `config⇄knowledge` |
| 3 | `searchutil`→`retrieval/support`、`storageurl`→`storage/support`、`webfetch`→`agent/support` | `retrieval⇄searchutil`、`storage⇄storageurl`、`session⇄storageurl` |

**两个必然的连带修复（下次搬迁直接照做）**：① 类型搬出 `config` 后，`@ConfigurationPropertiesScan` 要补新包
（`TenantProperties`、`ConversationProperties` 各踩一次，症状是 **Spring 上下文加载失败**："No qualifying bean of type …"，
且 Spring 会"失败阈值"跳过后续上下文，**看起来只有 4 个用例失败、实则整批失效**）；
② **同包内原先免 import** 的引用点（`config/WebConfig`、`auth/domain/TenantMember` 等 5 处）必须补 import。
非 Java 引用与 FQN 字符串本批为 0（§13.23 清单逐个查过）。

**原则**：`config` 是组合根，**只出不进**——凡被业务域读取的配置类都下沉到 `common/*`；扫描范围用**枚举**而非根包通配
（根包扫描会把 `session` 等处"未注册"的配置类一并绑定，属行为变化）。

## 11.9 P0 批 2 进度：端口化（2026-09-30，进行中）

**已完成：环 24 → 23**（消 `agent ⇄ knowledge`）——`AgentPromptPlaceholders`（agent 的纯静态占位符渲染器，
自足、零仓内依赖）下沉 `common/prompt/`；`chatpipeline`(3 文件) 与 `knowledge`(2 文件) 的引用随之改向。
**踩点**：搬家脚本只重写了 FQN，漏了**同包内免 import** 的 `agent/AgentPrompts.java`（§13.23 的检查项，已补）。

**余下（按性价比排序，各自独立可交付）**：

1. **`session ⇄ storage`**（背边 2 文件、另一侧 11）——**前半已完成（2026-09-30）**：
   ① ✅ `FileAccessResolver` 的 `MessageFileLookup` 端口载荷已收窄为 storage 侧记录
   `FileAccessResolver.MessageFileFacts`（`content` / `artifactUrls` / `knowledgeReferences` / `images` /
   `toolResults` / `agentTenantId` / `role`，与 `MessageReferencesFile`/`AuthorizeMessageFile` 实际读取的字段一一对应），
   会话侧在 `MessageFileProxyController.factsOf(...)` 做映射（含 `agentSteps → toolResults` 提取）。
   ⚠️ 端口设计判断：**别改成"整条消息序列化"**——那会让匹配范围变宽，等于越权。
   ② ✅ **后半已完成（2026-09-30，`session ⇄ storage` 消除 → 环 22）**：`storage/support/Rewriter` 的消息段
   （`rewriteMessagesResponse`/`cloneMessages`/`rewriteMessages`/`rewriteAgentSteps` + `CLONE_MAPPER`，103 行）
   整段搬到 **`session/support/MessageReferenceRewriter`**（内部持有 `Rewriter`，转发 `enabled/rewrite/rewriteRef/copyReferences`，
   四个内核方法本就是 public，无需放宽可见性）；用例随之搬到 `session/support/MessageReferenceRewriterTest`（4 例，逐一断言）。
   **纠一处此前的判断**：这段代码**不是"无调用者"**——真正调用者是 `MessageController` 消息列表端点
   （`rewriter.rewriteMessagesResponse(messages)`；此前 grep 用 `\brewriteMessagesResponse` 只扫了限定名，漏了接收者写法），
   已改为 `new MessageReferenceRewriter(rewriter).rewriteMessagesResponse(messages)`。
   **检查清单再补一条（§13.28）**：grep 调用点不能只按"方法名"扫，要按"`.` + 方法名"或全仓字符串扫，
   否则会漏掉带接收者的调用。
2. **`auth → system`**（背边 4 文件）——✅ **已完成（2026-09-30，环 22 → 21）**：
   `SystemSettingRegistry`（自足）下沉 **`common/settings/`**；新增**只读端口 `common/settings/SystemSettingGateway`**
   （只列 auth 实际用到的 `getString`/`getBool`/`getInt` 三个读方法），由 `SystemSettingService implements` 承载；
   auth 三处注入（`AuthController`/`TenantCatalogController`/`UserService`）改注入端口 → **auth 侧不再 import system**，
   方向变 `system → auth` 单向，环消。
   **端口模式定式（本批确立，后续 ③ 照此办）**：端口接口放 `common/<领域>`（最底层、零依赖），
   由**提供方的域**实现（`implements`），消费方注入接口；**写侧/列表等能力不出端口**，保持最小面。
   **踩点**：跨包后 `SystemSettingRegistry.goTypeName(...)` 原为 package-private → 编译报错，按需放宽为 public（§13.1 的老坑）。
3. ✅ **`auth → memory` 已完成（2026-09-30，环 21 → 20）**：[`auth → memory` 已消] `MemoryConfig`（**tenants 表的 jsonb 载荷**，
   auth 读写自己表的列时不该反向依赖 memory 域）+ 其同包依赖 `MemoryKinds`/`MemoryKeys` 一并下沉 `common/settings/`。
   **判定要点**：这属于"**共享 jsonb 载荷 / 表 schema 类型 → 下沉 common**"，与 `TenantProperties`/`ConversationProperties` 同一原则；
   注意搬之前先看它引用了哪些同包兄弟（否则 `common` 会反向依赖源域，编译器会拦）。
   ✅ **`audit → knowledge` 已完成（2026-09-30，环 20 → 19）**：新增只读端口 `common/knowledge/KnowledgeBaseGateway`
   （+ 载荷 `KnowledgeBaseFacts`，只带 `tenantId`/`creatorId`——两处消费者实际只读这两个；刻意不做租户过滤，
   调用方要区分"查不到"与"属于别的空间"），由 `KnowledgeBaseService implements`（**专用最小查询**，不触发
   `ensureDefaults` 的字段回填，与原裸 mapper 查询等价）；消费方 `AuditLogController` 与
   `auth/apikey/TenantAPIKeyController` 改注入端口 → 两者 import 里再无 `knowledge`；测试桩同步改桩端口。
   ✅ **`auth → storage` 已完成（2026-09-30，环 19 → 18）**：两处性质不同、两手法——
   ① `StorageAllowList` 是**纯规则**（读 `STORAGE_ALLOW_LIST`，无数据访问）→ 搬到 `common/storage/`（先例 `MemoryConfig`）；
   ② `StorageBackendRepository` 是**写操作**，且 auth 手里还握着 ~150 行"env → 存储后端实体/config JSON（含 Go 键序）"映射
   → 新增**命令端口** `common/storage/StorageBackendProvisioner`（`provisionForTenant`/`deleteForTenant`），
   实现 `storage/service/DefaultStorageBackendProvisioner` **逐字搬入**原块（已用 diff 验字节保真），
   auth 只留自己的事务编排（建 → 回写 `default_storage_backend_id` → 失败补偿删行）。
   副产物：`TenantService` 连 `knowledge.domain.StorageBackend` 的 import 一并消失（`auth → knowledge` 只剩 1 文件）。

   ✅ **`auth → knowledge` 已完成（2026-09-30，环 18 → 17）——批 2（P0 端口化）全部收口**：真实形态不是"租户开通建默认 KB"，
   而是聊天历史配置端点的**自动建隐藏 KB**（`__chat_history__`）。新增**命令端口** `common/knowledge/KnowledgeBaseProvisioner`
   （`provisionChatHistoryKnowledgeBase(embeddingModelId)`：只传模型 id、只回 id），实体语义（名字/类型/临时标记/描述）收回知识域；
   错误信封包装（"Failed to create chat history knowledge base" + details）留在 auth 原样。`auth` 对 `knowledge` 的 import 归零。
   ⚠️ 知识模块有注释门禁（`KnowledgeCodeConventionsTest` 禁 `golden|波 N|对照 Go` 字面词）——在新写的 javadoc 里写"对照 Go L1696"会被拦，
   出处置写"对照 L1696-1716"（不带 Go）即可。

   批 2 全景：**环 32 → 17**（批 1 消 8；批 2 消 7），端口三型 = 只读端口（`KnowledgeBaseGateway`）/ 命令端口
   （`StorageBackendProvisioner`、`KnowledgeBaseProvisioner`）/ 共享类型搬家（`MemoryConfig`、`StorageAllowList`）。

   ✅ **④-a `agent ⇄ mcp` 已完成（2026-09-30，环 17 → 16）**：两侧不对称——`agent → mcp` 7 文件（工具集成，保留），
   `mcp → agent` 只有 3 文件且**全部只用 `agent.approval.*`**（审批机制）。故按"共享能力搬家"轴：
   ① 先搬 `ResponseType`（18 文件共享的事件契约枚举）`llm.domain` → `common/llm`（否则 common 反向依赖 llm）；
   ② 再搬 `agent/approval`（27 文件 1,861 行，含 654 行 Gate）→ `common/approval`，其中 MCP 专用的
   `Adapter`/`McpToolPolicySource` 下沉 `mcp/service`（它们原先替审批包保管 mcp 的行类型）；测试包 9 文件同步搬。
   搬完 `common/approval` 对 agent/mcp/llm **零依赖**。`ToolPolicy.enabledToolsIndividually`（原包私有）放宽为 public（跨包调用）。
   验证：全量 4,550 绿 + spotless + 守卫（16/1/14）。

   ✅ **④-b 能力层配置类去实体化 已完成（2026-09-30，环 16 → 13）**：轴 =「配置类不得持有业务实体 `Model`」。
   5 处 `fromModel(Model)` 静态工厂（`embedding/EmbedderConfig`、`rerank/RerankerConfig`、`llm/provider/Config`、
   `llm/domain/ChatConfig`、`retrieval/vlm/VlmClient`——**后两个藏在全限定写法里**）的映射体逐字搬入
   `model/service/ModelRuntimeConfigs`（新家在最上层域；调用方本就依赖 model），配置类只留纯值字段、不再 import `Model`。
   跨类后私有字段直写改 setter（35 处；各处 setter 的 null 归一与原赋值等价，wire 测试逐字段断言通过）。
   效果：消 `embedding ⇄ model`、`llm ⇄ model`、`model ⇄ rerank`；**L2→L3 直连 14 → 11**；
   `model ⇄ retrieval` 只剩 `retrieval/HybridSearchService`（`Model` + `ModelService` 真业务用法，另案）。

   ✅ **④-c model 域边界收口 已完成（2026-09-30，环 13 → 11）**：两处各按既有轴——
   ① **只读端口**：`common/model/ModelGateway` + 载荷 `ModelFacts(modelId,name,baseUrl,apiKey)`，由 `ModelService` 实现
   （`findFacts`，空值归一 ""，顺带消除 `EmbedderClient.configFrom` 在 parameters 为 null 时的潜在 NPE）；
   `retrieval/HybridSearchService` 改注入端口（两处用法：查询嵌入的 HTTP 配置 + "同一嵌入模型"身份键；
   `EmbedderClient` 新增 `configFrom(ModelFacts)` 载荷重载，原 `Model` 重载保留并委托——它还有 5 个 knowledge/memory/wiki 调用方）→ 消 `model ⇄ retrieval`。
   ② **纯规则搬 common**：`MAX_FILE_SIZE_MB` 限额规则从 `knowledge.storage.LocalStorageService` 的静态方法提为
   `common/storage/UploadLimits`（原方法改委托，11 个使用者零感知）；model 调试端点改用它 → 消 `knowledge ⇄ model`
   （该环的反向只有这 1 行）。

   ✅ **④-d `knowledge ⇄ storage` 已完成（2026-09-30，环 11 → 10）**：① **实体归位**——`storage_backends` 表的实体
   `StorageBackend` 一直存在 `knowledge.domain`（storage 5 文件 + system 2 + knowledge 2 在用；auth 仅 javadoc 提及，
   **不会重建** `auth ⇄ storage`）→ 搬到 `storage/domain`，10 处引用改写；② **清死依赖**——`storage/fileserve/FileAccessResolver`
   注入了知识域的 `KnowledgeService`/`KnowledgeBaseService` 却从未调用（死注入），形参 `KnowledgeBase kb` 只用 `getTenantId()`
   → 改为 `Long kbTenantId`（调用点 1 处）→ `storage → knowledge` 归零，环断。

   📋 **另五环（本轮侦察结论，按性价比）**：`chatpipeline ⇄ knowledge/retrieval/session` 三环被**同一批契约类型**卡住
   （`ChatManage` 394 行 **40 文件**、`PipelinePorts` 212 行 **30 文件**、`SearchParams` 11、`ChunkTypes` 10）——
   **一次搬迁（建议 common/chatpipeline）可同时解三环**，是本批唯一的"规模效应"机会，但需先核对 `ChatManage` 自身依赖；
   `knowledge ⇄ retrieval` 反向 5 文件（用到 `Chunk` 实体 + `ChunkRepository`/`EmbedderClient`/`KnowledgeBaseService`，较重）；
   `knowledge ⇄ wiki` 反向 6 文件、切 `knowledge → wiki` 需 wiki 服务端口（4 类型）。

   ✅ **④-e `chatpipeline ⇄ retrieval` 已完成（2026-09-30，环 10 → 9）——契约类型搬迁第一批**：
   侦察先证伪了"一次搬迁解三环"：`ChatManage`(394 行)/`PipelinePorts`(212 行) **都不可搬**（前者拖 agent/llm/retrieval/session，
   后者拖 llm/memory/retrieval）；但外部域**只碰它们的嵌套类型**（值记录 / 端口接口），从不调用其方法。据此分开处理：
   - `ChatManage.GraphData/GraphNode/GraphRelation/NameSpace` → `common/graph`（顶层记录，三域共用；`GraphNode` 需去 `static`）；
   - `SearchParams`/`ChunkTypes` → `common/pipeline`（零域依赖）；`RetrievalObs` → `retrieval/obs`（依赖 `retrieval.domain.SearchResult`）；
   - `PipelinePorts.RetrieveGraphRepository`（由 retrieval 实现）→ `retrieval/graph`（端口下沉到实现方）；
   - `MessageAttachmentsPrompt`（依赖 `session.domain.MessageAttachment`）→ `session`；
   - `EventManager` **搬不得**（同包隐式用 `ChatManage`/`Plugin`/`PluginError`）→ 原样留在 chatpipeline。
   坑：整包替换 import 会顶掉同文件的 `PipelinePorts` import（应逐类型改）；FQN 收窄会造出 `chatpipeline.RetrieveGraphRepository`
   这种不存在的名字；javadoc 的 `{@link}` 会把跨域依赖带进 common（**新环 `chatpipeline ⇄ common` 就是这么冒出来的**，守卫当场拦下，
   改 `{@code}` 内联全名后消失）；跨包调用需放宽 `RetrievalObs.goFmt4`/`MessageAttachmentsPrompt.escapeHtml` 等包私有成员。
   余下 `chatpipeline ⇄ knowledge/session`：仍卡 `PipelinePorts`（各域实现的端口接口，逐接口下沉才解）。

   ✅ **④-f-① `chatpipeline ⇄ knowledge` 已完成（2026-09-30，环 9 → 8）**：knowledge → chatpipeline 只剩 **1 文件 2 类型**
   （`ChunkExtractService` 的 `EntityExtraction` + `PipelineConfig`）。处理：`PipelineConfig`(68 行，只依赖 common.graph) 与
   `EntityExtraction`(526 行，依赖 llm) **一起落到 `llm/extract`**（不能进 common——会造成 common → llm）；搬运中发现
   `EntityExtraction` 还隐式用 `GoJsonMarshal`/`GoValueStr`（chatpipeline 里的自足 Go 兼容助手）→ 一并搬到 `common/web`
   （与 `JsonMappers` 同族），否则 `llm → chatpipeline` 会造出**新环**。测试侧 4 文件的隐式使用补 import。
   剩余 `chatpipeline ⇄ session`：`session/QaWiring` 装配了 14+ 个 chatpipeline 插件（功能性依赖），另案。

   ✅ **④-g `knowledge ⇄ wiki` 已完成（2026-09-30，环 8 → 7）——用修正后的方法重做成功**：
   ① **标识符级传递闭包扫描**（不再只看 import）：从 `WikiImageMarkup`/`WikiLanguageSupport` 出发的闭包有 56 个类型（含
   `WikiPageServiceImpl` 等），**整簇不可搬**；但真小簇是 5 个自足类型：`WikiImageMarkup` → **wiki 自己的** `GoStrings`，
   `WikiLanguageSupport` → `SlugUpdate` → `ExtractedItem`（后三者 0 依赖）。
   ② **同名类陷阱**（上轮失败根因）：`wiki/service/GoStrings` 与 `datasource/connector/gitlab/GoStrings` 是**两个不同的类**——
   本次只搬 **wiki 那份** 到 `common/wiki`（gitlab 那份原样不动），并放宽为 public。
   ③ **端口**：`common/wiki/WikiIngestPort`（含 `EnqueueResult` 载荷，由 `WikiIngestService` 实现）+
   `common/wiki/WikiFinalizePort`（单方法 void `finalizeWikiSubtask`，由 `DefaultWikiKnowledgeFinalizer` 实现——它的
   `finalizeSubtask` 返回 Outcome，调用方不消费返回值，故端口只暴露 void 形态）；knowledge 三个调用点改注入端口。
   ④ **import 修复改为编译错误驱动**（写了个循环修复器：只给报错的文件补对应类型的 import，main 2 轮 + test 3 轮）——
   **不再按标识符做粗暴遍**（上轮教训）。测试侧 `ChunkExtractServiceTest` 的桩同步换端口；wiki 自己的
   `WikiKnowledgeFinalizerTest` 保持不动（误伤已回退）。

   ✅ **④-h `llm ⇄ retrieval` 已完成（2026-09-30，环 7 → 6）**：精确侦察定案——`llm → retrieval` 只有一处
   （`StreamResponse.knowledgeReferences` 的 `List<SearchResult>`，即 SSE 契约字段 `knowledge_references`），
   而 `SearchResult` 本体零域依赖（只 java/Jackson/`common.web` 两个序列化器）且被 43 文件引用 →
   按"共享契约类型搬 common"处置：`retrieval.domain.SearchResult` → `common/retrieval/`（先例 `ResponseType`）。
   搬迁用本批修正后的流程：import/FQN 改写 57 文件 → 编译错误驱动修复器（main/test 各 1 轮即通过，零误伤）。
   反向 `retrieval → llm`（`VlmClient` FQN + `VlmHttpTransport` 用 `LlmTransport`）仍在，但**单向不成环**，故不处理。

   ✅ **④-i `memory ⇄ session` 已完成（2026-09-30，环 6 → 5）**：取**便宜侧**（`memory → session` 仅 3 文件）。侦察发现
   `MemoryMessageReader`（70 行）是 `MessageRepository` 上两个方法的**纯转发**（还带着死注入的 `MessageMapper`），
   而 memory 蒸馏只读 4 个字段（id/role/content/createdAt）→ 新增只读端口 `common/session/SessionMessagePort`
   （2 方法 + `SessionMessageView` 视图，由 `session/mapper/MessageRepository` 实现并复用既有查询）；`MemoryExtractionService`
   改注入端口（视图是 record，getter 改访问器）、删除冗余 reader；`MemoryUsedMemories`（构造 session 的 `UsedMemory`）
   唯一调用方是 chatpipeline → 归位 `chatpipeline`。测试桩同步换端口。
   **反向 `session → memory`（5 文件、7 类型）未动**——单向不成环。

   ✅ **④-k `knowledge ⇄ retrieval` 已完成（2026-09-30，环 5 → 4）——"能搬的搬 + 搬不动的端口化"两步**：
   **第一步（`b320749`）背边归位**：① `knowledge/service/VectorStoreService` → `retrieval/engine/`——它是
   postgres 检索引擎的 embeddings 索引写面，与读面 `PgVectorRetrieveRepository` 同属引擎存储层
   （知识写链 6 个使用点改经 `retrieval.engine`，L3→L2 方向合法）；② `retrieval/support/ImageInfoEnricher`
   + `SearchChunkMerge` → `knowledge/support/`（chunk 图片富化 / 内容重叠拼接，语义属知识域；消费方
   chatpipeline/wiki/session/knowledge 改向——`chatpipeline → knowledge` 包边本就存在，L2→L3 净数不变）；
   新入知识域的两个类顺过 `KnowledgeCodeConventionsTest`（全限定名清零、黑话注释人话化、import 归整）。
   **第二步端口组**：`common/knowledge` 三个只读端口 + `common/embedding` 一个能力端口，全部由知识域实现：
   - `KnowledgeBaseSearchGateway`（+`KnowledgeBaseSearchFacts`：租户/类型/嵌入模型/绑店/两条索引开关；
     实现走 `getAllTenantById`，保留 `ensureDefaults` 的"索引策略零值 → vector+keyword 默认"回填）；
   - `KnowledgeDocumentGateway`（+`KnowledgeDocumentFacts`：结果装配用的文档元数据）；
   - `ChunkSearchGateway`（+`ChunkFacts` 15 字段：内容/坐标/类型/索引态/邻接/关系/两条 json 列）；
   - `EmbeddingGateway`（按 `ModelFacts` 嵌入；实现 `EmbedderClient` 把受检异常收敛为同名消息的运行时异常）。
   实现方分别是 `KnowledgeBaseService`/`KnowledgeService`/`ChunkRepository`/`EmbedderClient`（各加一个
   "实体 → 载荷"映射方法）；`HybridSearchService` 注入四个端口、**不再 import 知识域**。
   `resolveEmbeddingModelKeys` 对外签名由"KB 实体列表"改为"**KB id 列表**"（跨域端口不传实体）——
   `chatpipeline/PipelinePorts`、`PluginSearch`、session 的 `QaWiring`/`AgentToolBackends` 与测试桩同步。
   **收尾数据**：环 5 → **4**、L2→L3 10 → **9**；全量 4,675 用例 + `spotlessCheck` 绿；基线刷新 **4/1/9**。

   ✅ **④-l `initialization ⇄ knowledge/model` 已完成（2026-09-30，环 4 → 2）——"共享能力归位 L2"两笔小搬**：
   这两个环的背边各只有一个类，成因都是 Go 目录遗留（agentm 的 service 包当成"公共库"用）：
   - `ExtractPrompts`（config.yaml 的 `extract` 段三份提示词模板装载，产物是
     `llm.extract.PipelineConfig.PromptTemplateStructured`）`initialization/service` → **`llm/extract/`**
     与 `PipelineConfig` 成对——消 `initialization ⇄ knowledge`（知识域 `ChunkExtractService`
     是它的真实消费者）；vendor 资源 `initialization/extract_config.yaml` 位置不动（路径字符串即契约）。
   - `AsrTranscriber`（OpenAI 兼容 transcription 接缝，复用 `llm.chat.LlmTransport` 出站）
     `initialization/service` → **`llm/asr/`**——消 `initialization ⇄ model`，且顺带消掉
     `retrieval → initialization` 这条 L2→L3 违例（`vlm/VlmHttpTransport` 复用了它的
     OpenAI 错误文案静态映射）；消费方 initialization/model/session 三处改向。
   **收尾数据**：环 4 → **2**（余 `agent ⇄ modelcontext`、`chatpipeline ⇄ session`）、
   L2→L3 9 → **8**；全量 4,675 用例 + `spotlessCheck` 绿；基线刷新 **2/1/8**。

   ✅ **④-m `agent ⇄ modelcontext` 已完成（2026-09-30，环 2 → 1）——共享契约类型归位 common**：
   背边（`modelcontext → agent`）只有 4 个文件、2 个类型，都是"modelcontext 需要 agent 侧定义的共享契约"：
   - `agent/domain/ToolResult`（一次工具执行的结果）→ **`common/llm/`**，与 ④-a 的 `ResponseType`
     同层（跨域协议载荷）：agent 43 文件 / chatpipeline 4 / modelcontext 2 / session 2 只改 import，
     JSON 契约（字段序 / `@JsonInclude` / `@JsonIgnore`）不动；
   - `agent/tools/GoJsonCodec`（紧凑 JSON 编码器：键序 + 浮点形态 + HTML 转义恒开）→ **`common/web/`**，
     与 `GoJsonMarshal`/`GoJsonEscapes`/`GoDoubleSerializer` 合流；顺带把 `writeString` 由包私有改**公开**
     （`agent/tools/IssueView` 自行拼 JSON 时要逐字节复用它，跨包后原可见性不可达），原同包引用的 5 个类补 import。
   **收尾数据**：环 2 → **1**（余 `chatpipeline ⇄ session`）、L2→L3 8 → **7**；基线刷新 **1/1/7**。
   另修一处**既有偶发**（单独提交）：`WebToolsRecordingTest` 的两个抓取记录器用普通 `ArrayList` 接
   虚拟线程并发回调（`WebSearchTool`/`WebFetchTool` 各起 3 条虚拟线程），全量下偶发丢记录 →
   改 `CopyOnWriteArrayList`。

   ✅ **④-n `chatpipeline ⇄ session` 已完成（2026-09-30，环 1 → 0）——消息载荷视图，全仓零环**：
   要消的背边是 `chatpipeline → session`（7 文件），耦合面全是"借会话实体当载荷"（不是主链逻辑耦合）：
   - `common/session/` 新增 4 个载荷记录：`PipelineMessageView`（requestId/role/content/createdAt/
     images/attachments/knowledgeReferences——历史装载真正读的 7 个字段）、`PipelineMessageImageView`
     （url + caption，带 `withCaption` 供图片描述回写）、`PipelineMessageAttachmentView`（9 字段 = 提示词
     渲染面）、`PipelineUsedMemoryView`（id/kind/content；紧凑构造器把空值归一为空串，保持 SSE
     `memory_recalled` 事件载荷逐字节不变）；
   - `MessageAttachmentsPrompt`（附件 → LLM 提示词段）`session` → **`common/prompt`**，入参换成载荷视图
     （chatpipeline 与 session 两侧共用）；
   - 会话侧新增 `session/support/PipelineViews`（实体 ↔ 载荷映射，出域与回写都收敛到这一处）：
     `QaWiring` 的 `MessageService` 端口实现、`SessionKnowledgeQaService`/`SessionAgentQaService`
     （附件与"用到的记忆"）、`AgentStreamBridge`（事件载荷 → 实体落库）改走映射；
   - chatpipeline 侧 7 个文件只换类型与访问器（`PipelineCommon` 历史装载、`PluginQueryUnderstand`
     的图片描述回写改成"重建列表 + `withCaption`"、`ChatManage` 两个字段、`MemoryUsedMemories`/
     `PluginMemoryRecall`），端口签名换载荷。
   **收尾数据**：环 1 → **0**（全仓零包间环）、L2→L3 7 → **6**；全量 4,675 用例 + `spotlessCheck` 绿；
   基线刷新 **0/1/6**。余下 6 条 L2→L3（`chatpipeline → agent/knowledge/memory/websearch`、
   `retrieval → auth/vectorstore`）方向本身合法（能力层正常使用业务域），属阶段 4 物理模块化范畴。

4. **`agent ⇄ mcp`**（背边 13 文件）与 **`embedding`/`rerank`/`llm ⇄ model`**（`Model` 实体越界，应传配置值）
   体量较大，建议排在这批之后。

## 11.10 P3 小修执行记录：放错包 / 倒挂清零（2026-09-30）

包地图 §P3 四项一次收口（体检 `python3 scripts/pkg-audit.py` 的 ④⑤ 已无输出）：

1. **放错包 2 → 0**：`audit/controller/AuditLogListResponse` → `audit/dto/`（响应信封 record）；
   `wiki/controller/WikiActivityAudit` → `wiki/domain/`（这是 wiki→audit 的端口接缝，
   `audit/service/WikiActivityAuditRecorder` 实现它——放 controller 包才导致"service → controller"倒挂 2 处，
   顺手一起消掉）。
2. **控制器直连仓储 4 → 0**：`MemoryController` / `SessionController` / `MessageSuggestionController` /
   `StorageBackendController`。做法不是给控制器开新面，而是**把仓储的嵌套类型提成领域类型 + 服务加读面方法**：
   `MemoryRepository.Page<T>` → `memory/domain/MemoryPage`、`SessionRepository.PagedItems` →
   `session/domain/SessionPage`、`SessionRepository.SuggestionSetNotFoundException` →
   `session/domain/MessageSuggestionSetNotFoundException`；`StorageBackendService` 补
   `listBackends`/`tenantDefaultBackendId`/`getBackend` 三个读面方法，控制器撤掉仓储字段。
3. **dto → service 倒挂 1 → 0**：`CustomAgentService.Result` → `agentm/dto/CustomAgentResult`
   （响应装配 DTO 不再命名服务的嵌套类型）。
4. **顶层 package-info 30/31 → 31/31**：补 `session/package-info.java`
   （原计划留给 session 域批次，该批次已交付，顾虑不再成立）。

**遗留（观察项，非缺陷）**：`controller → domain` 40 处——控制器为响应装配直接读实体字段
（体检自标注"多为响应装配"）；要收只能给每个读面配视图类型，收益/成本比低，暂留。
**收尾数据**：环 0/1/6 不变；全量 4,675 用例 + `spotlessCheck` 绿。

## 11.11 P1 分包执行记录：chatpipeline / event 两个扁平包（2026-09-30）

包地图 §P1（扁平包无子包、靠文件名找东西）前两个（也是最大的两个）收口，
全部是**纯移动 + 引用改写**，行为零变化（Go 实录回放测试全绿兜底）：

| 包 | 前 | 后 | 拆法 |
|---|---|---|---|
| `chatpipeline` | 38 文件扁平 | 根 13 + `plugin/`(19) + `support/`(6) | `Plugin*` 与 `Plugin` 接口进 `plugin/`；纯逻辑进 `support/`；骨架与跨域 seam 留根 |
| `event` | 40 文件扁平 | 根 13 + `payload/`(26) | 26 个 `*Data` 载荷进 `payload/`；总线机制与事件信封留根 |

**两个坑（下次搬迁直接照做）**：
1. **跨子包可见性**：搬家后原"同包可见"的成员会不可达（④-e 先例是放宽为 public）。
   本次受影响 26 处——`SearchSupport` 的 4 个辅助、`PipelineLog.runeLen`、
   `PipelinePorts.DataAnalysisSession`，以及 plugin 里被**录制测试直接探针**的静态/实例辅助
   （PluginMerge/PluginSearch/PluginRerank/PluginMemoryAffinity/PluginFilterTopK/PluginQueryUnderstand）。
   测试仍在根包（静态导入 `Rec46cSupport` 需要），故只能放宽生产成员；
   自动化做法：循环"编译 → 解析 `is not public in` → 放宽声明"，7 轮收敛（配方见 §13 第 29 条）。
2. **同包引用变跨包**：搬家文件需要补 import；用"候选类型名 + 词边界命中 + 同名 import 则跳过"的
   脚本批量补（本次补 172 行）。`package-info.java` 里 javadoc 表格中的类型名会被误补 import，
   Spotless 的 removeUnusedImports 会清掉——可接受。

**顺带的结构收口**：`PluginIntoChatMessage.getEnrichedPassageForChat` 移入
`support/ReferencesSupport`（support 不再反向调 plugin）；`chatpipeline/plugin/` 与
`chatpipeline/support/` 补 package-info。
**收尾数据**：环 0/1/6 不变；全量 4,675 用例 + `spotlessCheck` 绿。

## 11.12 P2 分包执行记录：wiki/service（2026-09-30）

包地图 §P2 第一个真正要拆的超大单层（另两个是误报/待做，见包地图口径提醒）。

- `wiki/service` 76 文件 → **根 11 + `ingest/`(45) + `page/`(19)**：
  - `ingest/`：摄取管线（`WikiIngest*` 19 门面与四阶段、`WikiBatch*`、`WikiFinalize*`、任务队列与锁
    （`InProcessWiki*`/`RedisWiki*`/`SingleFlight`）、幂等凭据（IdentityClaim/Tombstone）、去重与清理支撑）；
  - `page/`：页面服务（接口 + 实现）、文件夹/视图支撑、链接与 lint（Linkify/CrossLinker/DeadLinks）、
    slug 锁与匹配、编辑上下文；
  - 根：跨切面端口与缺省实现（ChunkCleaner / ImageEnricher / KnowledgeFinalizer / ModelResolver）、
    prompt 与 LLM 重试策略。
- **测试同步迁移**（§13 第 27 条）：`src/test/.../wiki/service/` 下 17 个测试按目标类分入
  `ingest/`(10) 与 `page/`(7)，包声明一并改；否则会因短名解析与包私有探针而编译不过。
- 跨子包可见性：仅 1 处（`WikiLinkify` 调 `WikiIngestPageOps`）→ 该顶层类罕见地没写 `public`，补上。
- **收尾数据**：环 0/1/6 不变；全量 4,675 用例 + `spotlessCheck` 绿。

## 11.13 P2 分包执行记录：knowledge/dto（2026-09-30）

74 个 DTO → **`faq/`(21) + `chunk/`(12) + `kb/`(15) + `doc/`(16) + `tag/`(7) + 根 3**
（根留 `BatchDeleteRequest` / `HybridSearchRequest` / `TaskIdResponse` 三个跨面载荷），
6 个 package-info 一并补齐。

- 切分轴 = **功能**（FAQ / chunk / 知识库本体与其配置视图 / 文档 Knowledge / 标签）；
  命名沿用域内词汇：`kb/` 是 KnowledgeBase（知识库）、`doc/` 是 Knowledge（文档）。
- **本批是搬迁里最省的**：DTO 之间零跨包引用 → 脚本 0 处补 import、0 处可见性放宽、
  92 文件全部是 import 行同增同减（179/179）；编译一次通过。
- 收尾数据：环 0/1/6 不变；全量 4,675 用例 + `spotlessCheck` 绿。

## 11.14 P1 收尾：embedding / rerank 拆 provider（2026-09-30）

包地图 §P1 最后两个有天然族的扁平包：

| 包 | 前 | 后 |
|---|---|---|
| `embedding` | 22 扁平 | 根 9（Embedder/Factory/Http/GoJson/池化）+ `provider/`(11)（10 家实现 + 公共骨架 `BaseEmbedder`） |
| `rerank` | 15 扁平 | 根 6 + `provider/`(8) |

- **公共骨架随 provider 走**：`BaseEmbedder` 只被 provider 用（包外零引用），搬进 `provider/` 后
  子类的包私有访问天然成立——**比放宽可见性更干净**（首轮误把它留在根包，代价是构造器/字段/
  若干方法全要放宽，已回退）。工厂 `EmbedderFactory` 在根包，仍需 `BaseEmbedder.setCustomHeaders`/
  `applyDimensionOverride`/`setSupportsDimensionOverride` 与 rerank 各家的 `setCustomHeaders` 放宽为 public。
- 测试探针同前批：`EmbeddingWireTest`/`RerankWireTest` 留在根包，放宽 4 个成员
  （`VolcengineReranker.MAX_DOCUMENTS`、`LkeapReranker.Batch`/`lkeapRerankBatches`）。
- **教训（写进 §13 的搬迁配方）**：批量放下标正则前先确认签名形态；本轮一次误改把
  `static void applyDimensionOverride(Embedder, boolean)` 改成了 `public (Embedder e, ...)` 编译即报错，
  靠 `git diff` 逐行复核修回——**正则放宽后务必看 diff**。
- 收尾数据：环 0/1/6 不变；全量 4,675 用例 + `spotlessCheck` 绿。
- **P1 收尾状态**：原 10 个扁平包 → ① 早先批次已合并掉 3（`storageurl`/`searchutil`/`webfetch`）；
  ② 本次拆开 4（`chatpipeline`/`event` §11.11 + `embedding`/`rerank` 本节）；
  ③ 余 3（`stream`/`modelcontext`/`config`）判定单一职责、**保持扁平**；`agent/tools` 待 agent 域重构时按能力分组。

## 11.15 session 步骤 2 第二刀：TemporaryDocumentService 的 prompt 切片（2026-09-30）

§14.9b 的侦察落成（当时因工装失手中止，本次按它的结论"逐成员人工搬运"，不用行区间脚本）：

| | 前 | 后 |
|---|---|---|
| `TemporaryDocumentService` | 1,075 | 门面 **860** + `TemporaryDocumentPromptResolver` **271** |

- **搬走**（协作者只持 `repo` 一个引用）：`resolveForPrompt` + 选块/词元/图片一族
  `selectContent`/`queryTerms`/`parseChunks`/`imageUrlsOf`/`isVisualDocumentQuery`/
  `countOccurrences`/`isHan`/`intOf`/`strOf` + 两个内部 record（`ContentSelection`/`DocumentChunk`）
  + 5 个提示词常量（`PROMPT_BUDGET_TOKENS`/`PROMPT_INLINE_TOKENS`/`MAX_PROMPT_PARTS`/`MAX_IMAGE_URLS`/
  `VISUAL_QUERY_MARKERS`）。
- **与 §14.9b 表的差异（按调用点实测修正）**：`isImageFormat`/`readJsonArray` 是**双用**
  （存储/删除路径也在调）→ 留门面（`readJsonArray` 由 private 放宽为包内可见），协作者按类名调用；
  `isIconImage`/`extFromMime`/`MIN_IMAGE_DIMENSION`/`MIN_IMAGE_BYTES`/`IMAGE_EXTENSIONS` **切片内零调用**
  → 也留门面（侦察表把它们当"专用助手"是闭包算宽了）。**切片边界要用调用点核，别用名字猜。**
- 对外 API 面不动：`PromptResult`/`AttachmentResolveException`/`MAX_ATTACHMENTS_PER_MESSAGE` 留门面
  （测试与 `KnowledgeQaController` 直引），门面 `resolveForPrompt` 改薄委托，类注释记一句"已拆至 …"。
- **忠实性核验（本次收口手法，值得复用）**：`git show HEAD:<门面>` 抽旧方法体 + 新文件抽同名方法，
  归一化空白与 `TemporaryDocumentService.` 限定前缀后逐字比对——10 个方法 **9 个逐字一致**、
  1 个仅换行差异（`throw new AttachmentResolveException(...)` 的折行）。比"看 diff"更硬。
- 测试：`TemporaryDocumentResolveForPromptTest` 只改 10 处限定名（4 个纯函数按类名点 resolver）；
  环 0/1/6 不变；全量 4,675 用例 + `spotlessCheck` 绿。

## 11.16 session 步骤 2 第三刀：AgentStreamBridge 补测 + 抽发射器（2026-09-30）

| | 前 | 后 |
|---|---|---|
| `AgentStreamBridge` | 856 | **697**（折行后；跌出 ≥800 神类榜） |
| 该类测试 | **零直接覆盖** | `AgentStreamBridgeTest` 9 例 |

- **先补网再动刀**：该类只在 `KnowledgeQaController` 的 agent 流里构造，全仓 grep 无任何测试引用它
  → 先补 `AgentStreamBridgeTest`（真 `EventBus` + mock `StreamManager`，同 `SessionStreamControllerTest`）：
  thought（done 补 duration 元数据）/ tool_call（前导剔除）+ final_answer 分片 / tool_result（成功
  TOOL_RESULT、失败 ERROR）/ references（累积并落 assistantMessage）/ memory_recalled（视图→实体落库、
  原样进 SSE）/ complete（无 answer 事件时补 fallback 对 + usage 透传）/ 订阅表与实际订阅一致 /
  非载荷对象被忽略。
- **抽 `AgentStreamEmitter`（同包，94 行）**：17 个 handler 各抄一份"组装 + try 追加 + catch 日志"样板
  → `emit`/`emitTolerant`（组装 + 追加，级别分 error/warn）与 `append`/`appendTolerant`/`appendAll`
  （只追加：给带 `setUsage` 或一次追加两事件的站点）。**日志文案逐字由调用方传入**，零文案变更；
  `emitArtifactsPending` 保留原样（它的 warn 带 session/message 字段，且"流可能已结束"时要保这些信息）。
- **机械替换的核验手法**：逐行扫描（不用花括号配对、不用回溯正则）解析
  "`StreamEvent se = new StreamEvent();` → 连续 `se.setX(...)` → try 追加 → catch 日志"的规整块，
  先打印候选与断言形态、**再落盘**；少设 `setContent`/`setData` 的 4 处按 `StreamEvent` 字段默认值
  （`""`/null）补齐（等价改写）。第一版正则一次都没匹配上（`{ind}` 占位与 `\{` 转义互相干扰）——
  **规整块改写优先逐行扫描，别跟正则较劲**。
- 收尾：环 0/1/6 不变；全量绿 + `spotlessCheck` 绿。
  **用例数口径统一（本批起）**：以刚跑完的全量 XML 汇总为准——`server/build/test-results/test/*.xml` 的 `tests` 求和 = **4,559**（6 skipped）；此前各节沿用的 "4,675" 是另一口径（静态/汇总不等价）的旧值，**引用时别混**。

## 11.17 session 步骤 2 第四刀：MessageService 拆出聊天历史检索簇（2026-09-30）

| | 前 | 后 |
|---|---|---|
| `MessageService` | 1,028 | **510**（跌出 ≥800 神类榜） |
| `MessageSearch`（新，同包） | — | **596** |

- **切片边界**：`// ── 搜索 ──` 分节到 `// ── 统计 ──` 之前（505 行）——`searchMessages` 两个重载 +
  `getChatHistoryConfig`/`getRetrievalConfig` + KB 向量检索 + rerank 四阈值 + 关键词结果转换 +
  RRF 融合与累加 + 会话归属过滤 + 补对 + 按 request_id 分组；连同 `SearchItem` 记录与 `RRF_K` 常数。
  门面留 `searchMessages` 两个薄委托（`MessageController` / `AgentToolBackends` 调用面零改动）。
- **共用项的处置**：`requireTenantId` 与 `MODE_*` 词表**留门面**（前者 CRUD 路径也在用、后者被
  `AgentToolBackends` 直引），新类按类名引用；反过来 `getChatHistoryConfig` 被门面的入 KB 路径
  （`indexMessageToKb`）共用 → 移入新类并放为**包内可见**（门面调 `messageSearch.getChatHistoryConfig()`）。
  **教训：切片边界要看"调用点双向"——只按单向闭包取，会把被门面共用的成员一起搬走，编译期才炸。**
- 忠实性核验（§11.15 手法）：17 个成员里 **15 个逐字一致**；`searchMessages`（多载）与 `RRF_K`（字段）
  因抽取器按"单声明"正则各报一次计数异常，人工确认后放行。
- 测试：既有 `MessageServiceVectorSearchTest`（8 例：向量路径 / KB 未配置跳过 / 分模式降级与上抛 /
  rerank 阈值）与 `MessageServiceChatHistoryIndexTest`（5 例：入 KB）正是这条簇的验收，抽取后**零改动全绿**。
- 收尾：环 0/1/6 不变；全量绿 + `spotlessCheck` 绿（用例数口径见 §11.16）。

## 11.18 session 步骤 2 第五刀：MessageSuggestionService 拆出无状态管道（2026-09-30）

| | 前 | 后 |
|---|---|---|
| `MessageSuggestionService` | 1,088 | **601**（跌出 ≥800 神类榜） |
| `MessageSuggestionPipeline`（新，同包） | — | **513** |

- **切片边界**：`buildSuggestionGenerationContext` 起、`resolveLanguage` 止的**全静态管道**（输入侧
  上下文装配 / 轮次分组 / 历史渲染 / 证据抽取；输出侧解析 / 合并 / 相关性排序 / 文本规范化），
  连同 `ConversationTurn` 与 8 个常量（三个正则块、四个预算、`JSON_MAPPER`）。唯一读实例字段的
  `buildGenerationContext`（10 行，取最近消息）留在门面并由它调用新类——新类因此**零依赖、
  无构造器、无字段**。
- **跨簇共享类型的处置（沿用 §14.9 先例）**：`GenerationContext` 与 `Evidence` 两个 record 被门面
  （ensure/generate）与新类共读 → **留在门面**，新类 import 其嵌套类型；`MODE_HYBRID` 与 `strVal`
  门面也在用 → 留门面（前者放宽为包内常量、后者放宽为包内静态），新类按类名引用；反向的
  `modeVal`/`intVal`/`strList`/`emptySuggestionSection` 被门面调用 → 放宽为包内可见。
- **忠实性核验**：27 个成员 + 8 个常量块**逐字一致**（本批是纯搬迁、零改写）。
- 测试：`MessageSuggestionGenerateTest`（10 例，钉的正是这条管道）11 处限定名改指新类，
  record 引用保持不变，全绿。
- 收尾：环 0/1/6 不变；全量 + `spotlessCheck` 绿。

## 11.19 session 域卫生批：清 IDE 诊断的 29 条遗留（2026-09-30）

口径：IDE（CodeBuddy）Problems 面板按 `session` 主源 + 测试整个域扫（我上一轮只扫了 `service` 目录，
少算了 7 条），逐条核过后按 §13.15 的判据处置。**26 项改动 / 12 文件 / 净 −39 行**：

| 类别 | 处置 |
|---|---|
| 静态方法被实例访问 ×9（`SessionQaResolution` 的 `service.stringListOf` 等） | 改 `SessionKnowledgeQaService.` 静态访问（纯风格，零行为） |
| 只注入不读取 ×5（`SessionAgentQaService` 的 `customAgentService`/`builtinAgentRegistry`/`kbService`、`SteerSinkBridge.assistantMessage`、`KnowledgeQaController.artifactCollectorWiring`） | 字段 + 构造参数 + 赋值 + 调用点一起删（§13.15④⑤：逐个整行删，无需逗号手术；`ArtifactCollectorWiring` 是 `@Component`，删注入不影响容器实例化） |
| 死方法/常量/局部 ×4（`SessionAgentQaService.kbScopeIds`、`MessageSuggestionService.MODE_CURATED`、`SessionQaFallback.JSON`、`SessionAgentQaService` 的空 lambda 局部 `drain`） | 删（`MODE_CURATED` 的公开词表在 `agentm.AgentConfigJson.SUGGESTION_CURATED`，此处是重复） |
| 未用 import ×3 + 测试死字段 ×3（`resolver`/`mapper`/`owner`+`TENANT`） | 删 |
| unchecked 转换 ×1（`TemporaryDocumentService.readJsonArray` 的 `List.class`） | 加 `@SuppressWarnings("unchecked")`（零行为；换类型化 `TypeReference` 属另一轴） |
| **故意保留 ×2**：`AgentStreamBridge.tenantId` + `emitArtifactsPending(int)` | 有 Go 对照（`agent_stream_handler.go L841-858` 的 artifacts_pending 通知）但 Java 侧无调用点——**属"翻译了、未接线"，删掉就丢了接线证据**；旁边多余的 `@SuppressWarnings("unchecked")` 已删 |

- 收尾：`clean :test :spotlessCheck` 全绿（**432 类 / 4,664 例 / 0 失败**）、环 0/1/6 不变。
- 提醒：IDE 的 Problems 面板可能因「Gradle 导入失败」（§13.22 的默认 JDK 26 问题）整体失效或滞后——
  **判据以编译器与闸门为准**，面板只当线索。

## 11.20 session 步骤 2 第六刀：TemporaryDocumentService 第二刀（解析/落盘管线）（2026-09-30）

| | 前 | 后 |
|---|---|---|
| `TemporaryDocumentService` | 862 | **498**（两刀累计 1,075 → 498） |
| `TemporaryDocumentProcessor`（新，同包） | — | **423** |

- **切片边界**：投递与重试（`enqueueProcess`/`processWithRetry`/`processNow`）+ `process` + `optionsOf`
  + 解析器选择 `tenantParserEngine` + 音频转写 `transcribeAudio` + 资源租户切换 `withResourceTenant`
  + 文本/图片落盘（`storeDocumentImages`/`StoredImages`/`chunksJson`/`quote`/`quoteMap`/
  `cleanInvalidUtf8`/`isIconImage`/`extFromMime`）+ 7 个常量与 `executor` 字段。
- **共享项（§11.17 口径）**：`MAPPER`/`readJsonArray`/`extOf`/`isImageFormat` 门面也在用
  （删除/附件提示词/白名单判定）→ 留门面并放宽包内可见，管线按类名引用；`CreateOptions` 是门面公开
  record → 新类 import 嵌套类型；`processNow` 门面留薄委托（流程契约测试的驱动口，测试零改动）。
  门面不再使用的四个字段（`docReader`/`modelRuntimeFactory`/`asrTranscriber`/`tenantService`）
  删字段、留构造参数直传协作者。
- **忠实性核验（§11.15 手法）**：16 个成员 + 7 个常量**逐字一致**。
- **工具教训（并入 §14.9b 坑清单）**：花括号配对数遇上 **char 字面量** `'}'` 会提前收口
  （`chunksJson` 里就有）——剥离规则必须同时去 `"..."`/`'...'`/`//...`/`/*...*/`，否则块被截断
  （首次跑即踩，靠 diff 复核发现后回退重做）。
- 测试：流程契约 5 例（`service.processNow` 驱动）+ 提示词/附件 22 例（图片落盘探针改为对协作者构造）
  + 上传契约 7 例全绿；`clean :test :spotlessCheck` 全绿（432 类 / 4,664 例 / 0 失败）；环 0/1/6 不变。

## 11.21 session 步骤 2 第七刀：AgentToolBackends 拆出知识库检索簇（2026-09-30）

| | 前 | 后 |
|---|---|---|
| `AgentToolBackends` | 1,264 | **836**（余 wiki 簇待下一刀） |
| `AgentToolKbBackends`（新，同包） | — | **538** |

- **切片边界**：KB 检索簇 21 个成员（`searchConfig`/`knowledgeSearchBackend`/`chunkInfoBackend`/
  `imageEnricher`/`rerankerModel`/`grepChunkSearch` + 私有助手 `grepScopeClause`/`backfillTotalChunkCounts`/
  `regexDialect`+`RegexDialect`/`placeholders`/`metadataMap` + `knowledgeInfoReader`/`chunkById`/`pagedChunks`/
  `pagedChunkCount`/`imageInfoCollector`/`graphSearch`/`extractConfigView`/`toKbView`/`toSearchResultView`）
  + `TEXT_FAQ_TYPES` 常量。
- **共享项（§11.17 口径）**：`readJson`/`JSON`/`nz`（wiki 簇与门面也在用）与 `conversation`/`jdbc`
  （门面 datasource 簇共用）→ 静态项留门面按类名引用（`nz` 放宽包内可见，19 处限定），实例项经构造参数传入；
  **注意 `JdbcTemplate` 必须在门面 `this.jdbc = new JdbcTemplate(dataSource)` 之后构造协作者**（否则传 null）。
- **薄委托 11 个**：装配点 `createTool` 与契约测试直调面（含 `DbTest` 直用的 4 个）→ 三个测试与
  `SessionAgentQaService` 零改动。
- **忠实性核验**：21 个成员**逐字一致**；测试 `AgentToolBackendsDbTest`(6)/`KbToolTest`(5)/`WikiTest`(12) 全绿。
- **收尾闸门首次走新分档（§14.4 常规批）**：`--rerun-tasks` 重编 + session 域测试 + `spotlessCheck`
  ≈ **58 秒**（旧口径全量约 2m50s）。

## 11.22 session 步骤 2 第八刀：AgentToolBackends 拆出 wiki 簇（2026-09-30）

| | 前 | 后 |
|---|---|---|
| `AgentToolBackends` | （本刀前 836；两刀前 1,264） | **590**（**出 ≥800 榜**） |
| `AgentToolWikiBackends`（新，同包） | — | **282** |

- **切片边界**：`wikiPages()`（WikiPages 端口的匿名实现，120 行）+ 5 个视图转换器
  （`toPageView`/`toEntity`/`toIssueView`/`toIndexOverviewView`/`goTimeText`）。门面保留
  `createWikiTool` 装配点（改用 `wikiBackends.wikiPages()`）与 `wikiPages()` 薄委托（契约测试直调面）。
- **共享项**：`wikiPageService` 门面装配也在用 → 经构造参数传入；`readJson` 留门面按类名引用（1 处限定）。
- **忠实性核验**：6 个成员逐字一致；三个 `AgentToolBackends*` 测试 23 例全绿。
- **新分档再次抓到真断链（并入 §13 经验）**：`--rerun-tasks` 重编报出
  `AgentToolBackendsWikiTest:191` 引用了随簇搬走的 `AgentToolBackends.goTimeText(null)`——此前我按
  `实例.成员(` 扫测试调用面，**漏了静态成员的「类名.成员(」引用** → 改指 `AgentToolWikiBackends.goTimeText`。
  判据补充：**搬走静态成员后，除实例调用面还要 grep `旧类名.成员(`**。
- 收尾：常规档闸门（重编 + session 域测试 + `spotlessCheck`）**57 秒**全绿。

## 11.23 session 步骤 2 第九刀：SessionAgentQaService 拆出历史装配簇（2026-09-30，§14.9c 刀 1）

| | 前 | 后 |
|---|---|---|
| `SessionAgentQaService` | 1,430 | **1,195**（余刀 2 配置装配 ~280、刀 3 引擎/工具装配 ~500） |
| `AgentHistoryAssembler`（新，同包） | — | **268** |

- 切片 12 项：`loadAgentHistory` + `Turn` + `buildTurnBodyMessages` + `steeredUserMessage` +
  `buildUserHistoryMessage`/`buildAssistantHistoryMessages`/`buildAgentStepMessages` + `toJsonString` +
  `finalAnswerHistoryMessage` + `filterNonTerminalToolCalls` + `isPipelineToolCallId` + `steerMessageContent`。
- **边界按调用点修正（再次印证 §11.17）**：`stringListOf`（调用在 `buildAgentConfig` 316/337/367/374）
  与 `YAML_JSON`/`templateContentByIdAndFile`（419/429）看着像"历史工具"、调用点却在配置簇 → 归**刀 2**，不随本刀走。
- 共享项：`AGENT_HISTORY_FETCH_MIN/MULTIPLIER` 留门面（放宽包内可见），簇内按类名引用；协作者只持 `messageService`。
- 忠实性核验：12 个成员逐字一致；常规档闸门（重编 + session 域 + `spotlessCheck`）**≈1 分 9 秒**
  （Spotless 首跑报未用 import，`spotlessApply` 收掉后复检绿）。

## 11.24 session 步骤 2 第十刀：SessionAgentQaService 拆出配置装配簇（2026-09-30，§14.9c 刀 2）

| | 前 | 后 |
|---|---|---|
| `SessionAgentQaService` | 1,195 | **889**（余刀 3 引擎/工具装配 ~500） |
| `AgentConfigAssembler`（新，同包） | — | **324** |

- 切片 14 项：`buildAgentConfig`/`Prompts`/`resolveAgentPrompts`/`applyPerRequest*Scope`/`McpScope`/
  `resolvePerRequestMcpScope`/`intersect·pin·dedupPreservingOrder`/`agentRequiresRerankModel` + 尾部三件
  `stringListOf`/`YAML_JSON`/`templateContentByIdAndFile`（调用点全在配置簇 → 随刀走，§11.17 口径）。
- 共享项随构造注入两个：`knowledgeQa` + `hostSkillDirs`。**坑**：构造参数 `hostSkillDirs` 是 `String`、
  字段才是 `List<String>` → 实参必须写 `this.hostSkillDirs`；注入放构造器末尾（依赖已赋完）。
- `buildAgentConfig`/`agentRequiresRerankModel` 放宽为包内可见（跨类调用点）。
- **import 手法（本轮三连坑的收口）**：新类**整块复制门面的 import 表**，再让 `spotlessApply` 删未用的——
  比手工按简单名挑 import 稳（手工猜 FQN/静态导入连错三轮）；同包嵌套类型（`SearchTargetView`）用限定名、
  外部嵌套类（`agent.tools.SearchTarget.SearchTargets`）用显式 import，**别用 `import static`**（spotless
  会当未用删掉，编译期才暴露）。
- 忠实性核验：14 项逐字一致；常规档闸门绿。
- 注：核验脚本的成员匹配要允许字段以 `=` 结尾（`YAML_JSON` 这类常量），否则漏匹配报 0。
## 11.25 session 步骤 2 第十一刀：SessionAgentQaService 拆出引擎/工具装配簇（2026-09-30，§14.9c 刀 3）

| | 前 | 后 |
|---|---|---|
| `SessionAgentQaService` | 889 | **364**（**出 ≥800 榜**） |
| `AgentEngineAssembler`（新，同包） | — | **585**（13 个构造注入依赖） |

- 切片 11 块：`createAgentEngine` + `registerMcpTools` + `KbScopes` + `knowledgeBaseScopesForPrompt` +
  `getKnowledgeBaseInfos` + `kbRetrievalCapabilities` + `getSelectedDocumentInfos` + `nz` +
  `registerWebPageFiles` + `registerTools` + `filterSharedAgentWriteTools`。
- 共享项留门面：`MAX_ITERATIONS`（放宽包内可见 + 簇内按类名引用）、`chatModel`/`rerankModel`
  （**由 `agentQA` 解析后作参数传入**，簇内不调用——断言先写错过一次，靠"切片缺 `chatModel(`"弹回）、
  `parseHostSkillDirs`（构造器在用）。
- **依赖扫描的坑（新）**：只扫「`field.`」形式会漏掉**作参数传入的无点号用法** →
  `mcpClientManager`/`resourceCatalog`/`dataSource` 是编译期才补齐的。**字段依赖以编译为准，扫描只做预估**。
- 机械改写自伤两例：`rep` 拼实参漏逗号（门面语法错）、`replace("vlmDescriberWiring) {", …)` 把参数名前缀
  留在签名里（`VlmDescriberWiring com.ragagent…`）。**长签名改写后必看现场**（一次 `sed` 即定位）。
- 忠实性核验：11 项逐字一致；常规档闸门（重编 + session 域 27s + `spotlessCheck`）绿。
- **文档修正**：§11.24 的替换曾把 `## 12. knowledge 包结构地图` 标题吞掉，本次一并补回。

## 11.26 刀 4 侦察：KnowledgeQaController 请求解析簇边界修正（2026-09-30，规格已定未落刀）

**结论：原定"请求解析簇 ~350 一把切"要拆两小刀**——挡路的是一个 `@Autowired` 字段，不是类型嵌套：

- **4a（~90 行，可切）**：`BIND_JSON` + `bindQaRequest` + `bindSearchRequest` + `parseOrBindError` +
  `bindingError` + `decodeAndValidateAttachmentUploads`（静态、参数化、无字段依赖）+ `appendAll`
  （调用点仅 487/489/491，全在解析簇）。
- **4b（`parseQARequest` 主体 237 行，需先定方案）**：它读 `agentResolverField`（348 行）——该字段是
  `@Autowired private AgentResolver`（Go `resolveAgent` 接线），**普通协作者拿不到**（不能 ctor 快照一个可能为
  null 的字段，也不该把控制器自身传进协作者）→ 方案：把 `AgentResolver`/解析结果作**参数**传给解析方法
  （私有方法改签名无对外契约影响），随 4b 落地；4a 先落不影响它。
- **共享项留控制器（按调用点核过）**：`stringListOf`（455 在解析簇、**1407 在附件簇** → 双用）、
  `tenantServiceField`（544/545 在 `currentTenant`、**720 在 `executeQA`** → 双用）、`currentTenant`（随前者）、
  `ParsedRequest`（1 行私有 record，被 `agentQA`（174）与解析簇共用 → 放宽为**包内嵌套**，协作者按
  `KnowledgeQaController.ParsedRequest` 引用，同 §11.25 `SearchTargetView` 手法）。
- **4b 首次施工受阻（2026-09-30，已回退到全绿）**：解析体把 `fileService` / `storageBackendResolver` 按实例用，
  但控制器构造器里它们是 **`ObjectProvider<...>`**（Spring 延迟取值，见 135-137 行与 148 行注释）→ 协作者该拿
  provider 还是实例，属**接线语义**决定，不是机械搬运；下次先定语义（ctor 注入 provider + 体内 `getObject()`，
  或调用点传实例）再动刀。
  机械面另一课：**给多个调用点补实参要先收集全部调用点、从右到左插**——本轮出现「同一处插了两次、另一处漏插」，
  编译期才暴露（`parseQARequest cannot be applied to given types`）。
- **刀 5（SSE 编排簇）首探受阻（2026-09-30，已回退到全绿）**：该簇回调三个**实例方法**——`runWithTenant`（16 行）、
  `completeAssistantMessage`（57 行）、以及 `writeAgentQueryEvent` 自身也是实例。`private`/包内**实例**方法不能像
  `stringListOf`（静态）那样按类名限定（编译器直接报 non-static ... cannot be referenced from a static context），
  更不能把控制器自身传进协作者 → **协作者不该回调宿主**。三条出路，选一后再动刀：① 把两个小包装**随簇一起搬走**，
  控制器侧调用反转为 `sseOrchestrator.xxx()`（"宿主回调"变"持有协作者"）；② 只搬**不回调宿主**的部分先拿收益；
  ③ 调整批次顺序：先落刀 7（收尾簇），再看本簇依赖是否变简单。
  **教训（与 4b 对照）：切片前要区分「共享值」（可参数化：`agentResolver`/租户/`maxFileBytes`）与「共享行为」
  （实例方法：只能搬走，或反转调用方向）。**
- **侦察口径修正（值得记）**：`QaRequestContext`/`SseStreamContext`/`CreateKnowledgeQARequest`/
  `SearchKnowledgeRequest` **都是外部导入的顶层类型**，不是控制器嵌套类型——"嵌套上下文挡路"的第一印象是错的；
  真挡路的只有 1 行 `ParsedRequest` 与两个 `@Autowired` 字段。**先 grep 类型声明（`record X|class X` + `import`）
  再判断能不能切。**  **结论已解（同日复核，无需另定语义）**：控制器 150/151 行本来就是 `this.fileService = fileService.getIfAvailable();`
  —— provider 只是"容忍缺失"的入口，**字段是实例、构造期已取**，解析体（260 行）也按实例传下层 → 协作者照
  **实例**收依赖即可。首次失败是脚本取型 bug（按名字命中了构造参数 `ObjectProvider` 而非字段类型），不是设计问题。
  **4b 重试口径（三条，逐条对应首败）**：① 控制器里 `new QaRequestParser(...)` 传 `this.fileService` /
  `this.storageBackendResolver`（字段，不是同名构造参数）；② 新类补
  `import com.ragagent.session.controller.KnowledgeQaController.Base64Support;`（解析体用它解码）；③ 调用点补参
  **先收集全部（163/173）再右到左插**。其余同 §11.26 既定替换（`agentResolver`/`readerTenant` 参数化 +
  `stringListOf`/`ParsedRequest` 包内引用）。

## 11.27 session 步骤 2 第十二刀：KnowledgeQaController 静态解析助手簇（2026-09-30，§14.9c 刀 4a）

| | 前 | 后 |
|---|---|---|
| `KnowledgeQaController` | 1,614 | **1,525**（余刀 4b `parseQARequest` 主体 ~260、刀 5-7 执行/SSE/附件簇） |
| `QaRequestBinder`（新，同包） | — | **105** |

- 切片 7 块：`BIND_JSON` + `bindQaRequest` + `bindSearchRequest` + `parseOrBindError` + `bindingError` +
  `decodeAndValidateAttachmentUploads` + `appendAll`（零字段依赖，脚本已断言）。两个绑定方法原是**实例方法**
  但零字段依赖 → 收为 `static`。
- **共享项按调用点核定（本刀未动）**：`stringListOf`（1407 附件簇在用）、`tenantServiceField`/`currentTenant`
  （720 `executeQA` 在用）→ 留控制器；`ParsedRequest` 留待 4b 放宽为包内嵌套。
- **新坑**：`Base64Support` 是**控制器自己的嵌套静态类**（1515 行）——没有 import 可抄，协作者要靠
  `import com.ragagent.session.controller.KnowledgeQaController.Base64Support;` 引嵌套类型（同 §11.25 的
  `SearchTarget.SearchTargets`）。**找不到 import 时先怀疑它是嵌套类型**（直接 `grep "class X\b"` 搜声明）。
- **核验脚本的坑**：归一化只剥一层修饰符，会把「`private static`」与包内 `static` 比出**假差异**（本刀首跑
  5/7 假红）→ 要**循环剥掉全部前导修饰符**再比对。
- 忠实性核验：7 项逐字一致；常规档闸门（重编 + session 域 27s + `spotlessCheck`）绿。

## 11.28 session 步骤 2 第十三刀：KnowledgeQaController 解析主体（2026-09-30，§14.9c 刀 4b，重试成功）

| | 前 | 后 |
|---|---|---|
| `KnowledgeQaController` | 1,525 | **1,296** |
| `QaRequestParser`（新，同包） | — | **331** |

- 切片：`parseQARequest` 主体 234 行 → 同包 `QaRequestParser`（ctor 依赖 4 个：`sessionService` /
  `temporaryDocuments` / `fileService` / `storageBackendResolver`）。
- **参数化 2 项**（§11.26 既定）：`agentResolver`（控制器里是 `@Autowired` 字段，普通协作者不能快照）、
  `readerTenant`（控制器 `currentTenant()` 依赖 `tenantServiceField`）→ 两个调用点（163/173）各补两参。
- **包内引用 3 项**：`stringListOf`（附件簇也在用 → 留控制器，放宽包内可见）、`ParsedRequest`（私有 record →
  包内嵌套）、`maxFileBytes()`（`static long`；**首败后才发现**，之前只扫了字段没扫方法 → 放宽 + 限定）。
- 重试**一次通过**：传 `this.fileService` / `this.storageBackendResolver`（不是同名构造参数）、补
  `KnowledgeQaController.Base64Support` import、调用点**先收集全部再右到左插**、其余既定替换。
- 忠实性核验：主体（签名之后）按 4 项替换**反向**归一后逐字一致；常规档闸门（重编 + session 域 + spotless）绿。

## 11.29 session 步骤 2 第十四刀：KnowledgeQaController 收尾簇（2026-09-30，§14.9c 刀 7 提前落）

| | 前 | 后 |
|---|---|---|
| `KnowledgeQaController` | 1,290 | **1,175** |
| `QaTurnFinalizer`（新，同包） | — | **161** |

- **为什么提前落**（原计划在刀 5 之后）：刀 5 受阻于"簇内回调三个**实例方法**"（§11.26）——实例方法不能按类名
  限定，协作者也不该回调宿主。把 `runWithTenant`（13 行）+ `completeAssistantMessage`（55 行）随本簇一起搬走后，
  刀 5 的 SSE 协作者只需**持有 `QaTurnFinalizer`** 转发这两件事，阻碍自动消失。**先落"被依赖方"再落"依赖方"。**
- 切片 4 块：`runWithTenant` + `hasPendingAttachments` + `persistLastRequestState` + `completeAssistantMessage`
  （合计 117 行）；ctor 依赖 5 个（`sessionService`/`messageService`/`suggestionService`/`temporaryDocuments`/
  `memoryExtraction`）。
- **零"共享行为"回调、零常量、零参数化**——按 §11.26 判据先扫三类依赖再动刀，这次一次成型；门面 11 处调用
  只做 `turnFinalizer.` 前缀（4/2/1/5 处），成员体**逐字不动**。
- 忠实性核验：4 项**逐字一致**（无需任何反向替换，最干净的一刀）；常规档闸门（重编 + session 域 28s + spotless）绿。

## 11.30 session 步骤 2 第十五刀：KnowledgeQaController SSE 编排簇（2026-09-30，§14.9c 刀 5）

| | 前 | 后 |
|---|---|---|
| `KnowledgeQaController` | 1,175 | **874** |
| `QaSseOrchestrator`（新，同包） | — | **~370**（spotless 后） |

- 切片 7 块（294 行）：`setupSSEStream` + `writeAgentQueryEvent` + `startStopWatcher` + `handleAgentEventsForSSE` +
  `QUICK_ANSWER_TIMELINE_TOOLS` + `registerQuickAnswerTimelineRecorder` + `appendQuickAnswerToolCall`；
  ctor 依赖 6 个（5 字段 + `turnFinalizer`）；对外 3 个成员收为包内可见。
- **首探受阻的解法生效**：`runWithTenant`/`completeAssistantMessage` 随刀 7 进 `QaTurnFinalizer` 时**调用点已一并限定**
  （`turnFinalizer.xxx(`）→ 本协作者持有同名字段即可，成员体**逐字不动**、零转发改写。
  `ensureQuickAnswerStep`（static、972 行簇外也在用）→ 留控制器，放宽包内可见 + 簇内按类名引用。
- **三处踩坑（都值得记）**：① `(?<![\w.])runWithTenant\(` 被限定词的点号排除 → 首跑"簇内 0 处"是**假 0**；
  判"是否还有裸调用"要剥限定词或用 `(?<![\w])`。② **「放宽可见性 ⇄ 对外调用点」必须写进脚本同一步**——漏两次、
  编译期才暴露。③ heredoc 里写正则补丁极易双重转义（本次踩到 `unterminated subpattern`）→ **补丁用免转义的
  字符串替换，别在 heredoc 里嵌正则**。
- 忠实性核验：7 项逐字一致（仅 `ensureQuickAnswerStep` 一处反向归一）；常规档闸门绿。

## 11.31 session 步骤 2 第十六刀：KnowledgeQaController 附件解析簇（2026-09-30，§14.9c 刀 6a）

| | 前 | 后 |
|---|---|---|
| `KnowledgeQaController` | 874 | **691**（**出 ≥800 榜**） |
| `QaAttachmentResolver`（新，同包） | — | **221** |

- 切片 3 块：`resolveTemporaryAttachments`（110 行）+ `persistResolvedAttachmentContent`（49 行）+
  `waitForAttachments`（10 行）；ctor 依赖 3 个（`messageService`/`temporaryDocuments`/`turnFinalizer`）。
- **两次中止才定下的边界**：`waitForAttachments` 是**实例方法**（同 `runWithTenant` 那类"共享行为"）→ 按 §11.26
  判据**随簇搬走**（不是按类名限定），控制器剩余调用改 `attachmentResolver.waitForAttachments(`（实测 0 处，簇内用）；
  `appendQuickAnswerReasoning`（7 行）确认 static → 留控制器。
- 共享静态助手按需限定（命中 0 即跳过——它们只出现在未纳入切片的 `appendQuickAnswerReasoning` 里）。
- 忠实性核验：3 项逐字一致（反向归一）；常规档闸门（重编 + session 域 30s + spotless）绿。
- 工程面：8KB 命令上限逼出 **harness 文件模式**（脚本补丁 → 落刀 → 编译 → 闸门 → 忠实性 → 文档 → 提交，
  失败自动回退）——此后每刀都走它。

## 12. knowledge 包结构地图（样板，其余域照此靠拢）
> **全后端分包地图与体检结论见 `docs/backend-package-map.md`**（2026-09-30：34 顶层包 / 1,599 文件 / 284k 行；P0 包间成环 32 组、P1 扁平包 10 个、P2 超大单层 4 个、P3 顶层 package-info 仅 5/34；复测 `python3 scripts/pkg-audit.py`）。

> **模块手册**：`docs/knowledge-module-guide.md`（架构师接手版，500 行 / 8 张 Mermaid 图：全景 · 分层 · ER · 入库时序 · 检索 · FAQ 状态机 · 任务 span · 守卫）——它讲「结构 + 接口 + 实体 + 链路 + 改哪里」，新人先读手册、再读本节地图。

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
10. **移动/改名类之后必须 grep 全仓 `{@link 旧名}`**：Javadoc 链接不会被编译器发现（`{@link 已删类型}` 照样编译通过），会静默变成死链。
11. **别把 git 历史与批次代号写进注释**：`原为 X 内嵌段，阶段 N 拆分`、`本批未含`、`W5a：` 这类叙述读者无法解码；按 §4 的原则**摘出真实不变量、删掉阶段/批次代号**（本次已清 knowledge 包 8 处；新写注释也别再引入）。
12. **别在 shell 双引号里跑含反引号的 `python3 -c`**：zsh 会把反引号当命令替换（`{@link 旧名}`、`阶段 N` 之类的文本会被执行并清空，静默写坏文件）。改写脚本文件再 `python3 /tmp/xxx.py`，或在 Python 里用 chr(96) 拼反引号。
13. **写 javadoc 的判据（knowledge 包已按此做完）**：写"名字看不出来的"——三态语义（null = 不变更）、乐观锁字段、视图与写入形状的差异（如 VLM 视图不含 `apiKey`）、与仓储类型的对应关系、jsonb 列名；**不写**名字即语义的 CRUD 请求体（写了是噪声）。覆盖目标：承载语义的类型 100%，方法层保持"逻辑密集类 90%+ / 访问器 0%"的分布。
14. **跑 `test` ≠ 跑了闸门**：仓库早已配好 Spotless（`removeUnusedImports` + `trimTrailingWhitespace` + `endWithNewline`，`ratchetFrom("seed")`），但只跑 `:server:test` 时它**不执行**——每批收尾必须 `:server:spotlessCheck`（或 `check`）。两个已知盲点：①`removeUnusedImports()` **不去重**（本次手删 13 处重复 import，分布在 5 个文件；要根治可加 `importOrder()` 步骤，但那会重排 import，需单独一个轴）；②抽类/拆类时**别整块继承原文件的头部样板**——本次两类残留同源：import 列表（276 处）与**从未使用的 logger 字段**（全仓 15 处，其中 8 处在 knowledge）。修法：删字段 + `spotlessApply` 清掉随之失效的 `org.slf4j` import（`88c8054`）。
15. **核查"这个成员是不是没用"的标准做法**（用户逐条抽查时用的口径）：①私有字段/方法/局部变量 = 数**声明行之外**的引用（为 0 即死）；公开成员不能这样判（有外部消费者）；②**删之前先做来历追溯**——`git show <拆分类的引入提交>^:<原文件> | grep <名字>`：若拆分前也无调用点 → 翻译期遗留，可删；若拆分前**有**调用点 → 说明拆分把调用方留在了别处，先确认那边有等价实现（本次 `orEmpty` 就属后者：调用点落在 `ChunkEditService`，本处是重复遗留）；③**别按"删一行"的直觉动手**：声明可能跨行（链式调用、多行泛型、注解行），本次 `MAPPER = new ObjectMapper()` 换行接 `.disable(...)` 就差点留下悬空续行——Spotless 的 lint（`illegal start of type`）会兜住，所以**删完先跑 `spotlessApply`**。死成员清单：`88c8054`（logger ×8）、本次 `73e2343`（MAPPER ×5 + 死局部变量 ×4 + 死方法 ×1）。
    **④「只注入不读取」的依赖（662dece：knowledge 清 12 处，全仓 36 → 24）**：判据 = `private final` 字段的全部出现只落在「构造参数行 + `this.x = x;` 赋值行」上（别处零引用）。这类残留同样源自「抽类搬构造清单」——**它与「字段未使用」是两个不同口径**，粗算「声明外引用次数」会把构造赋值算成使用而**漏报**。
    **⑤按行号删代码必须逆序执行**：先删构造参数、再按旧行号删赋值行 → 误删相邻行（首次尝试即踩，diff 复核发现后回退重做）；两种形态要单独收拾：**末位参数**（行尾是 `) {`，删行后要给上一参数去掉逗号）与**参数与他人同行**（只抠片段，别删整行）。
    **⑥自查脚本的六个盲区（`7bf963e` 修正后 knowledge 才归零）**：①**注释/javadoc 里的同名词**别算读取（`worker`/`storage` 就是这样被漏报的）；②**import/package 行里的包名**别算读取（`import ...storage.LocalStorageService` 让 `storage` 显得被用）；③**跨行声明**要认（`MAPPER = new ObjectMapper()` 换行接 `.registerModule(...)`）；④`this::method` **方法引用**要算使用；⑤`serialVersionUID` 永远别报（Java 序列化隐式使用）；⑥**遮蔽 import** 单独查——类里声明了同名嵌套类型时，import 静默失效（javac/Spotless 都不报，只有 IDE 提示）。
    **⑦「静态方法被当实例方法调用」也是死依赖的入口**（IDE Java 603979893）：`folderService.rejectMovingKnowledge(row)` 调的是别类的 static 方法 → 改成静态调用后，该依赖若别无用途就变成死依赖（本次即如此，`2d1b375`）。**⑧未使用局部变量目前只有 IDE 能发现**（javac 不报、Spotless 不管、v2 扫描器不覆盖作用域）——本次 `KnowledgeTagService` 的 `OffsetDateTime now = ...` 即此类；根治需上静态分析闸门（见 §7 候选 5）。
16. **注解约定已落在代码里（`knowledge/domain/package-info.java`）**：① jsonb 列必须逐字段 `@TableField(typeHandler = PgJsonTypeHandler.class)` 且实体带 `@TableName(autoResultMap = true)`——**漏了 autoResultMap 会「写得进、查出来是 null」**（wrapper 的 `set()` 也不套 typeHandler，需三参写法）；② `@JsonInclude` 属 Go `omitempty` 直译，**不要新增**，存量分批清（批次 A/B）。
17. **留意「被程序化清理剪残的 javadoc」**：早前用脚本删注释行时会把句子开头一起删掉，留下 `不出响应）；…`、`answer_strategy / version / source} 带 omitempty（…`、`零值 {@code ""} 也输出）；…` 这类半句，读起来像天书且**编译器不报**。本次批次 B 一次修出 6 处（`7f1b2a7`）——触碰文件时顺手检查类注释首句是否完整，改写时保留真实不变量、删掉 Go/omitempty 叙述。

18. **本会话新增三条**（并行会话记录，2026-09-30）：(a) 被中止的 gradle 测试 run 会留孤儿 Test Executor 占固定端口 stub（11434）,下一轮误报"failed to start stub"——先 `lsof -ti :11434` 清进程再判回归；(b) 去逐字段 `@JsonProperty` 时,**失效的 `@JsonPropertyOrder` 旧名名单必须同删**——属性对 order 表不可见时 Jackson 序列化静默丢属性（approval 线上抓到）;(c) 闸门命令链不要依赖退出码（`cmd | tail` 恒 0）——用 `grep -q 'BUILD SUCCESSFUL'` 之类的字符串断言收口,否则红灯也会照常 commit（本轮 amend 修复过一次）。
19. **别写全限定名注解**：`@jakarta.validation.constraints.NotBlank` 这类写法不标准也不必须（与 import + 短名等价），通常是遗留或脚本产物；唯一合法例外是**真同名冲突**，此时在注释里写明原因。knowledge 已清零（22 处），全仓余 177 处随各域清。另注：`message = "字段名: 不能为空"` 是仓库**有意约定**（GlobalExceptionHandler 按 `^[a-z][A-Za-z0-9_]*: ` 解析字段名），别当风格问题删掉。
    **2026-09-30 扩展**：这条适用于**所有内联全限定名**（不只是注解）——knowledge 主源一次清出 **193 处**类型 FQN（`java.*` / `com.fasterxml.*` / `com.baomidou.*` …，`b9a583c`）。同时发现 `KnowledgeCodeConventionsTest` 的规则太窄（只匹配 `com.ragagent.*`，对 `java.*`/`jakarta.*`/`com.fasterxml.*` 全部漏检）→ 已把正则扩为通用 `(?:[a-z][\w]*\.){2,}[A-Z]\w*`，并新增「不得引入 `@JsonInclude` / 逐字段 `@JsonProperty`」守卫用例。
20. **推送到公开仓库前必须扫密钥**（2026-09-30 首次推送前执行）：① `git grep -nE '(sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)'`；② `git ls-files | grep -iE '\.env|\.pem$|id_rsa'`；③ `du -sh .git` 看体积（GitHub 单文件上限 100MB）。本次扫出**两个录制期 fixture 里未脱敏的 `sk-` 值**（`adm-key-create.json` 的 `token` 字段、`ct-create-apikey.json` 的 `api_key`），经用户确认是录制时的假值后才推送。**教训：从真实环境「录制」来的 fixture 最容易夹带真实凭据**；一旦推送，彻底清除需改写历史（会变更所有提交号），所以**先问清楚再推**。
16. **Go 兼容层退役的施工判据（2026-09-30，`0ac456e` 沉淀）**：
    - **先分清两个面**：`common/web` 那些类既是**线上注解/全局配置**（该退役），又是**工具函数**（`GoDoubleSerializer.format`、`GoTimeSerializer.GO_ZERO/isGoZero`、`GoMapSerializer.GO_KEY_ORDER`——被 chatpipeline 手搓载荷、agent 工具、事件总线、Redis 流、provider 客户端使用，属 §11 边界，**不能整类删**）。判据：`grep` 代码级引用（去掉 `*` 开头的 javadoc 行）后，仅剩"自身 + 待删配置"才可删。
    - **摘注解前先补 mapper 覆盖**：逐字段 `@JsonSerialize/@JsonDeserialize` 存在的真实理由是"jsonb 读路径用裸 mapper（无 JSR-310）"。先让 `JsonMappers.lenient()` 注册 `JavaTimeModule` + 关 `WRITE_DATES_AS_TIMESTAMPS`（加法、可独立全绿验证），再摘注解；`PgJsonTypeHandler` 用父类官方钩子 `JacksonTypeHandler.setObjectMapper(...)` 注入同一工厂，别去覆盖方法。
    - **期望值改法**：`ContractJson.deep()`（键序 + 整值浮点 + 时间归一到 UTC + 嵌套 JSON 串）是通用解，但两个坑：①`deep()` 会**排序键**，按声明序断言的用例（如 `AgentStepsJsonTest`）**不能用**；②Jackson 对 `OffsetDateTime` **保留值自带偏移**（`Instant` 字段才输出 `Z`），所以"同一字段不同构造方式"的期望值要分别设，别一刀切 UTC 化。
    - **量级参考**：208 失败 → 0，其中约 100 属于"测试用例自己造的裸 mapper"（接工厂即修，34 文件），~30 属于"钉住 Go 字节的期望值"，~10 属于"退役层自己的测试"（删）。

21. **体检启发式有三类误报，报告任何"卫生指标"前必须先人审样本**（2026-09-30 agent 域复验）：
    ①「注释掉的代码」正则会把 `// if / then / else`、`// scopeKey = kb:tenant:tagIDs` 这类**说明性注释**判成代码（agent 域报 11 处、实为 0；**按报告去删就是删文档**）；
    ②「坏 `{@link}`」的存在性检查会把 JDK/Jackson 类型（`JsonNode`/`LinkedHashMap`）与**同文件嵌套类型**报成坏链（agent 域报 42 处，绝大多数合法）；
    ③「死成员」的字段级口径**把构造函数里的赋值算作使用**——所以「只注入不读取的依赖」必须用依赖级口径单独扫（`agentm/ModelConnectivityTestService` 的 3 处就是这么漏到后续批次的）。

22. **"成环数量"是误导性指标，决定成本的是"背边规模"**（2026-09-30）：`agent ⇄ knowledge` 这种听起来吓人的环，
    实际可能只是**一个类型**被上层域引了一次（切它只需动 1 个文件）；而 34 组环里真正的重活只有 5 组（背边 ≥8 文件）。
    **教训**：给出架构级结论前先量"背边规模 / 引用文件数"，别用"环的数量"估成本（我第一版就估反了）；
    守卫用 `scripts/check-package-cycles.py`（环只许减不许增，与 Spotless ratchet 同精神）。

23. **包搬迁的引用检查清单（4 类，缺一类就编译不过或运行期才炸）**（2026-09-30 执行 `apikey` → `auth/apikey`）：
    ① `import com.ragagent.X.`（编译器会兜底）；
    ② **字符串里的 FQN**——MyBatis `typeHandler=com.ragagent.X.domain.Y` 这类注释/注解字符串（编译器**不**兜底，本次 4 处）；
    ③ `yml/xml/properties` 里的类名或扫描路径（本次为 0，但必须先 grep）；
    ④ 扫描路径**通配**（本仓 `@MapperScan("com.ragagent.**.mapper")` 是通配，搬迁无需改配置——若写成具体包名则必须改）。
    做法：`git mv` 目录 → 全仓替换 `com.ragagent.X`（含 test）→ **grep 残留**（现包名替换后仍能匹配旧串吗？不能——旧串 `com.ragagent.apikey` 不再是新串 `com.ragagent.auth.apikey` 的子串，可直接 grep 验证）→ 编译 → 全量 → 刷新环基线。

24. **本机 Redis 偶发假失败要会甄别**（2026-09-30）：`RedisStreamManagerTest`（跑 `EmbeddedRedis`，需 PATH 上的 `redis-server`）
    偶发 8 用例全红，错误 `RedisConnectionFailureException: Unable to connect to Redis`——**单跑即过**，与代码改动无关。
    判据：①错误是"连不上"而非断言失败；②`which redis-server` 有；③单跑该类通过。**别据此怀疑刚做的改动，也别把它当回归记进文档。**
    `agentm`/`initialization` 拆分时的真实规模：agentm 19 文件 / 2607 行，initialization 16 文件 / 2945 行。

25. **搬迁脚本要写成幂等 + 别做多余动作**（2026-09-30 批 1 踩了两次）：① `git mv` **要求目标目录已存在**——
    忘了 `mkdir -p` 会在第一行就失败（好在本脚本首行即死、无副作用）；② **整包并入时不要 `git rm -r` 旧目录**——
    逐文件 `git mv` 之后目录里已无被跟踪文件，`git rm` 只会报 `pathspec did not match` 并**中断后面的步骤**（本次因此
    只搬了 3 个包里的第 1 个、引用重写整段没跑，编译报一片 `cannot find symbol`）。正解：**每个包一个独立脚本段落，
    或写成"逐包 mv → 逐包重写 → 校验残留"的幂等函数**，失败可原地重跑。

26. **搬家的引用重写要覆盖三类写法**（2026-09-30 批 2a 又漏一次）：① FQN（`com.ragagent.a.B`）；② **同包内免 import 的简单名**
（把 `agent/B.java` 搬到别处后，`agent/` 里原来直接写 `B` 的文件会编译不过——**必须补 import**）；
③ 通配 import（`import com.ragagent.a.*;`）。**做法**：搬完先 `grep -rn '\b类名\b'` 全仓，逐个看是否已有对应 import。

27. **测试目录要跟主源一起搬**（2026-09-30 补记）：整包并入宿主域时，只搬 `src/main` 会让 **test 侧仍留在旧包名**下
    （`com.ragagent.storageurl.RewriterTest` vs 主源新家 `com.ragagent.storage.support`）——编译能过（测试包名不必与主源镜像），
    但 audit/体检与新人阅读都会被误导。**搬迁清单加上第 5 类：`src/test` 的同名包目录 + 其 `package` 声明与自引用 import。**

28. **grep 调用点要按"接收者 + 方法名"扫，别只扫限定名**（2026-09-30 踩）：判断 `Rewriter.rewriteMessagesResponse` 是否有人调时，
    我先用 `rewriteMessagesResponse` 扫 `src/main`，**没命中**，于是写进文档说"无生产调用者、不能当死代码删"；
    真正调用者是 `MessageController` 里的 `rewriter.rewriteMessagesResponse(messages)`——带接收者的调用被我的模式漏掉了。
    教训：**判断"有没有人调用"至少扫两种写法**（`Type.method(` 与 `.method(`），并用编译器兜底（删完先编译，报错即有人用）。

29. **"分二级子包"的搬迁配方（2026-09-30 批 P1 实测，chatpipeline/event 两个扁平包）**：纯移动也**别手改 import**，按四步走：
   ① `git mv` 到子包（git 认 rename，历史不断）；
   ② 脚本改 `package` 声明 + **给每个搬迁文件补跨子包 import**：候选集 = 该顶层包下所有类型（排除同子包），
      判据 = `\b类型名\b` 命中且文件里没有**同名** import（防遮蔽）——本次两个包共补 ~170 行，一次成型；
   ③ 全仓引用改写：`com.ragagent.<包>.<类型>` → 新 FQN，**必须带词边界**（否则 `Plugin` 会吃掉 `PluginSearch`）；
      主源 + `src/test` 同包文件（它们引用是短名，要补 import）一起处理；
   ④ **跨子包可见性收口**：循环"编译 → 解析 `is not public in X` → 把该成员/嵌套类型放宽为 public"，直到编译过。
      本次 chatpipeline 26 处（含录制测试直接探针的 plugin 辅助），event 0 处。**测试留在根包**（静态导入 `Rec46cSupport` 需要），
      所以只能放宽生产成员——这是 ④-e 的老先例，不是新问题。
   收尾：`spotlessApply`（会清掉 package-info 里 javadoc 表格误补的 import）→ 全量测试 → `check-package-cycles.py` 守卫 → 体检复跑。

21. **搬运类/包之后必须跑一次「干净全量」**（2026-09-30 实测：用户贴 IDE Problems 才发现）：
   `retrieval/support/SearchUtilTest` 自批 4k 起就编译不过——它测的 `SearchChunkMerge`/`ImageInfoEnricher`
   搬去了 `knowledge.support`，测试仍按旧包同名引用（无 import），但 `:server:test` **一路全绿**。原因两层：
   ① Gradle 增量编译**不重编未变更的引用方**，该文件自 4k 后没人碰过，错误永不暴露；
   ② 它的 `.class` 早已不在 `build/classes/java/test` 里 → 测试**根本没跑**（`build/test-results` 里
   连它的 XML 都没有）。`compileTestJava --rerun-tasks` 一秒暴露。**收口口径**：
   ①迁移/改名/删类之后，该批收尾闸门改用 `:server:clean :server:test :server:spotlessCheck`
   （本仓 clean 全量约 3.5 分钟，只比常规全量贵一点）；②**用例数只认「干净一遍」后的 XML 汇总**
   （本次修复后 425 类 4,559 例 → **432 类 4,664 例**：多出的 7 个测试类此前一直"静默缺席"，
   所以 §11.16 统一的口径还要再叠一条"需为 clean 构建的结果"）；③**IDE Problems 是这类断链最早的哨兵**
   ——用户视角看得见，助手侧应主动对刚动过的目录 `read_lints`。

22. **IDE 报「Gradle CONFIGURE FAILED」通常不是仓库问题（2026-09-30 排查记录）**：现象是
   `CONFIGURE FAILED in 69ms / The supplied build action failed with an exception`，
   真因在 IDE 的 `1-Gradle for Java.log`（`~/Library/Application Support/CodeBuddy CN/logs/<会话>/window*/exthost/*/`）：
   `BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 70`
   —— IDE 的 Gradle 扩展解析到本机**默认 JDK 26**（`/opt/homebrew/Cellar/openjdk/26.0.2.1`），
   而 Gradle 8.10.2 不支持；项目 `server/build.gradle.kts` 的 toolchain 是 **21**。
   修法：在本地（`.vscode/` 已在 `.gitignore`，不入库）`.vscode/settings.json` 钉
   `java.import.gradle.java.home` 与 `java.jdt.ls.java.home` = `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，
   然后 Reload Window（若仍失败：先停掉 IDE 里 JDK 26 起的旧 daemon，或 `./gradlew --stop` —— 注意它会连带停掉并行会话的 daemon）。
   **CLI 一直正常是因为每条命令都显式带 `JAVA_HOME=openjdk@21`。** 另注：同一份日志里还夹着
   `/Users/billy/ragagent-java`（旧副本的另一个窗口）的同名错误，与当前仓库无关。

## 14. 逐包重构范式（knowledge 为范本，其余域照此推进）

> **用户定稿（2026-09-30）：以 knowledge 包的重构为范本，逐步重构其他包。**
> §12 是"拆完长什么样"，本节是"**怎么拆**"——把 knowledge 那 40+ 个提交里可复制的部分固化成 SOP。
> knowledge 的量化基线（目标形态，实测 2026-09-30）：197 文件 / 25,138 行 / 最大类 1,234 行 /
> ≥800 行 3 个（皆有 javadoc 注明的例外理由）/**Go 锚点 0 / rawBody 0 / 逐字段 `@JsonProperty` 0 / 未使用 import 0**。

### 14.1 三条内核（范本之所以有效的地方）

1. **沿注释边界拆，不按行数硬切**：神类里的 `// ── X 段 ──` 分割线就是拆解点（`KnowledgeService`
   3,392 行 → 门面 + 7 切片服务就是照这个来的）。拆完"门面保留全部公共委托"→ 18+ 注入点与
   Mockito 测试**零改动**，这是能把大手术做小的关键。
2. **每步全绿再走下一步**（2026-09-30 起分档，命令见 §14.4）：闸门按改动性质分档——迭代中只跑单类/单域；
   **常规批**（同包抽协作者、成员增删、卫生、文案）= `--rerun-tasks` 重编（~31s）+ 受影响域测试（~30s）
   + `spotlessCheck`（~10s）≈ 1m10s；**结构搬迁批**（跨包 `git mv`、改共享 API、删类）=
   `clean :server:test :spotlessCheck`（~3m25s）。**判据：抓断链靠"强制重编"（便宜），防跨域行为回归才靠全量（贵）**
   ——别把两件事混成一件（§13.21 的两次假绿根因都是**编译错误**被增量编译掩盖，重编即可暴露；
   用例数只认"干净一遍"后的 `build/test-results/test/*.xml` 汇总）。
   这是"种子 fork + 渐进转型"优于重写的全部意义（§9）。
3. **一次只动一个轴**（§3 红线 1）：拆类期不改契约、换锚期不拆类、卫生期不动逻辑。
   轴混了就退化成大爆炸重写。

### 14.2 单域 SOP（七步，每步独立提交、独立全绿）

| 步 | 动作 | 关键点 / 产出 |
|---|---|---|
| 0 侦察 | 按 §14.4 命令出该域体检表 | 规模 / 神类 / Go 锚点 / `@JsonProperty` / rawBody / 未用 import |
| 1 边界 | 判"该域哪些**不能动**" + 列跨包缝合点 | 对照 §11 边界清单；缝合点用 `git grep` 实测，别凭印象 |
| 2 拆分 | 神类沿注释边界 → 门面 + 切片；容器类 → 一类型一文件 | 测试随被拆类**同包 `git mv`**；容器级常量/私有 helper 先安置（§13.4/13.5） |
| 3 分层 | `controller/service/support/task/client/storage/security/repository/mapper/domain/dto` | 每包一份 `package-info.java`（职责地图） |
| 4 契约 Java 化 | controller 入参 → DTO + `@Valid`；去 `@JsonNaming` / 逐字段 `@JsonProperty` / 信封 | **顺手消灭手写绑定器**（那是真实缺陷温床，§7 第 1 条）；同批带前端 |
| 5 数据访问去 Go | 落库 jsonb 去 snake（若该域有此面）| 改完必须 `grep` 全仓"按旧键读取"的代码（§11 ② 的 4 处真实缺陷） |
| 6 卫生 | 注释判据（§13.13）/ import（§13.14）/ 坏 `{@link}` / 批次代号清除 | `spotlessApply` 是标准手段，别自己写替换脚本 |
| 7 收尾 | 更新 §4 数据、§12 地图、§14.3 候选表 | 顺带把该域新踩的坑写进 §13 |

### 14.3 候选域盘点（2026-09-30 实测；`knowledge` 为已完成参照）

| 域 | 文件 | 行数 | 最大类 | ≥800 | Go 锚点 | `@JsonProperty` | 未用 import | 备注 |
|---|---|---|---|---|---|---|---|---|
| **wiki** | 130 | ~24.6k | PageServiceImpl(接口门面+三协作者) | 0 硬顶外 3 例外已注明 | 6(保留事实) | 218 | 4 | **步骤 2 完成(2026-09-30)**:六神类处置=BatchHandler 2,268→522+四协作者(4cd8701);IngestService 2,182→1,213+四协作者(7f3df4e);PageServiceImpl 1,642→门面+三协作者 FolderSupport/LinkRepair/ViewsSupport(3e031eb);PageController 1,342/DedupService 846/PageRepository 870 例外注明(64c6c81,C 波/数据轴重写时重塑)。**B 波完成(e026138)+ C 波完成(b407769:实体去 202 处注解转 camel/查询参数 Java 字段名/前端同批/wiki-* fixture 重录;PageController raw 形态保留但键已换锚,DTO 端点化随数据访问轴)**。**余**:数据访问轴(GORM 复刻层重塑) |
| **agent** | 171(+agentm 31) | 见 §11.6 | `ActPhase` 761 | **0** | **0** | **14**（登记边界：`AgentConfig`） | **0** | A/B/E 波 + Task 12 契约换锚完成；**§14.5 复验通过 + 卫生清零（§11.6，2026-09-30）**——本行为复验口径 |
| **datasource** | 121 | 28,390 | DataSourceService 1,828 | 3 | **1,393** | **473** | 1 | §6.2：**零外部引用，可纯删**——先决定删/留 |
| **session** | 94 | 24871 | SessionQaResolution 2,906 | **4**（QaController 1,613 / AgentQaService 1,430 / Resolution 2,906 + SKQA 门面 1,036，后者按 §14.5 已在类 javadoc 注明例外） | 721（审计口径） | 188（审计口径，逐字段） | 12（审计口径） | **步骤 0/1 完成；步骤 2 六刀已落（§11.15~§11.20）+ 卫生批（§11.19）**：SKQA 1,764→门面 1,036+Resolution 2,906/Fallback（89a4e44）；TempDoc 两刀 1,075→498（+PromptResolver 271、+Processor 423）；StreamBridge 853→697（+9 例契约测试）；MessageService 1,028→510（+MessageSearch 596）；Suggestion 1,088→601（+Pipeline 513）。**步骤 2 余项（按 §14.9c 批次清单连续落刀）**=AgentQaService **已出榜**（1,430 → 364，刀 1-3，§11.23~§11.25）/ QaController 1,613 / Resolution 2,906 自身需再切（`AgentToolBackends` 两刀已出榜：1,264 → 590，§11.21/§11.22）；**步骤 3（分层/package-info）与步骤 4（契约 Java 化=DTO/HTTP 面换锚）均未开始**——换锚按 §14.2 排在步骤 3 之后，**同批带前端、不加兼容别名**（§2.11）；agent config jsonb 内层键为登记边界保持 snake；`wip/chat-sse-slice2` 已裁定不并入（§14.8） |
| **memory** | 62 | 13,166 | MemoryService 1,661 | 3 | 559 | 134 | 2 | |
| **llm** | 94 | 10,826 | RemoteApiChat 1,367 | 1 | 476 | 171 | 1 | |
| **retrieval** | 58 | 19,404 | OpenSearchRetrieveRepository 1,653 | **10** | 267 | 19 | 2 | 契约已换锚（§11 ①） |
| **im** | 63 | 16,006 | ImService 1,447 | 2 | 200 | 17 | 11 | §6.2：可裁（1 个跨包引用） |
| **mcp** | 109 | 12,812 | McpServiceController 938 | 2 | 532 | 110 | 1 | |
| **auth** | 57 | 9,335 | AuthController 1,168 | 3 | 151 | 208 | 3 | 手写绑定器已修一处（§11 ②） |
| **agentm** | 19 | 5,265 | InitializationController 1,982 | 2 | 60 | 0 | 0 | 自有契约、内层 snake（§7 第 5 条） |
| 其余小域 | ≤44 | ≤8.9k | ≤1,146 | 0–2 | ≤244 | ≤86 | ≤8 | 顺手标准化即可 |

**建议顺序**（用户可按需调整）：① **agent**（阶段 2 的另一半，边界已明确、收益最大）→ ② **wiki**
（Go 债务最重）→ ③ **session**（神类最多，且已有在途切片分支）→ ④ **datasource / im**
（先决策"删 or 留"再动工）→ ⑤ 其余域。
**排序依据**：风险随"跨包引用数 × 契约可见面"上升，收益随"神类行数 × Go 债务"上升。

**进度跟踪**：`knowledge` ✅ 完成（范本，§12 地图 + §11 记录）｜`agent` ✅ 完成（含 Task 12 契约换锚 9cb74b4,2026-09-30;config jsonb 内层键为登记边界,见 §11 边界清单）｜`wiki` ⬜ ｜`session` ⬜ ｜
`datasource` ⬜（先定删/留）｜`im` ⬜（先定删/留）｜`memory` ⬜ ｜`llm` ⬜ ｜`retrieval` ⬜ ｜
`mcp` ⬜ ｜`auth` ⬜ ｜`agentm` ⬜ ｜其余小域 ⬜。
每完成一个域：把该行改为 ✅、在 §11 追加执行记录、按 §14.2 第 7 步回填数据。

### 14.4 体检命令（复制即用）

```bash
cd ~/ragagent
# 神类/大文件排行（全仓）
git ls-files 'server/src/main/java/**/*.java' | xargs wc -l | sort -rn | head -25
# Go 债务：锚点注释 / 逐字段 @JsonProperty / 手写 rawBody 绑定
git grep -cE '对照 Go|GORM|Go 的' -- 'server/src/main/java/**/*.java' | sort -t: -k2 -nr | head -15
git grep -c '@JsonProperty(' -- 'server/src/main/java/**/*.java' | sort -t: -k2 -nr | head -15
git grep -nE '@RequestBody\s+(String|Map<|JsonNode|Object)' -- 'server/src/main/java/**/*.java'
# 卫生闸门（ratchet：只覆盖 seed 后触碰过的文件，这是设计不是遗漏）
./gradlew :server:spotlessCheck
# 收尾闸门（按改动分档；实测：重编 ~31s / 单域 ~30s / spotless ~10s / 全量 ~2m50s / clean 全量 ~3m25s）
# ① 常规批（同包抽协作者、成员增删、卫生、文案）≈ 1m10s
./gradlew :server:compileJava :server:compileTestJava --rerun-tasks   # 专抓"引用被搬走"的断链
./gradlew :server:test --tests "com.ragagent.<受影响域>.*"
./gradlew :server:spotlessCheck
# ② 结构搬迁批（跨包 git mv / 改共享 API / 删类）≈ 3m25s
./gradlew :server:clean :server:test :server:spotlessCheck
# ③ 迭代中：单类/单域（秒级）
./gradlew :server:test --tests "com.ragagent.session.service.SomeTest"
# 前端契约同步（触及前端契约时）
(cd frontend && npx vue-tsc --build --force && npm test)
```

### 14.5 完成判据（Acceptance，逐项核对）

- [ ] 该域最大类 < 800 行；例外必须在类 javadoc 写明理由（对齐 knowledge 的 3 个例外）
- [ ] Go 锚点注释 0 / 注释掉的代码 0 / 坏 `{@link}` 0 / 批次与阶段代号 0
- [ ] 请求侧无 `@JsonNaming`、无逐字段 `@JsonProperty`；无 `{data,success}` 信封；删除返 204；可空显式 `null`
- [ ] controller 入参全部 `@Valid` DTO（multipart 与"固定文案兜底"端点可保留手绑，但须在 javadoc 注明）
- [ ] 每个子包有 `package-info.java`；`*Util`/容器类等反模式命名清零
- [ ] **无全限定名注解**（除真同名冲突并在注释说明）；校验 `message` 保持「字段名: 原因」前缀格式
- [ ] **死成员清零**：未使用 logger / `ObjectMapper` / 私有方法 / 局部变量 / **只注入不读取的 final 依赖**（口径见 §13.15 ①④）；javac 不报未使用私有成员、Spotless 也只查 import，**必须主动扫**
- [ ] 触点变更后按 §14.4 分档收口：**常规批** = `--rerun-tasks` 重编 + 受影响域测试 + `spotlessCheck`；
      **结构搬迁批** = `clean :server:test :spotlessCheck`（+ 触及前端契约时 `vue-tsc` 0 错误 / `npm test` 全绿）
- [ ] §4 数据、§12 地图、§13 经验、本节候选表四处同步更新

### 14.6 不要做什么（踩过的坑，别再踩）

- **别把"Go 序列化层删除"拆到各域**：409 处引用 / 94 文件的那一刀按 §3 红线必须**一次性全仓完成**。
  按域先换锚（同 PR 带前端）是允许的，删序列化器本体不是。
- **别动 §11 的边界清单**：租户配置 jsonb（`chat_parser_engine_rules` 等）、auth 域、agent 域 fixture（`ag-*`）、**Go 工具面 5 类**（`GoDoubleSerializer`/`GoTimeSerializer`/`GoMapSerializer`/`GoJsonEscapes`/`GoJson`——线上注解已清零，但手搓载荷/provider 请求体仍依赖其字节）、chat/工具域手搓载荷与**工具输出自有 schema**、检索引擎索引文档——
  这些"仍是 snake"是**对的**。**注意该清单会随各域推进而变动**：`wiki 域实体` 条目已作废
  （`b407769` C 波把 `wiki/domain` 换锚为 camelCase），`wiki/service` 残留的 `@JsonProperty` 载荷随其批次处理；
  **引用前先看 §14.3 该域的进度栏，别照抄旧结论**。
- **别为数字写注释**：getter/POJO 访问器保持 0 javadoc（§13.13）。
- **别做全仓文本替换**：先用单文件验证再决定扩大（§13.2 的两次翻车）。
- **别跳过闸门**：只跑 `:server:test` 会漏掉 Spotless（§13.14）。


### 14.7 wiki 域步骤 1 边界判定（2026-09-30）

- **不动**：`wiki/domain` 22 文件 173 处 `@JsonProperty`（§11 已登记的 wiki 域实体 snake 边界）；wiki 对前端契约整体（§2 第 4 条落地范围外，wiki 域 C 波另立切片）。
- **跨包缝合点（git grep 实测,11 文件）**：`service.WikiLanguageSupport`（agent PromptAssembly + knowledge×4 + session×2,消费最广）；`service.WikiIngestService`(+EnqueueResult)/`WikiKnowledgeFinalizer`/`DefaultWikiKnowledgeFinalizer`/`WikiImageMarkup`（knowledge 加工链）；`service.WikiPageService`/`WikiEditContext` + `domain.Wiki*`（session AgentToolBackends → agent wiki 工具,经 WikiPages seam 接口）；`controller.WikiActivityAudit`（audit）。
- **拆分纪律**：A 波门面保全部 public 成员与上述类型不动；WikiIngestBatchHandler 为 wiki 内部驱动（无跨包消费者），可自由拆。

### 14.8 session 域步骤 1 边界判定 + wip 分支裁定（2026-09-30）

- **wip/chat-sse-slice2(785cdc7)裁定:不并入**。理由:①用了 @JsonAlias 兼容别名,与现行 §2 第 11 条"无别名"政策冲突;②仅完成切片②后端主体,自带约 40 个失败、前端未动;③当前分支已领先 59 提交,session 域多处被触碰,合并即冲突。**处置**:保留分支作工作清单参考(其提交说明是完整的键清单与任务分解),session C 波在当前分支按 wiki C 波同法重做(去注解无别名)。
- **不动**:session/domain 实体 jsonb 键在 C 波前保持 snake(§11 边界);chat SSE 信封(event/*Data)归 session C 波切片③(与前端同批);工具输出自有 schema 不动。
- **跨包缝合点(git grep 实测,15 文件)**:chatpipeline×6、embed×2、evaluation/im/memory×3、storage×2 消费 session 类型;session 出向依赖 retrieval.SearchResult(10)/event.EventBus/agent.AgentStep(5)/knowledge 服务族。
- **C 波工作清单(自 wip 提交说明整理)**:20 实体+2 落库类型约 200 处注解;SessionController 8 键+3 解封+4 去 success;SteerController 11 键;KnowledgeQaController 2 处;删除/清空类端点 204;43 个 session-*/sug-* fixture;前端 93 键约 200+ 处读取(与 SSE 信封同名,切片③同批)。

### 14.9b session 步骤 2 下一刀：TemporaryDocumentService 的 prompt 切片（2026-09-30 已实施，见 §11.15）

**状态：已实施**——照本文结论"逐成员人工复制到新文件、再从门面删"做的；实施中按**调用点**修正了
切片边界（`isImageFormat`/`readJsonArray` 双用留门面；`isIconImage`/`MIN_IMAGE_*`/`IMAGE_EXTENSIONS`
切片内零调用也留），见 §11.15。本文保留侦察表与坑清单作为历史（第一轮中止的教训仍适用）。

**结论：本批中止**（工装三次失手，见下"坑"）——现场已恢复，`git status` 干净。
下次做这一刀时**别用行区间脚本抽取**，改"逐成员人工复制到新文件、再从门面删"（成员少、肉眼可校）。

**切片边界（已按声明序核实，行号=当时的 TemporaryDocumentService.java）**：

| 组成 | 成员（行号） |
|---|---|
| 公开入口 | `resolveForPrompt`(737-803) |
| 专用静态助手 | `selectContent`(835-898)、`queryTerms`(898-921)、`parseChunks`(811-823)、`imageUrlsOf`(823-840)、`isVisualDocumentQuery`(927-938)、`countOccurrences`(938-948)、`isImageFormat`(712-726)、`isIconImage`(682-696)、`isHan`(923-927)、`intOf`(950-952)、`strOf`(954-958)、`readJsonArray`(994-1004) |
| 内部记录（随切片走） | `ContentSelection`(803-807)、`DocumentChunk`(807-809) |
| 提示词常量（随切片走） | `PROMPT_BUDGET_TOKENS`/`PROMPT_INLINE_TOKENS`/`MAX_PROMPT_PARTS`/`MAX_IMAGE_URLS`(70-82)、`MIN_IMAGE_DIMENSION`/`MIN_IMAGE_BYTES`(85-89)、`IMAGE_EXTENSIONS`(89-93)、`VISUAL_QUERY_MARKERS`(93-97) |
| **留在门面** | `PromptResult`、`AttachmentResolveException`（对外类型，§14.9 的 record-留门面先例）、其余全部 |

**设计**：新建同包 `TemporaryDocumentPromptResolver`（`final class`，持 `facade` + `repo` 两个引用，
同 `SessionQaResolution`/`SessionQaFallback` 先例）；门面留一个 `resolveForPrompt` 薄委托 +
`private final TemporaryDocumentPromptResolver promptResolver`（构造器里 new）。
门面同时含 `MAPPER`（process 路径也用），协作者自带一份 ObjectMapper。

**三个坑（下次直接绕开）**：
1. **别用花括号配对数 span**：该类正文里全是 JSON 字符串（`"{\"a\":1}"`），朴素计数会越界/截断；
   真要脚本化，先按字面量剥离（去 `"..."`/`'...'`/`//`/`/*...*/` 再数）。
2. **声明正则必须钉死 4 空格缩进**（`^ {4}(?! )`）：松写成 `^    [\w .]+` 会把方法体里的
   8 空格语句（`        String lang = ...;`）当成员声明，span 全乱。
3. **span 边界用"下一个成员的 javadoc 起点 -1"**，别用"下一个声明行 -1"——后者会把下一位的
   javadoc 吞进上一位，抽取后新文件重复、门面反而丢注释。

### 14.9c session 步骤 2 收尾批次清单（2026-09-30 定稿；3 类约 9 刀，SKQA 保持例外）

批次口径（用户要求"多个一起"）：**一次侦察定边界 → 按清单连续落刀，每刀自带编译自检 + 独立提交，
共用一次收尾闸门**；不混改、不合并提交——本会话三次真问题（char 字面量截断 / `JdbcTemplate` 顺序 /
测试里的静态成员引用）都靠"每刀编译"暴露，混改会让定位成本指数上升。

| 刀 | 类 | 切片 | 规模 |
|---|---|---|---|
| 1 | SessionAgentQaService 1,430 | 历史/消息装配簇（`loadAgentHistory` + `buildTurnBodyMessages` + `build*HistoryMessage` + `buildAgentStepMessages` + `finalAnswerHistoryMessage` + `filterNonT…` + `templateContentByIdAndFile`） | ~235 |
| 2 | 同 | 配置装配簇（`buildAgentConfig` + `resolveAgentPrompts` + `applyPerRequest*Scope` + `resolvePerRequestMcpScope` + `pinPreservingRequestOrder` + `agentRequiresRerankModel`） | ~280 |
| 3 | 同 | 引擎/工具装配簇（`createAgentEngine` + `registerMcpTools` + `knowledgeBaseScopesForPrompt` + `getKnowledgeBaseInfos` + `getSelectedDocumentInfos` + `registerWebPageFiles` + `registerTools`） | ~500 → 门面 ~350 出榜 |
| 4a | KnowledgeQaController 1,613 | ~~静态解析助手簇~~ **已落**（§11.27：1,614 → 1,525 + `QaRequestBinder` 105） | 114 |
| 4b | 同 | ~~`parseQARequest` 主体~~ **已落**（§11.28：1,525 → 1,296 + `QaRequestParser` 331；`AgentResolver`/租户作参数传入） | 234 |
| 5 | 同 | ~~SSE 编排簇~~ **已落**（§11.30：1,175 → 883 + `QaSseOrchestrator`；解法＝持有 `QaTurnFinalizer`） | 294 |
| 6a | 同 | ~~附件解析簇~~ **已落**（§11.31：874 → 691 + `QaAttachmentResolver` 221） | 169 |
| 6b | 同 | 执行编排/落库簇（`executeQA` 211 + `runFollowUp`/`recoverFailedFollowUp`/`executeQaInternal` + `persistTurnMessages`/`buildUserMessage`/`rollbackTurnMessages`/`rejectIfOtherAgentRunLive`；共享行为已解，见 §11.31） | 348 |
| 8 | SessionQaResolution 2,906 | 模型选择簇（`resolveChatModelId` + `findModel` + `selectChatModelId`） | ~380 |
| 9 | 同 | KB 范围簇（`resolveKnowledgeBasesFromAgent` + `kbSatisfiesAgentRequirements` + `findKnowledgeBase`/`findKb` + `resolveRetrievalTenantId` + `callerCanReadKb`） | ~480 |
| 10 | 同 | mention/tag 收敛簇（`MentionScope` + `resolveKnowledge…` + `restrictMentionsToAgentScope` + `restrictTagScopesToAgentScope`） | ~500 |
| 待评估 | 同 | 两个巨型方法**内部提取**（`buildSearchTargets` 684 / `applyAgentOverridesToChatManage` 424）——需先补契约测试，单独评估 | ~1,100 |

- **范围登记**：`SessionKnowledgeQaService` 1,036 保持 ≥800（§14.5 例外，类 javadoc 已注明三条入口流
  状态机）；要压它需单独开刀（拆入口流），风险最高，本批不做。
- 每刀收尾走 §14.4 常规档（重编 + 受影响域 + spotless ≈1 分钟）；批次末跑一次结构搬迁档（clean 全量）。

### 14.9 session 步骤 2 半程（2026-09-30）

- SessionKnowledgeQaService 1,764 → 门面(约 1,000,例外注明:三条入口流状态机)+ SessionQaResolution(解析簇:mention/tag 收敛、模型选择、租户判定、搜索目标、agent 提示词)+ SessionQaFallback(固定/模型兜底)。外部 seam(resolveRetrievalTenantId/resolveChatModelId/resolveKnowledgeBases/buildSearchTargets/findKnowledgeBase/isAgentMode)门面委托,SessionAgentQaService 等消费面零改动(89a4e44)。
- **余三神类**（已出榜：TemporaryDocumentService 1,075 → 860 §11.15 → **498** §11.20、AgentStreamBridge 853 → 697 §11.16、MessageService 1,028 → 510 §11.17、MessageSuggestionService 1,088 → 601 §11.18）:KnowledgeQaController 1,616(与 SKQA 是同一条 QA 流的 HTTP 面,拆法沿用)、AgentQaService 1,446、AgentToolBackends 1,266。
- 教训:切片协作者时 record(MentionScope/SearchTargetView)容易随 take 溢出/误限定——**record 一律留在门面**(测试与外部直引面),协作者经门面限定引用;声明行误加 service. 前缀的恢复统一按"4 空格缩进+修饰符开头"行匹配(勿对调用点盲替)。
