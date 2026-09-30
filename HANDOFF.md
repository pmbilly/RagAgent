# ragagent 交接文档（新仓起步）

> 本文档写给在 `~/ragagent` 打开的新会话/新成员。**一切背景以本文为准**；最近的执行细节在 `git log`。
> 种子：自 `~/ragagent-java` @ `646aba7`（2026-09-28）分叉，git 历史完整保留。
> **最近更新 2026-10-01**（session 域批次收官 + 本文重排为交接版）。

## ⭐ 接手须知（5 分钟版）

1. **先验证基线全绿**（三条命令见 §9；session 域单域验证：`./gradlew :server:test --tests "com.ragagent.session.*"`，当前 **388 条 / 失败 0**）。
2. **总目标**＝按 Java 标准提升可读性（§0）；**行为不变**是底线（测试是安全网）；已定决策见 §2（勿再讨论）。
3. **已完成**：P0/P1/P2/P3 包结构治理（§11.8~§11.14）；**session 域阶段 2 神类批次全收官**（§11 总览表）——
   `SessionAgentQaService` 1,430→**364**、`KnowledgeQaController` 1,614→**328**、`SessionQaResolution` 2,906→**706**，
   域内 ≥800 只剩 `SessionKnowledgeQaService` 1,036（§14.5 已登记例外）。
4. **下一步（已定）**：**`wiki` 域**阶段 2 —— 见 §14.7 执行计划（两个类：`WikiPageController` 1,311、`WikiIngestService` 1,208）。
5. **落刀方法论**：§13 是**必读**（判据 + harness 流水线 + 守卫口径 + 忠实性核验手法），
   harness 模板已入库：`scripts/refactor-harness.sh`。
6. **全仓存量**：≥800 行的类还有 **38 个**（清单见 §14.3）；批次顺序建议见 §14.7 末尾。

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

## 7. 当前状态（2026-10-01 复核）

### 7.1 已完成

| 阶段 | 范围 | 结果 |
|---|---|---|
| P0 包间解环 | 全仓包间环 / 依赖 config 包 | **环 0 组**、依赖 config 仅 1 包、L2→L3 6 条（方向合法，属阶段 4）——守卫 `python3 scripts/check-package-cycles.py` |
| P1 扁平包 / P2 分包 / P3 归位 | chatpipeline·event·wiki/service·knowledge/dto·embedding·rerank 等 | 全部完成（§11.11~§11.14、§11.10） |
| 阶段 2 神类切片（session 域） | AgentQaService / QaController / Resolution / 其余神类 | **全部出榜**（§11 总览表；15 个同包协作者；4 条契约测试） |

### 7.2 当前存量（实测）

- session 域：**100 文件 / 25,147 行**；最大类 `SessionKnowledgeQaService` 1,036（例外）→ 其后 `SessionController` 791 / `SessionQaResolution` 706 / `QaSearchTargets` 706 / `AgentStreamBridge` 696 / `SessionService` 650。
- 全仓 ≥800 行的类：**38 个**（清单与分域建议见 §14.3）。
- 测试：session 域 388 条 / 失败 0（本轮实测）；全量闸门命令见 §9。

### 7.3 未完成 / 待办

1. **阶段 2 其余域**：wiki（下一步，§14.7）→ im → retrieval（4 个 engine 形似，可做"适配器批"）→ knowledge/auth/llm/chatpipeline 的 1,000+ 类 → datasource/memory（体量大，单独立项）。
2. **阶段 3**（分层 / `package-info`）与**阶段 4**（契约 Java 化 / DTO·HTTP 面换锚）均未开始；换锚按 §14.2 排在阶段 3 之后，**同批带前端、不加兼容别名**（§2 第 4/11 条）。
3. 可选尾巴：`QaSearchTargets`（706）内部 4 块细分；`SessionKnowledgeQaService` 1,036 的 §14.5 例外复核。

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

## 11. 已完成批次总览（细节见 `git log` 与历史版本；判据与手法见 §13）

### 11.1 契约换锚 / 落库 de-Go / knowledge 目录整治（2026-09-29~30）
（原 §11、§11.1、§11.5~§11.7 压缩）agent 域 A/B 波 + approval 换锚；知识库域 Java 本位重构完成（§7.1 存档）；
分支与残留清理；agent/agentm §14.5 复验与卫生清零；顶层包归并决策。

### 11.2 P0~P3 包结构治理（2026-09-30）
配置/工具类归位（批 1）、端口化（批 2）、放错包/倒挂清零（P3）、chatpipeline+event 分包（P1）、
wiki/service 分包（P2）、knowledge/dto 分包（P2）、embedding/rerank 拆 provider（P1 收尾）。
**结果**：环 0 组 / 依赖 config 1 包 / L2→L3 6 条（守卫长期绿）。

### 11.3 session 域阶段 2 神类批次（2026-09-30~10-01，本批主体）

| # | 类 | 前 → 后 | 切片（协作者） | 记录 |
|---|---|---|---|---|
| 1 | `TemporaryDocumentService` | 1,075 → **498** | prompt 切片（`TemporaryDocumentPromptResolver` 271）+ 解析/落盘管线（`TemporaryDocumentProcessor` 423） | §11.15 / §11.20 |
| 2 | `AgentStreamBridge` | 856 → **697** | 先补 9 例契约测试（`AgentStreamBridgeTest`），再抽发射器 `AgentStreamEmitter` 94 | §11.16 |
| 3 | `MessageService` | 1,028 → **510** | 聊天历史检索簇（`MessageSearch` 596） | §11.17 |
| 4 | `MessageSuggestionService` | 1,088 → **601** | 无状态管道（`MessageSuggestionPipeline` 513） | §11.18 |
| 5 | `AgentToolBackends` | 1,264 → **590** | 知识库检索簇（538）+ wiki 簇 | §11.21 / §11.22 |
| 6 | `SessionAgentQaService` | 1,430 → **364** | 历史装配（`AgentHistoryAssembler` 268）→ 配置装配（`AgentConfigAssembler` 324）→ 引擎/工具装配（`AgentEngineAssembler` 585） | §11.23~§11.25 |
| 7 | `KnowledgeQaController` | 1,614 → **328** | 静态解析助手（`QaRequestBinder` 105）→ 解析主体（`QaRequestParser` 331）→ 收尾簇（`QaTurnFinalizer` 161）→ SSE 编排（`QaSseOrchestrator` ~370）→ 附件解析（`QaAttachmentResolver` ~165）→ 执行/落库（`QaTurnExecutor` 418） | §11.26~§11.32 |
| 8 | `SessionQaResolution` | 2,906 → **706** | 模型选择（`QaModelSelection` 420）→ KB 范围（`QaKbScope` 341）→ mention/tag 收敛（`QaMentionTagScope` 422）→ 巨型方法项目三步（`QaChatManageOverrides` 540 + `QaSearchTargets` 706） | §11.33~§11.39 |

**批次口径（可复用）**：一次侦察定边界 → 按清单连续落刀，**每刀自带编译自检 + 独立提交**，共用收尾闸门；
不混改、不合并提交。会话域该批的实测结论：**"先落被依赖方"是解决受阻刀的关键**（见 §13）。

### 11.4 阶段 2 批次清单的历史版本
原 §14.9b / §14.9 / §14.9c（session 批次清单与刀序）已全部执行完毕，内容并入上表；不再保留独立章节。

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

## 13. 落刀方法论（新 Agent 必读；全部是踩坑换来的）

### 13.1 切片的先后动作（固定套路）

1. **侦察**（只读，1~2 条命令）：成员清单与行区间 → **三类依赖扫描**（字段／方法／常量；字段要含 `@Autowired`）→
   调用点（簇内 vs 簇外）→ 外部引用与测试床 → 打印出来再动刀。
2. **判定切片边界**：**按调用点定，不按名字猜**（同名/近名工具常是双用 → 留门面并放宽包内可见）。
3. **写 harness 脚本**（模板：`scripts/refactor-harness.sh`）：脚本补丁 → 落刀 → **干跑断言** → 编译 → 测试 → spotless → 忠实性 → 文档 → 提交，
   **任一环失败自动回退**。命令 >8KB 会超平台限制 → 一律写成 `/tmp/runN.sh` 再执行。
4. **落刀后核验**：忠实性（搬走的成员体**逐字**比对，允许且仅允许登记过的替换反向归一）。
5. **回填文档**：`HANDOFF §11` 总览表加一行 + `§14.3` 计数刷新（都是**实测值**，不估算）。

### 13.2 依赖判据（决定"能不能搬"）

| 依赖形态 | 处理 |
|---|---|
| **共享值**（可参数化） | 传参：`AgentResolver`、`readerTenant`、`maxFileBytes()` |
| **共享静态**（方法/常量） | 留门面 → 放宽为包内可见 + 簇内按类名限定（`SessionQaResolution.xxx(`） |
| **共享行为**（实例方法，带字段态） | ① **随簇搬走**（首选）② **先落被依赖方**（把该成员先搬进协作者，再让依赖它的刀持有协作者）③ 调整刀序 |
| **`@Autowired` 字段** | 构造期未注入，普通协作者拿不到 → 改 **`ObjectProvider` 构造注入**（仓内既有样式，语义等价）或作参数传值 |
| **嵌套/公共类型** | 类型不能委托 → 留门面，放宽包内可见，簇内按 `外层类.类型` 引用 |
| **`record`** | 跨类搬运要把**字段访问**改成**访问器**（`r.x` → `r.x()`） |

### 13.3 对外契约的保法：**全量薄委托**

每个搬走的成员，在门面留一行委托（`return collaborator.member(args);`）→ 宿主与同族调用点**零改动**。
注意：static 成员委托要写 `QaXxx.member(...)`；类型/常量不能委托。

### 13.4 守卫必须"正向证据"（血泪）

- 编译：断言日志含 **`BUILD SUCCESSFUL`**；**不要**用"没出现 `error:`"当判据（shell 报错/`command not found` 会被漏掉，
  曾因此误推坏提交）。
- 测试：读 `server/build/test-results/test/TEST-*.xml` 的 **`failures + errors == 0` 且用例数达标**；
  `compileTestJava` 绿 ≠ 测试绿（曾把一条失败测试推上去）。
- harness 里 Gradle 一律**显式 `./gradlew`**（别用变量跨进程传，曾因 `$GW` 未导出导致命令静默失败）。

### 13.5 忠实性核验（机械搬运的"逐字比对"）

从门面旧版（备份）与协作者新文件各抽同名成员体 → 剥掉注释与全部前导修饰符（**循环剥**，只剥一层会出假差异）→ 空白归一 → 逐字比较。
**只允许登记过的替换**反向归一（限定名前缀、参数化、字段→访问器、重命名）。

### 13.6 脚本与正则的坑（都踩过）

- **点号 lookbehind 会骗人**：`(?<![\w.])name\(` 匹配不到 `field.name(` → 报"0 处"（假 0）。判"是否还有裸调用"要剥限定词或用 `(?<![\w])`。
- **`this.X(` 形态**要单独识别。
- **空白行折叠**会改行数（曾一次折叠掉 935 行）：干跑断言必须写明 `剩余 == 行数 − 删除集 + 新增`，差值必须为 0。
- heredoc 里写正则补丁极易双重转义（曾有 `unterminated subpattern`）→ 补丁用**免转义字符串替换**。
- 机械改写自伤两例：拼实参漏逗号、`replace("...) {", …)` 把参数名前缀留在签名里 → **长签名改完必看一次现场**。

### 13.7 搜索/检查纪律

- 依赖扫描**只用于预估**，**编译才是权威**；每刀准备"显式补充清单"（扫描三次漏掉 `stringListOf`/`waitForAttachments`/`templateContentByIdAndFile`）。
- 找不到 import 时，先怀疑"它是嵌套类型"（`grep "class X\b"` 直接搜声明）。
- 差 1~2 行的异常先别慌：**先解释异常再动刀**（本次两次"异常"分别是空白折叠与扫描顺序假象）。

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

### 14.3 候选域盘点（2026-10-01 实测：全仓 ≥800 行的类共 **38 个**）

| 域 | ≥800 的类（行数） |
|---|---|
| datasource | `DataSourceService` 1,828 · `FeishuClient` 1,154 · `NotionConnector` 1,092 |
| memory | `MemoryService` 1,662 · `MemoryRepository` 1,514 · `MemoryExtractionService` 1,216 |
| retrieval | `OpenSearchRetrieveRepository` 1,652 · `DorisRetrieveRepository` 1,265 · `HybridSearchService` 1,260 · `QdrantRetrieveRepository` 1,025 |
| wiki | `WikiPageController` 1,311 · `WikiIngestService` 1,208（**下一步**，见 §14.7） |
| im / llm / knowledge / auth / chatpipeline | `ImService` 1,445 · `RemoteApiChat` 1,366 · `FaqImportService` 1,235 · `AuthController` 1,167 · `PluginMerge` 1,155 |
| session | `SessionKnowledgeQaService` 1,036（§14.5 已登记例外）；**本域已清零**（§11.3） |

复测命令：`git ls-files 'server/src/main/java/**/*.java' | xargs wc -l | sort -rn | head -20`

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


### 14.8 wiki 域边界判定（2026-09-30 侦察，动手前先读）

- **不动**：`wiki/domain` 22 文件 173 处 `@JsonProperty`（§11 已登记的 wiki 域实体 snake 边界）；wiki 对前端契约整体（§2 第 4 条落地范围外，wiki 域 C 波另立切片）。
- **跨包缝合点（git grep 实测,11 文件）**：`service.WikiLanguageSupport`（agent PromptAssembly + knowledge×4 + session×2,消费最广）；`service.WikiIngestService`(+EnqueueResult)/`WikiKnowledgeFinalizer`/`DefaultWikiKnowledgeFinalizer`/`WikiImageMarkup`（knowledge 加工链）；`service.WikiPageService`/`WikiEditContext` + `domain.Wiki*`（session AgentToolBackends → agent wiki 工具,经 WikiPages seam 接口）；`controller.WikiActivityAudit`（audit）。
- **拆分纪律**：A 波门面保全部 public 成员与上述类型不动；WikiIngestBatchHandler 为 wiki 内部驱动（无跨包消费者），可自由拆。


### 14.7 wiki 域执行计划（下一步，2026-10-01 定；边界侦察见 §14.8）

**目标类**：`wiki/controller/WikiPageController` 1,311、`wiki/service/ingest/WikiIngestService` 1,208（域内 ≥800 就这两个）。
先读 `§14.1~§14.6` 与 `§13`，再照下面的刀序走（每刀独立提交 + harness 自动回退）。

1. **刀 0（侦察）**：按 §13.1 第 1 步扫两个类 —— 成员清单/行区间 + 三类依赖（字段含 `@Autowired`）+ 调用点 + 测试床；
   把边界写进新 §14.7.x 小节（含"共享值 vs 共享行为"清单），**再动刀**。
2. **WikiPageController（参照 QaController 的六种切法，逐刀一簇）**：
   - 静态解析/绑定助手（对应 `QaRequestBinder`）→ 请求解析主体（对应 `QaRequestParser`）→ SSE/收尾（对应 `QaSseOrchestrator`/`QaTurnFinalizer`）
     → 执行/落库（对应 `QaTurnExecutor`）→ 附件/导出等专用簇；
   - 控制器域的统一口径：**薄重载探针**（对外薄方法放宽包内可见 + 门面全量薄委托）。
3. **WikiIngestService**：先找"无字段依赖的无状态簇"（prompt/解析/切片类）→ 再找"写面"（索引/落库）→
   最后处理与外部服务的交互簇；形如 `buildSearchTargets` 的巨型方法按**整体搬入协作者**先出榜，内部 4 块细分留后。
4. **收尾**：`§14.3` 计数刷新（实测）+ 域内测试全绿 + 环守卫 + 更新本节的"已落刀"记录。
5. **之后批次顺序（建议）**：`im`（单类 1,445，好收官）→ `retrieval` 的 4 个 engine（形似，做"适配器批"，一轮可重复）
   → `knowledge` / `auth` / `llm` / `chatpipeline` 的 1,000+ 类 → `datasource` / `memory`（体量最大，单独立项）。
