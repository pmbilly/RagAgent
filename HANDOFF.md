# ragagent 交接文档（新仓起步）

> 本文档写给在 `~/ragagent` 打开的新会话/新成员。**一切背景以本文为准**；最近的执行细节在 `git log`。
> 种子：自 `~/ragagent-java` @ `646aba7`（2026-09-28）分叉，git 历史完整保留。
> **最近更新 2026-10-02（晚）**（§15 全面修复计划执行中：B1/B2/B3/B4/B7/B8 六批完成——
> GoldenContract 对比器统一、契约文档 v1.1、恒输出化真面 68 处、死成员 19 处、FQ 注解清零、
> 零值哨兵判不改；两轮前端对齐债修复 + /tenants/search 等漏翻译端点补齐。**下一刀 B0 端到端走查**，
> 批次表与纪律见 §15）。

## ⭐ 接手须知（5 分钟版）

## ⭐ 接手须知（5 分钟版）

1. **先验证基线全绿**（三条命令见 §9；session 域单域验证：`./gradlew :server:test --tests "com.ragagent.session.*"`，当前 **388 条 / 失败 0**）。
2. **总目标**＝按 Java 标准提升可读性（§0）；**行为不变**是底线（测试是安全网）；已定决策见 §2（勿再讨论）。
3. **已完成**：P0/P1/P2/P3 包结构治理（§11.8~§11.14）；**session 域阶段 2 神类批次全收官**（§11 总览表）——
   `SessionAgentQaService` 1,430→**364**、`KnowledgeQaController` 1,614→**328**、`SessionQaResolution` 2,906→**706**，
   域内 ≥800 只剩 `SessionKnowledgeQaService` 1,036（§14.5 已登记例外）；
   **wiki 域阶段 2 已收官（2026-10-01，10 刀）**——`WikiPageController` 1,311→**272**、`WikiIngestService` 1,208→**509**（§14.7.2）；
   **im 域已收官（2026-10-01，5 刀）**——`ImService` 1,445→**664**（§14.7.4）；
   **retrieval 适配器批已收官（2026-10-01，9 仓 20 刀）**——sqlite/qdrant/milvus/tencentvectordb/weaviate/es8/es7/opensearch/doris 全部出榜（§14.7.5）；**HybridSearchService 已出榜（3 刀，1,260→775，§14.7.6）——检索域清零**。**AuthController 已出榜（3 刀，1,167→704，§14.7.7）——auth controller 清零**。**FaqImportService 已出榜（2 刀 F1/F2，1,235→583+419，knowledge 例外解除）**；
   **wiki page 面已收官（2026-10-01，w1~w5 + 两个卫生刀，§14.7.14）——`WikiPageServiceImpl` 1,008→732、`WikiPageRepository` 858→708、`WikiIngestDedupService` 851→468（例外解除）、`WikiPageFolderSupport` 822→383（切片产物空行折叠）——wiki 域 ≥800 清零**；
   **DataSourceService 已出榜（2026-10-01，d1~d3，§14.7.15）——1,828→666，四协作者**；
   **datasource 连接器批已收官（2026-10-01，f1~f3 + n1，§14.7.16）——`FeishuClient` 1,155→709、`NotionConnector` 1,093→625，datasource 域 ≥800 清零**；
   **memory 域四刀落定（2026-10-01，m1~m4，§14.7.17）——`MemoryExtractionService` 1,217→718、`MemoryRepository` 1,515→458、`MemoryService` 1,663→965（余 m5 评估）**；
   **memory 域契约换锚 M1 完成（2026-10-01，§14.9k）——20 端点去信封 + 6 响应实体 camelCase + 创建 201/删除 204 + 分页形态对齐；前端 8 文件同批（含 A2 漏改的 KV 解包）；真实服务冒烟 21 路通过**；
   **memory 域契约换锚 M2 完成（2026-10-01，§14.9k）——7 个落库/内部实体 + `MemoryConfig` 去注解 60 处（memory 域 @JsonProperty 137→22，余者为 LLM 载荷并登记保留）；`tenants.memory_config` 与 `memory_subjects.extraction_state` 两处 jsonb 已跑存量迁移 SQL；前端 2 文件同批；真实服务冒烟 11 路通过**；
   **memory 域 M3 收尾完成（2026-10-01，§14.9k）——请求侧手写 `rawBody+parse()` 全部退役（三个 DTO 进 `memory/dto` + `@Valid`），错误形态统一到全局处理器；LLM 载荷 22 处登记保留；真实服务冒烟 12 路通过**；
   **memory 域 m5 切片完成（2026-10-01，§14.7.17）——`MemoryService` 965→**768**（出榜），「召回」段外提 `MemoryRecallOps` 252 行；忠实性逐字核验通过；memory 域 ≥800 仅剩 `MemoryIndexStore` 929（已登记例外）**；
   **session 域 S1 完成（2026-10-01，§14.9l）——会话主资源全换锚**：实体面（`Session`/`SessionListItem`/
   `SessionLastRequestState`/`MentionedItem` 换 camelCase + 恒输出，`is_pinned`→**`pinned`**）+
   控制器面（请求体标准 DTO、列表 `{items,page,pageSize,total}`、置顶 `{pinned}`、产物裸数组、
   生成标题 `{title}`、删除类与停止 **204**、查询参数 `pageSize`/`agentId`），两处 jsonb 迁移 SQL 已备
   （dev 库 0 行需迁移）；**session 域 S1~S5 全部完成（S2/S3/S4 前后端同批，S5 后端面）——`@JsonProperty` 188 → 0**；
   **阶段 3 打样已跑通（2026-10-01，evaluation 域，§14.9b）——去信封 + camelCase + 标准 DTO 绑定，真实服务冒烟 8 路通过**；
   **阶段 3 第二域 model 全域收官（2026-10-01，§14.9c/§14.9e）——主资源 + debug + weknoracloud + 落库 jsonb 四块换锚，`@JsonProperty` 87→0，前端 15 文件同批（首次前后端同 PR）**；
   **阶段 3 其余域全部收官（2026-10-01~10-02）**：system（§14.9f/g）/ auth（§14.9h/i/j）/ memory（§14.9k）/
   mcp（§14.9n/o/p，余 22 处第三方协议面冻结）/ session（§14.9l，S1~S5，`@JsonProperty` 188→0）/
   embed（§14.9m）/ datasource（§14.9q，D1~D3，余 350 处 connector 线格式 + 5 处 `lf_*` 冻结）；
   **wiki ingest 载荷 W1 收官（2026-10-02，§14.9r）**；
   **契约尾巴全清 + 两个真单类出榜（2026-10-02，§14.9s，15 个提交）**——全局错误体去 `success:false` +
   622 个错误金片重录；散存量七域批（audit/favorite/im/storage/vectorstore/auth 补刀/agentm·init）+
   M6（mcp `@JsonInclude` 恒输出）；`SourceRegistry` 878→534、`UserService` 876→748 切片出榜；
   4 个登记例外复核结论入册（§14.3）。**至此 ⭐ 第 4 条所列全部剩余工作完成：
   复查批后全仓 `@JsonProperty` 余量 913 处全部是登记冻结面（§14.6）**。
4. **下一步（2026-10-02 更新）**：§15 计划已完成 **B0 端到端真实走查（P0，2026-10-02 收官，修 15 处断点）/
   B1 契约文档 v1.1 / B2 金片对比器统一（GoldenContract 上线，字节级对比清零）/ B3 `@JsonInclude` 恒输出化
   （真面 68 处；yunzhijia 第三方回退）/ **B3b KB 配置 jsonb 键名统一（camelCase + V2 存量迁移，2026-10-02 收官）** /
   B4 零值哨兵（结论：不改，已知例外）/ B7 死成员 19 处 / B8 FQ 注解 177→0**（✅ 记录见 §15.1 各行与 §15.1.1）。
   **剩余待做**：B5 lf_* 评估（判定后可搁置）/ **B6 getenv 收敛（🚧 批 1 storage 装配 + 批 2 langfuse + 批 3 检索驱动已完成，余 81 处按 §15.1.1 的 A/B 类清单继续）** /
   B9 Go 锚点随批 / B10 ArchUnit 进 CI / B11 多模块（最后做）。**B0 登记残留见 §15.1.1**（B3b′ 已修；其余 4 项含 `process_overrides` 写了不用、
   wiki 死信槽位无人释放、孤儿 wiki op 不重放、存储引擎设置孤儿组件）。
   **新会话接手**：直接读 §15.1 批次表（状态列）+ §15.1.1 执行记录 + §15.2 纪律五条（开工前必读）+
   §15.3 非目标冻结清单；做完一批把 ✅ 与记录写回 §15.1。
5. **落刀方法论**：§13 是**必读**（判据 + harness 流水线 + 守卫口径 + 忠实性核验手法），
   harness 模板已入库：`scripts/refactor-harness.sh`；切片产物排版闸门：`scripts/normalize-blank-lines.py`（§13.8）。
6. **全仓存量**：≥800 行的类只剩 **4 个登记例外**（§14.3，真单类已清零）；**检索 / auth controller / wiki / im / mcp / embed / chatpipeline / datasource / evaluation 域 ≥800 全为零**，memory 域已出榜 3/3（2026-10-01 m5 后仅剩 `MemoryIndexStore` 929，登记例外）。

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
- 后端测试：**4,681 用例全绿**（含 6 个 skip，2026-09-30 复核；2026-10-01 model 域收官批实测 **4,670 / 失败 0 / 跳过 4**，434 测试类）；前端 `vue-tsc` 0 错误 + **690 用例全绿**（§9 有命令）。
- **≥800 行的类（main，全仓）**：AgentEngine 3,235、WikiIngestBatchHandler 2,268、WikiIngestService 2,182、InitializationController 1,981、DataSourceService 1,827、SessionKnowledgeQaService 1,764、MemoryService 1,660、OpenSearchRetrieveRepository 1,652、WikiPageServiceImpl 1,642、KnowledgeQaController 1,616……
  （**2026-09-30 历史快照，多数已过时**——活榜单以 §14.3 为准：2026-10-01 实测 36 个）
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
| 阶段 2 神类切片（wiki 域，2026-10-01） | WikiPageController / WikiIngestService | **全部出榜**（10 刀 → 10 个包内协作者；1,311→272、1,208→509；§14.7.2） |
| 阶段 2 神类切片（im 域，2026-10-01） | ImService | **出榜**（5 刀 → 5 个包内协作者；1,445→664；§14.7.4） |
| 阶段 2 适配器批（retrieval，2026-10-01） | 9 个引擎仓 | **全部出榜**（sqlite 2 刀 + 6 仓 12 刀 + 尾仓 opensearch 3 刀/doris 3 刀，23 个协作者；≥800 引擎清零；§14.7.5） |
| 阶段 2 收官（HybridSearchService，2026-10-01） | 非引擎族检索编排 | **出榜**（3 刀 → FusionOps/ResultOps/StoreGroupOps；1,260→775；检索域清零；§14.7.6） |
| 阶段 2（AuthController，2026-10-01） | auth 域控制器 | **出榜**（3 刀 → OidcOps/SessionOps/BindingSupport；1,167→704；auth controller 清零；§14.7.7） |
| 阶段 2（llm RemoteApiChat，2026-10-01） | llm 域聊天客户端 | **出榜**（5 刀 → RequestOps/BodyCodec/HttpOps/StreamOps/ResponseOps；1,366→513；llm 清零；§14.7.8） |
| 阶段 2（TenantCatalogController，2026-10-01） | auth 域租户目录/KV 配置控制器 | **出榜**（4 刀 → BindSupport/CreateOps/CrudOps/ConfigOps；980→133；§14.7.9） |
| 阶段 2（mcp 域双类，2026-10-01） | McpServiceController + OAuthHandler | **出榜，mcp 清零**（4 刀 → UsageInstructionsOps/CrudOps/Discovery/TokenOps；937→372、825→220；§14.7.10） |
| 阶段 2（FeishuAdapter，2026-10-01） | im 域飞书适配器 | **出榜，im 清零**（4 刀 → CallbackOps/SendOps/CardStreamOps/MediaOps；926→331；§14.7.11） |
| 阶段 2（EmbedChannelController，2026-10-01） | embed 域渠道控制器 | **出榜，embed 清零**（3 刀 → MgmtOps/PublicOps/DelegateOps；925→561；§14.7.12） |
| 阶段 2（PluginSearch，2026-10-01） | chatpipeline 检索插件 | **出榜，chatpipeline 清零**（3 刀 → QueryTextOps/ExpansionOps/SearchOps；899→278；§14.7.13） |
| 阶段 2（wiki page 面，2026-10-01） | WikiPageServiceImpl / WikiPageRepository / WikiIngestDedupService / WikiPageFolderSupport | **出榜，wiki 域 ≥800 清零**（w1~w5 + 两个卫生刀 → RevisionOps/LinkOps/FolderRepository/IdentityDedup；1,008→732、858→708、851→468、822→383；§14.7.14） |
| 阶段 2（DataSourceService，2026-10-01） | datasource 域最长类 | **出榜**（d1~d3 → ResultOps/Support/ItemOps/SyncExecutor 四协作者；1,828→666，-63.6%；§14.7.15） |
| 阶段 2（datasource 连接器批，2026-10-01） | FeishuClient + NotionConnector | **双出榜，datasource 域 ≥800 清零**（f1~f3 + n1 → Transport/WikiTreeOps/DriveOps/FetchOps；1,155→709、1,093→625；§14.7.16） |
| 阶段 2（memory 域，2026-10-01 起） | MemoryExtractionService / MemoryRepository / MemoryService | **四刀落定**（m1 1,217→718 出榜；m2 1,515→1,200 出 `MemoryItemStore`；m3 1,200→**458** 出 `MemoryIndexStore`；m4 1,663→**965** 出 `MemoryCatalogOps`+`MemoryInsightOps`；⚠️ `MemoryIndexStore` 929 登记**已知例外**、`MemoryService` 965 待 m5 评估——用户 2026-10-01 定调「不硬切」，判据见 §14.7.17） |
| 阶段 3 打样（evaluation 域，2026-10-01） | 小域契约换锚打样（§14.9b） | **流水线跑通**：POST/GET 去信封 + camelCase + 标准 DTO 绑定；10 个 fixture（含新增空体用例）；前端零调用面；**真实服务冒烟 8 路通过** |
| 阶段 3（model 域，2026-10-01） | 模型域契约换锚四块（§14.9c + §14.9e） | **收官**：主资源 + debug + weknoracloud + 落库 jsonb 全部换锚；`@JsonProperty` **87→0**；**前端 15 文件同批**（首次前后端同 PR）；真实服务冒烟 11 路（§14.9c） |
| 阶段 3（system 域 S1，2026-10-01） | /system 读端与探测端 7 端点（§14.9f） | **完成**：去 `code/data/msg` 信封 + camelCase + 错误语义化（503/403/400）；`SystemDtos` 85 处清零（99→14）；前端 10 文件同批；契约 19/19 绿 |
| 阶段 3（system 域 S2/S3/S4，2026-10-01） | /system/admin 全部端点 + SystemSetting（§14.9g） | **收官**：账号面/平台密钥/设置/runtime+配额四组换锚（rawBody→DTO、PlainError→AppError、动作 204）；`@JsonProperty` 14→**0**；前端 5 文件同批；auth 域波及 fixture 4 个同批 |
| 阶段 3（auth 域 A1，2026-10-01） | 登录/会话/用户信息面 + User/Tenant/UserPreferences（§14.9h） | **完成**：成功裸 DTO / 失败 AppError / 动作 204；`@JsonProperty` 370→约 250（余 A2 租户成员邀请 + B apikey + 边界 tenantconfig 130）；前端 19 文件同批；**共享实体链条**牵动 4 测试类 ~50 fixture |
| 阶段 3（auth 域 A2，2026-10-01） | 租户/成员/邀请/配置面（§14.9i） | **完成**：成员/邀请列表去信封、租户 CRUD 裸 DTO + 删除 204、KV 配置裸对象、动作 204、三个手搓封装辅助删除；前端 20 文件同批；183 用例绿 |
| 阶段 3（auth 域 B，2026-10-01） | API 密钥面（§14.9j） | **收官**：4 文件去注解 + 四端点去信封/204 + 请求体 camelCase；波及平台密钥与 4 个外域测试；前端 5 文件同批；**auth 域 @JsonProperty 仅余边界** |
| 阶段 3（memory 域 M1，2026-10-01） | HTTP 响应面 16 路由（§14.9k） | **完成**（前三次尝试回滚后第四次成功）：6 响应实体去注解 + 去信封/camelCase + 创建 201 / 删除类 204 + 分页 `{items,page,pageSize,total}`；fixture JSON 解析改写 + Java 断言逐处手改；前端 8 文件同批（含修 A2 漏改的 KV 解包）；全域 339 用例绿 / 全量 4670 绿；**真实服务冒烟 21 路通过** |
| 阶段 3（memory 域 M2，2026-10-01） | 落库/内部 JSON 面（§14.9k） | **完成**：7 个落库实体 + `MemoryConfig` 去注解 60 处 → **memory 域 @JsonProperty 137→22**（余者 LLM 载荷，登记保留）；**真落库的两处 jsonb**（`memory_subjects.extraction_state`、`tenants.memory_config`）跑存量迁移 SQL；顺手修 `ModelService` 按旧键读 memory config 的运行时依赖 + KV 校验文案改 camelCase；前端 2 文件同批；全量 4670 绿；**真实服务冒烟 11 路通过（含迁移后存量行读回）** |
| 阶段 3（memory 域 M3，2026-10-01） | 请求侧绑定收尾（§14.9k） | **完成**：三个请求 DTO 进 `memory/dto` + `@Valid`，手写 `rawBody+parse()`/`MAPPER` 退役；错误形态统一到全局处理器（空体/null→请求体不能为空、畸形→请求体格式不正确、类型错→`<字段>: 类型不正确`、缺 enabled→`enabled: 不能为空`）；LLM 载荷 22 处**登记保留**；新增 5 条测试 + 2 个夹具、清 1 个孤儿夹具；**真实服务冒烟 12 路通过** |
| 阶段 2（memory 域 m5，2026-10-01） | 召回段外提（§14.7.17） | **完成**：`MemoryService` 965→**768**（出榜）、新协作者 `MemoryRecallOps` 252 行；忠实性逐字核验通过；memory 域 ≥800 只剩 `MemoryIndexStore` 929（登记例外）；跨域回归（memory/chatpipeline/session）+ 全量 4673 绿；**真实服务冒烟 7 路通过** |

### 7.2 当前存量（实测）

- session 域：**100 文件 / 22,734 行**（空行折叠后）；最大类 `SessionKnowledgeQaService` 1,036（例外）→ 其后 `SessionController` 791 / `AgentStreamBridge` 696 / `SessionService` 650。
- wiki 域（2026-10-01 page 面批次后）：**148 文件 / 22,964 行**；**≥800 = 0**（最大 `WikiIngestCitePipeline` 760，其后 `WikiPageServiceImpl` 732 / `WikiPageRepository` 708）。
- llm 域（2026-10-01 批次后）：chat 包 `RemoteApiChat` 家族 6 类全部 <800（最大 `RemoteApiStreamOps` 365）。
- auth 域 controller（2026-10-01 批次后）：`TenantCatalogController` 980→133，包内最大 `TenantInvitationController` 519。
- mcp 域（2026-10-01 批次后）：controller/oauth 两神类出榜，包内最大 `OAuthDiscovery` 439。
- im 域（2026-10-01 批次后）：`FeishuAdapter` 926→331，feishu 包最大协作者 `FeishuCardStreamOps` 256。
- embed 域（2026-10-01 批次后）：`EmbedChannelController` 925→561，controller 包最大协作者 `EmbedChannelDelegateOps` 279。
- chatpipeline 域（2026-10-01 批次后）：plugin 包 24 类全部 <800（最大 `PluginRerank` 686）。
- datasource 域（2026-10-01 全批后）：**115 文件 / 26,723 行**；`DataSourceService` 1,828→**666**、`FeishuClient` 1,155→**709**、`NotionConnector` 1,093→**625** —— **域内 ≥800 清零**（原三个：1,828 / 1,155 / 1,093）。
- memory 域（2026-10-01 m1~m5 + M1~M3 换锚后）：`MemoryExtractionService` 1,217→**718**（出榜）、`MemoryRepository` 1,515→**458**（出榜）、`MemoryService` 1,663→965→**768**（出榜，m5）；`MemoryItemStore` 462 / `MemoryCatalogOps` 517 / `MemoryInsightOps` 412 / `MemoryRecallOps` 252 / `MemoryIndexStore` 929；**≥800 只剩 `MemoryIndexStore` 929**（**登记例外**：同属索引侧一个关注点，§14.7.17）。
  **`@JsonProperty` 137→22**（HTTP 面 63 处 + 落库/内部面 52 处清零）：余 22 处**全是 LLM 载荷**
  （`MemoryExtractionLlm` 11 + `MemoryExtractPayload` 11，**保留**：模型输出 schema，§14.9k 三分法）；
  `common.settings.MemoryConfig` 12 处已清零（tenants.memory_config jsonb 换锚，M2）。
- evaluation 域（2026-10-01 打样后）：**`@JsonProperty` 66→0、`@JsonInclude` 12→0**；POST/GET 两端点契约已换锚（§14.9b）；`dto` 包 2 文件（`EvaluationDtos` 容器待拆分，另立批次）。
- model 域（2026-10-01 收官）：**`@JsonProperty` 87→0**、`@JsonInclude`/`Go*` 序列化引用清零；主资源（M1）+ debug（M2）+ weknoracloud（M3）+ 落库 jsonb 四块全部换锚（§14.9c/§14.9e）；前端 15 文件同批改；dev 库旧 jsonb 行已用迁移 SQL 改写。
- system 域（2026-10-01 收官）：**`@JsonProperty` 99→0**（S1 的 `SystemDtos` 85 + S3 的 `SystemSetting` 14）；`/system` 7 端点 + `/system/admin` 全部端点 + settings 实体均已换锚（§14.9f/§14.9g）；前端 16 文件同批；权威细节见 §14.9g（含"审计 details 有意保留"清单）。
- 全仓 ≥800 行的类：**7 个**（清单与分域建议见 §14.3）。
- 测试：session 域 388 条 / wiki 域 542 条，失败 0（本轮实测）；全量闸门命令见 §9。

### 7.3 未完成 / 待办

1. **阶段 2 其余域**：wiki（含 page 面）/ im / retrieval / auth controller / llm / mcp / embed / chatpipeline / knowledge(FaqImportService) **均已出榜**；
   `datasource` **全域出榜**（service §14.7.15；两个连接器 §14.7.16）——域内 ≥800 清零；
   memory 域**两条线都已走完**（切片 m1~m5 + 换锚 M1~M3，≥800 只剩登记例外 `MemoryIndexStore` 929）；
   单类：modelcontext(`SourceRegistry` 878)、auth service(`UserService` 876)、knowledge 例外 2 个；
   另登记：wiki 域 "原 ORM / 原实现" 措辞 19 文件（约 50 处，独立卫生批）、datasource 域 Go 锚点（`对照 Go` 多处，
   随连接器批清）、`SessionKnowledgeQaService` 1,036 例外复核。
2. **阶段 3 契约换锚**：**部分已执行** —— knowledge / retrieval / chunker-preview / evaluation / model / system /
   auth（A1+A2+B）/ **memory M1+M2+M3** / **session 全域收官（S1 会话主资源 → S2 消息面 → S3 附件·建议·steer → S4 QA 请求面 → S5 收尾，前四批前后端同批）** / **embed 域 E1（渠道管理 + 公开面，前后端同批）** / **mcp 域 M1（服务资源 + 凭据面）+ M4（工具审批 + OAuth 用户面，均前后端同批）** 已完成（同批带前端）；
   **mcp 域已收官（M1 + M4 + M5，仅剩第三方协议面 22 处永久冻结）**。
   **datasource 域已收官（D1 + D2 + D3，仅剩 connector 第三方线格式与 `lf_*` 共享载具，均冻结）**。
   下一步候选：**wiki（54）/ auth 余面（138）/ retrieval（21）/ agent（15）/
   datasource connector 面（若将来要改对方 API 版本）**（§2 第 4 条落地范围）。
   硬约束：**序列化层删除必须一次性全仓完成**，半删状态最危险（§5 阶段 3）；时机由用户定，可与阶段 2 对调。
   **入场前先做**：§14.9 的"端点 × 前端"清单盘点。
3. **阶段 4 其余域标准化 + 架构调整**（Gradle 多模块 + ArchUnit 边界固化等）：未开始。
4. **编号对照（防混淆）**：§5 用**阶段 0-4**；§14.2 用**步骤 0-4**（单域 SOP）。神类切片属「阶段 2 / 步骤 2」，
   契约换锚属「阶段 3 / 步骤 4」——两套编号并存，引用时写全称。
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

- 434 个测试类 / 1,366 契约 fixture（**4,670 用例 / 4 skip**，2026-10-01 实测）是重构回归网，**每一步（哪怕纯移动）结束都必须全绿**——近两轮的工作方式就是"改一步 → 全量验证 → 提交"。这是"种子 fork + 渐进转型"优于重写的全部意义。
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

> **编号说明**：文中与提交信息里可见的 `§13.9`~`§13.28` 是 §13 改写前的历史条目编号
> （判据已并入 13.1~13.7 与 §14.5）；本仓当前编号到 **13.8** 为止，新条目从 13.9 继续。

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

### 13.8 切片产物的排版与源码卫生（2026-10-01 wiki page 面批次，3 条）

1. **切片产物"每行后跟一个空行"的排版 artifact**（wiki/session 两批共 14 文件、空白行占比
   55%~78%，仓库中位数 13%）：文件被撑大一倍以上，`wc -l` 榜单随之失真——`WikiPageFolderSupport`
   表面 822 行、实际 383 行；`SessionQaResolution` 707→184。**落刀写盘一律单空行**；发现后
   用 `scripts/normalize-blank-lines.py` 折叠（只删空行 + "非空行逐一相同"断言；口径：>100 行且
   空白率 >30%）。**推广**：任何 `wc -l`/行数榜单先看空白率，别把排版当规模。
2. **源码里的裸 NUL 字节**：字符串字面量内嵌真实 NUL（`"...\x00..."`）会让 **git 把文件当二进制**
   （`Bin 5600 -> 5443 bytes`，`git grep` 只回 "Binary file matches"）→ diff/blame 全不可读。
   写成转义 `"\0"`（值不变）即可恢复文本。**判据**：`file <f>` 报 `data` / diff 报 `Bin` 就是它。
3. **死方法的判别不能信 javadoc 自述**：`assertNoFolderConflict` 的 javadoc 写着"供 service 在
   创建前显式判定冲突时抛错"，但全仓 `git grep` 0 调用方（service 侧真正用的是 `folderNameExists`）
   ——属未接线遗留，删。**删前两种写法都扫**：`<name>(` 与 `.<name>(`（含 `Type.name(`）。
   另一条连带经验：拆仓储时若某方法与目标聚合共用 mapper（`listDistinctCategoryPaths` 走
   `WikiFolderMapper`），**按数据归属判**（它是文件夹路径查询）→ 随该聚合走，别为了让新仓储
   少背一个 mapper 而留下它。

### 13.9 闸门命令的环境卫生（2026-10-01 evaluation 打样批，1 条）

**source .env 的 shell 会把 `SYSTEM_AES_KEY` 等变量泄漏给同 shell 里跑的 Gradle 测试**：
CLI 起服务常写 `set -a && . ./.env && set +a && ./gradlew :server:bootRun`；Agent 工具链会
**复用同一 shell**，后续在同一会话里跑的全量测试就带上了这些变量——`DataSourceConfig.toJSON()`
走 AES 加密分支，`DataSourceJsonTest.dataSourceConfigToJsonMatchesGoMarshal` 的"无 KEY 时凭据
原样落库"断言失败（**1/4669，其余全绿，极易误判为回归**）。
**判据**：全量里出现"孤零零 1 个与环境相关的失败"时，先 `env | grep SYSTEM_AES`；
**修法**：跑闸门用 `env -u SYSTEM_AES_KEY ./gradlew ...`（或换独立 shell 复跑该单类确认）。

### 13.10 校验文案的 locale / Accept-Language 漂移（2026-10-01 model 域换锚，1 条）

**`@Valid` 默认 message 的 400 文案随请求头/进程 locale 变化**：同一条缺失字段的请求，
不带 `Accept-Language` 时 details = `name: 不得为空白`（Hibernate Validator 中文资源包原样），
带 `Accept-Language: en` / `zh-CN` 时 details = `name: 不能为空`（英文资源包经全局
`translateMessage` 归一）——**同一个二进制、两种文案**。
**根因**：契约测试（MockMvc 不带该头）只能钉住其中一种，真实匿名客户端与浏览器看到的可能不同。
**修法（随批次）**：`@Valid` 注解显式写 `message = "字段名: 不能为空"`——`^[a-z][A-Za-z0-9_]*: `
前缀格式被全局 `handleBind` 原样采用，不依赖任何资源包（model 域 2026-10-01 已示范）。
**全仓现状**：knowledge/session 等域的旧注解仍用默认 message，属"错误形态统一批"存量，逐域顺手改。

### 13.11 前端测试运行时不解析 `@/` 别名（2026-10-01 session S2 前端批，1 条）

**给被 `node:test`（`tsx --test`）直接加载的模块加 `@/...` 的"值导入"会让整个测试文件在加载期崩掉**
（`ERR_MODULE_NOT_FOUND: Cannot find package '@/types'`）——报错停在测试文件那一层，看起来像用例失败，
其实是模块解析失败。`paths` 只在 `tsconfig.app.json` 里，而根 `tsconfig.json` 没有，tsx 以根为准。
**判据**：某测试文件"整档失败"（`# Subtest: src/xxx.test.ts` 直接 not ok、无具体断言）时先看这条。
**修法**：这类模块用相对导入（`../types/mention`）；`import type { X } from '@/...'` 是安全的（类型导入被擦除）。
App 侧（Vite / vue-tsc）不受影响。

### 13.12 契约夹具批量重录（换锚批的省力工具，2026-10-01 embed E1 起可用）

换锚一次会改几十个 golden（去信封 / 键改名 / 键恒输出）。手改慢且容易漏，从 embed E1 起提供开关：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :server:test --tests "com.ragagent.embed.EmbedContractTest" -Dcontract.refresh=true
```

命中时把**掩码后的实际响应**写回 `src/test/resources/contracts/`（`server/build.gradle.kts` 负责把该
属性转发给 fork 出的测试 JVM——Gradle 的 `-D` 只作用于 daemon，不转发测试读不到）。
目前 `EmbedContractTest` 已接入；其它契约测试按需照搬 `REFRESH_FIXTURES` 那三行。

**纪律（重要）**：重录之后**必须结构化复核差异**（解析新旧 JSON、比键集与取值），确认差异只是
本次换锚该有的那几类；否则就成了"测试适应实现"，夹具失去契约价值。复核脚本思路见 §14.9m。

### 13.13 契约夹具的掩码按键名匹配——键改名必须同步放宽正则（2026-10-01，三次实录）

**夹具掩码用的正则形如 `"([a-z_]+)":"<uuid>"`（按键名锚定）**：换锚（下划线 → camelCase）之后，
`knowledgeBaseId`/`dataSourceId`/`createdAt` 这类键不再匹配 → **掩码静默失效**，
夹具里混进真实 UUID 与**逐次变化的真实时间戳**（下次跑就红，看起来像业务回归）。
**判据**：一批换锚后先看"重录的夹具里还有没有未掩的 id/时间戳"，再跑第二遍确认确定性。
**修法**：把键名字符集放宽成 `[A-Za-z_]+`（含大写），并在掩码定义处留注释。
**三次实录**：S3 `att-get` 的 sessionId（掩码未覆盖，夹具混进真实会话 id）→ M4 `McpContractTest`
（加 `serviceId`）→ D1 `DataSourceHttpContractTest`（`[a-z_]+` → `[A-Za-z_]+`，一次影响 12 个夹具）。

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
| 6 卫生 | 注释判据与 import 卫生（§13.8）/ 坏 `{@link}` / 批次代号清除 | `spotlessApply` 是标准手段，别自己写替换脚本 |
| 7 收尾 | 更新 §4 数据、§12 地图、§14.3 候选表 | 顺带把该域新踩的坑写进 §13 |

### 14.3 候选域盘点（2026-10-02 收官复测：真单类清零，≥800 只剩 **4 个登记例外**）

| 域 | ≥800 的类（行数） |
|---|---|
| datasource | **已清零**（`DataSourceService` 1,828→666 §14.7.15；`FeishuClient` 1,155→709、`NotionConnector` 1,093→625 §14.7.16） |
| memory | **已清零**（`MemoryService` 1,662→965→**768** 于 m5 出榜 §14.7.17；`MemoryIndexStore` 929 为**登记例外**：六段同属「索引侧读写」一个关注点，用户 2026-10-01 定调不硬切；仓储 1,515→458、`MemoryExtractionService` 1,216→718 亦已出榜） |
| retrieval | **已清零**（9 引擎仓 + HybridSearchService 1,260→775 均出榜；§14.7.5/§14.7.6） |
| wiki | **已清零**（`WikiPageController`/`WikiIngestService` §14.7.2；page 面 4 类 §14.7.14：1,008→732 / 858→708 / 851→468 / 822→383，最大类 `WikiIngestCitePipeline` 760） |
| mcp | **已清零**（McpServiceController 937→372 + OAuthHandler 825→220，§14.7.10） |
| 其余单类 | **已清零**（`SourceRegistry` 878→534，工具参数编解码外提 `SourceToolCodec`；`UserService` 876→748，会话令牌操作外提 `UserSessionOps`——§14.9s） |
| chatpipeline | **已清零**（PluginMerge 1,155→670 C1 + PluginSearch 899→278 P1-P3，§14.7.13） |
| embed | **已清零**（EmbedChannelController 925→561，§14.7.12；EmbedChannelService 792 本就 <800） |
| im / llm | **均已清零**（llm：RemoteApiChat 1,366→513，§14.7.8；im：FeishuAdapter 926→331，§14.7.11） |
| session | `SessionKnowledgeQaService` 1,036（§14.5 已登记例外）；**本域已清零**（§11.3） |
| auth | **controller 已清零**（§14.7.7 AuthController + §14.7.9 TenantCatalogController 980→133）；service 域剩 `UserService` 876 + apikey 未动 |

**例外复核结论（2026-10-02，§14.5 判据＝接缝优先）**：四个例外全部**维持登记**——
`SessionKnowledgeQaService` 1,036（三条入口流单一状态机，javadoc 已备案）、`MemoryIndexStore` 932
（六段同属索引侧读写，用户定调不硬切；javadoc 本批补例外说明）、`KnowledgeService` 848 /
`KnowledgeProcessWorker` 816（knowledge 门面与摄取状态机，类注释已备案；后者本批补 javadoc）。

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
> **800 行是启发式判据（2026-10-01 用户定调）**：切完只略超、或再切不再落在自然接缝上时，
> **不硬切**——按本节登记为已知例外并说明理由；判据是接缝，不是行数。

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
- **别动 §11 的边界清单**：租户配置 jsonb（`chat_parser_engine_rules` 等）、auth 域、agent 域 fixture（`ag-*`）、**Go 工具面 5 类**（`GoDoubleSerializer`/`GoTimeSerializer`/`GoMapSerializer`/`GoJsonEscapes`/`GoJson`——线上注解已清零，但手搓载荷/provider 请求体仍依赖其字节）、chat/工具域手搓载荷与**工具输出自有 schema**、检索引擎索引文档、**`lf_*` 平铺追踪载具**（§14.9q D3：`TracingContext` 平铺进 4 个队列载荷，前缀是防撞名的命名空间；要清理应改为嵌套 `tracing` 键，不是去前缀）、**connector 第三方线格式**（`datasource/connector/**` 350 处，字段名由对方 API 决定）、**wiki LLM 输出解析面**（§14.9r：`CombinedExtraction`/`NewSlugFromCitation`/`CitationBatchResult`/`common/wiki/ExtractedItem` 约 17 处，键名由 `WikiPrompts` 三条 prompt 的正文钉住——要改 Java 侧键名必须连 prompt 一起改，属行为面，另批处理）、
**image_info 面**（§14.9s：`chunks.image_info` 列与 `retrieval.domain.ImageInfo`，入站是 **docreader Go 容器**
`PUT /knowledge/image/{id}/{chunkId}` 体的内层 JSON——键 `original_url/ocr_text/start_pos…` 由对方服务决定，
存储列透传同形，管线读侧 `.path("original_url")` 等不得"顺手 camel 化"）、
**chat span/log 载荷的 SearchParams**（§14.9s：`common.pipeline.SearchParams` 序列化进 PipelineLog params 载荷，
SearchRecordingTest 金片钉住 snake——死注判定必须扫"参数对象被泛型序列化"的路径）、
**Go 零值时间哨兵**（`0001-01-01T00:00:00Z`：AgentStep 时间戳、agentm/init 的 GO_ZERO_TIME 与 goTime 系——
涉冻结事件面与既有前端，登记保留；换 null 属另批形状变更）、
**rerank RankResult**（`index/document/relevance_score`，Jina/Aliyun/Lkeap 的第三方 rerank API 响应面）——
  这些"仍是 snake"是**对的**。**注意该清单会随各域推进而变动**：`wiki 域实体` 条目已作废
  （`b407769` C 波把 `wiki/domain` 换锚为 camelCase），`wiki/service` 残留的 `@JsonProperty` 载荷随其批次处理；
  **引用前先看 §14.3 该域的进度栏，别照抄旧结论**。
- **别为数字写注释**：getter/POJO 访问器保持 0 javadoc（§13.8 第 3 条）。
- **别做全仓文本替换**：先用单文件验证再决定扩大（§13.2 的两次翻车）。
- **别跳过闸门**：只跑 `:server:test` 会漏掉 Spotless（§13.9）。


### 14.7 wiki 域执行计划（2026-10-01 定并**已执行完毕**，落刀记录见 §14.7.2；边界侦察见 §14.8）

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

#### 14.7.1 wiki 两目标类边界判定（2026-10-01 刀 0 侦察实测，动刀前核对）

**测试床**：`WikiHttpContractTest` 936 行（`@AutoConfigureMockMvc` 全上下文，不直 new 控制器 → 构造装配可改，路由/注解/端点签名不可变）；`WikiIngestServiceTest` 740 行（**直 new 13 参构造** → 构造签名冻结，协作者照既有 `new WikiIngestPageOps(this)` 回引模式在构造器内装配）。

**WikiPageController 簇边界与共享项**：
- 静态工具簇（原 L1042-1285）：getSlugParam / parseWikiCategoryPath / q / hasParam / query / atoi / atoiOrNull / trimSpace / isGoSpace / errText / appErrorText / rawError / internal / message / currentTenantId / sanitize / requiredFieldErrors / toJsonName / isZeroValue + 绑定三件 readJsonBody / toType / bind（**静态化 + 首参 ObjectMapper**——必须用 Spring 注入的 mapper，其 lenient 语义是行为的一部分，不许自建）。
- 守卫簇：requireWikiKB + checkOwnership（持 `kbMapper`）；活动记账：recordManualWikiActivity（持 `activityAudit`）。
- 端点簇四片：页面 CRUD+修订/回滚 ｜ 文件夹+movePage ｜ index+graph+stats+search ｜ rebuild-links+lint+auto-fix+issues。
- 共享项处置：**`RawJsonError` 嵌套类型留门面**（`@ExceptionHandler` 必须在控制器；协作者按 `WikiPageController.RawJsonError` 引用——`QaRequestBinder`→`Base64Support` 同款）；errText/internal/message 等先在门面留薄委托，端点体搬走后委托随之消亡。
- 协作者（包内 final class，仿 `QaRequestBinder`/`QaTurnExecutor`）：`WikiRequestSupport`（全静态）→ `WikiKbAccessGuard` → `WikiPageOps`（CRUD+修订+回滚，内含 `WikiActivityRecorder`）→ `WikiFolderOps` → `WikiStatsOps` → `WikiMaintenanceOps`；门面保薄端点（注解+参数提取+委托）。

**WikiIngestService 簇边界与共享项**：
- 写面簇 → `WikiIngestEnqueueOps`：enqueueWikiPendingOp / enqueueWikiIngest / newWikiIngestPendingOp / enqueueWikiIngestTrigger / enqueueWikiRetract(+Internal) / enqueueFinalizeRow / enqueueFinalize / finalizeRow / uniqueWikiFolderIDs / scheduleFinalize / scheduleFinalizeRetry / scheduleCappedRetry / scheduleStaleClaimRecheck + toJson。
- 队列消费簇 → `WikiIngestQueueOps`：peekPendingList / claimPendingList / decodePendingRows / trimPendingList(+Detached)。
- 失败结算簇 → `WikiIngestSettleOps`：finalizeWikiSubtask / requeueFailedOps(+Detached)。
- 文档簇 → `WikiIngestContentSupport`：isKnowledgeGone / filterLiveUpdates / reconstructContent / reconstructEnrichedContent。
- 共享项处置：**`MAPPER` 静态留门面**（包内可见，handle/decode 与簇共用）；**`PendingBatch` 公共 record 留门面**（§13.2 嵌套公共类型）；**`CHUNK_TYPE_*` 常量留门面**（`WikiIngestBatchHandler` 按 `WikiIngestService.CHUNK_TYPE_TEXT` 引用，常量不能委托）；reconstructContent 门面留薄委托（`WikiImageEnricher` javadoc 的 `{@link}` 指向门面）；PromptWarmup/SingleFlight/warmupReaper 留门面（llm 协作者域）。
- 门面保全部 public 薄委托（§14.8 A 波纪律）；Lite 锁/handle/cleanupScope/锁与限流留门面（BatchHandler/RunSupport 直接消费）。

**两类的死成员（登记替换，落刀时删）**：`WikiPageController.currentQueryParam` 零调用点；`WikiIngestService.beginWikiSubspan` 零调用者（MapPhase 调的是 `WikiBatchSupport` 的同名方法，javadoc 自认"当前无调用者"）。

**闸门口径**：每刀 = `--rerun-tasks` 重编 + `--tests "com.ragagent.wiki.*"` + spotlessCheck + 忠实性核验；收官 = clean 全量（基线 **4,668 用例 / 0 失败**，实测 2026-10-01）+ 前端三绿（本批不动前端契约，理论零影响，跑一次确认）。域内其余 ≥800（WikiPageServiceImpl 1,008 / WikiPageRepository 858 / WikiIngestDedupService 851 / WikiPageFolderSupport 821）**不在本批**，收尾时 §14.3 如实刷新。
5. **之后批次顺序（建议）**：`im`（单类 1,445，好收官）→ `retrieval` 的 4 个 engine（形似，做"适配器批"，一轮可重复）
   → `knowledge` / `auth` / `llm` / `chatpipeline` 的 1,000+ 类 → `datasource` / `memory`（体量最大，单独立项）。

#### 14.7.2 wiki 批次落刀记录（2026-10-01 执行完毕，10 刀全绿；边界判定见 §14.7.1）

| 刀 | 协作者（包内 final class） | 内容 | 提交 |
|---|---|---|---|
| 1 | `WikiRequestSupport` | 静态解析/绑定 23 成员；删死成员 `currentQueryParam` | `9288da6` |
| 2 | `WikiKbAccessGuard` | 守卫簇（requireWikiKB+checkOwnership，持 kbMapper） | `8afb023` |
| 3 | `WikiPageOps` + `WikiActivityRecorder` | 页面 CRUD+修订/回滚 8 端点体 + 活动记账 | `7c3a7fd` |
| 4 | `WikiFolderOps` | folders+movePage 5 端点体 | `13d772a` |
| 5 | `WikiStatsOps` | index/graph/stats/search 4 端点体；GRAPH_* 常量随簇 | `6185f2d` |
| 6 | `WikiMaintenanceOps` + 收官清扫 | rebuild/lint/autofix/issues 5 端点体；薄委托与字段退役、死链接修正 | `e465fcb` |
| A | `WikiIngestEnqueueOps` | 投递+finalize 通道 15 成员；`MAPPER` 放宽包内可见 | `6d5594c` |
| B | `WikiIngestQueueOps` | 队列消费 5 成员；`PendingBatch` record 留门面 | `6b2e7c1` |
| C | `WikiIngestSettleOps` | 失败结算 3 成员 | `d274459` |
| D | `WikiIngestContentSupport` | 文档簇 4 成员；删死成员 `beginWikiSubspan` | `d057471` |

**结果**：`WikiPageController` 1,311→**272**（薄端点 + `RawJsonError`/`handleRawJsonError`）、
`WikiIngestService` 1,208→**509**（照既有 `new Xxx(this)` 回引样式，门面保全部 public 薄委托）——均出榜；
全仓 ≥800 类 38→**36**；wiki 域测试 542 条 / 全量 4,668 条 0 失败；前端三绿（本批零前端改动）；环守卫基线保持。
原控制器 javadoc 的 §14.5 例外声明已随出榜删除。

#### 14.7.3 im 域执行计划（2026-10-01 侦察，接替 wiki；目标类 `ImService` 1,445）

**测试床现状（风险已知）**：`im/service` 无直连单测——安全网 = 9 个适配器测试（im 域 2,000+ 行）+ 全量契约 fixture；
因此**忠实性逐字比对是本批主安全网**，每刀必做。外部 public 面只有 4 个：
`registerAdapterFactory`/`adapterFor`（wiring+callback）、`handleMessage`（callback）、`deleteChannelsByAgent`（CustomAgentService）——全留门面。

**簇边界（L 为现文件行号）**：
- `ImQaRequests`（刀 I1）：isAgentMode / buildIMQARequest / createUserMessage / createAssistantMessage
  （L975-1034，QA 管线共享底座——runQA 与 handleMessageStream 两个簇都用，按 §13.2"先落被依赖方"先抽）。
  `JSON` 静态 mapper 放宽包内可见（wiki `MAPPER` 先例）。
- `ImSessionResolver`（刀 I2）：resolveSession / resolveUserSession / resolveThreadSession /
  createImSession / newMapping / insertMapping（L505-602；持 channelSessions+sessionService 经 service 回引）。
- `ImOutboundFormatter`（刀 I3）：cleanIMContent / formatIMOutboundAnswerOrFallback / sendStreamReply /
  sendReplyQuiet——门面留薄委托（门面自身消息入口与命令簇仍用，协作者经 `service.` 调）。
- `ImStreamPipeline`（刀 I4）：handleMessageStream / StreamBuffers / subscribeStreamEvents /
  ToolEvent / toolOf / upsert / flushStream（L1057-1435 的流式管线；QA 构造经 `service.` 走 I1 协作者）。
- `ImQaRunner`（刀 I5）：QaTask / QaAttach / QaOutcome / executeQARequest / handleMessageFullOutput /
  runFallbackNonStream / runQA（L698-973）；**构造器 qaQueue lambda 改指 `qaRunner.executeQARequest`**
  （qaRunner 须先于 qaQueue 装配）。
- 留门面：字段/构造器、渠道生命周期（L173-316）、消息入口+去重+限流（L318-503）、命令执行+doLocalStop（L604-696）、
  StreamManager 延迟接、`ChannelState`/`InflightEntry` 类型。

**登记清理（收官刀）**：死方法 `asMap`（L1393-1396，零调用）；坏 `{@link ImRedisKeys}`（类不存在，
javadoc 改文字陈述）；随刀清理失效 import（Spotless 兜底）。

**预估**：门面 1,445→~530；五个协作者 90~400 行。闸门：每刀 im 域测试（适配器族）+ 忠实性比对；收官 clean 全量 + 环守卫。

#### 14.7.4 im 批次落刀记录（2026-10-01 执行完毕，5+收官刀全绿；边界判定见 §14.7.3）

| 刀 | 协作者 | 内容 | 提交 |
|---|---|---|---|
| I1 | `ImQaRequests` | QA 共享底座 4 成员（先落被依赖方）；JSON 放宽包内 | `5007b39` |
| I2 | `ImSessionResolver` | 会话解析 6 成员 | `13d5ec6` |
| I3 | `ImOutboundFormatter` | 出站整形+回复发送 4 成员（门面留委托） | `c2b3427` |
| I4 | `ImStreamPipeline` | 流式管线整簇 + 删死方法 asMap | `062765b` |
| I5 | `ImQaRunner` | QA 执行编排 4 成员；QaTask/QaAttach/QaOutcome 类型留门面；lambda 改线+死委托清理 | `42955a4` |
| 收官 | — | 坏 `{@link ImRedisKeys}` 修正；clean 全量 4,668/0 + 环守卫基线保持 | 本提交 |

**结果**：`ImService` 1,445→**664**（出榜）；im/service 包 7 类全部 <800（最大 ImStreamPipeline 415）；
全仓 ≥800 类 36→**35**。字段放宽面：JSON/channels/channelSessions/sessionService/messageService/
knowledgeQaService/agentQaService/storageResolver/inflight（协作者经 `service.` 访问）。

#### 14.7.5 retrieval 适配器批侦察（2026-10-01，下一批；§14.3 实测 9 个 ≥800）

**形似度确认**：各引擎仓都有清晰的注释段边界（写入/删除/复制批量/迁移 move/检索/惰性初始化/探针/
过滤构造），沿注释拆即可，不需要重新设计。测试床每引擎都有（459~1,163 行）：
doris 3 文件 / elasticsearch 2 / milvus 3 / qdrant 1 / sqlite 2 / tencentvectordb 3 / weaviate 3 / opensearch 1。

**重复式刀序（每引擎同构，一轮可复制）**：
1. 刀 E1「检索簇」→ `XxxSearchOps`（检索/查询构造段；最大段，通常 300~500 行）；
2. 刀 E2「写入删除簇」→ `XxxWriteOps`（写入/删除/批量更新段）；
3. 刀 E3「管理簇」→ `XxxAdminOps`（集合管理/迁移/复制/惰性初始化/探针段）；
4. 门面保 `RetrieveEngineRepository` 端口面（接口实现 + 引擎面 engineType/support/testConnection）——
   方法签名是端口契约，**只改体内委托**；构造器签名不动（Spring 装配 + EngineFactory 反射面）。

**注意**：`HybridSearchService` 1,260 不在引擎仓族里（域根的混合检索编排），单独侦察后处理，
不套引擎刀序；`PgVectorRetrieveRepository` 已 <800 不进批。动手前按 §13.1 对**第一个引擎**（建议 sqlite-459 测试最小，
或 opensearch-最大）做全量成员清单侦察，验证刀序后复制到其余 8 个。

**sqlite 模式验证仓执行记录（2026-10-01，刀序成立，可复制）**：
- 刀 S1 → `SqliteSearchOps` 189（检索簇 7 成员：retrieve/keywordsRetrieve/vectorRetrieve/readIndex/FilterWhere/buildFilterWhere/addFilter）`f70c0f0`；
- 刀 S2 → `SqliteWriteOps` 388（写入/删除/批量/拷贝簇 20 成员，Row/toRow/extractEmbedding 随簇）`865ffc6`；
- `SqliteRetrieveRepository` 985→**537 出榜**；门面保全部端口 @Override 委托 + 引擎面 + 连接/DDL 底座 +
  测试直调 static 面（cosineDistance/cleanInvalidUtf8/resolvePath/vecTableName/placeholders/bind/bindStrings/nullToEmpty）。
- **复制到其余引擎时的固定坑位**（sqlite 全踩过）：①端口方法的 `@Override` 行在搬移文本里要剥、在门面委托里要补（doc_start 不含注解行）；②static helper 跨类调用必须类名限定（同一包不自动解析），SUBSTS 加限定、REVERSE 反向归一；③共享底座（open/vecTables/ensureVecTable(Connection,int)）放宽包内可见；④测试直调的 static 成员留门面（或委托）。
- 收官闸门：clean 全量 4,668/0 + 环守卫基线保持；全仓 ≥800 类 35→**34**。

**适配器批推进记录（2026-10-01 续轮：6 仓 12 刀，刀序完全复制，全部出榜）**

| 引擎 | 刀 | 协作者 | 前→后 | 提交 |
|---|---|---|---|---|
| qdrant | Q1/Q2 | `QdrantSearchOps`/`QdrantWriteOps` | 1,025→**557** | `88b0990`/`c1e9a63` |
| milvus | M1/M2 | `MilvusSearchOps`/`MilvusWriteOps` | 1,020→**619** | `b6ce1ce`/`5e35778` |
| tencentvectordb | T1/T2 | `TencentVectorDbSearchOps`/`TencentVectorDbWriteOps` | 987→**655** | `fc1e5b4`/`95925bc` |
| weaviate | W1/W2 | `WeaviateSearchOps`/`WeaviateWriteOps` | 986→**559** | `6b22537`/`e773299` |
| elasticsearch v8 | ES8-1/2（+fix `b4fc8e6`） | `ElasticsearchV8SearchOps`/`ElasticsearchV8WriteOps` | 891→**547** | `4af8249`/`26f622e` |
| elasticsearch v7 | E7-1/E7-2 | `ElasticsearchV7SearchOps`/`ElasticsearchV7WriteOps` | 877→**387** | `b9f3f71`/`5a354a5` |

**批内新增坑位（复制刀序时逐条核对）**：
1. **跨引擎静态复用**：V7 直接调 V8 的 `VectorEmbedding/parseSource/docJson/fromDbVectorEmbeddingWithScore`
   → 这些留 V8 门面（E7-2/ES8-2 的 SUBSTS 不得重复限定——V7 源文本已是全限定）。
2. **测试直调实例方法**（`r.vectorRetrieve(`）与静态引用（`Repo.member`）都要扫——ES8-1 漏了前者，
   且当轮闸门读了**陈旧测试 XML** 判绿推送（`4af8249`），fix `b4fc8e6`；此后各刀测试步正向断言 BUILD SUCCESSFUL。
3. **跨簇共享 static**（如 qdrant matchAny、milvus rowNode/fromNode、tencent in/notIn、weaviate extractItems、
   V7 termsOnly）→ 留门面放宽包内，两个协作者都经 `service.`/类名限定。
4. **FIELD_* 前缀误替**（FIELD_CONTENT ⊂ FIELD_CONTENT_SPARSE）→ 用负向断言正则
   `(?<![A-Za-z0-9_.])FIELD_X\b`、最长优先；`in(` 同理（防 `join(` 误替）。
5. **端口方法的 @Override**：搬移文本剥、门面委托补（doc_start 不含注解行——块删除前要合并注解行）。
6. harness 生成/派生时：SUBSTS/REVERSE 的 `"/_'` 引号缺失会连环出现（本批修了 4 次）——
   生成后先 `compile()` 两个 heredoc 再跑；补丁派生连坏时**整体重写 harness**（生成脚本用 Write 工具落盘，
   不经 shell 嵌套 heredoc）。

**opensearch 尾仓记录（2026-10-01，3 刀出榜）**：`OpenSearchRetrieveRepository` 1,652→**605**
（O1 `f3505c4` 检索簇 → `OpenSearchSearchOps`；O2 `3dac252` 写/删/批量/拷贝/迁移+投影检视 19 成员
→ `OpenSearchWriteOps`；O3 `5a82093` 惰性初始化/建索引/探针/别名 16 成员 → `OpenSearchAdminOps`）。
**O3 新坑位**：①构造期探针委托的协作者装配必须**先于探针调用**（否则构造 NPE——首轮 8 测试全挂）；
②`cfg` 既是门面字段又是成员参数名 → 按成员区分（签名带 `InternalCfg cfg` 参数的保持参数名，其余限定
`service.cfg`）；③`http.send(`/`join(` 等自身含目标词的调用 → send/in 用负向断言正则限定；
④跨包共享静态（`classifyFailure` 门面侧 HTTP 底座也调）→ 留门面放宽包内。
收官闸门：clean 全量 4,668/0 + 环守卫基线保持；全仓 ≥800 类 28→**27**。

**doris 尾仓记录（2026-10-01，3 刀出榜）**：`DorisRetrieveRepository` 1,265→**423**
（D1 `8830875` 检索簇 6 成员 → `DorisSearchOps`；D2 `64316e3` 写/删/批量/拷贝/迁移 21 成员（含
`RowLocation` record 与 legacy 批量路径）→ `DorisWriteOps`；D3 `64bd557` 兼容模式解析/建表/ANN 就绪
17 成员（三 record 随簇）→ `DorisAdminOps`）。
**D3 新坑位**：①记录随簇后门面字段类型改指新宿主（`volatile DorisAdminOps.CompatResolution`），
ops 的同包嵌套 import 删除；②D2 已放宽的签名（`ensureTable`/`tableExists`）在 D3 侦察时签名已无
`private`——**侦察必须基于上一刀落盘后的现场**，别用陈旧行号或旧修饰符。
收官闸门：clean 全量 4,668/0 + 环守卫基线保持；全仓 ≥800 类 27→**26**。
检索域剩：`HybridSearchService` 1,260（非引擎族）。

### 14.7.6 HybridSearchService 记录（2026-10-01，3 刀出榜；非引擎族）

`HybridSearchService` 1,260→**775**（H1 `665a3a7` 融合+FAQ 后处理 10 成员 → `HybridFusionOps`；
H2 `57a9750` 结果装配 5 成员 → `HybridResultOps`；H3 `94569a1` store-group 解析 3 成员 →
`HybridStoreGroupOps`，持 service+storeOwnership 双依赖）。
**要点**：①测试直调 static（hasMixedEngineTypes/isKnownEngineType/multiStoreRetrieveTimeout/
storeKindLabel/StoreGroup）与 `RetrievalConfigView` record 全部留门面；②入口 hybridSearch 内的
跨簇调用点（applyFaqPostProcessing）改 `fusionOps.` 前缀；③static 薄委托（fuseOrDeduplicate/
deduplicateByScore）必须用**类名**调 ops 的 static 方法，实例字段在 static 上下文不可引用。
收官闸门：clean 全量 4,668/0 + 环守卫基线保持；全仓 ≥800 类 26→**25**；检索域 ≥800 清零。

### 14.7.7 AuthController 记录（2026-10-01，3 刀出榜；auth controller 域）

`AuthController` 1,167→**704**（A1 `1101b79` OIDC 簇 13 成员 → `AuthOidcOps`；
A2 `e902697` 会话簇 8 成员（logout/refresh/switch-tenant + RefreshTokenRequest record）→ `AuthSessionOps`；
A3 `83e75ce` 绑定校验簇 9 成员 → `AuthBindingSupport`）。
**要点**：①`@Value` 注入字段（edition/configuredRegistrationMode）留门面；②测试直调 static 无（安全网 =
AuthContractTest/OidcContractTest 等 MockMvc 契约测试）；③OIDC 常量 `OIDC_NONCE_COOKIE_*` 随簇；
④`RefreshTokenRequest` 嵌套 record 随会话簇；⑤`buildAuthLoginResponse`/`parseBody` 被多端点调用 →
门面留委托；⑥`bindingError`/`invalidParams` 被会话簇 + 门面端点共用 → 门面留非 static 委托。
收官闸门：clean 全量 4,668/0 + 环守卫基线保持；全仓 ≥800 类 25→**24**；auth controller 清零。

#### 14.7.8 llm 域执行计划（2026-10-01 侦察，接替 chatpipeline；目标类 `RemoteApiChat` 1,366）

**测试床**：`RemoteApiChatTest` 934 行（**直 `new RemoteApiChat(config)`，构造签名冻结**；直调门面
public `convertMessages`/`buildChatCompletionRequest`/`shapedRequest`/`chat`/`chatStream` 与
package-private `buildOutbound`/`processStreamDelta`/`applyStreamToolCallMetadata`/
`parseCompletionResponse`/`applyCompletionToolCallMetadata`/`setAdapter`，static 直调
`RemoteApiChat.goSorted`/`RemoteApiChat.removeThinkingContent`）；`ProviderAdapterRegistryTest`
直 new + `buildOutbound`。外部生产面只有 `LlmChatClients` 工厂的 `new RemoteApiChat(config)`
（SessionKnowledgeQaService/AgentConfigAssembler 仅注释提及）→ 门面全量薄委托即零改动。

**簇边界（L 为现文件行号；协作者持 service 回引，mutable `adapter` 必须每处经 `service.adapter`
取当前值，不得构造期缓存——测试 setAdapter 会换）**：
- `RemoteApiRequestOps`（刀 L1）：convertMessages / buildChatCompletionRequest / shapedRequest /
  buildBodyFromConverted / applyJsonSchemaHint(static) / messageToJson（L211-489 出站组装段）。
  依赖 adapter(transformMessages/shapeRequest/injectToolCallMetadata)、modelName、provider、MAPPER。
- `RemoteApiBodyCodec`（刀 L2，全静态）：GO_MARSHAL+goMarshal / goSorted / SDK_TOP_ORDER /
  SDK_NESTED_ORDER / structSorted / structSortedChild（L93-102 + L538-681）；`Outbound` record
  留门面（嵌套公共类型），其 bodyBytes() 改调 codec static；门面留 `goSorted` static 薄委托（测试直调）。
- `RemoteHttpOps`（刀 L3）：buildHeaders / authCreds / applyCustomHeaders(static) / sendRequest /
  ioDetail / readAll / statusError（L683-765）；`RESERVED_HEADERS` 常量随簇。
- `RemoteApiStreamOps`（刀 L4）：processRawHttpStream / terminalResponse / processStreamDelta /
  processToolCallsDelta / toolCallResponse(static) / applyStreamToolCallMetadata（L869-1194 流式段）；
  `THINKING_TOOL_NAME` 常量随簇；测试直调两 package-private → 门面留薄委托。
- `RemoteApiResponseOps`（刀 L5）：parseCompletionResponse / applyCompletionToolCallMetadata /
  removeThinkingContent(static)（L1200-1279）；`removeThinkingContent` 门面留 static 薄委托（测试直调）。

**留门面**：字段/构造器（L87-209）、buildOutbound+resolveEndpoint、`Outbound` record、
chat 两重载、chatStream 两重载、logUsage、compactForLog(+DATA_URL_PATTERN/MAX_* 常量)、
全部访问器（getModelName/getModelId @Override、getProvider/getBaseUrl/getApiKey、adapter()/setAdapter）、
共享 static `textOrEmpty`/`isBlank` 与 `MAPPER`（放宽包内）。
构造期常量 EXTRA_REMOTE_MODEL_NAME/EXTRA_API_VERSION/DEFAULT_AZURE_API_VERSION 留门面。

**预估**：门面 1,366→~550；五个协作者 90~330 行。闸门：每刀 `--rerun-tasks` 重编 + `--tests
"com.ragagent.llm.*"` + spotlessCheck + 忠实性逐字比对；收官 clean 全量 + 环守卫。

**落刀记录（2026-10-01 执行完毕，5 刀全绿；边界判定见上）**

| 刀 | 协作者 | 内容 | 提交 |
|---|---|---|---|
| L1 | `RemoteApiRequestOps` | 出站组装簇 6 成员（convertMessages/buildChatCompletionRequest/shapedRequest/buildBodyFromConverted/applyJsonSchemaHint/messageToJson） | `ee821bd` |
| L2 | `RemoteApiBodyCodec`（全静态） | GO_MARSHAL/goMarshal/goSorted/SDK 两张序表/structSorted/structSortedChild；Outbound.bodyBytes 改类名限定，门面留 goSorted static 薄委托 | `872f4a1` |
| L3 | `RemoteHttpOps` | buildHeaders/authCreds/applyCustomHeaders/sendRequest/ioDetail/readAll/statusError + RESERVED_HEADERS；门面留 sendRequest/readAll/statusError 薄委托 | `20a5da4` |
| L4 | `RemoteApiStreamOps` | processRawHttpStream/terminalResponse/processStreamDelta/processToolCallsDelta/toolCallResponse/applyStreamToolCallMetadata + THINKING_TOOL_NAME；门面留三薄委托 | `6a28a0f` |
| L5 | `RemoteApiResponseOps` | parseCompletionResponse/applyCompletionToolCallMetadata/removeThinkingContent；门面留三薄委托 | `08be8ce` |

**结果**：`RemoteApiChat` 1,366→**513**（出榜）；五个协作者 110~365 行；llm 域 ≥800 清零；
全仓 ≥800 类 22→**21**；llm 域测试 299 条 / 全量 4,668 条 0 失败（clean 全量 + spotless + 环守卫基线保持）。
**本批新增坑位**：①`textOrEmpty`/`logUsage` 等共享成员跨类调用须先在门面放宽包内（编译期才暴露）；
②忠实性抽取正则的修饰符字符类**不能含空格**（`[A-Za-z…, ?.]+` 里的空格会击穿 4 空格缩进锚点，
匹配到深层缩进的调用点报假"unbalanced"——已修为 `(?![ ])` 负向前瞻）；③harness 忠实性失败分支
也要回滚工作区（有一轮文件滞留落刀后状态，靠 `git checkout -- <门面>` 恢复后重跑）。

#### 14.7.9 TenantCatalogController 执行计划（2026-10-01 侦察，接替 llm；auth 域续刀 §14.7.7）

**目标类**：`auth/controller/TenantCatalogController` 980（跨空间租户目录 + KV 配置分发器，
POST /tenants 巨型端点 ~150 行 + W5a CRUD 4 条 + KV 分发器 + 6 组配置 get/put）。

**测试床**：`TenantCatalogContractTest` 471 行 + `W5aSundryRoutesContractTest` 428 行
（均 `@SpringBootTest + @AutoConfigureMockMvc` 全上下文直打路由，**不直 new、无 static 直调** →
构造装配可改，路由/注解/端点签名不可变）；`TenantMemberContractTest`（envelope 消费方）。
协作者样式照同包先例 `AuthOidcOps`：**service 回引**（`new XxxOps(this)`，门面构造器内装配）。

**簇边界（先落被依赖方）**：
- `TenantBindSupport`（刀 T1，全静态）：MAPPER + bindBody + bindingError + invalidParams +
  trimGo + goJsonKind + goStringField——四簇共用的 Go 绑定错误形态助手；随刀删死方法
  `parseIntOr`（零调用，§13.15 登记）。
- `TenantCreateOps`（刀 T2）：createTenant 端点体 + tenantWithApiKey + deepSortKeys +
  resolveMaxOwnedTenantsPerUser + quotaExceeded + validateCreateBinding +
  `CreateTenantRequest` 嵌套类型 + `DEFAULT_MAX_OWNED_PER_USER` 常量随簇。
- `TenantCrudOps`（刀 T3）：listTenants/getTenant/updateTenant/deleteTenant 4 端点体 +
  bindUpdateTenantRequest + `UpdateTenantRequest` 嵌套类型 + loadTenantOr500 + contextRoleHasAdmin。
- `TenantConfigOps`（刀 T4）：getTenantKV/updateTenantKV 两分发端点体 +
  requireIntegrationSecretsIfSensitive + canViewIntegrationSecrets + requireContextTenant +
  parseConfig + updateFailed + envelopeWithMessage + 6 组配置 get/put 12 方法 +
  validateParserEngineOutboundUrls + ssrfCheck + firstAllowedStorageProvider。

**留门面**：类注解/字段/10 参构造器（Spring 装配）、7 个薄端点（注解+参数提取+委托）、
4 个协作者字段。**登记替换**：字段 → `service.` 限定；MAPPER/绑定助手 → `TenantBindSupport.` 限定；
嵌套类型随簇后引用改 `XxxOps.` 前缀。端点搬移时 @Mapping 注解剥、参数注解（@RequestBody/
@PathVariable）随簇保留（AuthOidcOps 同款）。

**预估**：门面 980→~250；四个协作者 90~330 行。闸门：每刀 `--rerun-tasks` 重编 +
`--tests "com.ragagent.auth.*"` + spotlessCheck + 忠实性逐字比对；收官 clean 全量 + 环守卫。

**落刀记录（2026-10-01 执行完毕，4 刀全绿；边界判定见上）**

| 刀 | 协作者 | 内容 | 提交 |
|---|---|---|---|
| T1 | `TenantBindSupport`（全静态 76） | MAPPER/bindBody/bindingError/invalidParams/trimGo/goJsonKind/goStringField（先落被依赖方）；删死方法 `parseIntOr` | `2c6a076` |
| T2 | `TenantCreateOps` 272 | createTenant 端点体 + tenantWithApiKey/deepSortKeys/resolveMaxOwnedTenantsPerUser/quotaExceeded/validateCreateBinding + `CreateTenantRequest` + `DEFAULT_MAX_OWNED_PER_USER` | `cb8029e` |
| T3 | `TenantCrudOps` 196 | listTenants/getTenant/updateTenant/deleteTenant 4 端点体 + bindUpdateTenantRequest/loadTenantOr500/contextRoleHasAdmin + `UpdateTenantRequest` | `5caa584` |
| T4 | `TenantConfigOps` 436 | getTenantKV/updateTenantKV 分发端点体 + 守卫/解析/信封 6 助手 + 6 组配置 get/put 12 方法 + SSRF 三件 | `dad94d8` |

**结果**：`TenantCatalogController` 980→**133**（出榜，收官清扫删死 logger）；四个协作者 76~436 行；
auth 域测试 183 条 / 全量 4,668 条 0 失败（clean 全量 + spotless + 环守卫基线保持）；
全仓 ≥800 类 21→**20**。
**本批新增坑位**：①剥 `@Mapping` 注解行**必须连行首缩进一起剥**（只剥注解文本会把残余 4 空格
叠进下一行 → 协作者签名 8 空格、忠实性抽取失配）；②多切块落刀必须**按文件序排序切口后重建**
（T1 首轮按"编号序"手工拼接，R1(73 行)在 R0(119 行)之前 → 区间重叠、死方法复活）；③依赖计数
断言别凭读文件印象写死——本批 trimGo 4 非 5、tenantProperties 4 非 2、tenantService 7 非 6，
三次都是脚本实测纠偏（先跑计数再定断言）。

#### 14.7.10 mcp 域执行计划（2026-10-01 侦察，接替 auth tenant；目标 `McpServiceController` 937 + `OAuthHandler` 825，一轮清零 mcp 域）

**测试床**：controller 侧 `McpHttpContractTest` 539 行（`@SpringBootTest+@AutoConfigureMockMvc`，
不直 new）+ `McpUsageInputTest` **static 直调** `McpServiceController.buildMCPUsageInput`/
`mcpUsageExcerpt`（→ 门面留 static 薄委托）；oauth 侧 `OAuthHandlerTest` 343（**直
`new OAuthHandler(cfg)`，构造签名冻结**；实例面 processAuthorizationResponse/setExpectedState/
registerClient/refreshToken/getAuthorizationHeader 等 + **static 直调** resourceIdentifiersEqual/
buildWellKnownUrl/validateAuthServerMetadataUrls/authorizationServerMetadataUrls/queryEscape/
encodeForm 六个）+ `OAuthLifecycleTest` 299（Fake 仓 + OAuthServerStub）。mcp 域 236 个 @Test。

**簇边界**（协作者 service 回引、门面构造器内装配；`OAuthHandler` 的可变态按状态组归属）：
- `McpUsageInstructionsOps`（刀 M1）：generateMCPUsageInstructions + chatWithTimeout +
  buildMCPUsageInput/mcpUsageExcerpt/runeCount/runeSubstring + selectChatModel + chatClientFor；
  `MCP_USAGE_PROMPT`/`LANGUAGE_MAP`/`USAGE_INSTRUCTIONS_TIMEOUT`/`JSON` 常量随簇；
  依赖 mcpMetadataAppError（留门面，经 `McpServiceController.` 调）。
- `McpServiceCrudOps`（刀 M2）：create/list/get/update/delete/test/tools/resources 8 端点体 +
  mcpServiceResponses + validateServiceUrlForSsrf + stringMap；自有 logger。
- `OAuthDiscovery`（刀 O1）：**发现状态组整体随簇**（metadataLock/metadataFetched/serverMetadata/
  metadataFetchError/baseUrl/resourceUrl）+ getServerMetadata/discover/fetchMetadataFromUrl/
  extractBaseUrl/getDefaultEndpoints + setBaseUrl/setProtectedResourceMetadataUrl/getResourceUrl +
  静态 buildWellKnownUrl/authorizationServerMetadataUrls/validateAuthServerMetadataUrls/
  resourceIdentifiersEqual/trimTrailingSlash/trimSlashes/equalsIgnoreCase/equalsNn/isBlank；
  门面留 4 个实例委托 + 4 个 static 委托（测试直调）。
- `OAuthTokenOps`（刀 O2）：refreshToken/registerClient/getAuthorizationUrl/processAuthorizationResponse
  + 静态 extractOAuthError/parseOAuthError/parseToken/encodeForm/queryEscape/stringOf；
  CSRF 态（stateLock/expectedState）留门面放宽包内，O2 经 `service.` 访问。
- 留门面（controller）：字段/7 参构造器、metadata 簇（getMCPMetadata/refreshMCPMetadata/mcpMetadata/
  mayWriteSharedMCPMetadata/mcpMetadataAppError）、tool-approvals 两端点、共享助手
  （requireTenant/canViewIntegrationSecrets/isOAuth/rawMessage/isNotFound/ok/envelope/successOnly/
  sanitize——放宽包内）；留门面（oauth）：config/timeout/CSRF 态、setExpectedState/getExpectedState、
  getAuthorizationHeader/getValidToken、`INVALID_STATE_MESSAGE`/`MAPPER`（放宽包内）。

**预估**：controller 937→~330（M1/M2 后）；oauth 825→~200（O1/O2 后）；四个协作者 200~380 行。
闸门：每刀 `--rerun-tasks` 重编 + `--tests "com.ragagent.mcp.*"`（阈值 ≥230）+ spotlessCheck +
忠实性逐字比对；收官 clean 全量 + 环守卫。

**落刀记录（2026-10-01 执行完毕，4 刀全绿；边界判定见上）**

| 刀 | 协作者 | 内容 | 提交 |
|---|---|---|---|
| M1 | `McpUsageInstructionsOps` 302 | generateMCPUsageInstructions 端点体 + chatWithTimeout/buildMCPUsageInput/mcpUsageExcerpt/runeCount/runeSubstring/selectChatModel/chatClientFor + 4 常量（含 JSON mapper）；门面留 static 薄委托 ×2（测试直调） | `538c61c` |
| M2 | `McpServiceCrudOps` 397 | create/list/get/update/delete/test/tools/resources 8 端点体 + mcpServiceResponses/validateServiceUrlForSsrf/stringMap（尾部工具段三 helper 同刀）；回引 `ctrl`（body 有 `McpService service` 局部变量） | `3123d1a` |
| O1 | `OAuthDiscovery` 439 | **发现状态组整体随簇**（metadataLock 等 6 字段）+ getServerMetadata/discover/fetchMetadataFromUrl/extractBaseUrl/getDefaultEndpoints + setBaseUrl/setProtectedResourceMetadataUrl/getResourceUrl + 静态 9 件（buildWellKnownUrl 等）；门面留 4 实例委托 + 4 static 委托 | `5c6fe47` |
| O2 | `OAuthTokenOps` 293 | refreshToken/registerClient/getAuthorizationUrl/processAuthorizationResponse + extractOAuthError/parseOAuthError/parseToken/encodeForm/queryEscape/stringOf；CSRF 态留门面放宽包内，O2 经 service 访问 | `7e2ed4a` |

**结果**：`McpServiceController` 937→**372**、`OAuthHandler` 825→**220**——均出榜，mcp 域 ≥800 清零；
全仓 ≥800 类 20→**18**；mcp 域测试 250 条 / 全量 4,668 条 0 失败（clean 全量 + spotless + 环守卫基线保持）。
**本批新增坑位**：①回引字段名撞局部变量（generateMCPUsageInstructions 有 `McpService service` 局部
变量、buildMCPUsageInput 有同名形参 → 该协作者回引改叫 `ctrl`，编译期暴露）；②`brace_end` 对
**多行签名首行无 `{`** 的方法会在首行就返回（depth==0 即退）→ 须加 started 标志；③实测计数
先行再次生效（sanitize 8 非 7、log 18 非 17、canViewIntegrationSecrets 漏放宽、协作者漏 import
JsonNode/ObjectMapper/ModelService/McpMetadataSummary/LinkedHashMap——五处都靠编译守卫抓回）。

#### 14.7.11 FeishuAdapter 执行计划（2026-10-01 侦察，接替 mcp；im 域收尾刀）

**目标类**：`im/feishu/FeishuAdapter` 926（五面适配器：验签/挑战/解析 + reply/send 发送 +
CardKit 流式卡片 + 图片上传/文件下载 + token/解密；带静态流表 STREAMS 与 image_key 缓存）。

**测试床**：`FeishuAdapterTest` 561 行（**直 `new FeishuAdapter(...)` ×7，构造签名冻结**；
static 直调 `cardSummaryPreview`/`safePathParam` + 直摸 `STREAMS`/`IMAGE_KEY_CACHE`；实例直调
`getTenantAccessToken`/`resolveMarkdownImages`）；跨类 `LarkEventConverter` →
`FeishuAdapter.stripBotMention`。im 域 121 个 @Test。

**共享定置（不随簇）**：`STREAMS`/`IMAGE_KEY_CACHE`/`StreamState`（测试直摸）与
`MD_IMAGE_RE`/`MD_LINK_RE`（双簇共用）留门面放宽包内；`readTree`（全簇共用 static）留门面；
token 态 + `getTenantAccessToken` 留门面（四簇都消费）；构造器/`validateApiBaseUrl`/`api()`/
`apiBaseUrl()` 留门面。

**簇边界**（协作者 service 回引；先落被依赖方 F2）：
- `FeishuCallbackOps`（刀 F1）：verifyCallback/handleURLVerification/parseCallback +
  stripBotMention(static，门面留委托——LarkEventConverter 消费) + baseMessage/textMessage + decrypt。
- `FeishuSendOps`（刀 F2）：sendReply/resolveReceiveId(static)/sendWithFallback/postFeishuMessage +
  `ApiResult` record + safePathParam(static，门面留委托——测试直调)；门面留
  sendWithFallback/resolveReceiveId 委托（F3/F4 消费）。`FALLBACK_ELIGIBLE` 常量留门面。
- `FeishuCardStreamOps`（刀 F3）：startStream/updateStreamContent/finalizeStream/endStream +
  cardkit 四件 + sendCardByCardId + purgeOrphans/cardSummaryPreview(static，门面留委托——测试直调)/
  buildStreamingCardJson；`STREAMING_ELEMENT_ID`/`STREAM_ORPHAN_TTL_MS` 常量随簇。
- `FeishuMediaOps`（刀 F4）：resolveMarkdownImages(门面留实例委托——测试直调)/imageKeyForUrl/
  imageCacheKey/uploadImageFromUrl/downloadFile；`MAX_IMAGE_BYTES` 常量随簇。

**预估**：门面 926→~300；四个协作者 110~230 行。闸门：每刀 `--rerun-tasks` 重编 +
`--tests "com.ragagent.im.*"`（阈值 ≥110）+ spotlessCheck + 忠实性逐字比对；收官 clean 全量 + 环守卫。

**落刀记录（2026-10-01 执行完毕，4 刀全绿；边界判定见上）**

| 刀 | 协作者 | 内容 | 提交 |
|---|---|---|---|
| F1 | `FeishuCallbackOps` 237 | verifyCallback/handleURLVerification/parseCallback + stripBotMention/baseMessage/textMessage + decrypt；剥 3 个 @Override；门面留 stripBotMention static 委托（LarkEventConverter 消费） | `3945a40` |
| F2 | `FeishuSendOps` 147 | sendReply/resolveReceiveId/sendWithFallback/postFeishuMessage + `ApiResult` record + safePathParam；门面留 sendWithFallback 包内委托（F3/F4 消费）+ resolveReceiveId/safePathParam static 委托 | `f78062c` |
| F3 | `FeishuCardStreamOps` 256 | 流式四端点 + cardkit 四件 + sendCardByCardId + purgeOrphans/cardSummaryPreview/buildStreamingCardJson + STREAMING_ELEMENT_ID/STREAM_ORPHAN_TTL_MS；STREAMS/StreamState 留门面（测试直摸） | `dc719e1` |
| F4 | `FeishuMediaOps` 182 | resolveMarkdownImages/imageKeyForUrl/imageCacheKey/uploadImageFromUrl/downloadFile + MAX_IMAGE_BYTES；IMAGE_KEY_CACHE/MD 正则留门面 | `4e63159` |

**结果**：`FeishuAdapter` 926→**331**（出榜，收官清扫删死 logger）；四个协作者 147~256 行；
im 域 ≥800 清零；im 域测试 121 条 / 全量 4,668 条 0 失败（clean 全量 + spotless + 环守卫基线保持）；
全仓 ≥800 类 18→**17**。
**本批新增坑位**：①implements 继承的嵌套类型作用域（`DownloadedFile` 双层嵌套于
`AdapterInterfaces.FileDownloader`，门面靠 implements 直用，协作者须全链限定）；②形参遮蔽的
点位化替换（`buildStreamingCardJson(FeishuRegion region)` 的形参 region 不能盲替，只能替换
`region.label()`/`buildStreamingCardJson(region)` 等具体形态）；③跨刀依赖先登记（F3 的
resolveMarkdownImages 属 F4，落 F3 时先经 `service.` 调用，F4 搬走后委托天然接住）。

#### 14.7.12 EmbedChannelController 执行计划（2026-10-01 侦察，接替 im；embed 单类）

**目标类**：`embed/controller/EmbedChannelController` 925（管理面 9 端点 + 公开面 5 端点 +
QA/文件/消息/建议/webhook/MCP 委托 16 端点 + 响应组构件助手；委托型控制器——大半端点是对
session/message/suggestion/MCP 控制器的转发）。

**测试床（薄弱，忠实性为主安全网）**：`EmbedContractTest` 570 行，`@SpringBootTest+
@AutoConfigureMockMvc` 全上下文直打路由（**不直 new、无 static 直调**，2 个 @Test 驱动全
fixture）→ 路由/注解/签名冻结、装配可改；每刀另过 `--tests "com.ragagent.session.*"`
（委托消费方域）作旁证。

**簇边界**（协作者回引 `ctrl`——门面 EmbedChannelService 字段名为 `service`，撞名；helper 全留
门面放宽包内，协作者 `EmbedChannelController.` 限定调用）：
- `EmbedChannelMgmtOps`（刀 E1）：create/listByAgent/listAll/get/update/delete/rotate/preview/
  stats 9 端点体 + `UpdateCommand` 嵌套类随簇。
- `EmbedChannelPublicOps`（刀 E2）：exchange/config/suggestedQuestions/chunk/createSession
  5 端点体。
- `EmbedChannelDelegateOps`（刀 E3）：knowledgeChat/agentChat/delegateEmbedChat/
  patchEmbedChatPayload/embedFiles/load/stop/suggestionsGet/suggestionsEnsure/suggestionEvents/
  events + `EventRequest` record + mcpAuthorize/mcpStatus/mcpResolve/mcpResolveCancel/
  toolApprovals 共 16 端点体。
- 留门面：`EmbedChannelRequest` record（bind 消费）、ensureSession/suppressedIfChannelOff/
  validVisitor、响应组构件（row×3/rows/originsJson/dataEnvelope/successEnvelope/plainError/
  writeMgmtError/currentTenant/channel/request0/bind/allowedOriginsColumn/stringList/trim/orEmpty）、
  字段/10 参构造器、30 个薄端点（注解+委托）。

**预估**：门面 925→~300；三个协作者 140~280 行。闸门：每刀 `--rerun-tasks` 重编 +
`--tests "com.ragagent.embed.*"`（正向 BUILD + ≥2）+ spotlessCheck + 忠实性逐字比对；
收官 clean 全量 + 环守卫。

**落刀记录（2026-10-01 执行完毕，3 刀全绿；边界判定见上）**

| 刀 | 协作者 | 内容 | 提交 |
|---|---|---|---|
| E1 | `EmbedChannelMgmtOps` 198 | 管理面 9 端点体（create/listByAgent/listAll/get/update/delete/rotate/preview/stats）；`UpdateCommand` 是 service 嵌套类型（import 即可，不随簇） | `b84976e` |
| E2 | `EmbedChannelPublicOps` 141 | 公开面 5 端点体（exchange/config/suggestedQuestions/chunk/createSession） | `3c608e7` |
| E3 | `EmbedChannelDelegateOps` 280 | 委托面 16 端点体（QA 双路/文件代理/load/stop/建议×3/webhook events + `EventRequest` record 随簇 + MCP OAuth×4/tool-approval）；门面 ensureSession/suppressedIfChannelOff 放宽为实例包内，E3 经 `ctrl.` 调 | `908800b` |

**结果**：`EmbedChannelController` 925→**561**（出榜）；三个协作者 141~280 行；embed 域 ≥800 清零；
embed+session.controller 152 条测试 0 失败 / 全量 4,668 条 0 失败（clean 全量 + spotless + 环守卫基线保持）；
全仓 ≥800 类 17→**16**。
**本批新增坑位**：①回引撞名二次确认——门面 `service` 字段（EmbedChannelService）→ 回引叫 `ctrl`，
`service.` → `ctrl.service.` 限定；②随簇嵌套 record（EventRequest）引用**不限定**（声明与引用同迁），
与跨类嵌套类型（EmbedChannelRequest 留门面 → `EmbedChannelController.` 限定）方向相反，先判归属再定
替换式；③实例方法与静态方法放宽后的调用形态不同（ensureSession/suppressedIfChannelOff 经 `ctrl.`，
channel/request0/successEnvelope 经类名）；④sed 插入 `\w` 会被 shell 吃反斜杠 → 插入一律用 python 行级
操作（§13.6 heredoc 坑的 sed 变体）。

#### 14.7.13 PluginSearch 执行计划（2026-10-01 侦察，接替 embed；chatpipeline 续刀）

**目标类**：`chatpipeline/plugin/PluginSearch` 899（检索插件：onEvent 编排 + embedding 分组检索 +
web 检索 + 查询扩展 + 关键词/分词静态工具）。

**测试床**：`SearchRecordingTest`（直 `new PluginSearch(...)`，构造签名冻结；实例直调
`searchByTargets`/`runQueryExpansion`/`expandQueries`，**static 直调** extractKeywords/extractPhrases/
splitByDelimiters/removeQuestionWords/tokenize 五个）+ `SearchGradingTest`；chatpipeline 域 45 @Test。
外部面：`QaWiring` 装配；`SearchSupport`/`PipelinePorts` 仅 javadoc/注释提及。

**共享定置（不随簇）**：`withTenant`/`joinQuietly`（P2/P3 与 onEvent 共用 static，留门面放宽包内）、
`mapOf` 随 P3（onEvent 不用）；字段 `knowledgeService`/`chunkService`/`sessionService`/
`webSearchStateService`/`webSearchProviderRepo`/`config` 仅 onEvent 用，留门面。

**簇边界**（协作者 `service` 回引——本类无 `service` 局部变量；`chatManage` 是形参不替换）：
- `QueryTextOps`（刀 P1，全静态）：STOPWORDS/QUESTION_WORDS/QUOTED_PHRASE/DELIMITERS +
  extractKeywords/extractPhrases/splitByDelimiters/removeQuestionWords/tokenize/isHan/runeCount；
  门面留五 static 薄委托（测试直调）。
- `PluginExpansionOps`（刀 P2）：runQueryExpansion/expandQueries/addIfNew；门面留两 public 薄委托。
- `PluginSearchOps`（刀 P3）：searchByTargets/searchModelGroup/searchSingleTarget +
  targetReportsEmbedFailure/isVectorEnabled/isKeywordEnabled + searchWebIfEnabled/
  currentTenantWebSearchConfig/effectiveWebSearchConfig/mapOf；门面留 searchByTargets public 委托 +
  searchWebIfEnabled 包内委托（onEvent 消费）。

**预估**：门面 899→~300；三个协作者 120~390 行。闸门：每刀 `--rerun-tasks` 重编 +
`--tests "com.ragagent.chatpipeline.*"`（≥45）+ spotlessCheck + 忠实性逐字比对；
收官 clean 全量 + 环守卫。

**落刀记录（2026-10-01 执行完毕，3 刀全绿；边界判定见上）**

| 刀 | 协作者 | 内容 | 提交 |
|---|---|---|---|
| P1 | `QueryTextOps` 141（全静态） | STOPWORDS/QUESTION_WORDS/QUOTED_PHRASE/DELIMITERS + extractKeywords/extractPhrases/splitByDelimiters/removeQuestionWords/tokenize/isHan/runeCount；门面留五 public static 薄委托（测试在上级包直调） | `3098f5f` |
| P2 | `PluginExpansionOps` 213 | runQueryExpansion/expandQueries/addIfNew；withTenant 经门面类名调 | `9a98c3b` |
| P3 | `PluginSearchOps` 405 | searchByTargets/searchModelGroup/searchSingleTarget + 降级判定三件 + searchWebIfEnabled/currentTenantWebSearchConfig/effectiveWebSearchConfig/mapOf；withTenant/joinQuietly 留门面（与 onEvent 共用） | `e299fb8` |

**结果**：`PluginSearch` 899→**278**（出榜，chatpipeline 域 ≥800 清零）；三个协作者 141~405 行；
chatpipeline 域测试 45 条 / 全量 4,668 条 0 失败（clean 全量 + spotless + 环守卫基线保持）；
全仓 ≥800 类 16→**15**。
**本批新增坑位**：①门面写盘必须用**全部编辑完成后的最终文本**（P2 首轮把 `out_lines` 在装配编辑
之前算好 → 字段放宽与协作者装配静默丢失，编译守卫抓回）；②静态委托的可见性按测试包位定
（SearchRecordingTest 在 `chatpipeline` 上级包 → 五个委托保 `public static`，不能剥成包内）；
③字段替换用 `\b` 而非 `\.`（webSearchService/tenantService 有裸 null 比较）；④同一 harnese 里
重名 helper 函数（brace_end 一参/两参）后定义覆盖前定义 → 两参版先移到顶部。

### 14.7.14 wiki page 面批次（2026-10-01 立；刀 0 侦察 + 两个前置卫生刀已完成）

**目标类（§14.3 实测）**：`WikiPageServiceImpl` 1,008 · `WikiPageRepository` 858 · `WikiIngestDedupService` 851
——原列第 4 个 `WikiPageFolderSupport`（822）**已由卫生刀 w0 出榜**（折叠后 383 行，见下）。

**刀 0 侦察的新发现（已写进 §13.8 的教训）**：wiki/session 两批切片脚本产出的 **14 个协作者文件被写成
"每行后跟一个空行"**（空白行占比 55%~78%，仓库中位数 13%）——文件被撑大一倍以上，`wc -l` 榜单随之失真
（`WikiPageFolderSupport` 表面 822 行、实际 383 行；`SessionQaResolution` 707→184）。处置：
- **w0a** `scripts/normalize-blank-lines.py`（入库脚本，只删空行 + "非空行逐一相同"断言）：14 文件 7,346→2,821 行（`d1f8180`）；
- **w0b** `WikiPageLinkRepair` 源码里的**裸 NUL 字节**（`"\x00"` → `"\0"`，git 此前视该文件为二进制、diff 不可读）（`8be19c3`）。

**刀序与边界判定**（每刀一簇、独立提交）：

1. **w1 `WikiPageServiceImpl` → `WikiPageRevisionOps`**（修订历史 + 页面问题簇）：`revisionFromPage`(static) /
   `pruneRevisions` / `listRevisions` / `getRevision` / `revertPageToVersion` / `createIssue` / `listIssues` /
   `updateIssueStatus`（含 `deletePage` 里的历史清理 try 块 → `deletePageRevisions(page)`）。
   共享项：门面保 6 个 public 薄委托（接口面）；`updatePage` 内部改调 `revisionOps.revisionFromPage/pruneRevisions`；
   revert 经门面回引 `service.updatePage`（`WikiEditContext.callWith` 语义不变）。测试床（`WikiPageRevisionServiceTest`、
   `WikiPageServiceTest` 均 `@SpringBootTest` 注入接口）**零改动**。
2. **w2 `WikiPageServiceImpl` → `WikiPageLinkOps`**（链接维护簇 + wiki 链接文本静态工具）：`parseOutLinks` /
   `normalizeSlug` / `slugNamespace` / `stripWikiInlineChunkCitations` / `stripWikiPageInlineChunkCitations` /
   `updateInLinks` / `removeInLinks` / `rebuildLinks` / `injectCrossLinks` + 两个正则常量。
   外部引用改向：`SlugFuzzy`（2 处）、`WikiPageLinkRepair`（2 处）、`WikiPageViewsSupport`（1 处）、
   `WikiPageServiceTest`（5 个静态用例）。门面保 `rebuildLinks`/`injectCrossLinks` 两个 public 薄委托。
3. **w3 `WikiPageRepository` → `WikiFolderRepository`**（文件夹树段 615-707 + `folderNameExists`/`assertNoFolderConflict`）：
   新 `@Repository`（持 `WikiFolderMapper` + `WikiPageMapper`；**该段无方言分支，不需 DataSource**）。
   调用点改向：`WikiPageFolderSupport` 10 处（`service.repo.` → `service.folderRepo.`）+ `WikiPageRepositoryTest`
   文件夹用例段（换注入 bean）。顺带 **w3b**：`detectPostgres` 收敛到 `common/jdbc/DatabaseDialects`
   （同族 5 处早已收敛，wiki 是漏网；语义一致：探测失败 → 非 PG）。
4. **w4 `WikiIngestDedupService` → 静态算法簇外提**（**解除该类现有"846 行略超 800"的 §14.5 例外**——
   复核该例外理由后的判定：外提的是**纯静态函数**（无实例态），不产生"参数传递层"；
   编排（claim/stabilize/attach/reclaim/remap + `Identities` 结果载体）留在服务）：
   预筛/评分（`DedupSurface` / `countEntityConceptPages` / `selectDedupCandidatePages` / `dedupPairScore` /
   `slugBaseTokens` / `gramsPerSurface` / `surfaceGrams`）+ 身份文本（`normalizeWikiIdentityTitle` /
   `exactIdentityTarget` / `preferWikiIdentityDisplayName` / `mergeExtractedIdentity` / `appendUniqueString`）
   → 新包内类 `WikiIdentityDedup`（命名避让既有端口 `WikiDedupSupport`）。测试 `WikiIngestDedupServiceTest`
   的静态调用改向新类。

**闸门口径**：每刀 = `--rerun-tasks` 重编 + `--tests "com.ragagent.wiki.*"` + spotlessCheck + 忠实性逐字比对；
收官 = clean 全量 + 环守卫（`scripts/check-package-cycles.py`）+ §14.3/§7.2 刷新（本批不动前端契约）。

**落刀记录（2026-10-01 执行完毕，7 刀全绿；边界判定见上）**

| 刀 | 协作者 / 内容 | 结果 | 提交 |
|---|---|---|---|
| w0a | 空行折叠（14 文件：wiki 7 + session 7） | 7,346→2,821 行；`WikiPageFolderSupport` 出榜 | `d1f8180` |
| w0b | 裸 NUL → `"\0"`（WikiPageLinkRepair，git 视其为二进制） | diff 恢复可读 | `8be19c3` |
| w1 | `WikiPageRevisionOps`（修订 + 问题簇，166 行） | 1,009→923 | `e415d13` |
| w2 | `WikiPageLinkOps`（链接维护 + 链接文本工具，249 行） | 922→734（出榜） | `afc7716` |
| w3b | `detectPostgres` 收敛 `DatabaseDialects`（死 logger 一并清） | 858→846 | `a61b34e` |
| w3 | `WikiFolderRepository`（文件夹树 + 目录路径段，152 行；删死方法 `assertNoFolderConflict`） | 846→708（出榜） | `c49dd6a` |
| w4 | `WikiIdentityDedup`（纯算法簇，405 行；**解除 §14.5 例外**） | 852→468（新类 405） | `fcfebfc` |
| w5 | 收尾卫生：Go 锚点 5 处清零（§14.4 口径）+ 死 logger 2 处 | wiki 域 Go 锚点 0 | `80e9385` |

**结果**：四个目标类全部出榜——`WikiPageServiceImpl` 1,008→**732**、`WikiPageRepository` 858→**708**、
`WikiIngestDedupService` 851→**468**（例外解除）、`WikiPageFolderSupport` 822→**383**（空行折叠）；
**wiki 域 ≥800 清零**（最大类 `WikiIngestCitePipeline` 760）；全仓 ≥800 类 15→**11**。
验证：每刀 `--rerun-tasks` 重编 + wiki 域 542 条 + spotlessCheck + 忠实性逐字比对（w1 9/9、w2 11/11、
w3 12/12、w4 13/13 全等）；收官 `clean :server:test :server:spotlessCheck`（4,645 条 + 2 条环境依赖
用例失败——`GrepChunksRecordingTest`/`DatabaseQueryRecordingTest` 需本地 PG 15432，与改动无关，
改动前基线即如此）+ 环守卫通过（环 0 组）。
**本批新增坑位**：见 §13.8（切片产物"每行后跟空行"的排版 artifact / 裸 NUL 字节 / 死方法判别）。

### 14.7.15 datasource service 批（2026-10-01，DataSourceService 1,828→666）

**为什么先动它**：datasource 域三个 ≥800 类里它最长（1,828），且是域内所有同步路径的编排中枢；
两个连接器（`FeishuClient` / `NotionConnector`）各自独立成批，域内 Go 锚点随那批清。

**切片边界（按依赖方向，从叶子往上搬）**：
1. 结果落库 + 内部工具（叶子：只有下游，无上游）
2. 条目灌入（只依赖知识桥与上面的叶子）
3. 同步执行（handle + 流式路径，依赖前两者）

管理面（create/update/credentials/delete/validate/list… 11 个方法）与同步控制（manualSync/pause/resume/getSyncLogs）
留在服务里——它们是外部（两个控制器 + `YuqueClient`）直接消费的公开面。

**闸门口径**：每刀 = `--rerun-tasks` 重编（main+test）+ `--tests "com.ragagent.datasource.*"` + spotlessCheck +
忠实性逐字比对；收官 = clean 全量 + 环守卫 + §14.3/§7.2 刷新（本批不动前端契约、不动 wire 面）。

**落刀记录（2026-10-01）**

| 刀 | 协作者 / 内容 | 结果 | 提交 |
|---|---|---|---|
| d1 | `DataSourceSyncResultOps`（186 行）+ `DataSourceSupport`（290 行） | 1,829→1,438 | `51497cc` |
| d2 | `DataSourceItemOps`（338 行） | 1,433→1,129 | `30d0be7` |
| d3 | `DataSourceSyncExecutor`（500 行） | 1,125→**666**（出榜） | `46fec70` |

**结果**：`DataSourceService` 1,828→**666**（-63.6%），datasource 域 ≥800 由 3→2（剩两个连接器）；
忠实性逐字比对 d1 23/23 + d2 5/5 + d3 7/7 全等，datasource 域 921 条用例每刀全绿。

**手法要点（本批新增）**：
- **依赖方向决定搬的顺序**：先搬叶子（工具 / 结果落库），再搬调用它们的中间层（条目灌入 / 同步执行）；
  反过来做，每搬一层都要回头重指上层的调用点，且中间态编译不过。
- **协作者取态沿用 §13.3 回引**：服务把 9 个依赖字段放宽为包内可见、`MAPPER` 放宽为包内静态；
  协作者内一律 `service.<field>` / `DataSourceService.<常量>`；**静态工具被跨类调用时用类名限定，不走回引**。
- **两个隐蔽坑**：①嵌套类 `StreamSyncHandler` 持自己的 `svc` 引用，`svc.applyFetchedItem(...)` 这类调用点
  在机械替换里会被漏掉（`(?<![\w.])` 前缀断言挡住了 `.` 前导）——编译能抓到，按提示改；
  ②接口方法（`handle`）**搬体不搬签名**：服务留 `@Override` 薄委托，详细 javadoc 随实现走，避免文档与实现分家。

### 14.7.16 datasource 连接器批（2026-10-01：FeishuClient 1,155→709 + NotionConnector 1,093→625）

**为什么单独成批**：它是飞书连接器的唯一出口（wiki 空间/节点、Drive 列举、导出与下载、表格块），
调用方是 `WikiConnector` / `DriveConnector` / `SyncEngine` / 若干测试；公开面保住即零改动。

**切片边界（同 §14.7.15 的"从叶子往上搬"）**：
1. 传输层（token 缓存 + `doRequest` 退避 + `Retry-After`）——认证状态随实现走，客户端只留测试缝常量
2. wiki 树遍历（空间/节点/递归遍历）
3. Drive 列举（单页/收全/递归）

导出与下载、docx 块、表格块三段留在客户端（下一批再切）。

| 刀 | 协作者 / 内容 | 结果 | 提交 |
|---|---|---|---|
| f1 | `FeishuTransport`（216 行） | 1,155→991 | `58ce0d5` |
| f2+f3 | `FeishuWikiTreeOps`（235 行）+ `FeishuDriveOps`（167 行） | 991→**709**（出榜） | `368ea95` |
| n1 | `NotionFetchOps`（495 行）：抓取簇整段（fetchPage / fetchDatabase / 增量 / 记录查询 + 两个嵌套结果类型） | 1,093→**625**（出榜） | `a0940d3` |

**Notion 侧的手法要点**：整段搬迁（"内部：抓取"是连续 465 行，一段一搬比拼成员更快）；
嵌套结果类型 `DatabaseIncremental` 提到包内静态（留在连接器的 `fetchIncremental` 要读它的字段）；
被协作者回调的连接器私有方法（`getDatabaseOrDataSourceInfo` / `equalInstants`）放宽为包内可见；
测试对包内 helper 的直呼按新家改向（`connector.buildRecordItem` → `connector.fetchOps.buildRecordItem`）。

**手法要点（本批新增）**：
- **委托换零改动**：客户端为每个搬走的公开方法留同名薄委托（含静态 `parseRetryAfter`），
  于是 `doRequest` / `doRequest` 的全部内部调用点与四个调用方一处都不用改——比"改所有调用点"省一半 diff。
- **公开 DTO 留在原类**：`DriveFilePage` 记录不搬（调用方类型引用会炸），协作者按 `FeishuClient.DriveFilePage` 引用。
- **递归遍历的 `this` 传参**：`walkWikiNodes(this, …)` / `walkDriveFolder(this, …)` 里的 `this` 在新类里是协作者，
  必须换成回引（`walkXxx(client, …)`）——这是机械替换最难自动发现的一类，编译期能抓到但要看懂报错。
- **测试缝常量不搬**：`MAX_RETRIES` / `MAX_5XX_RETRIES` / `retry5xxDelay` / `retryBackoff` 留在客户端，
  传输层按类名读取（保住"测试可覆盖"这条既有约定）。

### 14.7.17 memory 域批（2026-10-01 起：三神类）

**为什么最后动它**：memory 域三个类互相咬合（服务 → 仓储 → 抽取服务），且是唯一带"后台蒸馏"的域；
按 §14.7.15 的"依赖方向定序"，先切最外层的抽取服务（m1），再切仓储（m2），最后切服务门面（m3）。

| 刀 | 协作者 / 内容 | 结果 | 提交 |
|---|---|---|---|
| m1 | `MemoryExtractionLlm`（399 行）+ `MemoryTranscriptOps`（159 行） | 1,217→**718**（出榜） | `30213c4` |
| m2 | `MemoryItemStore`（462 行）：条目写/读 + 生命周期 + 墓碑（仓储原 L213-638） | 1,515→**1,200** | `a2d83ef` |
| m3 | `MemoryIndexStore`（929 行）：话题统计/文档亲和/向量/抽取进度/内部工具/方言探测（仓储原 L326-1199） | 1,200→**458**（出榜） | `cf8c2ca` |
| m4 | `MemoryCatalogOps`（517 行）+ `MemoryInsightOps`（412 行）：目录管理 + 检索/亲和 | 1,663→**965** | 见下 |
| m5 | `MemoryRecallOps`（252 行）：召回段（`Recall` + 两个 trace 辅助 + 块内命中筛选） | 965→**768**（出榜） | 见下 |

**m1 手法要点（与前面几批的差异点）**：
- 两簇里混着 `static` 工具与实例方法：**静态走类名限定**（`MemoryExtractionLlm.parseExpiry(...)`）、
  实例走协作者字段（`llmOps.relevantExisting(...)`），服务侧调用点按这条规则一次性改完。
- 服务把 6 个依赖字段 + 4 个常量（`MAPPER` / `LINE_TIME` / `EXTRACTION_SCHEMA` /
  `EXTRACTION_SYSTEM_PROMPT`）放宽为包内可见——**常量与 MAPPER 这类被跨类读的静态也要放宽**，
  这是本批新增到清单的一条。
- 服务的**嵌套类型**（`TranscriptSegment` / `TranscriptLine` / `InvalidExtractionOutputException`）
  不搬，协作者按 `MemoryExtractionService.X` 引用；搬走的 `ExtractionDecision` / `ExtractionResponse`
  的 `@JsonIgnoreProperties` **必须跟着走**（漏了会让模型输出反序列化静默变宽容）——javadoc/注解与
  方法体不在同一个块里时，harness 容易漏，要在核验时专门看一眼。

**m2 手法要点**：条目簇（426 行）**整段换 22 个同名薄委托**——公开面一字不动，仓储的几十个调用点（含测试）
零改动；代价是委托占 ~90 行，所以净减 315 行。仓储的 9 个 mapper/事务字段与 `postgres` 放宽为包内可见，
三个内部静态工具（`applyInsertDefaults` / `stampForCreate` / `copyInto`）放宽为包内静态供协作者回调。

**m3 手法要点与遗留**：整段（874 行）换 28 个薄委托，仓储 1,200→**458**；新协作者 `MemoryIndexStore`
929 行登记为已知例外（见下「不硬切」判据）。**"整段搬"要如实报账**：一段搬完不等于出榜——
汇报须说明净效果（出榜一个、新协作者 929 仍在榜），别用"出榜"掩盖。
两个坑：①`repo.postgres` 这类字段前缀会被正则打进**字符串字面量与 javadoc**（`"postgres"` 变 `"repo.postgres"`），
收尾必须扫一遍字面量；②前缀替换会打到**被搬成员的声明行**（`static void X.helper(`），编译报
"invalid method declaration" 即是此症状——修法是把声明行的类名前缀回退。

**m5 手法要点（2026-10-01，「召回」段外提 `MemoryRecallOps` 252 行）**：
- 段内依赖只有四类：仓储 `repo`、选择器 `recallSelector`、三层开关 `enabledScope` / `scopeDisableReason`、
  用量回写 `touchAsync`——按 m4 惯例用 `service.` 回引；日志换成本类自己的 logger（8 处 `log.` 不用动）；
  `ScopeState` 这类嵌套类型用 `MemoryService.ScopeState`（§13.2「嵌套类型不能委托」）。
- **`touchAsync` 留在门面**：`MemoryInsightOps.searchMemory` 也在用它（跨段共享），搬走会把协作者变成互调。
- 可复用教训三条：
  ① **干跑断言的行数算式要把"顺手加的字段与构造行"算进去**——我起初按 `加 = 委托行数`，
     实际还有字段块 3 行 + 构造 1 行，断言当场抓住（这正是干跑断言的价值，别嫌它啰嗦）；
  ② **被测试直接调用的包内 helper 跟着搬，并改测试调用点**（`residentItemsWithinBlock` 被
     `MemoryExtractionHelpersTest` 调）——比为测试在门面留一个死委托干净；
  ③ **删未用导入时别信被 `head` 截断的 grep**：`MemoryRender` 的另一次使用在第 668 行（首轮 grep 被截掉），
     误删后靠"编译失败"才发现——**编译是权威**（§13.7）。

**`MemoryIndexStore` 929 的处理（用户 2026-10-01 定调：不硬切）**：它的六段（话题统计 / 文档亲和 /
向量 / 抽取进度 / 内部工具 / 方言探测）同属「索引侧读写」一个关注点，**为压进 800 再切一刀只有数字意义**，
故登记为已知例外。将来若「向量段」因独立演进出现真实接缝（如 pgvector 换成独立存储），再按自然边界外提
——**判据是接缝，不是行数**。
- **`MemoryService` 1,662（下一批的正式目标，2026-10-01 已侦察到位）**：四段分布为
  `开关：工作区配置与三层判定` L114-227（约 114 行）→ `召回` L232-474（约 243 行，含
  `finishSubjectLoadFailure` / `recallEmptyMeta` / `residentItemsWithinBlock`）→ `写入路径` L475-713
  （约 239 行，`remember` + `findContainedDuplicate` + `statusForWrite`）→ `记忆管理器` L714-1662
  （约 950 行，占本类一半以上）。
  **建议两刀**：m4 = 记忆管理器段**按自然接缝对半外提**——「条目 CRUD」（`listItems` / `createItem` /
  `updateItem` / `deleteItem`）与「主题·文档视图」（`listTopics` / `promoteTopic` / `deleteTopic` /
  `listDocuments` / `deleteDocument` / `familiarKnowledgeIds`）各成一个协作者（各约 450~480 行，
  两个新类都 <500，避免再造一个 929 式大件）；m5 = 若切完仍明显超阈值，再评估 `召回` 段
  （243 行，独立关注点，接缝自然）。
  **两刀均已执行**（m4 见上表；m5 = 召回段 209 行 → `MemoryRecallOps` 252 行，服务 965→768 出榜）。

### 14.9b 小域打样（阶段 3 起手，2026-10-01 用户拍板：小域打样）

**目的**：先跑通「盘点 → 换锚 → 同批改前端 → 验收」整条流水线，再按域推重域（auth 247 / session 188 / datasource 127 / memory 123 / mcp 110 / system 86 / model 78）。

**选域结论（2026-10-01 只读盘点；执行时修正两处误判，见下）**：
- **`evaluation` 域 = 打样首选**：`dto/EvaluationDtos.java` 单文件集中 **62 处 `@JsonProperty`**（+ controller 内 4 处全限定写法，占该域全部），
  配一个 `controller/EvaluationController.java`；HTTP 面小且集中，改一处即是一整条端点的换锚。
  **修正①**：注解实测 66 处（侦察写 69 是口径差：漏算 controller 全限定写法、多算了 `@JsonPropertyOrder` 等）。
  **修正②**：**前端无调用面**——`frontend/src/` 仅有 API-Key capability 名 `run_evaluations` 与 i18n 文案，
  与端点无关；"前端有调用面可同批改"是误判，本域因此不是"前后端同 PR"的完整样本。
- **`wiki` 域不作为打样域**：其 39 处经甄别**全部是 wiki 内部载荷**（`WikiIngestPayload` 队列载荷、
  `WikiRetractPayload`、`WikiPendingOp` 待办行、`WikiFinalizeRow`/`WikiFinalizeChange` 批次结果、
  `CombinedExtraction` / `NewSlugFromCitation` 模型输出形状），`git grep` 确认**无跨包消费者**；
  按 §14.9 三分法多数落 ②（事件/队列载荷→保留），少数（若写进 jsonb 列）落 ③ —— **需逐类确认"是否落库"**，
  属"甄别"工作而非端点换锚。wiki 的 HTTP 端点面（`WikiPageController` 等）另立切片。

**打样执行记录（2026-10-01 完成，提交 `9ed0847`）**：

**① 入场清单（只读产物）**：

| 端点 | 请求（旧 → 新） | 响应（旧 → 新） | 涉及 fixture | 前端调用点 |
|---|---|---|---|---|
| `POST /api/v1/evaluation`（Admin） | rawBody 手绑 snake 4 字段 → `EvaluationRequest` record（camelCase，字段全可选） | `{data:{task,params,metric},success:true}` → 裸 `EvaluationDetail` | `ev-post`（创建快照）/ `ev-post-empty`、`ev-post-kb-missing`（500）/ `ev-post-badjson`（400）/ `ev-post-viewer`（403）/ **新增 `ev-post-nobody`**（400 空体） | **无** |
| `GET /api/v1/evaluation?taskId=`（Viewer） | query `task_id` → `taskId` | 同上去信封 | `ev-get`（终态含 metric）/ `ev-get-viewer` / `ev-get-missing`（400）/ `ev-get-unknown`（500） | **无** |

**② 换锚（改动面）**：
- `EvaluationDtos`：去 62 处 `@JsonProperty` + 12 处 `@JsonInclude` + 6 处 `@JsonPropertyOrder`——
  字段名即键名；可空字段显式 `null`（`metric` 未产出输出 `null` 而非缺键；`thinking`/`citationEnabled` 同）；
  `startTime` 默认值由 Go 零值时间改 `null`（`GoTimeSerializer` 引用随之摘除）。7 个嵌套类结构不动
  （容器拆分 = 一类型一文件，另立批次，不与换锚混轴）。
- `EvaluationController`：手写 `bind()`/`MAPPER`/`GoJsonBindError` 全删 →
  `@Valid @RejectEmptyBody @RequestBody(required = false) EvaluationRequest`（knowledge 域同款惯例）；
  响应改裸 `EvaluationDetail`；GET `task_id` → `taskId`，缺失校验文案对齐全局形态
  （`请求参数不合法` + `taskId: 不能为空`）。新增 `dto/EvaluationRequest.java`（一类型一文件）。
- **错误信封形态未动**（`{error:{...},success:false}` 的去 `success` 属全仓统一批）——本批只替掉
  evaluation 自己手写的那层绑定文案（`ev-post-badjson`/`ev-get-missing` 两 fixture 因此变化）。
- 测试：`EvaluationContractTest` 8 用例（新增空体 400）+ `JsonContractRoundTripTest` label 同步。

**③ 同批前端**：**零改动**（该域前端无 HTTP 调用面，见 ①）——完整"前后端同 PR"样本待第二域
（建议挑前端真有调用的域，如 auth/memory）。

**④ 验收**：
- 干净一遍全量：**4669 用例 / 失败 0 / 跳过 4**（434 测试类）+ `spotlessCheck` 绿；
  ⚠️ 首轮全量出 1 个失败（`DataSourceJsonTest`）是 shell 环境泄漏所致，见 §13.9 新条；
- **真实服务冒烟 8 路通过**（`bootRun` + 真实 PG/Redis + 注册登录拿 token）：
  POST `{}`→500 无默认模型 / 空体→400「请求体不能为空」/ 畸形 JSON→400「请求体格式不正确」/
  字面量 `null` 体→零值放行 / camelCase 新键→识别（500 knowledge base not found）/
  旧 snake 键→宽松读忽略（不再映射）/ GET 缺 taskId→400「taskId: 不能为空」/ GET 未知→500 task not found。

**打样暴露的方法论补充**：选域侦察时的"前端有调用面"要**逐调用点 grep 实证**（本域只查到了
API-Key capability 名与 i18n 文案，差点误当调用面）；换锚批可顺手做"请求侧手写绑定器清除"
（与 §14.2 步骤 4 一致），但错误信封统一与容器拆分都**不与换锚混轴**。

### 14.9c 第二域：model 换锚（M1 主资源，2026-10-01）

**选域理由**：① HTTP 面与落库面**已解耦**（`ModelResponse.from` 显式字段构造，改 DTO 不牵动
`ModelParameters` 落库 jsonb）；② 前端**有真实调用面**——补上打样缺的"前后端同 PR"完整样本；
③ 端点 10 个、形态齐全（CRUD 201/204 + 列表 + providers + credentials 子资源）。

**M1 范围（已完成，提交 `c87fdd7`（后端）+ `e6d1af5`（前端））**：`ModelController`（6 端点）+ `ModelCredentialsController`（2 端点）
+ DTO 9 文件（新增 `ModelParametersRequest`）+ 15 个 fixture + `ModelContractTest` + 前端 12 文件。

| 端点 | 请求（旧 → 新） | 响应（旧 → 新） |
|---|---|---|
| `POST /api/v1/models`（Admin） | `parameters` 由**直绑落库实体** `ModelParameters` → 新 `ModelParametersRequest`（含 apiKey/appSecret，`toDomain()` 显式转换）；其余字段去 snake 注解 | `{data:…,success:true}` → 裸 `ModelResponse`（201） |
| `GET /api/v1/models` / `{id}`（Viewer） | — | 裸对象 / 裸数组（去信封） |
| `PUT /api/v1/models/{id}`（Admin） | 同上；空值覆盖语义（name 空串不改、type/source 无条件覆盖）保留 | 同上 |
| `DELETE /api/v1/models/{id}`（Admin） | — | `{message,success}` → **204 无响应体** |
| `GET /api/v1/models/providers`（Viewer） | query `model_type` → `modelType` | 去信封 |
| `PUT /api/v1/models/{id}/credentials`（Admin） | `{api_key,app_secret}` → `{apiKey,appSecret}` | 去信封（`fields` 键恒为 `api_key`/`app_secret`） |
| `DELETE /api/v1/models/{id}/credentials/{field}` | `{field}` 取值域**保留** `api_key`/`app_secret` | 204（原样） |

**有意保留（勿当漏网）**：① credentials 的 Map 键与 `{field}` 路径值 `api_key`/`app_secret` 是
**字段标识符**（DELETE 路径取值域 + 前端 `ModelCredentialField` 类型共用），不是蛇形命名债；
② `ModelParameters`（19 处，落库 jsonb）属**落库换锚批**（存量数据键名兼容）；③ `ModelDebug*`（14 处）
属 M2；④ 错误 details 的 `ModelUsage` 载荷键（`knowledge_bases` 等）属错误形态批。

**M2/M3 待办**：`ModelDebugController`（multipart + 手搓响应 Map + 24 个 md-* fixture）与
`WeKnoraCloudController`（Map 手绑 + `{message,success}`）；model 域 `@JsonProperty` 现状 **87 → 33**
（剩 debug 14 + 落库 19）。

**验收**：后端干净一遍全绿（4669 用例）+ `spotlessCheck`；前端 `vue-tsc` 0 错误 + **690 用例全绿**
（首次前后端同 PR）；**真实服务冒烟 11 路**：创建 201 裸对象 / 列表裸数组 / 单查 / 更新（覆盖语义保持）/
credentials 写入与查询 / credentials 删除 204 / providers `?modelType=` / 缺 name 400 / 删除 204 /
旧 `model_type` 参数被忽略。

**顺带修**：`@Valid` 显式 message（§13.10 的 locale 漂移）；前端**类型逃逸**漏网点 2 处——
`thinkingControl.ts` 的局部结构类型 `parameters: { extra_config?: … }` 与
`initialization/index.ts` 的 query 参数，**vue-tsc 不报错也要 grep 兜底**（局部类型绕过编译器）。

### 14.9e model 域收官三块（M2 debug + M3 weknoracloud + 落库面，2026-10-01）

**提交**：`8f1af81`（M2）+ `91311a0`（M3）+ `7d55435`（落库面）+ `290557b`（前端）。

**M2：debug 端点（`POST /api/v1/models/{id}/debug`）**
- 响应去 `{data,success}` 信封 → 裸 `ModelDebugResult`（`ok`/`elapsedMs`/`error`/`request`/`rawResponse`/
  `observations`，`error` 恒输出）；运行时错误仍**落在结果对象里**（HTTP 恒 200——这是"调试结果"语义，
  不是 HTTP 错误；参数校验错误才是 400 错误信封）
- `rawResponse` 的 chat/asr 分支去 snake（`reasoningContent`/`toolCalls`/`finishReason`/`streamEvents`）；
  **不动**：`usage` 与 `streamEvents` 的**元素**（LLM 域事件载荷）、rerank 的 `RankResult`（rerank 域）、
  `extraConfig` 的 map 键（用户自定义配置键）
- 可空字段显式 null（chat 的 reasoningContent/toolCalls、asr 的 segments）
- options JSON 键改 camelCase（`systemPrompt`/`topP`/`maxTokens`）；输入解析错误文案去 Go 仿真
  （`invalid options: malformed JSON`、`systemPrompt must be a string`、`maxTokens must be between 1 and 8192`…）
- 请求预览去 snake（`modelId`/`modelName`/`modelType`/`customHeaderNames`/`modelExtraConfig`）+ observations
  去 snake（`answerCharacters`/`reasoningCharacters`/`resultCount`/`segmentCount`/`textCharacters`/
  `requestedThinking`/`thinkingControl`/`thinkingParameterSent`/`reasoningReturned`）；温度/topP 预览改
  标准 Jackson 数字（原 `GoDoubleSerializer`+RawValue 去除——预览不属工具面）
- fixture：24 个 `md-*` 重录；`ModelDebugContractTest` 24 用例绿；前端 `ModelDebugDrawer` 同步

**M3：weknoracloud 端点**
- `POST /api/v1/weknoracloud/credentials`：Map 手绑 → `WeKnoraCloudCredentialsRequest`（camelCase + @Valid
  显式 message）；错误 `{"error":"原文"}` 直写 → AppError 信封；成功 `{message,success}` → **204**
- `GET /api/v1/models/weknoracloud/status`：手搓 ObjectNode → `WeKnoraCloudStatusResponse`
  （`hasModels`/`needsReinit`/`reason`，reason 恒 null 保留位）
- fixture：`weknoracloud-status` 重录 + 新增 `weknoracloud-cred-validation`（校验路径可测；外呼校验路径
  需真实 WeKnoraCloud，不做集成）；前端 3 个调用点同步

**落库面（`models.parameters` jsonb）**
- `ModelParameters` 去 19 处 `@JsonProperty` + `@JsonPropertyOrder` + `@JsonInclude(NON_NULL)`：
  jsonb 键名 = Java 字段名（camelCase）；`BuiltinModelsReconciler.parseParameters` 的 YAML 键名同步
  （`config/builtin_models.yaml` 当前不存在，属可选配置）
- **存量数据**（系统未上线、无正式存量；按 §11 `ecbdf56` 同一原则）：**不做代码层宽容读**——
  TypeHandler 的 mapper 保持严格，旧格式行会显式报 "Unrecognized field"（避免静默丢参数）；
  dev 库旧行用下方 SQL 一次性改写（2026-10-01 已对 ragagent 库执行，`UPDATE 1`）
- 迁移 SQL（其他环境复用；embeddingParameters 子对象单独改写）：
  ```sql
  UPDATE models SET parameters = (
    SELECT jsonb_object_agg(
      CASE e.key
        WHEN 'base_url' THEN 'baseUrl' WHEN 'api_key' THEN 'apiKey'
        WHEN 'interface_type' THEN 'interfaceType' WHEN 'embedding_parameters' THEN 'embeddingParameters'
        WHEN 'parameter_size' THEN 'parameterSize' WHEN 'extra_config' THEN 'extraConfig'
        WHEN 'custom_headers' THEN 'customHeaders' WHEN 'supports_vision' THEN 'supportsVision'
        WHEN 'context_window' THEN 'contextWindow' WHEN 'max_output_tokens' THEN 'maxOutputTokens'
        WHEN 'max_concurrency' THEN 'maxConcurrency' WHEN 'app_id' THEN 'appId'
        WHEN 'app_secret' THEN 'appSecret' ELSE e.key END,
      CASE WHEN e.key = 'embedding_parameters' AND jsonb_typeof(e.value) = 'object' THEN (
        SELECT jsonb_object_agg(
          CASE e2.key WHEN 'truncate_prompt_tokens' THEN 'truncatePromptTokens'
                      WHEN 'supports_dimension_override' THEN 'supportsDimensionOverride'
                      ELSE e2.key END, e2.value)
        FROM jsonb_each(e.value) e2
      ) ELSE e.value END)
    FROM jsonb_each(parameters) e)
  WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(parameters) k
    WHERE k IN ('base_url','api_key','interface_type','embedding_parameters','parameter_size',
                'extra_config','custom_headers','supports_vision','context_window','max_output_tokens',
                'max_concurrency','app_id','app_secret'));
  ```

**model 域收官口径**：`@JsonProperty` **87 → 0**（主资源 54 + debug 14 + 落库 19 全清）；
M1/M2/M3 + 落库面四块完成，域内无 `@JsonInclude`/`Go*` 序列化引用。

**验收（2026-10-01）**：后端干净一遍 **4670 用例 / 失败 0 / 跳过 4**（434 测试类，比上批 +1 =
M3 新增校验用例）+ `spotlessCheck` 绿（`spotlessApply` 顺手删掉 `BuiltinModelsReconciler` 的僵尸
`java.util.UUID` import）；前端 `vue-tsc` 0 错误 + **690 用例全绿**；**真实服务冒烟**：cloud status
（`{hasModels:false,needsReinit:false,reason:null}`）/ 模型创建→库中 jsonb 即 camelCase→单查反序列化
/ 凭证缺字段 400（显式 message）/ debug 未知模型 404。**全量抓到一处漏改**：
`W5bInitializationContractTest` 用 SQL 直写旧键名 jsonb（§13 判据：落库面换锚后要全仓搜
`models SET parameters` 这类直写点）。

### 14.9f system 域换锚（S1：/system 读端与探测端，2026-10-01）

**提交**：`f7df80e`（后端）+ `09805ee`（前端）。

**选域与边界**：system 域 99 处 `@JsonProperty`（`SystemDtos` 85 + `SystemSetting` 14）。本批（S1）做
**`SystemController` 的 7 个端点**（capabilities / info / parser-engines / parser-engines/check /
docreader/reconnect / storage-engine-status / storage-engine-check）；`SystemAdminController`
（账号 / 平台密钥 / 设置 / runtime 面，S2~S4）与 `SystemSetting` 落库面留后续批次。

**边界（§11 不动面）**：parser-engines/check 与 storage-engine-check 的**请求键名保持 snake**——
与租户配置 jsonb（`chat_parser_engine_rules` 等）同形，待该边界解冻后统一 DTO 化（controller
javadoc 已注明）。

**换锚内容**：
- 响应去 `{"code":0,"data":…,"msg":"success"}` 信封 → 裸资源对象；新增
  `ParserEnginesResponse`（connected / docreaderAddr / docreaderTransport / engines 收拢原顶层散键）；
  错误 `{"code":1,"msg":…}` → AppError 信封
- `SystemDtos` 去 85 处注解：键名 camelCase（含 `ParserEngineInfo` 的历史**大写键** Name/Description/
  FileTypes/Available/UnavailableReason → 小写 camelCase）、可空字段显式 null（reason/commitId/…）
- 错误语义化：docreader 连接失败 200+`code:1` → **503**；被禁存储引擎 403（保持）；请求体坏 400
  （统一 `请求参数不合法` + `请求体格式不正确`）
- **共享 DTO 波及**：`SystemAdminController` 的 promote/revoke/list/createUser/runtime-queues 响应体
  随 `UserInfoResponse`/`SystemAdminListResponse`/`CreateUserResponse`/`RuntimeQueuesResponse` 一并
  camelCase（端点外壳未动，留给 S2）——`adm-*` fixture 同步 14 个（`adm-key-*` 属 auth 域，**不动**；
  `adm-settings-*` 属 S3 的 `SystemSetting` 面，**不动**）

**验收**：后端干净一遍 **4670 用例 / 失败 0 / 跳过 4**（434 测试类）+ `spotlessCheck` 绿；
`SystemContractTest` **19/19 绿**（掩码模式 `[a-z_]+`→`[a-zA-Z_]+` 支持 camelCase、内联断言更新）；
前端 `api/system` 响应类型去解包 + camelCase，11 个组件/store 同步（`vue-tsc` 0 错误 + 690 用例绿）；
**类型逃逸兜底**：`GraphSettings.vue` 的 `ref<any>` 绕过编译器，靠 grep 抓到 `graph_database_engine`
残留（同 §14.9c 判据：改完 vue-tsc 仍要 grep）。

**system 域进度口径**：`@JsonProperty` **99 → 14**（余 `SystemSetting`，属 S3）。下一步候选：
S2（账号面）/ S3（密钥+设置+runtime）/ S4（配额）或转 auth/memory 域。

### 14.9g system 域收官（S2/S3/S4：admin 面，2026-10-01）

**提交**：`6ff4333`（后端）+ `1b835d9`（前端）。

**范围**：`SystemAdminController` 全部端点 + `SystemSetting`（14 处注解）。

**S2 账号面**（promote / revoke / list / users/reset-password / users/create）
- 请求 rawBody 手绑 → 标准 DTO（camelCase + @Valid 显式 message，如 `userId: 不能为空`）；
  `PlainErrorException`（`{"error":"原文"}`）→ AppError 信封；重置密码 `{message}` → **204**
- service 层 5 处 `PlainErrorException` 一并转 AppError（self / last-admin / not-found / policy / conflict）

**S3 平台密钥 + 设置**
- api-keys：`{data,success}` → 裸数组 / 裸对象；删除 → **204**；创建请求 DTO（`expiresAtUnix` 等）
- settings：`SystemSetting` 去 14 处注解（`valueType`/`isSecret`/`requiresRestart`/`lastModifiedBy`/
  `createdAt`/`updatedAt`/`enumOptions`/`lastModifiedByName`——**含 `enum` → `enumOptions` 键名修正**，
  字段名即键名）；PUT 请求 → DTO（`value` 必填）；删除 → **204**

**S4 runtime + 配额**
- tasks 查询参数 `page_size` → `pageSize`；mutate / purge 的 503 从纯字符串 → AppError（1008）
- 配额应用响应手搓 Map → `StorageQuotaApplyResponse`（`affected`/`quotaBytes`/`quotaGb`）

**有意保留（勿当漏网）**：① **审计 details 的键名**（`target_email`/`quota_gb`/`old_value`/`task_id`…）
——跨域事件载荷（RBAC / settings / 队列产生方一起改才自洽），属独立批次；本批前后端一致保持原样；
② `adm-guard-*` 的 403 文案（`{"error":"Forbidden: …"}`，`GuardForbiddenException` 未换锚）；
③ auth 域 API Key 响应元素（`api_key`/`created_at`，auth 域未换锚）。

**验收**：`SystemContractTest` 19/19 绿；前端 5 文件同步（`vue-tsc` 0 错误 + 690 用例）；
**类型逃逸兜底**：前端 admin 面类型是手写接口——后端改键名 `vue-tsc` **不报错**，靠 grep 抓到
`SystemAuditLog.vue` 的审计 details 读取（据此判定为保留面）。

**system 域收官口径**：`@JsonProperty` **99 → 0**（S1 的 `SystemDtos` 85 + S3 的 `SystemSetting` 14）。

### 14.9h auth 域 A1（登录 / 会话 / 用户信息面，2026-10-01）

**提交**：`51a8683`（后端）+ `801623d`（前端）。**口径修正**：auth 域实测 **370 处**（含全限定写法；
此前 272 是漏算——**统计要两种写法都 grep**）。

**A1 范围**：`auth/dto` 登录/注册/会话/OIDC/邀请查询面 + `domain/{User,UserPreferences,Tenant}` +
`dto/TenantResponse` + `TenantAPIPrincipalController`。

**形态**：
- `/login`、`/auto-setup`、`/switch-tenant`：成功裸 `AuthLoginResponse{user,activeTenant,memberships,token,refreshToken}`；
  **失败 401 + AppError**（原 200/401 + `{success,message,...}`）
- `/register`：201 + **裸 User**；`RegisterResponse` 类型删除
- `/logout`、`/change-password`：**204**；`/refresh`：裸 `TokenPairResponse{token,refreshToken}`
- `/invitations/lookup`、`/tenants/{id}/api-principal-*` 三端点：去 `{data,success}`
- `/config`→`AuthConfigResponse`；`/validate`→裸 `UserInfo`；
  `/me`→`CurrentUserResponse{capabilities,memberships,preferenceDefaults,tenant,tenantRequired,user}`；
  `/me/preferences`→裸 `UserPreferences`；`/oidc/{config,url}` 去 `success` + camelCase
- 请求侧：`@Valid` + `@NotBlank`（显式 message）+ `@RejectEmptyBody`/`@NonNullBody`；
  邮箱正则与码点长度沿用 gin 语义并**逐条收集**（与 Go 一次返回全部错误一致）

**本批最大教训（共享实体链条）**：`User` → 所有返回 user 的端点；`Tenant`/`TenantResponse` →
登录响应 `activeTenant` **与租户 CRUD（`w5a-tenant-*`/`ct-*`）**——一次换锚牵动 4 个测试类、
~50 个 fixture。`UserPreferences` 的 `NON_NULL` **保留**（局部更新协议：省略 = 保持原值，
不是省略美化）——只改键名。
**另一处坑**：`@JsonProperty` 与 `@JsonInclude` 的**全限定写法**（`@com.fasterxml...`）会漏过
短名 grep（`Tenant` 的 `defaultStorageBackendId` 因此残留 NON_NULL，多花了 3 轮才定位）。

**边界保留**：`auth/domain/tenantconfig/` 130 处（§14.6「租户配置 jsonb」）；
`refresh`/`switch-tenant` 的宽容手绑（`bindRefreshBody`/`bindSwitchTenantRequest`）暂留。

**验收**：auth 域 183 用例全绿；后端全量 + spotlessCheck 绿；前端 `vue-tsc` 0 错误 + 690 用例。

**A1 未做（后续批）**：**A2**（租户/成员/邀请列表：`TenantMemberResponse`/`Membership`/`TenantInvitationResponse`
+ `TenantMemberController` 的手搓 Map `envelope()`/`successOnly()`）；**B**（`apikey/domain` 41 处）。

### 14.9i auth 域 A2（租户/成员/邀请/配置面，2026-10-01）

**提交**：`142c58a`（后端）+ `ae67a07`（前端）。

**范围**：`TenantMemberResponse`(8)/`TenantInvitationResponse`(18)/`TenantInvitation`(1) +
`TenantMemberController`/`TenantInvitationController`/`TenantCrudOps`/`TenantConfigOps`/`TenantCreateOps`/`TenantCatalogController`。

**形态**：
- 成员/邀请列表：`{members,page,pageSize,total}` / `{invitations,page,pageSize,total}`（去信封）；
  邀请的 `isShareLink`/`acceptedCount` 由 omitempty 改**显式输出**
- 租户 CRUD：create/get/update → 裸 `TenantResponse`；delete → **204**（原 `{message,success}`）
- KV 配置 6 类：GET/PUT → 裸 config 对象（原 `{data,message,success}`）；
  **web-search 无配置 → 显式 `null`**（原 `{"data":null,"success":true}`，直接返回 Java null 会变空体，
  需 `NullNode`）
- 动作 **204**：成员改角色/移除/离开、邀请撤销/拒绝
- `/me/invitations*`：裸 `{invitations,total}` / `{pendingCount}` / `membership + tenantName`
- **三个手搓封装辅助删除**：`TenantMemberController.envelope`/`successOnly`/`TenantConfigOps.envelopeWithMessage`
  ——去信封的连带清理（引用点 12 处）

**验收**：auth 域 183 用例全绿；全量 **4670 / 失败 0 / 跳过 4** + spotlessCheck 绿；
前端 `vue-tsc` 0 错误 + 690 用例（20 文件同步）。

**A2 时发现的 A1 前端遗漏**：`UserInfo.tenant_id/created_at/updated_at` 与 `getCurrentUser` 的
`{success,data}` 解包在 A1 未同步（vue-tsc 骨架不报，靠本轮类型收紧才暴露）——
**换锚批的前端要按"后端 DTO 字段全集"逐项核对，不能只改明显的少数**。

**该域遗留（B 批）**：`apikey/domain` 4 文件 41 处（`TenantAPIKey`/`TenantAPIKeyCreateResponse`/
`TenantAPIKeyResponse`/`TenantAPIKeyRequest`）+ 平台密钥端点元素；前端 `ApiIntegrationSettings.vue`
的 API Key 面（`expires_at` 等）保持 snake 待同批。另：`APIPrincipalConfig`（jsonb 落库结构）
与 `auth/domain/tenantconfig/`（126 处）为**边界保留**。

### 14.9j auth 域 B 批（API 密钥面）+ 该域收官（2026-10-01）

**提交**：`40e683c`（后端）+ `81cf3bd`（前端）。

**范围**：`TenantAPIKey`(13)/`TenantAPIKeyResponse`(10)/`TenantAPIKeyCreateResponse`(11)/
`TenantAPIKeyRequest`(5) + `TenantAPIKeyController` 四端点。

**形态**：
- 列表 → 裸数组；创建 → 裸 `TenantAPIKeyCreateResponse`（201，一次性 token）；更新 → 裸 DTO；**删除 → 204**
- 可空字段显式 null（`lastUsedAt`/`expiresAt` 恒在，原 omitempty 省略）
- **请求体同批 camelCase**（`fullAccess`/`knowledgeBaseIds`/`expiresAtUnix`）——请求侧与响应侧一致
- 校验文案 camelCase（`knowledgeBaseIds contains ...`）；手搓 `body()` 封装删除

**⚠️ 本批最危险的坑（自动化替换差点毁落库）**：`knowledge_base_ids` 在 `TenantAPIKeyMapper`
的**手写 SQL 与 @Result 列名**里也存在——批量脚本按字符串替换时**必须按文件/上下文白名单**，
否则改掉 SQL 列名（本次靠 repository 测试的 `Column "KNOWLEDGEBASEIDS" not found` 立刻暴露并回退）。
**判据**：`mapper/`（手写 SQL）与 `domain/` 的 TypeHandler 注释里的列名要单独核对。

**波及面**：平台密钥端点元素（`SystemContractTest`）+ **4 个外域测试的 Key 创建**（memory /
datasource / storage 的 scoped-key 用例）——它们的请求体与 `data.token` 解包都要同步。
**auth 域至此收官**：`@JsonProperty` 仅余边界（`APIPrincipalConfig` 落库结构 + `tenantconfig/` 126 处）。

**验收**：apikey 域 129 + auth/system 域 202 + memory/datasource/storage 三组全绿；
全量 **4670 / 失败 0 / 跳过 4** + spotlessCheck 绿；前端 `vue-tsc` 0 + 690 用例。

### 14.9k memory 域作战计划（2026-10-01 只读侦察；**M1/M2 已完成**，余 M3 收尾）

**存量**：`@JsonProperty` **137 处 / 14 文件**（`git grep -c '@JsonProperty\|@com.fasterxml...JsonProperty'` 双写法口径）。

**分层（换锚判据）**：

| 层 | 文件（处数） | 判据 |
|---|---|---|
| **HTTP 响应实体**（63） | `MemoryItem`(23)、`MemoryTopicStat`(12)、`MemoryTopicView`(7)、`MemoryDocView`(7)、`MemorySettings`(7)、`MemoryConsolidationResult`(7) | 换锚：裸 DTO + camelCase + 可空显式 null |
| **落库实体**（49，✅ M2 完成） | `MemorySubject`(16)、`MemoryDocAffinity`(11)、`MemoryTombstone`(8)、`MemoryItemEmbedding`(8)、`MemoryMessageCursor`(3)、`MemoryExtractionState`(3) | 落库面：改键名 + dev 存量 SQL 迁移（**实测只有 `MemoryExtractionState` 一处真落 jsonb**，其余仅测试面） |
| **LLM 载荷**（22） | `MemoryExtractionLlm`(11)、`MemoryExtractPayload`(11) | **保留**（模型输出 schema，同 §11「工具输出自有 schema」） |
| 边界 | `MemoryIndexStore`（929 行，已知例外，§14.7.17） | 不动 |

**端点**：`MemoryController`（590 行）**20 个**，全 `ResponseEntity<Map<String,Object>>`：
- 四个资源组（settings / items / topics / documents）的读写 + 动作（confirm/reject/promote）
- 信封：`{data, success}`（直接资源）与 `pageBody(page)` 的分页（`{items, page, pageSize, total}`，须对齐 §2 第 4 条）
- 错误已 AppError ✓；请求体已 record（`UpdateMemorySettingsRequest` 等）但带注解

**三刀划分**：
- **M1（HTTP 响应面，✅ 2026-10-01 完成）**：16 路由去信封 + 6 个响应实体去注解（63 处）+ fixture 重录 + 前端同步
- **M2（落库实体，✅ 2026-10-01 完成）**：7 实体 + `MemoryConfig` 共 60 处 + 两处真落库 jsonb 的迁移 SQL + repository/JSON 测试同步
- **M3（收尾）**：LLM 载荷边界登记 + 请求侧手写绑定器清除（`rawBody + parse()` → DTO + `@Valid`）+ 残留核对

**风险点（B 批教训直接适用；②③ 已随 M1 关闭）**：
1. 批量替换**必须按文件白名单**——memory 的 `mapper/` 与手写 SQL 里的列名不能碰（M2 仍然适用）；
2. ~~分页形态对齐~~ ✅ M1 已做（`{items,page,pageSize,total}`，请求侧仍 limit/offset）；
3. ~~前端面~~ ✅ M1 已做（`api/memory.ts` + `MemorySettings.vue` + `MemoryWorkspaceSettings.vue` + 5 个 locale 文案）；
4. ✅ **M2 已按此条执行**（把"真落库的 JSON"与"只是注解残留在实体上"分开）：
   **真落库**＝`MemoryExtractionState`（`MemorySubject.extraction_state` jsonb，走 `MemoryExtractionStateTypeHandler`）
   与 `MemoryConfig`（`tenants.memory_config` jsonb）→ 改键名**必须配 dev 存量 SQL 迁移**；
   其余落库实体（`MemorySubject` 本体 / `MemoryDocAffinity` / `MemoryTombstone` / `MemoryItemEmbedding` /
   `MemoryMessageCursor`）都是**列**不是 jsonb，注解去掉即可，不动数据；
   `MemoryExtractionSession`(3 处) 的 JSON 只出现在测试里，M2 一并定夺（建议同批去掉，理由与上同）。

**✅ M1 执行记录（2026-10-01 完成，第四次尝试；前三次回滚的经过见 git 历史 `4e5d3c3`~`8b07ed1` 的文档提交）**：

*改动面*：`MemoryController` 16 路由全部换锚；6 个响应实体去注解 63 处（`MemoryItem`/`MemoryTopicStat`/
`MemoryTopicView`/`MemoryDocView`/`MemorySettings`/`MemoryConsolidationResult`，`@JsonIgnore` 一律保留）；
新增 `memory/dto/`（`MemoryListResponse`、`MemoryExportResponse`、`package-info`）；fixture 22 → **20 个**
（删 `memory-ack`/`memory-clear`/`memory-item-reject` 三个 204 类，新增 `memory-settings-user-off`）；
测试同步 3 个类 19 个失败点；前端 8 文件同批。

*逐条决策（可复核）*：
1. 创建条目 → **201**（§1.15）；更新 / 确认 / 提升主题 / 整理 → 200 裸对象；
   删除条目·主题·文档、拒绝、**清空** → **204**（§1.13；清空的 `{removed:N}` 计数退役，前端 toast 去掉计数）。
2. 列表（items / topics / documents）→ `{items, page, pageSize, total}`；**请求侧仍只有 limit/offset**
   （容错语义不变），`page = offset/limit + 1`、`pageSize = limit`。
3. Export → `{items, total, truncated}`（下载语义与两个响应头不变）；**空仓库 `items` 仍是 `null`**
   ——保留 Go 的 nil 语义（契约未要求改成 `[]`），与列表空 `[]` 的差别继续由测试钉住。
4. **请求侧手写绑定器（`rawBody` + `parse()`，含 1010/EOF 文案）本批不动**，留 M3——不与响应面混轴。
5. 前端同批修掉一处 **A2 漏改**：`MemoryWorkspaceSettings.vue` 读 KV 配置仍是 `response.data`，
   而 A2 已把 `/tenants/kv/{key}` 改成裸对象 → 会静默读空；本批改为裸对象（payload 内层键名等 M2）。

*手法结论（回答前三轮"换锚方法需要升级"）*：
- **代码层**（实体去注解）与 **fixture**（JSON 解析改写：snake→camel + 去信封 + 分页重排）可脚本化，一次通过；
- **Java 断言不要用正则**：按测试失败清单**逐处手改**——本轮 19 个失败点一轮收敛，说明失败根因是
  "用正则扫 Java 源"，**不是"手写断言"本身**；
- 可选的进一步提升（非必须）：把 `MemoryEntityJsonTest` / `MemoryContractTest` 的逐字节字面量搬进 fixture，
  后续换锚可纯脚本改写。**M2 未做**（直接结构化改写也一轮过，见下），登记为可选卫生项。

*验收*：memory 域 **339 用例 / 0 失败**；全量 `clean test` **4670 / 0 失败 / 6 跳过** + `spotlessCheck` 绿；
前端 `vue-tsc` 0 错误 + **690 用例通过**；**真实服务冒烟 21 路通过**（settings 读写与合并视图、items 建/改/确认/拒/删/清空、
非法 status 400、空内容 500、分页容错、topics/documents/export/consolidate、404 三连、空导出 `items:null`、
`/tenants/kv/memory-config` 裸对象）。

**✅ M2 执行记录（2026-10-01 完成，一轮过）——落库/内部 JSON 面**：

*入场结论（先分类，避免过度改造）*：7 个落库实体在生产代码里**没有一个**是 HTTP 响应体，
其中**只有两个**真的经 Jackson 落进 jsonb：`MemoryExtractionState`（→ `memory_subjects.extraction_state`，
经 `MemoryExtractionStateTypeHandler`）与 `MemoryConfig`（→ `tenants.memory_config`，auth 的 KV 端点读写）。
其余 5 个（`MemorySubject` 本体 / `MemoryDocAffinity` / `MemoryTombstone` / `MemoryItemEmbedding` /
`MemoryExtractionSession`）的 JSON 面**只在测试里**，注解去掉即可、不动数据。

*改动面*：8 个文件去 Jackson 注解 **60 处**（`@JsonProperty` 51 + `@JsonPropertyOrder` 8 +
`@JsonInclude(NON_EMPTY)` 1；`@JsonIgnore` 一律保留）→ **memory 域 `@JsonProperty` 137→22**；
`MemoryExtractionState.leaseId` 的 `omitempty` 退役（两个键恒输出，§1.6）；
顺手修两处**运行时键名依赖**：`ModelService` 读 `memoryConfig.get("embeddingModelId"/"extractModelId")`（模型删除影响面），
`TenantConfigOps.putMemory` 的七段校验文案改 camelCase；`TestSchema` 无需改（无写死的 sample JSON）。

*存量迁移 SQL（其他环境复用；2026-10-01 已对 dev 库 ragagent@15432 执行，两处各 `UPDATE 1`）*：
```sql
-- 1) memory_subjects.extraction_state：lease_id/lease_until → leaseId/leaseUntil
UPDATE memory_subjects SET extraction_state = (
  SELECT jsonb_object_agg(
    CASE e.key WHEN 'lease_id' THEN 'leaseId' WHEN 'lease_until' THEN 'leaseUntil' ELSE e.key END,
    e.value)
  FROM jsonb_each(extraction_state) e)
WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(extraction_state) k WHERE k IN ('lease_id','lease_until'));

-- 2) tenants.memory_config：11 个键改 camelCase
UPDATE tenants SET memory_config = (
  SELECT jsonb_object_agg(
    CASE e.key
      WHEN 'write_mode' THEN 'writeMode' WHEN 'extract_model_id' THEN 'extractModelId'
      WHEN 'max_items' THEN 'maxItems' WHEN 'extract_delay_seconds' THEN 'extractDelaySeconds'
      WHEN 'extract_min_interval_seconds' THEN 'extractMinIntervalSeconds'
      WHEN 'extract_instructions' THEN 'extractInstructions'
      WHEN 'interest_threshold' THEN 'interestThreshold'
      WHEN 'embedding_model_id' THEN 'embeddingModelId'
      WHEN 'vector_recall' THEN 'vectorRecall'
      WHEN 'retrieval_conditioning' THEN 'retrievalConditioning'
      ELSE e.key END, e.value)
  FROM jsonb_each(memory_config) e)
WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(memory_config) k
  WHERE k IN ('write_mode','extract_model_id','max_items','extract_delay_seconds',
              'extract_min_interval_seconds','extract_instructions','interest_threshold',
              'embedding_model_id','vector_recall','retrieval_conditioning'));
```
**⚠️ 与 model 落库批的口径差异**：那批**不做代码层宽容读**（旧行显式报错，逼迁移）；
本批的 `MemoryExtractionState` 读路径**本来就是宽松的**（`FAIL_ON_UNKNOWN_PROPERTIES=false`，Go 同款），
旧键会被**静默忽略成零值租约**——所以迁移必须做，且跑完才算完（已写进实体 javadoc）。

*测试与前端同步*：`MemoryEntityJsonTest`（JSON 键结构性改写 50 处 + 键序数组 + 零值断言改名）、
`MemoryContractTest`（MemoryConfig 三条）、`MemoryRepositoryTest` / `MemoryExtractionRepositoryTest`（jsonb 字节断言 5 处）、
`MemoryServiceOrchestrationTest`（CONFIG 常量）、`TenantCatalogContractTest`（PUT 体 6 处）；
夹具 `ct-kv-mem-*.json` 7 个（3 个 payload camelCase + 4 个校验文案）；
前端 `api/memory.ts` 的 `MemoryConfig` + `MemoryWorkspaceSettings.vue`（39 处字段访问 + 默认值块）。

*验收*：memory 域 + auth 契约 + 跨域 round-trip 全绿；全量 `clean test` **4670 / 0 失败 / 4 跳过** + `spotlessCheck` 绿；
前端 `vue-tsc` 0 错误 + **690 用例通过**；**真实服务冒烟 11 路通过**——含
「**迁移后的存量行读回**（`writeMode=explicit_only` 等 11 键全对）」、「PUT camelCase 落库后库里也是 camelCase」、
「非法值 400 文案为 `writeMode/maxItems/interestThreshold …`」、「改配置后 `/memory/settings` 的 `writeMode`/`maxItems` 跟着变」。

**✅ M3 执行记录（2026-10-01 完成）——请求侧绑定收尾 + LLM 载荷边界登记**：

- **请求侧**：`MemoryController` 三个带体端点从 `@RequestBody(required=false) String rawBody` + 手写
  `parse()` 改成标准 DTO 绑定（§1.10）——`memory/dto` 新增 `UpdateMemorySettingsRequest`
  （`@NotNull(message = "enabled: 不能为空")`）/ `CreateMemoryItemRequest` / `UpdateMemoryItemRequest`；
  手写的 `MAPPER` / `parse()` / `invalidRequestData()` 与内嵌 record 全部删除，缺省语义改由
  `orEmpty` / `orZero` 显式表达（**缺省与显式 null 同义＝零值**，与 Go 的非指针字段一致）。
- **错误形态统一到全局处理器**（旧 Go 仿真文案 1010 / `EOF` / `Invalid request data` 退役）：
  空体与字面量 `null` → `请求体不能为空`；畸形 JSON → `请求体格式不正确`；字段类型错 →
  `<字段>: 类型不正确`；缺 `enabled` / 显式 null → `enabled: 不能为空`。
  **未知字段仍被忽略**（Spring Boot 的 mapper 关掉了 FAIL_ON_UNKNOWN，与 Go 的 `encoding/json` 同款）——
  已用一条测试钉住，避免"前端多带一个字段就整条 400"。
- **LLM 载荷 22 处登记保留**（`MemoryExtractionLlm` 11 + `MemoryExtractPayload` 11）：它们是**模型输出的 schema**，
  不是我们的线格式，改名等于改提示词契约（同 §11「工具输出自有 schema」）。memory 域 `@JsonProperty`
  因此**停在 22**，这是收尾态、不是欠账。
- 测试：新增 5 条（空体/null 体/未知字段容错/显式 null 字段/只给 content）；夹具新增
  `memory-body-empty.json`、`memory-body-malformed.json`，`memory-settings-required.json` 改文案，
  孤儿夹具 `memory-settings-invalid.json` 删除。
- 验收：全量 **4673 / 0 失败 / 4 跳过** + `spotlessCheck` 绿；**真实服务冒烟 12 路通过**（含类型错文案
  `enabled: 类型不正确`、`content:null` 落零值 → 500 空内容）。

**注意**：序列化层删除仍须**全仓一次性**（§2 第 7 条 + §14.9 执行顺序第 2 步），打样只做"域内换锚"，
不触碰全仓序列化层。

### 14.9l session 域换锚作战计划（2026-10-01 只读侦察，待执行）

**存量**：`@JsonProperty/@JsonPropertyOrder` **约 207 处 / 21 文件**（19 个 domain 类 + 3 个 controller 内嵌 record + `dto/QaRequests.java` 用全限定写法约 25 处）。

**三个前提判定（先纠正三处误判，省一大批工作量）**：
1. **没有全局信封 Advice**——强类型端点（`ResponseEntity<Session>`、`List<Message>`…）本来就是裸返回；**手写信封只存在于 15 个返回 `Map<String,Object>` 的端点**。controller 里"响应是 `{data,success}`"的 javadoc 是 Go 遗留描述，与现行代码不符。
2. **SSE 是独立外部契约，冻结不动**：`llm/domain/StreamResponse.java`（`response_type/session_id/assistant_message_id/knowledge_references/finish_reason`）与 `common/llm/ResponseType.java` 的 22 个事件类型**严禁顺手 camelCase**——它们是线协议，不是本域实体。session 实体本身不进 SSE（只传 id + 手写 map）。
3. **落库 jsonb 与"线格式"必须同批**：`sessions.agent_config`、`messages.*` 9 列、`message_suggestion_sets.questions`、`temporary_documents.4 列` 里的键就是这些实体的键名。**改键名的同一批必须带存量迁移 SQL**（memory M2 的教训：读路径宽松 → 旧键被静默吞成零值）。所以本域按**资源**分批，而不是按"线上/落库"分批。

**跨域泄漏（唯一的真泄漏）**：`embed` 的 `GET /api/v1/embed/{channel_id}/messages/{session_id}/load` 直接返回 `ResponseEntity<List<Message>>`
（`embed/controller/EmbedChannelController.java:245` + `EmbedChannelDelegateOps.java:128`）——**消息批必须把 embed 一起改**（widget 与 `embed/EmbedContractTest` 在同一发布窗口）。
已核实**不泄漏**：embed 对 `SessionPage` 只读 `.total()`（`EmbedChannelService.java:766`）；`im` 的 Message/Session 只是内部对象；chatpipeline/agent/tracing/knowledge/system/model 对 `com.ragagent.session` **零 import**。

**✅ S1（会话主资源）执行记录（2026-10-01）**：
- **实体换锚**（4 个）：`Session`（12 处）/`SessionListItem`（17 处）/`SessionLastRequestState`（10 处）/
  `MentionedItem`（8 处）去 `@JsonProperty`/`@JsonPropertyOrder`/`@JsonInclude`；键名＝Java 字段名、
  **全部字段恒输出**（§1.6）。两个决策：① `is_pinned` → **`pinned`**（§1.24 布尔不带 is 前缀；
  Jackson 的字段名与 getter 名一致，不会出重复键）；② 六个 IM 字段与 `userId` 等在无 IM 来源时输出
  **空串而非 null**（原 Go 的"恒输出键不允许出 null"语义 —— 靠字段初始值 + setter 归一 `null → ""`）。
- **两个"改名批次专属"的连带修复**（各域的换锚批都要检查这两处）：
  ① **契约测试的掩码正则**写死了 `"([a-z_]+)":"…"`——键名换 camelCase 后匹配不到，时间戳/UUID 不再掩码、
     两侧差异直接把测试打红；本批把 session 域五个契约测试的掩码键模式改成大小写感知。
  ② **MyBatis 结果映射走 setter**：字段初始值会被 null 覆盖，所以"恒输出非 null"必须同时改 **setter**
     （只改字段初始值不够——本批实测过）。
- **测试同步**：`SessionJsonContractTest` 重写为声明序断言（含"空行也输出全部 22 键"）、
  `SessionQueryPagedTest` 的空 IM 字段断言改 `isEmpty()`、20 个 `session-*.json` 夹具改写 + 17 个补齐恒输出键。
- **存量迁移 SQL**（dev 库实测 **0 行**需迁移；其他环境复用；两段都已用合成数据验证输出）：
```sql
-- 1) sessions.agent_config（SessionLastRequestState）：顶层键改名 + mentioned_items 元素键改名
UPDATE sessions SET agent_config = (
  SELECT jsonb_object_agg(
    CASE e.key
      WHEN 'agent_id' THEN 'agentId' WHEN 'agent_enabled' THEN 'agentEnabled'
      WHEN 'model_id' THEN 'modelId' WHEN 'knowledge_base_ids' THEN 'knowledgeBaseIds'
      WHEN 'knowledge_ids' THEN 'knowledgeIds' WHEN 'tag_ids' THEN 'tagIds'
      WHEN 'mcp_service_ids' THEN 'mcpServiceIds' WHEN 'skill_names' THEN 'skillNames'
      WHEN 'web_search_enabled' THEN 'webSearchEnabled'
      WHEN 'mentioned_items' THEN 'mentionedItems' ELSE e.key END,
    CASE WHEN e.key = 'mentioned_items' AND jsonb_typeof(e.value) = 'array' THEN (
      SELECT coalesce(jsonb_agg(
        (SELECT jsonb_object_agg(
           CASE m.key WHEN 'kb_type' THEN 'kbType' WHEN 'kb_id' THEN 'kbId'
                      WHEN 'kb_name' THEN 'kbName' WHEN 'service_id' THEN 'serviceId'
                      WHEN 'skill_name' THEN 'skillName' ELSE m.key END, m.value)
         FROM jsonb_each(el) m)), '[]'::jsonb)
      FROM jsonb_array_elements(e.value) el)
    ELSE e.value END)
  FROM jsonb_each(agent_config) e)
WHERE agent_config IS NOT NULL AND EXISTS (
  SELECT 1 FROM jsonb_object_keys(agent_config) k
  WHERE k IN ('agent_id','agent_enabled','model_id','knowledge_base_ids','knowledge_ids',
              'tag_ids','mcp_service_ids','skill_names','mentioned_items','web_search_enabled'));

-- 2) messages.mentioned_items：数组元素键改名（MentionedItem 的五个键）
UPDATE messages SET mentioned_items = (
  SELECT coalesce(jsonb_agg(
    (SELECT jsonb_object_agg(
       CASE m.key WHEN 'kb_type' THEN 'kbType' WHEN 'kb_id' THEN 'kbId'
                  WHEN 'kb_name' THEN 'kbName' WHEN 'service_id' THEN 'serviceId'
                  WHEN 'skill_name' THEN 'skillName' ELSE m.key END, m.value)
     FROM jsonb_each(el) m)), '[]'::jsonb)
  FROM jsonb_array_elements(mentioned_items) el)
WHERE jsonb_typeof(mentioned_items) = 'array'
  AND mentioned_items @> '[{"kb_type":null}]' IS NOT TRUE
  AND EXISTS (SELECT 1 FROM jsonb_array_elements(mentioned_items) el
              WHERE el ?| array['kb_type','kb_id','kb_name','service_id','skill_name']);
```
- 前端同批：`sessionGrouping.ts`/`sessionMutations.ts`/`ChatHeader.vue`/`SessionSidebarRow.vue`/`menu.vue`
  的会话字段（`is_pinned`→`pinned`、`pinned_at`、`im_platform`、`user_id`、会话的 `created_at/updated_at`）+
  `stores/settings.ts` 的 `SessionLastRequestStatePayload`（含 mentionedItems 元素键）+ `views/chat/index.vue`
  的 `lastRequestState` 读取点；**刻意不动**：消息对象与 SSE 载荷里的 `created_at`/`session_id`（S2 范围）。

**✅ S1b（会话控制器面）执行记录（2026-10-01）**：
- **请求体**一律改标准 DTO + `@Valid`（§1.10）：新增 `session/dto/` 的 `CreateSessionRequest`/
  `UpdateSessionRequest`/`BatchDeleteSessionsRequest`/`GenerateTitleRequest`/`StopSessionRequest`；
  `PUT /sessions/{id}` 不再直接绑定 `Session` 实体（仓储白名单只写 title/description）；
  手写的 `MAPPER`/`bindBody`/`parseCreateBody`/`parseSessionBody`/`parseBatchBody` 全部删除
  ——Go 仿真文案（`EOF`、`GoJsonBindError`）退役，绑定错误由全局处理器给。
- **响应**（§2.1）：列表 → `{items,page,pageSize,total}`（新 `SessionListResponse`，与内部 `SessionPage` 分开）；
  置顶 → `{"pinned":bool}`（`SessionPinResponse`）；产物列表 → 裸数组（`ArtifactView`）；
  生成标题 → `{"title":"…"}`；删除/批量删除/清空消息/停止生成 → **204**（§1.13/§1.17）；
  旧 `{"message":…,"success":true}` 与 `artifactListItems` 的手写 Map 全部退役。
- **查询参数**：`page_size`→`pageSize`、`agent_id`→`agentId`（§1.16）；分页门槛文案改标准中文
  （`分页参数不合法` + `pageSize: 必须是整数/必须为正整数/超出上限`），go-playground 的 tag 文案退役。
  保留历史语义：缺席或显式 `0` 跳过、服务层再归一化。
- **路径变量**改名（`{session_id}`→`{sessionId}`、`{message_id}`→`{messageId}`）——**URL 本身不变**
  （变量名只在路由模板里），前端无需改 URL。
- **两处刻意不动**（避免混轴/半改）：① `stop` 的错误仍是纯字符串信封 `{"error":"…"}`（错误形态统一是 §14.9 第 ④ 项）；
  ② **产物字段名仍是下划线**——同一批元数据还嵌在 `messages.artifacts`（消息面 jsonb）里，前端抽屉同时消费两处，
  改名必须与 S2 同批（`ArtifactView` 的 `@JsonProperty` 是这个过渡的显式标记，S2 一并去掉）。
- **跨域连带**：embed 的 stop 端点请求体键从 `message_id` 变 `messageId`（delegate/controller/前端同批改）；
  embed 其余下划线键留给 embed 域自己的批次。
- 夹具：8 个绑定错误夹具改写、4 个分页错误夹具改写、3 个置顶夹具改写、11 个列表夹具 `page_size`→`pageSize`、
  3 个产物夹具去信封、`g6-title-existing` 去信封；**删除 8 个 204 类夹具**（`session-delete`/`-delete-all`/
  `-batch-mixed`/`g6-stop-running`/`-stop-completed`/`emb-pub-stop-done`/`msg-clear`/`-clear-again`）。
- 验收：session+embed 域绿；前端 `vue-tsc` 0 错误 + 690 用例通过；全量 `clean test` 绿 + `spotlessCheck`；
  **真实服务冒烟 18 路通过**（列表/详情/改名/置顶/产物/生成标题/清空/删除/批量删除 + 空体 400 + 分页 400 两条）。

**✅ S2（消息面）执行记录（2026-10-01）——后端 + 前端同批均已交付**：
- **实体**：`Message`（23 处）/`MessageAttachment`（20）/`MessageImage`（3）/`UsedMemory`（3）/
  `MessageArtifact`（7）/`MessageSearchResult`（2）/`MessageSearchGroupItem`（8）/`ChatHistoryKbStats`（9）
  去键名映射 → 键名＝Java 字段名、全部键恒输出（§1.6）。两个键名决策：`is_completed`→**`completed`**、
  `is_fallback`→**`fallback`**（§1.24，与 S1a 的 `pinned` 同款）；`MessageAttachment.truncated` 的线格式
  从 `is_truncated` 回到字段名 `truncated`。
- **按 §1.19「jsonb 保持不透明」保留的三处**（登记，不做改名）：`knowledge_references`（元素是检索域
  `SearchResult`）、`agent_steps`（元素是 agent 域 `AgentStep`）、`usage`（llm 域 `TokenUsage`）；
  另有 `execution_context`（`MessageExecutionContext`，`@JsonIgnore` 不出响应）。
- **控制器**（`MessageController`）：load → 裸数组、search → 裸 `MessageSearchResult`、stats → 裸对象；
  删除消息 → **204**；路径变量 `{sessionId}`、查询参数 `beforeTime`/`resourceUrls`（§1.16）；
  搜索请求体改 DTO + `@NotBlank(query)`（Go validator 文案退役）；`beforeTime` 解析失败文案改中文；
  手写 `MAPPER`/`bindBody` 删除。
- **产物面解锁**：`ArtifactView` 的过渡 `@JsonProperty` 已摘除（S1b 登记的过渡项），产物字段终为 camelCase。
- **存量迁移 SQL**（dev 库 **0 行**需迁移——`messages` 表当前为空；表达式已用合成数据验证）：
```sql
-- messages.attachments：数组元素键改名（8 个键）
UPDATE messages SET attachments = (
  SELECT coalesce(jsonb_agg(
    (SELECT jsonb_object_agg(
       CASE m.key WHEN 'file_name' THEN 'fileName' WHEN 'file_type' THEN 'fileType'
                  WHEN 'file_size' THEN 'fileSize' WHEN 'line_count' THEN 'lineCount'
                  WHEN 'content_mode' THEN 'contentMode' WHEN 'token_count' THEN 'tokenCount'
                  WHEN 'selected_chunks' THEN 'selectedChunks'
                  WHEN 'total_chunks' THEN 'totalChunks' ELSE m.key END, m.value)
     FROM jsonb_each(el) m)), '[]'::jsonb)
  FROM jsonb_array_elements(attachments) el)
WHERE jsonb_typeof(attachments) = 'array'
  AND EXISTS (SELECT 1 FROM jsonb_array_elements(attachments) el
              WHERE el ?| array['file_name','file_type','file_size','line_count',
                                 'content_mode','token_count','selected_chunks','total_chunks']);

-- messages.artifacts：数组元素键改名（6 个键）
UPDATE messages SET artifacts = (
  SELECT coalesce(jsonb_agg(
    (SELECT jsonb_object_agg(
       CASE m.key WHEN 'file_name' THEN 'fileName' WHEN 'file_type' THEN 'fileType'
                  WHEN 'file_size' THEN 'fileSize' WHEN 'source_path' THEN 'sourcePath'
                  WHEN 'mod_time' THEN 'modTime' WHEN 'created_at' THEN 'createdAt'
                  ELSE m.key END, m.value)
     FROM jsonb_each(el) m)), '[]'::jsonb)
  FROM jsonb_array_elements(artifacts) el)
WHERE jsonb_typeof(artifacts) = 'array'
  AND EXISTS (SELECT 1 FROM jsonb_array_elements(artifacts) el
              WHERE el ?| array['file_name','file_type','file_size','source_path',
                                 'mod_time','created_at']);
```
（`messages.mentioned_items` 的迁移在 S1a 已给，`images`/`used_memories` 无键名变化，不需迁移。）
- **✅ 前端同批（2026-10-01 交付，26 文件）**——换锚规则一句话：**"从 REST 消息对象上读"的键改 camelCase；
  SSE 载荷键一律不动**（§14.9l 前提判定 2）。逐类：
  ① 消息对象的键：`request_id→requestId`、`is_completed→completed`、`is_fallback→fallback`、`knowledge_references→knowledgeReferences`、
  `mentioned_items→mentionedItems`、`used_memories→usedMemories`、`agent_steps→agentSteps`、`agent_duration_ms→agentDurationMs`、
  `created_at→createdAt`、附件 `file_name/file_type/file_size→fileName/fileType/fileSize`、
  产物 `source_path/mod_time→sourcePath/modTime`、搜索组 `session_title/query_content/answer_content/match_type→camelCase`、
  统计 `embedding_model_id/knowledge_base_id/knowledge_base_name/indexed_message_count/has_indexed_messages→camelCase`。
  ② **冻结不动**（已实测后端无这些键或属其它线协议）：SSE 顶层/`data` 载荷（`response_type`、`session_id`、`assistant_message_id`、
  `created_at`、`knowledge_references`、`user_message_included` 家族的 `user_created_at`/`assistant_created_at`、`is_fallback`）、
  agentEventStream 事件字段（`event_id`/`tool_call_id`/`display_type`/… 与 SSE 同形）、请求面（`streame.ts` 的 chat 请求体、
  `attachment_uploads`、steer 请求体、`data.references` 直通载荷属 S4/S3）。
  ③ **两个本地键保留蛇形**：`assistant_message_id`（前端自有、不对应 REST 字段）与 steer 队列项的 `mentioned_items`（S3 面）。
  ⚠️ 后者在 **S3 已移位**：队列项键改 camelCase（`steerId`/`mentionedItems`），消息对象上镜像 SSE 的 `steer_id` 仍保留下划线——见下方 S3 记录。
  ④ **跨形状转换点**（新增 `types/mention.ts::fromMentionRequest`）：本地乐观用户消息与 steer 预览把**上送项**（snake 元素）
  转成消息元素形状（camelCase），保证"刷新前 = 刷新后"；`views/chat/index.vue` 的 steer 合并处同用。
  ⑤ 接口参数：`GET /messages/{id}/load` 的查询参数 `before_time`→**`beforeTime`**（`api/chat/index.ts`；embed 的
  `/embed/.../load` 仍是 `before_time`，属 embed 域）；搜索结果请求体 `session_ids`→**`sessionIds`**（与 S2 后的 DTO 对齐，
  顺带修掉旧的不一致）。`ChatHistoryConfig`（tenants KV）**不在本批**——它的键名仍由 auth 域 KV 面决定。
  ⑥ 顺带修两处隐藏键名 bug（同 M2 的"顺手修"口径，已在文件里留注释）：`utils/sessionMarkdown.ts` 的引用元素按
  `knowledgeTitle/knowledgeFilename/…` 双拼写读（此前只读 snake，导出里的引用段静默为空）；`utils/rag-pipeline-history.ts`
  的 `chunkType/knowledgeId/knowledgeTitle` 同款双拼写（历史回放合成检索步骤此前恒判成"文档"）。
  ⑦ 形态测试同步：`useChatStreamHandler.test.mjs`、`steerStreamFork.test.mjs`、`steerInteraction.test.mjs`、
  `steerFollowUp.test.mjs`、`chatLinksNewTab.test.mjs`、`RagPipelineProgress.style.test.mjs`、`AgentStreamDisplay.style.test.mjs`、
  `rag-pipeline-history.test.mjs`、`attachmentPreview.test.mjs`、`sessionArtifacts.test.ts`、`sandboxArtifactRefs.test.ts`、
  `sessionMarkdown.test.ts`、`messageTimestamp.test.ts`、`sessionActivityState.test.ts`、`embedThinkingStatus.test.ts`。
  ⑧ 验收：`vue-tsc --build` 0 错误；前端 **690 用例全绿**；`vite build` 通过。**未做真机 UI 走查**——
  dev 库 `messages` 表为空（无历史数据），要跑走查须先产生一轮真实对话（需 provider + 真模型）。
  ⑨ 另登记一个**不属于本批**的既有缺陷（未改）：`composables/useEmbedChatSession.ts` 的 `getmsgList` 读 `res?.data` 解包，
  而 embed `load` 端点的响应体是裸数组（`[Message]`）——embed 历史分页实际拿不到数据；归 embed 域的批次处理。

**✅ S3（附件 / 建议 / steer）执行记录（2026-10-01）——后端 + 前端同批**：
- **实体 5 个去键名映射**（键名＝Java 字段名，`@JsonIgnore` 保留）：`TemporaryDocument`(18)
  / `MessageSuggestionSet`(20) / `MessageSuggestionEvent`(7) / `SuggestionItem`(5) /
  `SuggestionAttribution`(2)；**三类 `@JsonInclude`（Go omitempty 直译）一并去掉**——可空字段改显式 null
  （§1.6），这条直接改变了响应键集合，夹具必须按实际重录（见下）。
- **请求面**：`SteerController` 请求记录三键 + 两键 camelCase；`MessageSuggestionController.EventRequest`
  三键 camelCase；附件上传**表单字段** `agentId`/`parserEngine`（§1.16 的表单类比）。
- **响应面**：`SteerController` 的手写 `Map` 键（steerId / assistantMessageId / removed / status / items）
  camelCase——但**事件 data 保持冻结的线协议**（`steer_id`/`mentioned_items`/`channel`/`delivery`），
  列表项里的提及项经 `MentionedItem.fromRawList` 从事件载荷（下划线）转成实体（camelCase）后输出。
- **提及项形状一次收口（本批最有价值的一处）**：`QaRequests.MentionedItemRequest` 的元素键改 camelCase
  ⇒ 请求元素＝消息元素＝落库 jsonb **同一形状**。起因是一个**活缺陷**：S1a 把 `MentionedItem` 改成
  camelCase 后，steer 请求仍按实体反序列化、前端仍发下划线元素 → `kbType/kbId/kbName/serviceId/skillName`
  **被静默丢弃**（`@JsonIgnoreProperties(ignoreUnknown=true)`），注入消息的 @提及作用域全丢。
  前端随之删掉 `MentionRequestItem` 与 `fromMentionRequest`（S2 引入的过渡转换函数），
  `Input-field.vue` / `streame.ts` / `steerStreamFork.ts` / `index.vue` 全部只留一种形状。
  **S4 只剩请求「信封」键**（`knowledge_base_ids`/`agent_id`/`suggestion_attribution`/`attachment_uploads`…）。
- **四处落库 jsonb**（键名＝字段名，§2 第 11 条；表达式已在真 PG 用合成数据验证，dev 库 0 行需迁移）：
  `temporary_documents.chunks`（contextHeader/tokenCount）、`temporary_documents.image_refs`
  （originalRef/mimeType）、`temporary_documents.processing_options`（6 键；**写入 `toJson` 与读取 `optionsOf` 必须同批**）、
  `message_suggestion_sets.questions[].knowledge_base_ids`→`knowledgeBaseIds`、
  `messages.execution_context.suggestion_attribution`（两键）。SQL 见下方"存量迁移 SQL"。
- **embed 同批**：channel 关闭的建议分支从 `{data,success}` 信封改裸对象 + `suppressionReason`
  （与委托路径同形）；embed 建议 GET/ensure 前端不再解包 `.data`。
- **顺手修 3 处「前端还期待信封」的活缺陷**：① `views/chat/index.vue` 的 `upload.data.id/.status`
  （附件上传后 `upload.data` 是 undefined，图片/附件 id 拿不到）；② `useEmbedChatSession` 的历史加载
  `res?.data` 解包（S2 已登记）；③ `api/message-suggestion.ts` 的 `{data: set}` 泛型（消费侧原本已是裸读，
  只是类型骗人）。**纪律复盘**：S1b/S2 换锚时只改了「服务端 + 消费侧」，漏了「API 模块的类型/解包」这一层。
- **入场风险 1 的结论（`SuggestionItem` 一物两用）**：**不需要**改提示词或另立 DTO——
  `MessageSuggestionPipeline.parseGeneratedSuggestions` 只逐字段读 `path("text")`/`path("category")`
  后手工 new，**不经 Jackson**；已把这条边界写进 `SuggestionItem` 的类注释。
- 夹具：`att-*`/`sug-*` JSON 解析改写 + 4 个 golden 按实际响应**重录**（去 omitempty 后多出
  completionTokens/errorCode/latencyMs/modelId/promptTokens/generatedAt/suppressionReason 等恒输出键）；
  `emb-pub-suggestions-get/off` 同批；`AttachmentContractTest` 的掩码正则键名（`sessionId`/`attachmentId`）
  随批改——**掩码按键名匹配，键改名必须同步掩码，否则 fixtures 里会混进真实 UUID**。
- 验收：全量 **4673 / 0 失败 / 4 跳过** + `spotlessCheck` 绿；前端 `vue-tsc` 0 错误 + **690 用例全绿**
  + `vite build`；**真实服务冒烟 14 路通过**（建会话 201 / 上传 202 / 上传体键名断言 / 解析 ready /
  详情体断言 / 列表裸数组 / 预览字节 / 未知附件 404 / 删除 204 幂等 / steer 列表 `{items:[]}` /
  steer `new_run` / 建议事件 camelCase→404 / **旧 snake 体→400（换锚生效的反证）** / 建议 GET 404），
  并在**真 PG 核验落库键名**（`chunks.tokenCount`、`processing_options.parserEngine`：旧键名一条不留）。

**S3 存量迁移 SQL（真 PG，dev 库 0 行需迁移；表达式已用合成数据验证）**：
```sql
-- temporary_documents.chunks：数组元素键改名
UPDATE temporary_documents SET chunks = (
  SELECT coalesce(jsonb_agg((SELECT jsonb_object_agg(
      CASE e.key WHEN 'context_header' THEN 'contextHeader'
                 WHEN 'token_count' THEN 'tokenCount' ELSE e.key END, e.value)
    FROM jsonb_each(elem) AS e)), '[]'::jsonb)
  FROM jsonb_array_elements(chunks) AS elem)
WHERE jsonb_typeof(chunks) = 'array' AND chunks <> '[]'::jsonb;

-- temporary_documents.image_refs
UPDATE temporary_documents SET image_refs = (
  SELECT coalesce(jsonb_agg((SELECT jsonb_object_agg(
      CASE e.key WHEN 'original_ref' THEN 'originalRef'
                 WHEN 'mime_type' THEN 'mimeType' ELSE e.key END, e.value)
    FROM jsonb_each(elem) AS e)), '[]'::jsonb)
  FROM jsonb_array_elements(image_refs) AS elem)
WHERE jsonb_typeof(image_refs) = 'array' AND image_refs <> '[]'::jsonb;

-- temporary_documents.processing_options（空对象要守卫：jsonb_object_agg 空集返回 null）
UPDATE temporary_documents SET processing_options = (
  SELECT jsonb_object_agg(e.key_new, e.value) FROM (
    SELECT CASE k.key WHEN 'resource_tenant_id' THEN 'resourceTenantId'
                      WHEN 'asr_model_id' THEN 'asrModelId'
                      WHEN 'parser_engine' THEN 'parserEngine'
                      WHEN 'vlm_model_id' THEN 'vlmModelId'
                      WHEN 'image_understanding' THEN 'imageUnderstanding'
                      WHEN 'ocr_max_pages' THEN 'ocrMaxPages' ELSE k.key END AS key_new, k.value
    FROM jsonb_each(processing_options) AS k) AS e)
WHERE jsonb_typeof(processing_options) = 'object' AND processing_options <> '{}'::jsonb;

-- message_suggestion_sets.questions[].knowledge_base_ids
UPDATE message_suggestion_sets SET questions = (
  SELECT coalesce(jsonb_agg((SELECT jsonb_object_agg(
      CASE e.key WHEN 'knowledge_base_ids' THEN 'knowledgeBaseIds' ELSE e.key END, e.value)
    FROM jsonb_each(elem) AS e)), '[]'::jsonb)
  FROM jsonb_array_elements(questions) AS elem)
WHERE jsonb_typeof(questions) = 'array' AND questions <> '[]'::jsonb;

-- messages.execution_context.suggestion_attribution
UPDATE messages SET execution_context = jsonb_set(execution_context, '{suggestion_attribution}', (
  SELECT jsonb_object_agg(e.key_new, e.value) FROM (
    SELECT CASE k.key WHEN 'suggestion_set_id' THEN 'suggestionSetId'
                      WHEN 'question_id' THEN 'questionId' ELSE k.key END AS key_new, k.value
    FROM jsonb_each(execution_context->'suggestion_attribution') AS k) AS e))
WHERE jsonb_typeof(execution_context->'suggestion_attribution') = 'object'
  AND execution_context->'suggestion_attribution' <> '{}'::jsonb;
```

**分批（按资源，每批自带 jsonb 迁移 + 前端 + 夹具）**：
| 批 | 内容 | 文件（主要） | 预估 |
|---|---|---|---|
| **S1 会话主资源 ✅（S1a 实体面 + S1b 控制器面）** | `Session` / `SessionListItem` / `SessionLastRequestState` / `MentionedItem` + `SessionController`（求体 DTO / 信封 / 分页参数）+ 两处 jsonb 迁移 | `domain/Session.java`、`SessionListItem.java`；`controller/SessionController.java`；前端 `api/chat/index.ts` + `components/sessionGrouping.ts`、`SessionSidebarRow.vue`；夹具 `session-*.json`；`SessionJsonContractTest` / `SessionHttpContractTest` / `SessionQueryPagedTest` | ~55 处 |
| **S2 消息主资源（最高风险）✅ 前后端已交付** | `Message` + 9 列 jsonb + `MessageAttachment`/`MessageImage`/`MentionedItem`/`UsedMemory`/`MessageExecutionContext`/`MessageArtifact` + 搜索/统计 + **embed 同批** | `domain/Message*.java`、`mapper/MessageMapper|MessageRepository`、`controller/MessageController.java`、`embed/controller/EmbedChannel*`、前端 `api/{chat-history.ts,chat/index.ts}`、`composables/{useChatStreamHandler,useEmbedChatSession}.ts`、`views/chat/index.vue`、`views/chat/components/*`、`views/embed/*`、`utils/{messageTimestamp,sessionArtifacts,sessionMarkdown,steerStreamFork,rag-pipeline-history,attachmentPreview,sandboxArtifactRefs,referenceSources,citationMarkdown}.ts`、`types/mention.ts`；夹具 `msg-*.json`、`emb-*.json` | 已完成 |
| **S3 附件 / 建议 / steer / artifacts ✅ 前后端已交付** | `TemporaryDocument` / `MessageSuggestionSet` / `MessageSuggestionEvent` / `SuggestionItem` / `SuggestionAttribution` + 三个 controller 的请求面与手写响应 map + **四处落库 jsonb**（详见下方执行记录） | `domain/*`、`controller/{TemporaryDocumentController,MessageSuggestionController,SteerController}.java`、`service/{TemporaryDocumentProcessor,TemporaryDocumentService,TemporaryDocumentPromptResolver,SteerSinkBridge}.java`、`dto/QaRequests.java`（仅提及项元素）、`embed/controller/EmbedChannelController.java`（channel 关闭分支）；前端 `api/{message-suggestion.ts,chat/steer.ts,chat/temporary-attachments.ts,chat/streame.ts,embed/index.ts}`、`types/mention.ts`、`utils/steerStreamFork.ts`、`components/{Input-field.vue,AttachmentUpload.vue}`、`views/chat/index.vue`、`views/embed/EmbedChatCore.vue`、`composables/useEmbedChatSession.ts`；夹具 `att-*/sug-*/emb-pub-suggestions-*` | 已完成 |
| **S4 请求面 DTO ✅ 前后端已交付** | `dto/QaRequests.java`（三入口共用：`/knowledge-chat`、`/agent-chat`、`/knowledge-search`）+ `GoJsonBindError` 的字段名映射 + embed 请求改写器（**错误文案不动**）；新增 2 个绑定守卫测试 | `dto/QaRequests.java`、`common/web/GoJsonBindError.java`、`embed/controller/EmbedChannelDelegateOps.java`（patch 键）；前端 `api/chat/{streame.ts,index.ts}`、`views/chat/index.vue` 发送段、`composables/useEmbedChatSession.ts`、`utils/chatRequestDebug.ts`、`views/integrations/ApiIntegrationSettings.vue`；新增 `QaRequestBindingTest` / `EmbedChatPayloadPatchTest`；SSE 体断言 `streame.test.ts` | 已完成 |
| **S5 收尾 ✅ 后端已交付（本批无前端改动）** | `MessageExecutionContext`（`execution_context` 落库面，11 处键名映射）+ 各 TypeHandler 挂载点核对 + 残留核对与边界登记；**session 域收官：`@JsonProperty` 188 → 0** | `domain/MessageExecutionContext.java`、`service/MessageSuggestionService.java`（tagScopes 双拼写读）、`session/MessageJsonContractTest.java`（新增键集契约） | 已完成 |

**✅ S4（QA 请求面 DTO）执行记录（2026-10-01）——后端 + 前端同批**：
- **换锚**：`QaRequests` 的 `CreateKnowledgeQARequest`（11 键）/ `SearchKnowledgeRequest`（4）/ `AttachmentUpload`
  （`fileName`/`fileSize`）去 `@JsonProperty`；三处照抄 Go `omitempty` 的 `@JsonInclude` 一并摘除——
  这些类**只做入参**（解析后转 `QaSupport.QaRequest`），注解对入参没有语义，留着只会误导下一个读者。
- **错误文案不动**（本批硬约束）：`QaRequestBinder` 仍按 Go 措辞抛错；只有**字段级类型错误里的 json 名**
  跟着换成 camelCase（`GoJsonBindError.FIELD_GO_TYPES` 的登记键），例如
  `… into Go struct field CreateKnowledgeQARequest.agentSourceTenantId of type uint64`。
- **旧 snake 键静默失效**（`@JsonIgnoreProperties` 与 Go 的 `json.Unmarshal` 同款宽松读）：不报错、
  不映射——这是换锚的既定代价，已用测试与冒烟双向钉住（见下）。
- **embed 请求改写器同批**：`patchEmbedChatPayload` 写回/读取/删除的键全部跟随
  （`agentId` / `knowledgeBaseIds` / `webSearchEnabled` / `mcpServiceIds` / `agentEnabled` /
  `attachmentUploads` / `attachmentIds`）。**这类"写 JSON 键"的代码是换锚里最危险的一类**：
  写错不报错，只会静默丢掉渠道约束（KB 注入失效 = 访客越权检索面），或让禁用上传的渠道
  仍收到附件。为此把该私有方法开放到包可见并新增单测。
- **新增两个绑定守卫测试（此前这条链路没有任何绑定测试）**：
  · `QaRequestBindingTest`（6 条）：camelCase 全字段读回（含提及项元素 / 附件项 / 归因）、
    旧 snake 键静默失效（显式断言）、必填文案、类型错误文案、畸形体口径（空体 `EOF` /
    `not-json` 的 Go 字面量措辞 / 深结构回落 Jackson 的既定差异）。
  · `EmbedChatPayloadPatchTest`（4 条）：改写结果**直接用 DTO 反序列化**（等价于 QaRequestBinder
    的映射），钉住改写器与 DTO 的键对齐；渠道值覆盖客户端值、`webSearchEnabled` 仅 bool 生效、
    禁上传时剥掉 `images`/`attachmentUploads`/`attachmentIds`、坏体（空体/null/标量）口径。
- **前端同批**：`streame.ts` 的入参与 postBody、`views/chat/index.vue` 主发送段与附件上传项构造、
  `api/chat/index.ts` 两个非流式助手、`useEmbedChatSession.ts` 访客请求体、`chatRequestDebug.ts`
  的消毒器（改错会把整段 base64 打进调试面板）。
- **顺手修 2 处活缺陷**：① `ApiIntegrationSettings` 的试跑读 `sessionPayload?.data?.id`——S1b 后
  会话创建返回裸对象，该路径一直抛「missing session id」（同时把 curl 示例与联调体改 camelCase）；
  ② `streame.test.ts` 的 SSE 体断言补一条"旧 snake 键不得出现"的反断言。
- **反转方向的发现**：cmdk 的 `knowledgeSemanticSearch` 一直发 `knowledgeBaseIds`（camelCase），
  而后端此前只认 `knowledge_base_ids` → **该检索此前静默无作用域**（表现为搜不到 chunk）；S4 之后才真正生效。
- **登记不改（属知识域遗留，非本批）**：① `api/knowledge-base/index.ts` 的 `agent_id`/`agent_source_tenant_id`
  查询参数（Java 侧已随空间分享裁撤，无人读）；② `manual-knowledge-editor.vue` 的 `tag_ids`/`process_config`
  ——服务端 `CreateManualRequest` 只有 title/content/status/channel，这两个字段（含标签与解析配置）**被忽略**，
  是知识域的功能缺口（补实现时按 camelCase 落）。
- 验收：全量 **4683 / 0 失败 / 6 跳过** + `spotlessCheck` 绿；前端 `vue-tsc` 0 错误 + **690 用例全绿** +
  `vite build`；**真实服务冒烟 9 路通过**——camelCase 被读到 ×3（knowledge-chat 未知 KB → SSE 错误帧、
  agent-chat `agentEnabled` → 400 固定文案、knowledge-search → 1003）、**旧 snake 键失效 ×3**
  （两个 chat 端点 + search 落到「作用域为空」分支）、绑定文案 ×2（必填与字段级类型错误）、提及项元素 ×1。

**✅ S5（session 域收尾）执行记录（2026-10-01）——后端 + 文档（无前端改动）**：
- **`MessageExecutionContext` 换锚**（`messages.execution_context` 落库面）：11 个外层键去 `@JsonProperty`、
  9 处 `@JsonInclude`（Go omitempty 直译）与 `@JsonPropertyOrder` 一并摘除 → 键名＝Java 字段名、
  12 键恒输出；`@JsonIgnoreProperties(ignoreUnknown=true)` **保留**（历史行/已删键不能炸）。
  三处**刻意保留**：`questionSuggestions` / `tagScopes` 的**内层**键属 agent 域配置（跨模块透传，
  见类注释）、`locale` 本无映射。
- 连带把 `MessageSuggestionService.tagScopes(ec)` 的读取改成**双拼写**（`knowledge_base_id`/`knowledgeBaseId`
  与 `tag_ids`/`tagIds`）——写入方若改用 `QaSupport.TagScope` 的字段名也不会静默丢作用域。
- **新增键集契约测试**（`MessageJsonContractTest.executionContextJsonbKeysMatchFieldNames`）：
  12 键声明序 + 全空实例同样输出 12 键 + 三个旧下划线键不得出现 + 内层 snake 键保留。
  这一列**不出响应**，没有任何 HTTP 夹具能守它——漏改只会让追问建议静默降级。
- **TypeHandler 挂载点核对**（S5 指定动作）：真 PG 里 session 五表共 **18 个 jsonb 列**，
  逐个核对到实体 `@TableField(typeHandler=…)` 或 mapper 方法级 `@Result`：**全部有处理器**；
  两个例外是**刻意未映射的遗留列** `sessions.context_config` / `sessions.summary_parameters`
  （列名保留、不进实体，也不该挂处理器）。
- **残留核对（收官口径）**：session 域真实注解 `@JsonProperty` **0** / `@JsonInclude` **0** / `@JsonNaming` **0**；
  代码里剩余的下划线字符串**全部**属三类冻结面——① SSE/事件载荷（`session_id`/`is_fallback`/`steer_id`/…）；
  ② 工具与内部 agent 载荷（`tool_call_id`/`chunk_index`/`knowledge_base_ids`/…）；
  ③ DB 列名与手写 SQL（`lease_until`/`updated_at`/…）；另有 `new_run`/`already_injected`/`user_requested`
  是**取值**不是键名。
- **登记一处移植缺口（S5 侦察发现，未实现）**：Java 侧**从不填充** `execution_context` 的 11 个字段
  （全仓只有两处写入：`QaRequestParser` 的空骨架与 `QaTurnExecutor` 的 suggestionAttribution）——
  于是读侧的 `questionSuggestions`（建议配置）、`langfuseTraceparent`（续接原对话 trace）、
  `tagScopes`（标签作用域还原）、`regenerate` 闸门（`!enabled || !allowRegenerate` → 恒 400）在 Java 上
  **全部落空**。这是 Go `buildMessageExecutionContext` 的移植缺口，属功能补齐切片（换锚批不补功能）。
- 验收：全量 **4684 / 0 失败 / 6 跳过** + `spotlessCheck` 绿；**本批无前端改动**
  （`stores/settings.ts` 的 `SessionLastRequestStatePayload` 在 S1 已同批对齐；`executionContext` 不出响应）。
  **session 域收官**：S1（会话主资源）→ S2（消息面）→ S3（附件·建议·steer）→ S4（QA 请求面）→ S5（收尾）全部交付，
  `@JsonProperty` **188 → 0**。

**S5 存量迁移 SQL（真 PG；dev 库 `messages` 为空，**0 行需迁移**；表达式已用合成数据验证）**：
```sql
-- messages.execution_context：11 个外层键改名（suggestion_attribution 外层键 S3 已改，
-- 其内层两键由 S3 的 SQL 负责；question_suggestions / tag_scopes 的内层键刻意不动）
UPDATE messages SET execution_context = (
  SELECT jsonb_object_agg(e.key_new, e.value) FROM (
    SELECT CASE k.key
      WHEN 'agent_config_hash'   THEN 'agentConfigHash'
      WHEN 'question_suggestions' THEN 'questionSuggestions'
      WHEN 'knowledge_base_ids'  THEN 'knowledgeBaseIds'
      WHEN 'knowledge_ids'       THEN 'knowledgeIds'
      WHEN 'tag_ids'             THEN 'tagIds'
      WHEN 'tag_scopes'          THEN 'tagScopes'
      WHEN 'mcp_service_ids'     THEN 'mcpServiceIds'
      WHEN 'skill_names'         THEN 'skillNames'
      WHEN 'web_search_enabled'  THEN 'webSearchEnabled'
      WHEN 'langfuse_traceparent' THEN 'langfuseTraceparent'
      ELSE k.key END AS key_new, k.value
    FROM jsonb_each(execution_context) AS k) AS e)
WHERE jsonb_typeof(execution_context) = 'object' AND execution_context <> '{}'::jsonb;
```

**已登记的风险点（S3 后状态）**：
1. ~~`SuggestionItem` 一物两用~~ **已结案（S3）**：LLM 那条路不经 Jackson（只读 `text`/`category` 后手工 new），键名换 camelCase 不影响提示词；边界已写进类注释。
2. ~~搜索请求体 `session_ids` 不一致~~ **已结（S2）**：前端改发 `sessionIds`。
3. ~~steer 事件 data 与前端耦合~~ **已结（S3）**：事件 data 判为**冻结线协议**（保留下划线），HTTP 响应键 camelCase；列表项里的提及项经实体转换输出。
4. ~~artifacts 三端点带信封~~ **已结（S1b/S2 实测）**：`SessionController` 的 artifacts 三端点早已返回裸 `List<ArtifactView>` / 204 / 字节流，前端 `ChatArtifactsDrawer` 的 `res.data || res` 双读保留（无害）。
5. **（新登记，S4 侦察发现，不改）知识域两处遗留**：① `api/knowledge-base/index.ts` 的
   `agent_id`/`agent_source_tenant_id` 查询参数——Java 侧随空间分享裁撤后**无人读**（死参数）；
   ② `manual-knowledge-editor.vue` 发 `tag_ids`/`process_config`，而服务端 `CreateManualRequest`
   只有 title/content/status/channel → **标签与解析配置被静默忽略**（功能缺口，补实现时按 camelCase 落）。
6. **（新登记，S5 侦察发现，不改）`execution_context` 移植缺口**：Java 侧从不填充该列的 11 个字段
   （只有空骨架与 `suggestionAttribution` 被写）→ 追问建议的配置读取、langfuse trace 续接、
   标签作用域还原与 `regenerate` 闸门在读侧全部落空（Go `buildMessageExecutionContext` 未移植）。
   补实现时按 S5 后的 camelCase 写；迁移 SQL 已就位。

### 14.9m embed 域换锚（E1：渠道管理 + 公开面，2026-10-01）

**✅ E1 执行记录（后端 + 前端同批）**：
- **响应形态**：渠道管理 8 端点去 `{data,success}` 信封（§2.1）——create **201** 裸对象（§1.15）、
  list-by-agent / list-all 裸数组、get/update/rotate 裸对象、preview → `{sessionToken,expiresIn}`、
  stats → `{sessionCount}`、delete → **204**（§1.13，不再是 `{"success":true}`）；公开面同批：
  config 裸对象、exchange → `{sessionToken,expiresIn}`、create-session **201** → `{id,sig}`、
  suggested-questions → `{questions:[]}`、chunk 裸 `Chunk`、**访客事件上报 → 204**（无响应体）。
- **键名**：渠道行视图 24 键、公开配置 20 键全部 camelCase（键名＝实体字段名）。
  **唯一条件键是 `publishToken`**（列表行不带、详情/创建/轮换带）——那是**授权边界**
  （列表里带 token = 把渠道会话签发权发给所有能读列表的人），不是 §1.6 的数据条件键，故保留并注释。
  公开配置则按 §1.6 把原先 9 个条件键**全部改为恒输出**（空集合写 `[]`、空串照写）。
- **请求面**：`EmbedChannelRequest`（19 处注解）与事件体 `EventRequest`（4 处）摘 `@JsonProperty`；
  访客事件体随 S4 的 DTO 收口为 camelCase（前端 `sessionId`）。
- **前端同批**（8 文件）：`api/embed/index.ts` 的类型与泛型去信封；`AgentEmbedChannelPanel`
  （93 处）表单/解包；`useEmbedBridge`（widget 引导链三处解包）；`EmbedPage`/`EmbedChatCore`/
  `menu`/`AgentEditorModal`/两个引用弹层。**接入示例（Node/Go 代码片段）同批改**——
  示例里解码我们响应的 `body.data.session_token` 必须跟着变，否则等于发布错文档。
- **刻意不改（登记）**：① 访客事件的 mcp authorize/status/resolve 仍委托 mcp 域，其 `{data,success}`
  信封随 **mcp 域批**收；② `/embed/:cid/files` 是文件代理（非 JSON）；③ widget↔host 的
  **postMessage 协议**（`channel_id`/`session_id`/`type`）不是 HTTP 契约，本批不动
  （`onEmbedHostToken`/`postEmbedReady` 等仍用下划线）。
- **新增工具：契约夹具重录开关**（`-Dcontract.refresh=true`）。换锚批会一次影响几十个 golden，
  逐个手改既慢又易错：`server/build.gradle.kts` 把该属性转发给测试 JVM，`EmbedContractTest` 支持它
  （命中时把**掩码后的实际响应**写回 `src/test/resources/contracts/`，平时是断言）。
  ⚠️ 用它之后**必须结构化复核差异**（本次 21 个夹具的差异只有三类：少 `success` 键 /
  config 多 3 个恒输出键 / 键序归一），别让"测试适应实现"。
- 验收：全量 **4684 / 0 失败 / 6 跳过** + `spotlessCheck`；前端 `vue-tsc` 0 + **690 用例** + `vite build`；
  **真实服务冒烟 14 路通过**（管理 6：201 裸对象 + 24 键 / 列表裸数组且**不泄漏 publishToken** /
  详情含 token / 更新无 token / preview / stats；公开 8：config 20 键恒输出 / exchange /
  建会话 201 / 建议 `{questions:[]}` / chunk 404 错误形态未动 / 事件 400 与 **204** / 删除 204→404）。
- **embed 域 `@JsonProperty` 23 → 0**；余下 E2（若有）：访客事件的 mcp 委托面随 mcp 域批。

**E2 候选（未做）**：mcp 委托三端点的信封（随 mcp 域）、`/embed/:cid/files`（非 JSON，无需改）、
postMessage 协议（登记为 SDK 边界）。

### 14.9n mcp 域换锚（M1：服务资源 + 凭据面，2026-10-01）

**✅ M1 执行记录（后端 + 前端同批）**：
- **响应形态**：服务资源面去 `{data,success}` 信封（§2.1）——create **201** 裸对象（§1.15）、
  list 裸数组、get/update 裸对象、tools/resources 裸数组、metadata 裸对象（**从未同步 = 裸 `null`**，
  显式 `NullNode`——Spring 对 null body 会发空正文，前端 JSON.parse 会炸）、
  usage-instructions 裸 `{usageInstructions}`、**delete 204**（§1.13）；
  凭据面同批：PUT → 裸 `{fields:{apiKey:{configured},token:{...}}}`、DELETE 保持 204。
- **键名**：`McpServiceResponse` / `McpServiceCreateRequest` / `McpAuthConfigResponse` /
  `McpCatalogSummary` / `CredentialsResponse` / `CredentialFieldMetadata` 全 camelCase；
  **布尔改名**：`is_builtin` → **`builtin`**（§9：布尔字段不带 is 前缀，字段与访问器一起改，
  否则 Jackson 会同时吐 `builtin` 与 `isBuiltin` 两个键）。
  **刻意保留**：`inputSchema`（MCP 协议字段名本身就是 camelCase，Go tag 也这么写）；
  `McpAuthType` 的**枚举值**（`api_key`/`bearer`/`oauth`）是取值不是键名，一律不动。
- **落库 jsonb 三处**（键名＝字段名）：`mcp_services.auth_config`（7 键）、
  `mcp_services.advanced_config`（3 键）、`mcp_metadata.tools[]`（`require_approval`→`requireApproval`）；
  `env_vars`/`headers` 是**用户自定义键**的映射，只需改列名不需迁移。SQL 见下。
- **请求面**：主 PUT 的**存在性映射**（Go 的 updateFields）键随之 camelCase——
  它按原始 JSON 键记录"哪些字段被显式提供"，改漏会让部分更新静默失效（`usageInstructions`/
  `authType`/`apiKeyHeader` 三个最容易漏）；凭据 DELETE 的**路径值** `api_key` → `apiKey`
  （它镜像响应里 credentials 映射的键名）。
- **前端同批**（9 文件）：`api/mcp-service.ts` 类型与解包（M1 函数去 `.data`；
  **M2/M3/M4 的查询（tool-approvals GET / oauth status）刻意保留 `.data`**）、
  `McpSettings`/`McpServiceDialog`（96 处）/`McpMetadataPanel`/`Input-field`/`editorResources` + 两个测试。
- **顺手修 3 处前端读错键的活缺陷**：① `MCPMetadata` 类型写成 `service_id`/`server_name`/… 而后端
  `McpMetadata` 字段本就是 camelCase → **服务文档行（server name/version/description）从来没显示过**；
  ② `McpTestResult.oauth_required` → 后端字段是 `oauthRequired` → OAuth 引导提示从不触发；
  ③ 元数据面板 `syncedAt` 与 `stale` 之外的键全错位（同 ①）。
- **刻意不改（登记）**：① 工具审批面（`GET/PUT /{id}/tool-approvals`：`McpToolApproval` 9 处 +
  `McpToolApprovalPolicyRequest` 2 处）与 agent 侧 `ResolveToolApprovalRequest`（3 处）→ **M4**；
  ② OAuth 面（`oauth/*` 39 处：AuthServerMetadata/OAuthState/OAuthToken/…）与
  `McpOAuthController` 的 4 处 → **M3**；③ agent 工具载荷里的 `usage_instructions`
  （`McpCatalog`/`McpDiscoverTool` 的 LLM 结果）与 agent 配置 jsonb 的 `is_builtin`（agentm）→ 他域冻结面。
- 验收：全量 **4684 / 0 失败 / 6 跳过** + `spotlessCheck`；前端 `vue-tsc` 0 错误 + **690 用例** + `vite build`；
  **真实服务冒烟 10 路通过**（创建 201 裸对象 24 键 + 密钥剥离 + credentials 映射 /
  列表裸数组 / 更新忽略主 PUT 里的秘密且不动既有凭据 / 凭据 PUT 裸 fields 且不回显 /
  凭据删除 204 幂等 + 状态回读 / tools 无信封 / metadata 裸 null / test 裸 McpTestResult /
  **工具审批面仍是信封（M4 未动，反证本批边界）** / 删除 204 → 404）。
- **`McpToolListTypeHandler` 的别名 mixin 退役**（M1 的关键一处）：该 handler 曾用
  `@JsonProperty("require_approval")` 的 mixin 把 `McpTool.requireApproval` 别名成 Go 的下划线键——
  键名收口后必须**同时**退役 mixin 并迁移存量行，否则旧行读出来会把 `requireApproval` 静默重置为 false
  （`FAIL_ON_UNKNOWN_PROPERTIES=false` 的宽容读）。
- **mcp 域 `@JsonProperty` 119 → 58**（余：oauth 面 39 = M3；审批/agent 侧 14 + 其它 5 = M4，详见下）。
  夹具：mcp-create/-list/-get/-update/-create-auth/-credentials-put/-metadata 共 7 个经重录开关刷新，
  逐条复核差异 = 去信封 + 键改名 + metadata→null，无其它变化。

**M1 存量迁移 SQL（真 PG；dev 库 mcp_* 表为空，0 行需迁移；表达式已用合成数据验证）**：
```sql
-- mcp_services.auth_config（7 键；枚举值 'api_key' 与用户自定义键不动）
UPDATE mcp_services SET auth_config = (
  SELECT jsonb_object_agg(e.key_new, e.value) FROM (
    SELECT CASE k.key
      WHEN 'auth_type' THEN 'authType'
      WHEN 'api_key' THEN 'apiKey'
      WHEN 'api_key_header' THEN 'apiKeyHeader'
      WHEN 'token' THEN 'token'
      WHEN 'custom_headers' THEN 'customHeaders'
      WHEN 'scopes' THEN 'scopes'
      WHEN 'auth_server_metadata_url' THEN 'authServerMetadataUrl'
      ELSE k.key END AS key_new, k.value
    FROM jsonb_each(auth_config) AS k) AS e)
WHERE jsonb_typeof(auth_config) = 'object' AND auth_config <> '{}'::jsonb;

-- mcp_services.advanced_config（3 键）
UPDATE mcp_services SET advanced_config = (
  SELECT jsonb_object_agg(e.key_new, e.value) FROM (
    SELECT CASE k.key WHEN 'retry_count' THEN 'retryCount'
                      WHEN 'retry_delay' THEN 'retryDelay'
                      ELSE k.key END AS key_new, k.value
    FROM jsonb_each(advanced_config) AS k) AS e)
WHERE jsonb_typeof(advanced_config) = 'object' AND advanced_config <> '{}'::jsonb;

-- mcp_metadata.tools[]（数组元素键；inputSchema 本就是这个拼写，不在改名表里）
UPDATE mcp_metadata SET tools = (
  SELECT coalesce(jsonb_agg((SELECT jsonb_object_agg(
      CASE e.key WHEN 'require_approval' THEN 'requireApproval' ELSE e.key END, e.value)
    FROM jsonb_each(elem) AS e)), '[]'::jsonb)
  FROM jsonb_array_elements(tools) AS elem)
WHERE jsonb_typeof(tools) = 'array' AND tools <> '[]'::jsonb;
```

**M3/M4 候选（未做）**：M3 = OAuth 面（`oauth/*` 39 处 + `McpOAuthController` 4 处，含
`AuthServerMetadata`/`OAuthState`(Redis)/`OAuthToken`(DB) 的落库与线协议判定）；
M4 = 工具审批面（`McpToolApproval` 9 + `McpToolApprovalPolicyRequest` 2 + `ResolveToolApprovalRequest` 3 +
agent 侧 `AgentToolApprovalController`，含 embed 事件委托的信封）。

### 14.9q datasource 域作战计划（2026-10-01 只读侦察，待执行）

**总量**：`datasource/` 主代码 **493 处** `@JsonProperty`（130 个文件）。**按"谁的面"三分**：

| 面 | 处数 | 判定 |
| --- | --- | --- |
| **第三方 connector 线格式** | **350** | **永久冻结**：飞书 `FeishuApiTypes`+`DocxBlocks`、语雀 `YuqueApiTypes`、IMA `ImaApiTypes`、`GitLabClient` 等——字段名由对方 API 决定（同 mcp 的 RFC 面）。⚠️ 早期写的 270 是窄口径（漏算全限定写法与部分文件），以 350 为准 |
| **队列载荷（我方内部）** | 16 | `DataSourceSyncPayload`(13)+`TaskInitiator`(3)：进程内同步队列的 JSON（`InProcessDataSourceSyncTaskQueue`）；**但有 Go 逐字节 golden（`DataSourceJsonTest`）** → 归 D3 |
| **我方 HTTP/落库面** | ~143 | 拆 D1（76）/D2（51）/D3（16） |

**批次划分**
- **D1 主资源 + 凭据 + 资源目录（~140）**：`domain/DataSource`(22)、`dto/DataSourceResponse`(22)、`domain/Resource`(10)、
  `ConnectorMetadata`(8)、`domain/DataSourceConfig`(5)、`dto/DataSourceConfigDto`(4)、`dto/CredentialsResponse`(2)、
  `dto/CredentialFieldMetadata`(2)、`controller/DataSourceController`(1) 等。
  - **响应形态已基本是裸的**（侦察实录：`listAvailableResources` 返回裸 `List<Resource>`、`deleteDataSource` → 204、
    validate → `statusBody("connected")`）→ 本批以**键名**为主，只需清点剩余信封。
  - **落库 jsonb（`data_sources` 3 列，含 `config`）** → 迁移 SQL（§2 第 11 条）。
  - 前端：`api/datasource/index.ts` + 数据源设置页（侦察到 121 处旧键引用，**注意其中含 i18n 文案键与
    `auditActionRegistry` 的动作名——那些不是线格式键，别动**）。
- **D2 同步日志与结果（~60）**：`SyncLog`(17)、`FetchedItem`(15)、`SyncResult`(10)、`SyncItemError`(5)、`SyncCursor`(4)；
  `sync_logs` 的**嵌套 jsonb**（fetched items 一类的数组列）→ 迁移 SQL。
- **D3 队列载荷（~16）**：`DataSourceSyncPayload`/`TaskInitiator` + `DataSourceJsonTest` 的处理。

**入场前必须判定的两件事（别跳过）**
1. **`data_sources.config` 内层的键名归属**：外层（`type`/`credentials`/`settings`）是我方 schema ✓ 可换，
   但 `settings` 内层是**自由 map**，其键往往就是各 connector 的配置字段名（如飞书 `app_id`）——
   那些键由 `ConnectorMetadata` 的凭据字段描述符定义、且前端表单按同名提交，**改了会同时打断
   构建期表单与既有行**。判定口径：**外层包装键换 camelCase，内层字段名保持不动**（它是数据，不是键）。
2. **`DataSourceJsonTest` 的定位**：它钉的是队列载荷的逐字节 Go 输出（Go 内部结构体，非 HTTP 契约）。
   按 §2/§13.12 应改写为"键名＝字段名"的重录制，并在提交信息里写明改判理由（"Go 内部载荷不属对外契约"）。

**验收口径**：三闸门 + **真实服务冒烟**（datasource 端点完整、可本机跑通：`GET /datasources/connectors`
（裸数组）→ `POST /datasources`（201）→ `GET /datasources` → `GET /datasources/{id}/logs` → 凭据 PUT/DELETE → 204）；
**39 个 `ds-*` 夹具**（`DataSourceHttpContractTest`）用 `assertGoldenBody` 重录制（键改名一律走重录，别手改结构）。

**冻结登记**：270 处 connector 线格式（`connector/{feishu,yuque,ima,gitlab,notion,rss}` 的 `*ApiTypes`/客户端），
与 mcp 的 RFC 面同类——**别把它们的键名"改回 camelCase"**。

**✅ D1（主资源 + 凭据 + 资源目录）执行记录（2026-10-01）**——76 处 / 9 文件：
- **键名换锚**：`DataSource`(22)、`DataSourceResponse`(22)、`Resource`(10)、`ConnectorMetadata`(8)、
  `DataSourceConfig`(5)、`DataSourceConfigDto`(4)、`CredentialsResponse`(2)、`CredentialFieldMetadata`(2)、
  控制器请求记录(1)；`@JsonPropertyOrder`（Go 声明序）与 9 处 `@JsonInclude`（omitempty 直译）一并退役
  （§1.6：可空字段显式 null、空串照写——`Resource` 的三个原 omitempty 键、`ConnectorMetadata.icon`
  因此由"键消失"变为恒输出）。
- **请求面**：创建/更新体直接绑定 `DataSource` 实体 ⇒ 键名＝字段名（同批改）；
  查询参数 `kb_id`→**`kbId`**、`parent_id`→**`parentId`**；`resource-ancestors` 体 `resource_ids`→**`resourceIds`**。
  ⚠️ 校验文案照抄 Go（`kb_id is required`）——属已知的"文案-键名不一致"（cosmetic，同 S4/M4 口径）。
- **响应面**：`PUT /datasource/{id}/credentials` 从 `{data:{fields:…},success:true}` 改**裸对象**
  `{fields:{"credentials":{"configured":bool}}}`（§2.1）。
- **落库 jsonb（1 列）**：`data_sources.config` 顶层 `resource_ids`→`resourceIds`
  （内层 `settings`/`credentials` 是各 connector 的**字段名**，按 §14.9q 入场判定**保持不动**）；
  表达式已在真 PG 用合成数据验证（有键行改名、无键行与 null 行不动、同层其它键不受影响）；
  dev 库 0 行需迁移。`last_sync_cursor`/`last_sync_result` 是 connector 载荷，不在本批。
- **Go 逐字节测试退役**：`DataSourceJsonTest` 的 10 个 D1 用例与 `ConnectorFrameworkTest` 的 2 个用例
  **保留结构、改写期望值**为本方契约形状（同 memory `MemoryEntityJsonTest` 的做法）；
  D2/D3 类型的用例原样通过——正好证明批次边界。
- **契约测试可重录化**：`DataSourceHttpContractTest` 加 `-Dcontract.refresh=true` 开关 + `assertGoldenBody`
  统一入口（原来 31 处 `assertEquals(golden(...))` 各自为政），33 个 golden 一次重录。
  ⚠️ **掩码按键名匹配，键改名必须同步放宽正则**（第三次踩，见 §13.13）：`[a-z_]+` → `[A-Za-z_]+`，
  否则 `knowledgeBaseId`/`createdAt` 这类键漏掩，夹具里会混进真实 UUID 与逐次变化的时间戳
  （本次已混入并修掉：重录后逐个夹具扫过"未掩 id/时间戳 = 0"）。
- 前端：`api/datasource/index.ts`（类型 + `?kbId=`/`?parentId=` + `{resourceIds}` + 凭据裸读）+
  `DataSourceSettings.vue`/`DataSourceEditorDialog.vue`（D1 键；`DataSourceSyncLogs.vue` 属 D2 未动）。
- 验收：全量 **4686 / 0 失败 / 6 跳过** + `spotlessCheck`；前端 `vue-tsc` 0 错误 + **690 用例**；
  **真实服务冒烟 10 路通过**（连接器目录裸数组+authType/icon 恒输出 / 建源 201 + **21 键 camelCase** /
  旧 snake 建库体→400 **反证** / 列表 `?kbId=` 裸数组 / 旧 `?kb_id=`→400 **反证** / 详情 / 更新往返 /
  日志端点 200（**D2 键未动，边界证明**）/ 凭据 PUT 裸 `{fields:…}` / 凭据删除 204）。
  冒烟前置：本地 RSS 桩（18099）+ `SSRF_WHITELIST_EXTRA=127.0.0.1` 重启（否则创建被 SSRF 拦）。
- 剩余：**D2（同步日志与结果，51 处）→ D3（队列载荷 16 处 + `DataSourceJsonTest` 的 D2/D3 用例）**；
  350 处 connector 第三方线格式永久冻结（§14.9q；口径见存量表脚注）。

**⚠️ 本轮发现（登记待办）**：M1 只去了 `@JsonProperty` 与 `@JsonPropertyOrder`，**漏了类级/字段级 `@JsonInclude`**——`McpServiceResponse`、`McpAuthConfigResponse`、`McpTool`、`McpTestResult`（mcp 域 7 个文件）仍是 omitempty 直译，按 §1.6 应改恒输出（会动响应键集合 ⇒ 需重录夹具）。另 `McpCatalogSummary` 的 `Include.ALWAYS` 是默认值可删。作为 **M6** 小批处理。

**✅ D2（同步日志与结果）执行记录（2026-10-01）**——51 处 / 5 类：
- **键名换锚**：`SyncLog`(17)、`FetchedItem`(15)、`SyncResult`(10)、`SyncItemError`(5)、`SyncCursor`(4)；
  `@JsonPropertyOrder` 全退役。**条件键一并改恒输出**（§1.6），这批的键集合变化最明显：
  `SyncResult` 的 `deletionFailed`（原 NON_DEFAULT）/`errors`（原 NON_EMPTY）/`nextCursor`（原 NON_NULL）
  → 零值写 0 / nil 写 null；`SyncItemError` 四键恒在（**零值对象不再是 `{}`**，而是
  `{"title":"","code":"","params":null,"message":""}`）；`FetchedItem` 的 `replacesSubtree`/`subtreeKeep` 同款。
- **落库 jsonb（3 处，已用真 PG 合成数据验证）**：`sync_logs.result`（含**内层** `nextCursor` 的三个键）、
  `data_sources.last_sync_result`（同形）、`data_sources.last_sync_cursor`（包装三键；
  **内层 `connectorCursor` 是各 connector 私有键，一律不动**——RSS 自己的 `last_sync_time` 就藏在里面）。
  dev 库 0 行需迁移（表达式见下方 SQL）。
- **业务键不动的两处**：`TaskInitiator`（`user_id`/`role`，属 D3 队列载荷）与 `RssCursor.toMap()`
  （connector 私有游标键）——`DataSourceJsonTest` 的键序用例里两者并存，**证明分块换锚没串味**。
- 测试：`DataSourceJsonTest` 12 个 D2 用例按本方形状改写（含**改名**两个口径已变的用例：
  `syncResultOmitsEmptyCollectionsAndZeroDeletionFailed` → `…Keeps…`、
  `syncItemErrorZeroMatchesGoAsEmptyObject` → `…AsFullObject`）；`RssCursorJsonTest` 的外层包装键同批；
  `DataSourceHttpContractTest` 4 个日志/同步 golden 重录 + 内联断言改键。
  顺带**修正一条从未生效的断言**：`fetchedItemDoesNotLeakPinnedStyleIsProperty` 原第二条子句写成
  `doesNotContain("\"deleted\":")`，与第一条自相矛盾（等于没断言），已改为 `doesNotContain("is_deleted")`。
- 前端：`api/datasource/index.ts`（SyncLog/SyncResultDetail/SyncItemError 类型改 camelCase 且标为恒在）+
  `DataSourceSyncLogs.vue`(37 处)/`DataSourceSettings.vue`(13 处)。
- 验收：全量 **4686 / 0 失败 / 6 跳过** + `spotlessCheck`；前端 `vue-tsc` 0 错误 + **690 用例**；
  **真实服务冒烟 6 路通过**（跑真同步打本地 RSS 桩：手动同步 200 + sync_log 16 键 camelCase /
  日志行 + `result` 全 camelCase（`errors` 恒在）/ 列表 `latestSyncLog` 嵌套同批 /
  **落库 `sync_logs.result` 与 `data_sources.last_sync_result` 键名已换锚** / 响应无旧 snake 键）。
  ⚠️ 冒烟发现两处口径（已记）：GET 详情**不补** `latestSyncLog`（只有列表补）；RSS **全量**同步不写
  `last_sync_cursor`（增量才有）——所以落库断言取的是 `last_sync_result`。
- 剩余：**D3（队列载荷 `DataSourceSyncPayload`/`TaskInitiator`，16 处 + `DataSourceJsonTest` 的 D3 用例）**
  ——做完即该域收官（余 350 处为 connector 第三方线格式，永久冻结）。

**D2 存量迁移 SQL（真 PG 合成数据验证；dev 库 0 行）**：
```sql
-- ① sync_logs.result：顶层两键 + 内层 nextCursor 三键
UPDATE sync_logs SET result = (
  SELECT jsonb_object_agg(CASE e.key
      WHEN 'deletion_failed' THEN 'deletionFailed'
      WHEN 'next_cursor' THEN 'nextCursor' ELSE e.key END,
      CASE WHEN e.key = 'next_cursor' AND jsonb_typeof(e.value) = 'object' THEN (
        SELECT jsonb_object_agg(CASE k.key
            WHEN 'last_sync_time' THEN 'lastSyncTime'
            WHEN 'connector_cursor' THEN 'connectorCursor'
            WHEN 'last_schema_hash' THEN 'lastSchemaHash' ELSE k.key END, k.value)
        FROM jsonb_each(e.value) AS k) ELSE e.value END)
  FROM jsonb_each(result) AS e)
WHERE jsonb_typeof(result) = 'object' AND (result ? 'deletion_failed' OR result ? 'next_cursor');

-- ② data_sources.last_sync_result：同 ①（顶层两键 + 内层 nextCursor 三键）
UPDATE data_sources SET last_sync_result = (
  SELECT jsonb_object_agg(CASE e.key
      WHEN 'deletion_failed' THEN 'deletionFailed'
      WHEN 'next_cursor' THEN 'nextCursor' ELSE e.key END,
      CASE WHEN e.key = 'next_cursor' AND jsonb_typeof(e.value) = 'object' THEN (
        SELECT jsonb_object_agg(CASE k.key
            WHEN 'last_sync_time' THEN 'lastSyncTime'
            WHEN 'connector_cursor' THEN 'connectorCursor'
            WHEN 'last_schema_hash' THEN 'lastSchemaHash' ELSE k.key END, k.value)
        FROM jsonb_each(e.value) AS k) ELSE e.value END)
  FROM jsonb_each(last_sync_result) AS e)
WHERE jsonb_typeof(last_sync_result) = 'object'
  AND (last_sync_result ? 'deletion_failed' OR last_sync_result ? 'next_cursor');

-- ③ data_sources.last_sync_cursor：只改包装三键（connectorCursor 内层是 connector 私有键）
UPDATE data_sources SET last_sync_cursor = (
  SELECT jsonb_object_agg(CASE k.key
      WHEN 'last_sync_time' THEN 'lastSyncTime'
      WHEN 'connector_cursor' THEN 'connectorCursor'
      WHEN 'last_schema_hash' THEN 'lastSchemaHash' ELSE k.key END, k.value)
  FROM jsonb_each(last_sync_cursor) AS k)
WHERE jsonb_typeof(last_sync_cursor) = 'object' AND last_sync_cursor ? 'last_sync_time';
```

**✅ D3（队列载荷）+ datasource 域收官（2026-10-02）**——我方面 16 处清零：
- **换锚**：`DataSourceSyncPayload` 的 7 个自有键（`data_source_id`→`dataSourceId` 等）+ `TaskInitiator`(2)；
  `@JsonPropertyOrder` 与自有键上的 `@JsonInclude`（omitempty 直译）一并退役（§1.6：`forceFull` false 照写、
  `trigger` 空串照写、`maxItems` 0 照写、零值 `TaskInitiator` 是两个空串而非 `{}`）。
- **⚠️ `lf_*` 五键判为冻结（本批最重要的边界判定）**：它们是**平铺载具的命名空间前缀**——
  `com.ragagent.common.context.TracingContext` 被**平铺**进 4 个载荷（datasource / memory / wiki / knowledge），
  去掉前缀就会与载荷自有字段撞名（如 `userId`/`sessionId`）。四域 + 共享记录是同一形状，改名要一起动
  且失去命名空间保护；**确需清理时应改成"嵌套一个 `tracing` 键"（形状变更，另批）**，而不是去前缀。
  故 `DataSourceSyncPayload` 里这五个 `@JsonProperty("lf_*")` 与它们的 `NON_EMPTY` 语义**刻意保留**，
  并在类注释里写明了理由。判定口径已同步进 §11 边界清单。
- **零兼容负担**：载荷只在**进程内队列**流动（不落库、不出响应、无第二个实现）→ 无需迁移 SQL、
  无需兼容读（对比 M5 的 Redis blob 要兼容读：那是**跨进程 + TTL 窗口**的场景）。
- 测试：`DataSourceJsonTest` 的 4 个 D3 用例（`taskInitiatorMatchesGo` / 载荷零值 / 全值 / 键序条目）
  按本方形状改写；两处类注释里的旧 JSON 样例同步（SyncLog 与载荷各自的"Go 实录"块）。
- 验收：全量 **4686 / 0 失败 / 6 跳过** + `spotlessCheck`；前端无面（内部载荷，未动）；
  真实服务冒烟沿用 D2 那轮（打本地 RSS 桩跑真同步走的就是本载荷：调度器 → 队列 → worker），
  换锚后同轮冒烟 6 路仍全绿。
- **datasource 域收官**：493 →（D1）417 →（D2）366 →（D3）**355**，其中 **350 处是 connector 第三方线格式
  （永久冻结）+ 5 处 `lf_*` 共享载具（冻结）** ⇒ **该域可换锚面 = 0**。

### 14.9r wiki ingest 载荷 W1 收官 + 阶段 3 域级换锚收官（2026-10-02）

**范围甄别（动刀前三分）**：`wiki` 包 `@JsonProperty` 计 **39 处 / 7 文件**，不是一面：
- **task_pending_ops 落库载荷 4 类（可换锚 17 键）**：`WikiPendingOp`(7)、`WikiFinalizeRow`(4)、
  `WikiFinalizeChange`(3)、`WikiIngestPayload` 自有键(3)；键序注解与声明序一致，`@JsonPropertyOrder` 直接退役。
- **死注解 8 处**：`WikiRetractPayload` ——侦察证实**全仓无任何序列化点**（字段在
  `WikiIngestEnqueueOps.enqueueWikiRetract` 里摊平进 `WikiPendingOp` 落库、追踪载体进 `WikiIngestPayload` 进队列），
  8 处 `@JsonProperty` + 3 处 `@JsonInclude` 是 Go 时代遗物，**整类摘除**而非改名。
- **冻结 14 处**：`WikiIngestPayload` 的 `lf_*` 五键（平铺载具，D3 同款判定，`@JsonProperty`+NON_EMPTY 刻意保留）
  + `CombinedExtraction`(2)/`NewSlugFromCitation`(7) ——**LLM 输出解析面**，键名由 `WikiPrompts` 三条 prompt
  正文钉住（`entities`/`concepts`/`source_chunks`/`new_slugs`…），已登记进 §14.6 边界清单；
  另 `WikiIngestCitePipeline.CitationBatchResult` 2 处全限定注解同面同冻结。

**✅ W1 执行记录（2026-10-02）**：
- **换锚**：上述 4 类 17 键去 `@JsonProperty`（JSON 名=Java 字段名）；**条件键改恒输出**（§1.6）——
  `WikiPendingOp` 五个 retract 专属键零值写 `null`、`WikiFinalizeRow` 未选分支两键显式 `null`
  （"恰好一个被设置"语义不变）、`WikiFinalizeChange.docTitle/docSummary` 恒在、`WikiIngestPayload.language`
  null 照写。
- **零兼容负担面**：`WikiIngestPayload` 只在单 JVM 的 `InProcessWikiIngestTaskQueue` 流动（队列实现 javadoc
  明示跨实例语义需换 Redis/MQ 实现）⇒ 无需兼容读；**唯一落库点**是 `task_pending_ops.payload`
  （`WikiPendingOp`/`WikiFinalizeRow`）与 `task_dead_letters.payload`（死信留档，`readTree` 原样存、不重放）
  ⇒ 迁移 SQL 如下（dev 库两表实测 **0 行**；表达式已在真 PG 用 5+1 合成行验证：ingest/retract/change/folder_prune
  四形状改名正确、`slug` 行无键不动、死信行 `lf_*` 不动）。
- **前端无面**（内部队列与落库载荷；wiki 对前端契约已在 C 波完成）。
- 测试：`JsonContractRoundTripTest` 注释去 Go 对齐理由（改列本批边界口径）、"language 省略"用例改
  "language null 显式输出"；`InProcessWikiIngestTaskQueueTest` 三处硬编码 snake 载荷字面量改 camelCase
  （**侦察教训：grep 蛇形键要带转义引号模式**，普通 `'"[a-z_]*_'` 漏掉 Java 字符串里的 `\"`）；
  `WikiIngestServiceTest$FinalizeRows.rowJsonShapes` 原"与 Go 的 omitempty 一致"三断言按 §13.12
  改写为本方形状（改判理由：内部落库载荷不属对外契约，§2 第 11 条）。
- 验收：全量 **4686 / 0 失败 / 6 跳过** + `spotlessCheck`；无 HTTP 面改动，冒烟不适用（wiki 域测试全绿
  覆盖 enqueue→peek→process 真序列化路径）。
- **收官判定**：W1 后 wiki 包 `@JsonProperty` 39→**14**（`lf_*` 5 + `CombinedExtraction` 2 + `NewSlugFromCitation` 7），
  连同同面的 `common/wiki/ExtractedItem`(6) 与 `CitationBatchResult`(2，全限定写法) 合计 22 处**全部是登记冻结面
  ⇒ 该域可换锚面 = 0**。至此**阶段 3 域级契约换锚收官**：knowledge/retrieval/会话链/
  chunker/evaluation/model/system/auth/memory/mcp/session/embed/datasource/wiki 十四域可换锚面全部清零，
  全仓 `@JsonProperty` 余量（约 1,060 处）均为第三方 API、RFC 协议、SSE/Redis 事件、provider 请求体、
  LLM 载荷、`lf_*` 载具、租户配置 jsonb 等登记冻结面；剩余真存量只有**小额散尾巴**（见 ⭐ 接手须知第 4 条）。

**W1 存量迁移 SQL（真 PG 合成数据验证；dev 库 0 行）**：
```sql
-- ① task_pending_ops.payload：顶层 5 键 + 内层 change 的 2 键（表为 wiki 专属，键名条件已限定范围）
UPDATE task_pending_ops SET payload = (
  SELECT jsonb_object_agg(
      CASE e.key
        WHEN 'knowledge_id' THEN 'knowledgeId'
        WHEN 'doc_title' THEN 'docTitle'
        WHEN 'doc_summary' THEN 'docSummary'
        WHEN 'page_slugs' THEN 'pageSlugs'
        WHEN 'folder_ids' THEN 'folderIds'
        ELSE e.key END,
      CASE WHEN e.key = 'change' AND jsonb_typeof(e.value) = 'object' THEN (
        SELECT jsonb_object_agg(CASE k.key
            WHEN 'doc_title' THEN 'docTitle'
            WHEN 'doc_summary' THEN 'docSummary' ELSE k.key END, k.value)
        FROM jsonb_each(e.value) AS k) ELSE e.value END)
  FROM jsonb_each(payload) AS e)
WHERE jsonb_typeof(payload) = 'object'
  AND (payload ? 'knowledge_id' OR payload ? 'doc_title' OR payload ? 'doc_summary'
       OR payload ? 'page_slugs' OR payload ? 'folder_ids'
       OR (payload -> 'change') ? 'doc_title' OR (payload -> 'change') ? 'doc_summary');

-- ② task_dead_letters.payload：只改包装两键（lf_* 冻结不动）
UPDATE task_dead_letters SET payload = (
  SELECT jsonb_object_agg(CASE e.key
      WHEN 'tenant_id' THEN 'tenantId'
      WHEN 'knowledge_base_id' THEN 'knowledgeBaseId' ELSE e.key END, e.value)
  FROM jsonb_each(payload) AS e)
WHERE jsonb_typeof(payload) = 'object'
  AND (payload ? 'tenant_id' OR payload ? 'knowledge_base_id');
```


### 14.9s 契约尾巴全清 + 两个真单类出榜（2026-10-02，15 个提交）

**批 C（`ef887db`）全局错误体**：`GlobalExceptionHandler.errorBody` 从
`{"error":{…},"success":false}` 改 `{"error":{"code","message","details"}}`（§2 第 4 条标准形态；
旧 code/details/message 字母序是 Go gin.H 复刻）。同族纯字符串错误体四处手写构造点
（SessionStreamController.writeJsonError、StorageBackendController、WebSearchProviderController
errorEnvelope、VectorStoreController.errorEnvelope）一并去 success。**夹具 622 个**：604 个
`{error,success:false}` 金片去 success、571 个多行格式压回紧凑单行、23 个纯字符串错误金片同批、
10 个字节级对比金片按实况重录。死信封类（common/web 的 ApiResponse/R/DataMessageResponse/
MessageResponse）全仓零引用删除。
**教训**：契约测试的 `raw()` 走 `ContractJson.semantic` 归一（键序排序）——断言字面量要与
**归一化后**的形态对齐，不是服务器原始输出；字节级与语义级对比器并存，改金片前先看对比器。

**A-audit（`ff53d90`）**：审计三端点 `{success,data,next_cursor}` → `{"items":[…],"nextCursor":N}`
+ 查询参数 `after_id`→`afterId`；前端 audit-log.ts 类型 camelCase（原类型按 Go tag 抄的 snake 与
实况不符——TS 断言掩盖的实况 bug，顺带纠正）+ 三视图同批。

**A-favorite（`ed83cac`）**：收藏三端点裸数组 + 创建 201 / 删除 204；实体摘 5 处注解 +
**GoTimeSerializer 退役**（ISO-8601）；fav-* 金片重录；前端 useResourcePins 同批。

**A-im（`014e000`）**：IM 渠道面 17 处注解退役 + create 201/裸行 + delete 204 + 列表裸数组 +
wechat 扫码两端点裸对象（confirmed 条件键改恒输出）；行键 camelCase（credentials 内层是各平台
凭据字段名，照 D1 口径不动）。`ImContractTest` 加 `-Dcontract.refresh=true` 重录开关。
**教训**：请求体键名换锚后宽松绑定会吞掉旧 snake 键——bad-session-mode 用例静默从 500 变 201，
重录前先核对状态码语义。

**A-storage（`0996885`）**：StorageConfig（`storage_backends.config` jsonb）13 处 + StorageBackendResponse
11 处 + 请求记录 4 处注解退役；types→裸数组、test→`{connected}`、create 201 裸资源、list→
`{items,defaultStorageBackendId}`、delete/setdefault→204；迁移 SQL 只改键名（密文与键名正交，
真 PG 合成数据验证；dev 库 2 行均 `{}` 无需迁移）。
**边界判定**：`StorageFileResolver.toStorageEngineConfig` 把实例行配置**翻译成冻结的引擎面（snake）**
再交工厂——工厂与租户引擎配置统一读 snake，camel 只存在于行配置一侧；TenantCatalogContractTest 的
KV storage 请求体是冻结面，误 camel 化后回退。

**A-vectorstore（`f0a19dd`）**：ConnectionConfig 14 + IndexConfig 16 + VectorStoreResponse 11 +
VectorStoreTypes（含表单 schema 广告字段名）全 camelCase；信封拆除、delete 204、test→`{version}`；
`vector_stores` 两列 jsonb 迁移 SQL 备好（dev 0 行，合成数据验证）；vs-* 金片重录；前端五个文件同批。

**A-misc（`6449e39`）**：死注摘除 WebSearchResult 7 处（管线内转 SearchResult，自身从不序列化）+
RuntimeStat 5 处；TempKbState（Redis）换锚 + **M5 式部署窗口兼容读**（migrateLegacyKeys）。
**判定翻转（本批最重要教训）**：SearchParams 13 处**不是死注**——它序列化进 PipelineLog 的 span
载荷（SearchRecordingTest 金片钉住），回退并登记 §14.6；死注判定必须扫"参数对象被泛型序列化"的路径。

**A-auth 补刀（`657597b`）**：APIPrincipalConfig（`tenants.api_principal_config`）5 处换锚 +
迁移 SQL（密文随键移动，合成验证；dev 0 行）；RegisterResponse 死类删除（A1 已裸返，前端
register() 的 success 包装实况 bug 顺带修）；四处陈旧 javadoc 清洗。

**A-agentm/initialization（`581d583`）**：SkillsCatalog 去 success 键；Ollama 下载"任务已存在"分支
信封拆除；ModelConnectivityTest 的 success/processingTime 判为业务结论字段保留；误入库的
`.bak12` 备份文件删除。

**M6（`e929be7`）**：mcp 六类 `@JsonInclude`（omitempty 直译）全部退役改恒输出（§14.9q 登记的
待办），McpCatalogSummary 的 ALWAYS（默认值显式写法）同删；mcp-* 五金片重录。

**本轮三条落库迁移 SQL（均已真 PG 合成数据验证；dev 库对应行要么 0 行、要么空 `{}`）**：
```sql
-- ① storage_backends.config（StorageConfig 10 键改名；密文值随键移动）
UPDATE storage_backends SET config = (
  SELECT jsonb_object_agg(CASE e.key
      WHEN 'access_key_id' THEN 'accessKeyId'
      WHEN 'secret_access_key' THEN 'secretAccessKey'
      WHEN 'bucket_name' THEN 'bucketName'
      WHEN 'path_prefix' THEN 'pathPrefix'
      WHEN 'app_id' THEN 'appId'
      WHEN 'use_ssl' THEN 'useSsl'
      WHEN 'force_path_style' THEN 'forcePathStyle'
      WHEN 'use_temp_bucket' THEN 'useTempBucket'
      WHEN 'temp_bucket_name' THEN 'tempBucketName'
      WHEN 'temp_region' THEN 'tempRegion'
      ELSE e.key END, e.value)
  FROM jsonb_each(config) AS e)
WHERE jsonb_typeof(config) = 'object';

-- ② vector_stores.connection_config（6 键）
UPDATE vector_stores SET connection_config = (
  SELECT jsonb_object_agg(CASE e.key
      WHEN 'api_key' THEN 'apiKey'
      WHEN 'insecure_skip_verify' THEN 'insecureSkipVerify'
      WHEN 'use_tls' THEN 'useTls'
      WHEN 'grpc_address' THEN 'grpcAddress'
      WHEN 'use_default_connection' THEN 'useDefaultConnection'
      WHEN 'http_port' THEN 'httpPort'
      ELSE e.key END, e.value)
  FROM jsonb_each(connection_config) AS e)
WHERE jsonb_typeof(connection_config) = 'object';

-- ③ vector_stores.index_config（16 键）
UPDATE vector_stores SET index_config = (
  SELECT jsonb_object_agg(CASE e.key
      WHEN 'index_name' THEN 'indexName'
      WHEN 'number_of_shards' THEN 'numberOfShards'
      WHEN 'number_of_replicas' THEN 'numberOfReplicas'
      WHEN 'collection_prefix' THEN 'collectionPrefix'
      WHEN 'collection_name' THEN 'collectionName'
      WHEN 'shard_number' THEN 'shardNumber'
      WHEN 'replication_factor' THEN 'replicationFactor'
      WHEN 'shards_num' THEN 'shardsNum'
      WHEN 'replica_number' THEN 'replicaNumber'
      WHEN 'desired_shard_count' THEN 'desiredShardCount'
      WHEN 'buckets_num' THEN 'bucketsNum'
      WHEN 'replication_num' THEN 'replicationNum'
      WHEN 'hnsw_m' THEN 'hnswM'
      WHEN 'hnsw_ef_construction' THEN 'hnswEfConstruction'
      WHEN 'hnsw_ef_search' THEN 'hnswEfSearch'
      WHEN 'knn_engine' THEN 'knnEngine'
      ELSE e.key END, e.value)
  FROM jsonb_each(index_config) AS e)
WHERE jsonb_typeof(index_config) = 'object';

-- ④ tenants.api_principal_config（4 键；hmac_secret 密文随键移动）
UPDATE tenants SET api_principal_config = (
  SELECT jsonb_object_agg(CASE e.key
      WHEN 'direct_header_name' THEN 'directHeaderName'
      WHEN 'signed_token_header_name' THEN 'signedTokenHeaderName'
      WHEN 'require_direct_header' THEN 'requireDirectHeader'
      WHEN 'hmac_secret' THEN 'hmacSecret'
      ELSE e.key END, e.value)
  FROM jsonb_each(api_principal_config) AS e)
WHERE jsonb_typeof(api_principal_config) = 'object'
  AND (api_principal_config ? 'direct_header_name' OR api_principal_config ? 'signed_token_header_name'
       OR api_principal_config ? 'require_direct_header' OR api_principal_config ? 'hmac_secret');
```

**复查批（2026-10-02 深夜，全量复查揪出的对齐债 + 文档勘误）**：
- **文档勘误**：本节提交数 9→13（实际）；storage/vectorstore/APIPrincipalConfig 三条迁移 SQL 补录（上文）。
- **websearch provider CRUD 换锚**（此前整域漏在甄别外）：types/list/test/create/get/update/delete 全部
  去信封（create 201、delete 204、test→`{connected:true}` 与失败体 `{connected:false,error}` 同形）；
  `WebSearchProviderResponse`(16)+`Types`(10)+`Params`(5) 注解退役（表单 schema 广告字段名同 camel，
  credentials 路径段 `api_key`→`apiKey`）；`CredentialsController` 的
  `{data:{fields:{...}},success}` → 裸 `{fields:{apiKey:{configured}}}`（D1 同款）；wsp-* 金片重录。
- **前端对齐债清扫**（后端已换锚、前端还读 success/data/旧键的实况断点，全部实锤修复）：
  auth 登录族 api 函数全部适配器化（**login 成功分支此前永不可达**——裸响应无 `success`；
  **refreshToken 流程读取 access_token 旧键**——401 刷新静默失败强制重登；changePassword 204、
  validateToken、updateMyPreferences、members 分页五函数同批）；RuntimeQueues 的 task 字段与
  游标（snake→camel）；wiki IndexGroup next_cursor；chatResources/AgentList 的 agents 列表解包
  （`{agents,disabledOwnAgentIds}`）；menu.vue 的 IM 平台列表；WebSearchSettings 表单全批；
  agentWebSearch 测试夹具同批。
- **教训入册**：换锚批的"同 PR 带前端"必须核到**视图层字段读取**，api 类型文件对齐不等于消费端对齐——
  TS `as unknown as` 断言把实况断点全部藏住了；本轮用「后端形态 × 前端读取」交叉 grep 才扫出。
- **漏翻译端点补齐（复查二批）**：`GET /tenants/search` 与 `/tenants/all` 前端在调、RbacInterceptor
  有守卫注册、javadoc 有路由清单，**但控制器实现整体缺失**（service 层 `searchTenants` 是现成的）——
  已补（裸分页 `{items,total,page,pageSize}` / 裸数组），加两条契约钉（裸形态四键 + 空命中 + all 数组）。
- **前端对齐第二批**：`/me/invitations` 适配器（裸 `{invitations,total}`）、knowledge-processing-timeline
  裸响应读取（`latestAttempt/parseStatus/trace`）、TenantSelector 搜索（`searchTenants` 裸分页）、
  TenantInfo/TenantSelector 的 `storage_quota` 系字段 camel、menu 会话删除 204 判定。
  **注意**：`tenant.default_storage_quota_gb` 是系统设置键（DB 字符串）、i18n key 同理——不是线格式键，
  交叉 sweep 时第一刀误扫已回退。

**切片（`9277d3e` + `432624c`）**：`SourceRegistry` 878→**534**——「工具参数编解码」段（~400 行）
外提 `SourceToolCodec`（439），门面保全部签名，SHORT_SOURCE_HANDLE 常量留注册段共用；
`UserService` 876→**748**——W5a 会话令牌段外提 `UserSessionOps`（180），三个失败通道异常留
UserService（控制器捕获面不变）。忠实性核验：文案逐字、两遍编码形状、先落偏好再签发等顺序不变。

**例外复核**：四个登记例外维持（§14.3）；MemoryIndexStore/KnowledgeProcessWorker 补 javadoc 例外说明。

**最终态**：全仓 `@JsonProperty` 913 处 / 102 文件全部是登记冻结面（残留尾巴与后续批次已集中立项为 **§15 全面修复计划**）；`@JsonInclude` 残 288 处
（大头冻结面 + 早批域的 omitempty 语义）登记为已知尾巴；错误体、信封、时间、键名四轴在
十四域 + 本轮七域全部对齐 §2 第 4 条。**阶段 3 收官。**

### 14.9p mcp 域 M5（OAuth 内部 blob 面）+ 该域收官（2026-10-01）

**✅ M5 执行记录**：`OAuthState`(9) + `OAuthAttempt`(4) 去键名映射——这两个记录**只序列化进
Redis/内存的同一份 JSON**（`OAuthStateStore` 的 `writeJson`/`readState`/`readAttempt`，TTL 10 分钟），
客户端看不到、DB 里没有。改键名 + **部署窗口兼容读**（`migrateLegacyKeys`：按已知旧键改名后再反序列化）。

- **为什么必须带兼容读**（本批最容易踩的坑）：两个记录都带 `@JsonIgnoreProperties(ignoreUnknown=true)`，
  旧 blob 不会反序列化失败，而是**静默变成 `tenantId=0 / serviceId=""` 的空壳** —— 滚动发布期间
  "已完成 authorize-url、还没点回调"的用户会拿到 `authorization_failed`（fail-closed，但体验差且难排查）。
  兼容读的删除条件：过完一次 `STATE_TTL`（10 分钟）的部署窗口后，连同 `LEGACY_KEY_RENAMES` 一起删。
- **判定为永久冻结（第三方协议，**别改**）**：`OAuthToken`（我方的唯一 Jackson 出口是解析授权服务器的
  RFC 6749 §5.1 token 响应；落库走 `McpOAuthToken` 实体列映射，表里没有 jsonb 列）、
  `AuthServerMetadata`（RFC 8414）、`OAuthProtectedResource`（RFC 9728）、`OAuthError`（RFC 6749 §5.2）。
  这几个类的注释里都写明了冻结理由。
- 测试：`OAuthStateStoreTest` 新增两条——`writesBlobsWithCamelCaseKeys`（写出口反证：旧键一条不留）
  与 `readsLegacySnakeCaseBlobsDuringDeployWindow`（兼容读：旧 blob 必须按旧键读出非空壳）。
- **验收口径的坦白**：本批**没有真机冒烟路径**——内部 blob 只有 Redis 分支才走 JSON（内存分支存的是
  类型化对象）；全流程由 `OAuthServerStub`/`OAuthHandlerTest`/`OAuthLifecycleTest` 覆盖，
  blob 往返由 `FakeOAuthStateRedis` 的两条新用例覆盖。
- **mcp 域收官**：119 →（M1）58 →（M4）35 →（M5）**22**，剩下的 22 处**全部是第三方协议面**
  （上一条列的四类），按 §14.9l 前提判定 2 永久冻结。该域 `@JsonProperty` 收尾完成。

### 14.9o mcp 域 M4（工具审批 + OAuth 用户面，2026-10-01）

**✅ M4 执行记录（后端 + 前端同批）**：
- **响应形态**：`GET /mcp-services/{id}/tool-approvals` → **裸数组**；`PUT .../tool-approvals/{tool}` → **204**；
  `POST .../oauth/authorize-url` → 裸 `{authorizationUrl, authorizationAttempt}`；
  `GET .../oauth/status` → **裸对象 4 键恒输出**（§1.6：`expiresAt` 为 null 表示"不过期"，不再是 omitempty 式"键消失"）；
  `POST /agent/mcp-oauth-resolutions/{id}` 与 `.../cancel` → **204**；`POST /agent/tool-approvals/{id}` → **204**。
  **embed 的 5 个委托端点自动跟随**（它们直接返回被委托控制器的响应）——E1 登记的"embed 事件委托信封"就此收掉。
- **请求面**：`AuthorizeRequest`（`redirectUri`/`frontendRedirect`）、`ResolveRequest`（`serviceId`/`decision`）、
  `McpToolApprovalPolicyRequest`（`requireApproval`/`enabled`）、`ResolveToolApprovalRequest`（`decision`/`modifiedArgs`/`reason`）。
- **实体**：`McpToolApproval` 去键名映射（响应行＝实体字段名）；`OAuthAuthorizationStatus` 同批。
- **前端**：`api/mcp-service.ts` 与 `api/embed/index.ts`（授权体/状态体/审批体 + 裸解包）+
  5 个组件（`McpToolsList`/`McpTestResultBody`/`McpServiceDialog`/`McpOAuthCard`/`ToolApprovalCard`）+ 1 个形态测试。
- **掩码同步（S3 的教训复现）**：`McpContractTest.UUID_KEY_PATTERN` 补 `serviceId`——掩码按键名匹配，
  键改名必须同步掩码，否则夹具里会混进随机 UUID（本次审批行夹具已混入一次，已重录修掉）。
- **刻意冻结（登记，别再碰）**：
  ① `oauth/AuthServerMetadata`（RFC 8414）、`OAuthProtectedResource`（RFC 9728）、`OAuthError`（RFC 6749）
  ——**外来协议文档，字段名由 provider 决定**（共 16 处，永久冻结）；
  ② 回调的 query 参数（`state`/`code`/`error`，provider 发来）与 URL fragment（`#mcp_oauth_result=success`
  / `#mcp_oauth_error=<code>`，弹窗↔前端的非 JSON 协议）；
  ③ 校验文案里的旧键名（`redirect_uri is required` / `service_id is required` /
  `require_approval or enabled is required`）→ 照抄 Go 原文（错误文案不动），属**已知的文案-键名不一致**（cosmetic）。
- **M5 候选（未做）**：`oauth/OAuthState`(9) / `OAuthToken`(6) / `OAuthAttempt`(4) = **19 处内部与落库面**
  （Redis 记录 + `mcp_oauth_tokens` 行）。改键会打断进行中的授权（TTL 有界），M5 决定兼容读还是直接改+登记。
- 验收：全量 **4684 / 0 失败 / 6 跳过** + `spotlessCheck`；前端 `vue-tsc` 0 错误 + **690 用例** + `vite build`；
  **真实服务冒烟 10 路通过**（审批行裸 `[]` / 策略写入 204 / 审批行 8 键 camelCase /
  oauth status 裸 4 键含 `expiresAt:null` / authorize-url 读到 `redirectUri` /
  agent 审批解析读到 `modifiedArgs` / OAuth 解析体读到 `serviceId`，外加 **4 处"旧 snake 键被忽略"的反证**）。
- **mcp 域计数**：119 →（M1）58 →（M4）**35**，其中 16 处是永久冻结的 RFC 文档面 → 只剩 19 处内部面（M5）。

### 14.8 wiki 域边界判定（2026-09-30 侦察，动手前先读）

- **不动**：`wiki/domain` 22 文件 173 处 `@JsonProperty`（§11 已登记的 wiki 域实体 snake 边界）；wiki 对前端契约整体（§2 第 4 条落地范围外，wiki 域 C 波另立切片）。
- **跨包缝合点（git grep 实测,11 文件）**：`service.WikiLanguageSupport`（agent PromptAssembly + knowledge×4 + session×2,消费最广）；`service.WikiIngestService`(+EnqueueResult)/`WikiKnowledgeFinalizer`/`DefaultWikiKnowledgeFinalizer`/`WikiImageMarkup`（knowledge 加工链）；`service.WikiPageService`/`WikiEditContext` + `domain.Wiki*`（session AgentToolBackends → agent wiki 工具,经 WikiPages seam 接口）；`controller.WikiActivityAudit`（audit）。
- **拆分纪律**：A 波门面保全部 public 成员与上述类型不动；WikiIngestBatchHandler 为 wiki 内部驱动（无跨包消费者），可自由拆。

### 14.9 契约工作（阶段 3 换锚）——何时做 / 做什么 / 怎么验收（2026-10-01 补写）

**为什么单独立节**：标准在 §2 第 4 条、进度散在 §2/§5、细则在 `docs/knowledge-api-contract-v1.md`，
而"什么时候做、一次做多少、怎么算完成"此前没有集中交代 —— 新 Agent 容易误判（本轮曾误写成"均未开始"）。

**已定、勿再讨论**
- 标准 = §2 第 4 条四段：camelCase 且 **JSON 字段名＝Java 字段名**（禁逐字段 `@JsonProperty` / `@JsonNaming`）；
  成功响应不包 `{data,success}`（分页 `{"items","page","pageSize","total"}`、删除返 **204**）；
  错误统一 `{"error":{"code","message","details"}}`；时间 ISO-8601 带时区；可空字段**显式 null**；不用 Problem Details。
- 落库格式（jsonb）同走 Java 字段名、**不加兼容别名**（§2 第 11 条）。
- 每个改契约的 PR **同批带前端**（§2 第 3 条）；产品未上线，无兼容期（§2 第 2 条）。
- 细则与历史差异表：`docs/knowledge-api-contract-v1.md`（v1.0，32KB）。

**进度（2026-10-02 收官）**
- 已完成：knowledge（含 34 个端点 DTO 化）/ retrieval / 会话链 / chunker-preview / evaluation（§14.9b）/ model（§14.9c/e）/ system（§14.9f/g）/ auth（§14.9h/i/j + 补刀 §14.9s）/ memory（§14.9k）/ session（§14.9l）/ embed（§14.9m）/ mcp（§14.9n/o/p + M6 §14.9s）/ datasource（§14.9q）/ wiki（§14.9r）/ **散存量七域 + 错误体统一（§14.9s）**。
- **无未完成项**。残留 `@JsonInclude` ~288 处与 Go 零值时间哨兵为登记尾巴（§14.6 / ⭐ 第 4 条），非在办。

**存量表（2026-10-01 盘点实测，§14.9 第 1 步交付物；只读扫描，三分法甄别）**

`@JsonProperty` 全仓 1,978 处 / 221 文件，**不是都是债**：

| 类别 | 量级 | 处置 |
|---|---|---|
| ① 外部 API 映射面（第三方 snake_case 合法映射） | 346 处 / 24 文件（feishu/yuque/ima/gitlab/notion 等 connector+client） | **保留**（映射外部 API 不是 Go 债） |
| ② §11 已登记边界面（SSE/Redis 事件载荷、provider 请求体、手搓载荷、agent config jsonb） | event 155 + agent(`AgentConfig`) 14 + stream 9 + tracing 7 + llm 大部（provider 面） | **保留**（§14.6 边界清单；动它=改事件契约，须独立切片） |
| ③ 真·阶段 3 存量（HTTP 契约面 + 落库 jsonb 面） | **~897 处 / ~150 文件**，重域：auth 247 / datasource 127 / memory 123 / mcp 110 / system 86 / **wiki 39 → 0（W1 收官，§14.9r：17 键换锚 + 8 处死注解摘除；余 22 处 = `lf_*` 5 + LLM 解析面 17，登记冻结）**；evaluation 62 → **0**（打样，§14.9b）；model 87 → **0**（四块收官，§14.9c/§14.9e）；**session 188 → 0（S1+S2+S3+S4+S5 全部收官，§14.9l，含 5 处落库 jsonb 迁移 SQL）**；**embed 23 → 0（E1 收官，§14.9m）**；**mcp 119 → 58（M1，§14.9n）→ 35（M4，§14.9o）→ 22（M5 收官，§14.9p；余 22 处全是第三方协议面：RFC 8414/9728/6749 文档 + 授权服务器 token 响应，永久冻结）**；**datasource 493 → 417（D1）→ 366（D2）→ 355（D3 收官，§14.9q）：余 350 处为 connector 第三方线格式 + 5 处 `lf_*` 平铺载具，**均为永久冻结** ⇒ 该域可换锚面 0**。**2026-10-02 两段收官判定**：域级换锚（§14.9r）后散尾巴约 77 处由 §14.9s 七域批处理完——甄别结果：
真存量已换锚（audit 1+信封、favorite 5、im 17、storage 24、retrieval WebSearchResult 7、TempKbState 3、
APIPrincipalConfig 5、RuntimeStat 5 死注、WebSearchResult 死注）；**判冻结新增登记**：image_info（docreader
第三方）、SearchParams（chat span 载荷）、RankResult（第三方 rerank API）。复查批后全仓 `@JsonProperty` 余量 **913 处
/ 102 文件** 全部是登记冻结面（§14.6）⇒ **③ 类真存量 = 0，阶段 3 收官**。⚠️ **计数口径**：`QaRequests` 那批用的是全限定注解（`@com.fasterxml…JsonProperty`），只 grep `@JsonProperty` 会漏——盘点时两种写法都要扫 | 按域推进，一域一 PR 同批带前端 |

`@JsonInclude`（Go omitempty 直译）存量：**~487 处**（NON_EMPTY 256 / NON_NULL 123 / NON_DEFAULT 108；ALWAYS 19 处是正确形态的显式 null，保留）。
`@JsonNaming` **0**、Problem Details **0**、Go 序列化器线上引用 **0**（2026-09-30 已一次性删除）。
Controller 全仓 52 个；每域 PR 入场时再做该域的"端点 × 前端调用点"细清单（§14.9 执行顺序第 3 步的入场检查）。

**执行顺序（关键约束）**
1. **先做清单盘点**（低风险、只读）：按域扫出「未换锚端点 + 对应前端调用点 + 涉及的 Go 序列化残留（注解 / jsr310 / NON_NULL / Problem Details 引用）」，
   产出一张存量表（形如 §14.3）。
2. **再一次性全仓删除序列化层**（§2 第 7 条已删的 156 处注解 + `JacksonConfig` 是第一批；剩余引用按清单扫净）——
   **不允许半删状态**（§5 阶段 3 红字：一部分端点走 Go 格式、一部分走标准 Jackson 最危险）。
3. 端点/落库面**按清单逐域推进**：一个域一个 PR、同批带前端、重录 fixture。

**验收（Acceptance）**
- 全量测试绿（当前口径 **4,670 用例**）；每一步"改一步 → 全量验证 → 提交"。
- 契约 fixture 重录后**语义对比**（键序/转义归一化）全过；200 路径 fixture 应零改动。
- 前端同 PR；`docs/knowledge-api-contract-v1.md` 同步更新版本与差异表。

**与阶段 2 的关系**：路线图上可对调（§5）；但换锚要动 HTTP 面与落库面，
**建议目标域先做完"神类切片"再换锚**，避免同一批文件反复改。

---

## 15. 全面修复计划（2026-10-02 立项；批次由用户排期，本文档即排期输入）

> **输入与现状**：阶段 2（神类切片）与阶段 3（契约换锚）已收官（§14.3/§14.9s）——≥800 只剩
> 4 个登记例外，`@JsonProperty` 913 处全部是登记冻结面。本计划覆盖**全部已知残留**：
> 两轮复查暴露的"实况断点"类别（视图层读取漂移）、登记在案的尾巴（`@JsonInclude` 288 处、
> Go 零值时间哨兵、lf_* 载具）、以及 §5 阶段 4 既定面。**批次表就是排期输入**，做完一批
> 勾一批、把执行记录写回本节。

### 15.1 批次总表（状态：✅ 完成 / ⬜ 待做；执行记录见 15.1.1）

| 批 | 内容 | 优先级 | 量级 | 状态 |
|---|---|---|---|---|
| **B0 端到端真实走查** | 起服（后端 8083 + 前端 dev）按域走查 14 条链路：注册/登录/**令牌刷新**/登出 → 空间创建/切换/成员邀请/**审计页** → KB 创建/摄取/**处理时间线**/预览 → 检索/对话（SSE）→ wiki 浏览/编辑 → datasource（RSS 桩）同步/凭据 → im 渠道 CRUD → vectorstore/storage 设置 → 收藏/技能目录/模型调试/系统运行时页。每条记录 API 形状 × 视图渲染 × 控制台报错 | **P0** | 1-2 天 | ✅ **完成（2026-10-02）**——14 条链路全走通，修 15 处断点（前端 12：裸体未适配 8 + 字段漂移 4 族；后端 2）+ 1 处 dev 环境配置；登记 5 项残留（含 B3b′ 升格）。详见 15.1.1 |
| **B1 契约文档同步** | `docs/knowledge-api-contract-v1.md` v1.0→v1.1：错误体、裸信封/裸数组、游标分页、恒输出、204 语义、七域差异表 | P0 | 小 | ✅ |
| **B2 金片对比器统一** | `support/GoldenContract` 共享基建（deep 归一 + strip + 单一 refresh 开关）；字节级（`goldenBytes`/裸 compare）与语义级双轨并存 → 语义单轨，存量字节级测试逐个迁移 | P0 | 中 | ✅ |
| **B3 `@JsonInclude` 恒输出化** | 真面 68 处（19 文件：wiki domain 全家 + websearch 三 DTO + VectorStoreTypes）；冻结面豁免（tenantconfig/LLM 载荷/event/tracing/common/agent/stream + connector + lf_*）；每域重录夹具 + 前端键集合核对 | P1 | 中 | ✅（**B3b 已登记并已执行**——见下行） |
| **B3b KB 配置 jsonb 键名统一（camelCase）** | 由 B0 走查升格为真实缺陷：`knowledge_bases` 的 `*_config` 列三方咬合面（前端 payload / 服务端读取器 / 落库 jsonb）键名分裂，导致界面上的 wiki 合成模型、问题生成参数、索引开关被静默忽略。服务端读取器 + 更新路径 dispatch 键 + 前端 payload/读取/类型 + V2 存量迁移 + 列默认值（连带修掉「编辑弹窗恒打不开」的裸资源读取） | **P1** | 中 | ✅ **完成（2026-10-02）**——详见 15.1.1 |
| **B4 Go 零值时间哨兵 → null** | `0001-01-01T00:00:00Z`（AgentStep / agentm GO_ZERO_TIME / init goTime 系） | P1 | 小-中 | ✅ **结论：不改**（调查后判已知例外，见 15.1.1） |
| **B5 lf_* 载具嵌套化评估** | 四域队列载荷的 `lf_*` 平铺键 → 嵌套 `tracing` 键；先出判定再动刀 | P1 | 判定小 | ⬜ 待做（可判定后搁置——载具当前工作正常） |
| **B6 getenv 收敛 151 处** | 裸 `System.getenv()` → `@ConfigurationProperties`，按域分批 | P1 | 中 | 🚧 **批 1（storage 装配）+ 批 2（langfuse）+ 批 3（检索驱动）完成（2026-10-02）**——全仓 149→86（代码内 81）；余量清单与「静态上下文」口径见 15.1.1 |
| **B7 死成员清扫** | 只注入不读取依赖（依赖级口径）+ 死 logger/`ObjectMapper`/`Pattern`/私有方法/冗余 import | P1 | 小-中 | ✅ |
| **B8 注解形态收尾** | 全限定名注解 → import 短名；`@JsonIgnoreProperties` 44 处接工厂评估 | P2 | 小 | ✅（FQ 177→0；`@JsonIgnoreProperties` 评估后保留） |
| **B9 Go 锚点注释清洗** | ~6,000 处；按 §4 既定"随触碰清洗"继续；若专项则按域分批 | P2 | 大（专项）/零（随批） | ⬜ 建议随批，不立专项 |
| **B10 ArchUnit 边界规则进 CI** | 环 0 组基线 + 包依赖白名单固化（§5 阶段 4） | P2 | 中 | ⬜ 待做（规则现成，差 CI 化） |
| **B11 Gradle 多模块** | 按域拆模块（§5 阶段 4 尾） | P2 | 大 | ⬜ **最后做**；B10 先行 |

#### 15.1.1 执行记录（按批次，✅ 批必读）

**✅ B2**：`support/GoldenContract` 共享基建上线（`ContractJson.deep` 归一 + strip + 单一 `-Dcontract.refresh` 开关）；Faq/W5d/W5c 三文件迁移；Knowledge/Mcp/Model/Wiki 四个字节级文件 23 处 `content().bytes` 断言迁移（`goldenBytes` 助手退役——字节级对比类别清零）。教训：跨行 Java 语句的正则迁移必须以**语句锚**扫描（perform 起点 + `;` 终点），逐行扫描会在嵌套 perform 上重复插入（第一版已回退重写）。

**✅ B1**：`docs/knowledge-api-contract-v1.md` v1.0→v1.1——升格全服务端标准；§2.2 错误体（顶层仅 `error`、键序声明序、`details` 显式 null、纯字符串错误体两类场景）+ §2.2.1 收敛口径表（游标分页/附加字段分页/条件键恒输出/连通性测试/凭据状态/客户端本地态）+ §8 收官批记录。

**✅ B3**：真面 68 处（19 文件）`@JsonInclude` 退役——wiki domain 全家（10 文件）+ websearch 三 DTO + VectorStoreTypes；键恒输出/空数组 `[]`/空串照写；金片 vs-types/wsp-*（11 文件）/wiki 四件 refresh 重录；WikiDomainTest omitempty 负断言翻转、WikiHttpContractTest `depth:0` 断言翻转。
- **回退一则**：`im/yunzhijia/YunzhijiaTypes` 误列真面——它是云之家第三方出站线格式（NON_EMPTY 省略键是对端 API 契约），YunzhijiaAdapterTest 抓回，并入 §14.6 IM 第三方口径。
- **B3b 登记（独立可选批）**：KB 更新请求的 `faq_config/wiki_config/chunking_config/...` 外层 dispatch 键 + 内层业务键（`question_count`/`synthesis_model_id`/`index_mode`…，服务端 `path()` 读取器保留 snake 是收官批口径）+ `knowledge_bases.*_config` 落库列三方咬合——改键=落库格式变更+迁移 SQL，超出恒输出轴；仅当有真实需求（如前端统一读 camel）时立项。
- **新坑**：掩码正则 `TS_PATTERN` 只匹配数字、替换串带引号，产出 `""<ts>""` 非法 JSON——掩码应**连成对引号一起匹配**；refresh 写出的夹具才可再解析。

**✅ B4（结论：不改）**：哨兵不是债——① datasource 域零值=「从未同步」的**业务信号**（`lastSyncTime` 进调度比较，改 null 要动调度语义）；② AgentStep 是冻结 SSE 事件面，前端消费已安全（负时间戳 falsy→undefined，`useChatStreamHandler` 实测）；③ agentm/init 两处前端无读点；④ 11 个金片 126 处字面量钉住。收益 < 风险，按 §14.5 登记已知例外。

**✅ B7**：依赖级口径（声明+构造赋值 ≤2 次）实锤断成员 19 处全清——只注入不读取字段 3（`PluginSearchParallel`）+ 死 logger 8 + 死 `ObjectMapper` 3 + 死 `Pattern` 1 + 死私有方法 4（含孤立 javadoc 清理）+ 冗余 import 4（javadoc 行规避后复核）。66 处历史候选复核为零。

**✅ B8**：全限定名注解 177+6 → 0（14 文件，注入前逐文件同名符号冲突扫描）；`@JsonIgnoreProperties` 48 处评估**保留**——14 个严格 mapper 读取点全是外部/不可信 JSON（第三方 API 响应/OAuth 文档/租户落库/Redis blob），注解是正确纵深防御；WEB 侧 8 个读路径已宽松，冗余但无害（§2 第 12 条勿批量删）。

**✅ B0（2026-10-02，走查批收官）**：后端 8083（postgres 驱动）+ 前端 5173 + 桩 LLM（127.0.0.1:18090，`b0-stub-chat`）+ 桩 RSS（127.0.0.1:18091）起服，按域走查 14 条链路，每条「API 形状 × 视图渲染 × 控制台报错」三录。
- **走通（实测状态码 + 截图）**：注册/登录/刷新/登出；空间创建·切换·成员邀请·成员列表·审计页；KB 创建·上传·摄取（docreader 解析→分块→嵌入→索引，处理时间线四段）·预览·删除；检索（关键词/向量/混合，返回命中）；对话 SSE（`agent_query → query_understand → knowledge_search → references → answer 流 → complete`）；wiki 浏览页与 tab 呈现；datasource（RSS 桩）创建·同步（2 条入库）·资源树·日志·删除；im 渠道 CRUD（创建/总览不含凭据/toggle/更新/删除 204）；vectorstore·storage·parser 设置页；收藏（星标 + `/user/favorites`）；技能目录/智能体页；模型管理；系统运行时·审计·全局设置（临时提权 smokeuser 验证后未回退，dev 数据）。
- **修 15 处断点（前端 12 + 后端 2 + 环境 1）**：
  - **前端 A 类·裸体未适配（8 处）**：服务端 §2.1 早改成裸对象/裸数组，前端 API 层仍 `as ListXxxResponse` 强转 → 消费端 `data` 恒 `undefined`。命中：system KV 四件（`prompt-templates`/`parser-engine-config`/`storage-engine-config`/`retrieval-config` 的读与写）、tenant `invitations` 五个入口、`chat-history`、`web-search-provider`、`api/knowledge-base` 的 `vector_store_*` → `id/name/engineType` 注释口径。**实测症状**：成员管理页整块弹「U1 参数不可解析」红条（`data.invitations` 为 undefined，邀请列表也不渲染）；修后成员表 + 待接受邀请正常。
  - **前端 B 类·字段读取漂移（视图层读 snake，服务端已 camelCase，共 4 处族）**：① wiki stats 四键（`pending_issues/pending_tasks/is_active/pages_by_type`）→ wiki 头部徽标与类型分桶恒空；② folderTree 计数键（`root_document_count` 等）+ 两个文档视图 + 测试夹具 → 文件夹计数恒 0（`vue-tsc` 报 17 错）；③ `KnowledgeBase.vue` 三处：`item_count`→`documentCount`（卡片计数恒 0）、`storageConfig.provider`→`defaultProvider` 与 KV 的 `default_provider/provider/storage_type`（双触发「尚未选择存储引擎」误报）、`resource_type/resource_id`→camel（收藏星标失效）；④ 其余同族：`KBInfoPopover`、`knowledge-processing-timeline`+`utils/knowledgeTrace`（含测试）、`UploadConfirmDialog`、`TagEditDialog`/`BatchTagDialog`、`AgentEditorModal`、`KnowledgeBaseEditorModal`、`api/auth` 的 `is_active`。**最重一例**：检索引擎设置（⌘K 面板）`embedding_top_k`→`embeddingTopK`——表单恒显默认 5，用户保存即被覆盖（静默改写用户配置，走查抓出）。
  - **后端 2**：⑩ `KnowledgeProcessWorker.planFinalizing` wiki 子任务入队前先校验合成模型可解析（原先无模型也占槽入队 → ingest 重试 11 次后死信、**文档永远 finalizing**，实测复现）；⑪ `StartupTaskRecovery` Lite 模式不再排除「wiki 独槽」finalizing 行（Go 原文「启动后能重建触发器」在单机形态不成立——实测重启后无人触发，卡片永远「优化中」；重启后该行已实际复位为 failed 可重试）。
  - **环境 1**：`.env` 补 `DOCREADER_ADDR=localhost:50051`（连接态判据是「该 env 是否为空」而非探活，缺失时 `/system/parser-engines` 报 `connected:false`、内置引擎显示「不可用」、KB 页提示「暂无可解析引擎」，而解析其实正常）。
- **登记 5 项残留**：① **B3b 升格为真实缺陷（B3b′）**——KB 更新请求 `wiki_config` 内层键是 snake（前端写）而 Java 值类型 `wiki.domain.WikiConfig` 读 camelCase（无线名注解）→ **界面选的 wiki 合成模型被静默忽略**（实测：写入 `synthesis_model_id` 后 ingest 仍报 `missing_synthesis_model`）；修需一次落库键迁移 + 前端 payload + 读回三处同批。**（2026-10-02 同会话已执行：见本页 ✅ B3b 记录）**② wiki op 永久失败时无人释放槽位（死信路径缺 `WikiFinalizePort.finalizeWikiSubtask` 调用）——⑩ 只挡「无模型」这一新发生，运行期其它永久失败仍会搁浅。③ 孤儿 wiki op 不重放（Lite 重启后持久化 op 不再触发；⑪ 让文档不卡，但该文 wiki 内容要等下一次 KB 触发补生成）。④ `views/settings/StorageEngineSettings.vue` 是孤儿组件（`Settings.vue` 用该别名 import 的实为 `StorageBackendSettings.vue`）→ 存储引擎 KV 表单 UI 不可达。⑤ 每次登录都会打一发 `POST /auth/auto-setup` → 403「auto-setup is only available in lite edition」（控制台噪音，会掩住真 403；前端若能从 `/auth/config` 拿到版本信号即可前置跳过）。
- **未走**：第三方 connector 真凭据面（飞书/Notion/GitLab 等）、embed 渠道公开面、mcp OAuth（无桩、属 §14.6 冻结面）。
- **闸门**：前端 `vue-tsc` 0 错 + `npm test` 690 绿；后端 `spotlessCheck` 绿 + config/knowledge/wiki/system/session/datasource 六域测试全绿；上述每处修复都有真实服务复验（成员表与邀请列表出现、横幅消失、wiki/图谱 tab 出现、`/system/parser-engines` 转 `connected:true`、新文档 `completed + pending 0`、旧卡死文档复位 failed）。
- **教训**：「api 类型文件对齐 ≠ 消费端对齐」在本轮被证伪到第 9 例，且**类型断言把漂移全藏住**——凡是 `get<T>()` 泛型断言过的响应，消费端读错键 TS 一声不吭；这类断点只能靠「真数据 × 真渲染」走查兜底（VII 复查两批的静态闸门盲区判断成立）。

**✅ B3b（2026-10-02，由 B0 走查升格）**：知识库配置 jsonb 键名统一到 Java 字段名（camelCase）——前端 payload / 服务端读取器 / 落库列三方咬合面同批对齐。
- **修前真相**（走查实测 + 代码核查）：同一批配置列的键名分裂成三种——① `wiki_config` 读端是 camel（`wiki.domain.WikiConfig` 无线名注解：`synthesisModelId/maxPagesPerIngest/...`）而前端写 snake → **界面选的 wiki 合成模型与全部 wiki 调参被静默忽略**（实测写入 `synthesis_model_id` 后 ingest 仍报 `missing_synthesis_model`）；② `faq_config`/`question_generation_config` 的**同一列有两个读端**：`ChunkQuestionService`/`FaqIndexRows` 读 snake，而 `InitializationConfigService` 读 camel（`questionCount`/`customInstructions`）→ 编辑器加载配置时把已存的问题数显示成 0、**保存即写回 0（静默数据丢失）**；③ 更新路径的 `config.*` 外层 dispatch 键是 snake（`faq_config/wiki_config/auto_tag_config/indexing_strategy`）而创建面是 camel → 编辑态保存的索引开关（`vector_enabled` 等）落库后服务端读不到（`KnowledgeBaseIndexingStrategy` 读 camel）。
- **服务端（7 文件）**：读取器统一 camel——`KnowledgeProcessWorker`/`ChunkQuestionService`/`QuestionGenerationService`（`question_count`→`questionCount`、`custom_instructions`→`customInstructions`）、`FaqIndexRows`/`FaqChunkCodec`（`index_mode`/`question_index_mode`→camel）、`ModelService` 的「模型被谁引用」扫描（`image_processing_config`/`vlm_config`/`asr_config` 的 `model_id` 与 `wiki_config` 的 `synthesis_model_id`→camel）；`KnowledgeBaseService.applyUpdateConfig` 的 dispatch 键改 camel（与 `CreateKnowledgeBaseRequest` 同名同形）。**未动**：`ChunkQuestionService` 里的 `{{question_count}}` 是提示词占位符不是配置键。
- **前端（7 文件）**：创建/更新 payload 内层键（`wikiConfig` 五键、`faqConfig` 两键、`questionGenerationConfig` 两键、`autoTagConfig` 三键、`extractConfig.customInstructions`）+ 更新外层 dispatch 键改 camel；读取点（编辑器 `loadKBData`、上传确认框 `initFromKbInfo`、`Input-field` 能力回退、`KnowledgeBaseList` 内联类型）同步；`api/knowledge-base` 创建/更新 DTO 改 camel。**顺带修死读**：`Input-field.vue` 的 `s.vector_enabled`/`s.keyword_enabled` 在 camel 对象上永远取不到（能力回退恒 false）。
- **迁移**：新增 `migrations/versioned/V2__kb_config_keys_camel.sql`——递归键改名（含数组内对象，如 `parser_engine_rules[].file_types`）覆盖 12 个配置列，幂等（已 camel 的键不在映射表）；并把 `chunking_config`/`image_processing_config` 的**列默认值**改 camel（默认值也是新行的落库内容）。dev 库 8 行已迁，Flyway 启动 `Successfully applied 1 migration ... now at version v2`。
- **测试**：`FaqContractTest`（6 例）+ `WikiPageServiceTest` + `KnowledgeBaseEnsureDefaultsTest` 的种子数据改 camel——FAQ 那 6 例红是本次唯一既有断言冲突，方向正确（种子就是旧 snake 写法）。`WikiDomainTest` 的历史行容忍用例保留 snake（它断言"未知键被忽略"，仍是有效形状）。
- **连带修掉 2 处存量断点（B3b 验证时打开编辑器才暴露）**：① `KnowledgeBaseEditorModal.loadKBData` 读了 `kbInfo.data`，而 `GET /knowledge-bases/{id}` 是裸资源（§2.1）——**知识库设置/编辑弹窗对每个知识库都恒抛「知识库不存在」**（其余 5 个消费点都按裸体读，只有这一处漏改）；② 同处 `(kb as any).tenant_id` → `tenantId`（漂移使 `kbTenantId` 恒 0 → `canViewActivity` 恒 false，**编辑弹窗里的活动面板对所有人不可见**）。两条都实测复现/实测修复：修前点齿轮弹「加载知识库数据失败」，修后弹窗正常打开且回显存量参数（问题数 5、指令「用中文提问」、提取粒度「详细」、标签上限 6）。
- **验证**：前端 `vue-tsc` 0 错 + `npm test` 690 绿；后端 `spotlessCheck` 绿 + **全量测试绿**；真机冒烟——camel 建库回读一致、`/initialization/config/{id}` 的问题数由 0 变 5、PUT 用 camel dispatch 后库内三列全 camel、上传文档后 wiki ingest 由 `missing_synthesis_model` 变 `status=success` 且 `tunables(batch=5,map_par=10,reduce_par=10,max_inflight=4)` 生效、问题生成任务用上了 KB 选的模型与参数（失败仅因桩 LLM 返回固定文案、非 JSON）、编辑器 UI 回显一致（截图）。
- **登记（本批未动）**：① `knowledges.metadata.process_overrides`（每文件上传覆盖配置）键名仍 snake，且全服务端**无任何读取点**——该功能写了不用（前端上传框照写、时间线照读，服务端忽略）；② agent 域 `custom_agents.config` jsonb 内层键仍 snake（读写两端一致，属 §11.2 边界）；③ `ModelService` 的绑定标签值（`vlm_model` 等）是线格式字符串值，未动。

**🚧 B6 批 1（2026-10-02，storage 装配）**：`storage/service/DefaultStorageBackendProvisioner` 单文件 **46 处 `System.getenv` 清零**（占全仓三分之一）——「env 快照 → 存储后端实体/config JSON → 落库」的手写 switch 收敛为 `@ConfigurationProperties` 绑定的 provider 环境变量族。
- **新增** `storage/config/StorageProviderEnv.java`：8 个记录（`StorageType`/`Local`/`Minio`/`Cos`/`Tos`/`S3`/`Oss`/`Obs`）+ 公共接口 `ProviderEnvFamily{provider(), writeConfig(ObjectNode)}`；装配器改为 `Map<String, ProviderEnvFamily>` 查表（未知 provider 仍返回 null）。`RagAgentApplication` 的 `@ConfigurationPropertiesScan` 名单加 `com.ragagent.storage.config`（**域内配置类不进 `config/` 装配层**——域反向依赖装配层是本仓的既定禁线，见该类注释）。
- **契约保持**：env 变量名一个没改（`MINIO_ACCESS_KEY_ID`→`minio.access-key-id` 走 Spring 松散绑定，部署侧 .env 原样）；字段一律 `String` 而非 `Boolean`/数字——保留 Go 的宽容语义（`S3_USE_SSL` 只在恰为 "false" 时为假、`MINIO_USE_SSL` 只在恰为 "true" 时为真，写错的字面量不该让启动失败）；落库键名与省略规则（空串/假值整键省略）、键序都与原 switch 逐调用一致。
- **验证**：新增 `DefaultStorageBackendProvisionerTest`（测试属性代替 env，钉子断言 s3 族全键 + `use_ssl` 缺省真 + `force_path_style`）；真机三分支冒烟——`STORAGE_TYPE=s3`（含缺省 use_ssl=true）、`STORAGE_TYPE=oss`（临时桶 → `use_temp_bucket`+`temp_*`）、缺省（`System LOCAL` + `{}`）三条落库结果与改前逐键一致；`spotlessCheck` 绿 + **后端全量测试绿**。
- **踩坑（记给下一批）**：多次 `export A=B && nohup gradlew bootRun &` 后，**shell 的 export 会粘到后续命令**，导致「改了 env 重启却没变化」的假象；换变量族冒烟前先 `unset`，并核 `env | grep` 而不是只看日志。（另：bootRun 会 fork JVM，`pkill -f server:bootRun` 可能只杀 wrapper——要 `pkill -f RagAgentApplication` + 核 8083 端口。）
- **余量（105 处，代码内 100）**：retrieval 11（`RETRIEVE_DRIVER`×7 + `RetrievalEngineWiringConfig`/引擎仓的 `ELASTICSEARCH_*`/`QDRANT_*` 等现场建驱动）、storage 余 9（`LOCAL_STORAGE_BASE_DIR`/`STORAGE_TYPE`/`SYSTEM_AES_KEY` 的**静态工具方法**读点，转 bean 会牵动调用方，需先定静态→bean 的过渡口径）、common 7（`SSRF_WHITELIST*`/`JWT_SECRET`/`WEKNORA_LANGUAGE`）、knowledge 5（`BATCH_EMBED_SIZE`/`DOCREADER_ADDR`）、auth 5（`WEKNORA_INVITATION_TTL` 等）、system 4（`GIN_MODE`——**Go 框架名残留**，值得先判定是否还有意义）、tracing/langfuse 12（`LangfuseConfig` 单文件）。

**🚧 B6 批 2（2026-10-02，langfuse）**：`tracing/langfuse` 12 处清零（全仓 149→93，代码内 88）。
- 新增 `tracing/langfuse/LangfuseEnvProperties`（前缀 `langfuse`，12 个 **String** 字段）；`LangfuseConfig.loadFromEnv()` → `fromEnv(LangfuseEnvProperties)`（默认值与解析规则逐条照抄：Go 时长串、十种真值写法、采样率越界忽略、0 采样率视为整体关闭）；`LangfuseWiring` 改构造注入；扫描名单加 `com.ragagent.tracing.langfuse`。
- **为什么字段不用数字/布尔**：Go 的语义是「非法字面量静默回落默认值」，绑类型会把「写错一个字母」升级成启动失败——宽容语义优先。
- 验证：真机冒烟 `LANGFUSE_*` 起服 → `[Langfuse] enabled host=https://langfuse.example.com flush_at=20 flush_interval=90000ms sample_rate=0.5`（`1m30s`→90000ms ✓、采样率 ✓、启用判据=有 keys ✓）；`spotlessCheck` 绿 + 全量测试绿。（`LangfuseRegistry.init` 的这行启用日志是整条链路的可观测量，后续 langfuse 相关批次继续用它做冒烟断言。）
- **踩坑**：`export LANGFUSE_* && nohup gradlew bootRun &` 后必须 `unset`——shell 变量会粘到后续命令，否则下一批冒烟会被上一批的 env 污染（本批已按此收尾）。

**B6 余量口径（下一批动手前先定，2026-10-02 侦察结论）**：剩 88 处代码内读取，按上下文分两类——
- **A 类·bean 上下文（直接注入属性即可）**：`retrieval` 11（`RETRIEVE_DRIVER`×7 + `RetrievalEngineWiringConfig` 与各引擎仓的 `ELASTICSEARCH_*`/`QDRANT_*`/`MILVUS_*`/`WEAVIATE_*`/`TENCENT_VECTORDB_*` 现场建驱动）、`system` 13（`SystemSettingService` 6 / `SystemController` 4 / `SystemInfoService` 2 / `DeploymentCapabilitiesHolder` 1）、`datasource` 4（`DocxFetcher`）、`knowledge` 7（`KnowledgeBaseService` 3 / `HousekeepingService` 2 / `DocReaderClient` 2）、`model` 2、`vectorstore` 3、`initialization` 2、`session` 2、`mcp` 1、`embed` 1。
- **B 类·静态/构造上下文（先定过渡口径）**：`common` 7（`CryptoService`/`SsrfGuard`/`WikiLanguageSupport`/`UploadLimits`/`StorageAllowList`/`Gate`/`GateOptions` 全是静态方法，SSRF 那处在静态初始化块）、`storage` 余 9（`StoragePaths`/`support/Mode`/`support/FileServiceResolver` 静态工具 + 动态 key 助手）、跨域单值 9（`JWT_SECRET`×2 在**无参构造器**里、`BATCH_EMBED_SIZE`×3 静态、`WEKNORA_LANGUAGE`×3 静态、`GIN_MODE`×1）。
  **建议口径**：① 静态工具族（批大小/语言/SSRF/白名单）用「`@ConfigurationProperties` + 一个 `@Component` 启动时写入的静态快照持有者」——这几个都是启动期语义（进程内不变），成立，但持有者要写清「只允许启动期写入」；② `JwtService`/`OidcStateCodec` 这类无参构造器改构造注入（先核 `new Xxx()` 调用点）；③ `GIN_MODE` 先判定语义：Go 框架名残留，Java 侧真正问的是「是否生产部署」——建议改 `WEKNORA_DEPLOYMENT_MODE` 或复用既有部署信号，属对外配置变更，**需用户拍板**。




**🚧 B6 批 3（2026-10-02，检索驱动 + 向量库 env 查找面）**：`RETRIEVE_DRIVER` 的 7 个读取点清零（全仓 93→86，代码内 81）。
- 新增 `common/retrieval/RetrievalDriverProperties`（前缀 `retrieve`；只承载**原始串**——各读点缺省语义不同：知识库 `postgres`、系统信息页「未配置」、有效引擎空集＝检索全关、env 店空列表）；扫描名单加 `com.ragagent.common.retrieval`。
- 读取点改造：`RetrievalEngineWiringConfig`（@Bean 方法参数注入，保留 Go 的「不 trim、精确匹配」）、`SystemInfoService`（字段注入）、`KnowledgeBaseService`（构造器字段）、`VectorStoreConfigService`（构造器 + 新增 `findEnvStore(id)`）、`VectorStoreController`（2 处改调服务）、`EffectiveEngines.defaults()/of()` 改纯函数（驱动由调用方注入）、`HybridSearchService` → `HybridStoreGroupOps` 传递驱动。
- 新增 `config/EnvLookupWiring`：`EnvVectorStores.EnvLookup` bean 由 `Environment::getProperty` 支撑——键名不变（`OPENSEARCH_ADDR` 等照样解析，系统环境变量本就是 Environment 的一个 property source），但可被测试属性/命令行覆盖；`EnvVectorStores` 原本就是「纯函数 + 注入查找面」形状，本批只换查找面的来源。
- 测试：`EffectiveEnginesTest` 改传固定驱动串（不再依赖进程 env）；`HybridSearchServiceStoreGroupTest` 传 `null` 对齐改前「env 未配置 → 有效引擎为空」语义——**第一版写成 `"postgres"` 立刻红了一条**，这条正好提醒「驱动串就是引擎总开关」：测的语义变了，不是实现错了。
- 验证：真机冒烟（`RETRIEVE_DRIVER=postgres` 起服）——`/system/info` 报 `vectorStoreEngine=postgres`、向量库列表含 `__env_postgres__`、`GET /vector-stores/__env_postgres__` 200（走 Environment 查找面）、`POST .../test` 200、hybrid-search 200；`spotlessCheck` 绿 + 全量测试绿。

### 15.2 批次纪律（每批通用，违者必翻车——全是本轮实锤）

1. §14.2 七步 SOP：一次只动一个轴、独立提交独立全绿、双端闸门（后端全量+spotless；触前端契约则 vue-tsc+npm test）。
2. "同 PR 带前端"必须核到**视图层字段读取**——api 类型文件对齐 ≠ 消费端对齐（TS 断言会藏住一切）。
3. 键名替换前分清三类键：**线格式键**（换）/ **系统设置键与 i18n 键**（不换，DB/文案字符串）/ **客户端本地态字段**（保留并注释）。
4. 触 HTTP 面的批做真实服务冒烟；金片重录前先看该测试用字节级还是语义级对比器（B2 统一后此条自动消解）。
5. 每批执行记录写回本节（沿用 §14.9x 的 ✅ 格式）。

### 15.3 非目标（冻结面，见 §14.6，勿列入修复）

租户配置 jsonb（`auth/domain/tenantconfig`）、connector 第三方线格式（datasource 345 处等）、
SSE/Redis 事件载荷（event/stream）、provider 请求体（llm/ollama/anthropic）、LLM 输出解析面
（wiki/mcp/memory）、image_info（docreader 第三方载荷）、`lf_*` 平铺键（B5 判定前）、
Go 工具面 5 类、IM 平台 ACK、chat span 载荷（SearchParams）、rerank RankResult、
i18n 键与系统设置键（`tenant.default_storage_quota_gb` 等——DB/文案字符串）。
