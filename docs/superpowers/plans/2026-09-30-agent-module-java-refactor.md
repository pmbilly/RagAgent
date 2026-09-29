# agent 域 Java 本位重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** agent 域(`agent/` + `agentm/` 两包,133 文件 / 29.5k 行)按 knowledge 范本(HANDOFF §14)完成 Java 本位改造:七个 ≥800 行神类消失、包结构与导览对齐 §12 标准、agent 域对外契约换锚(同批带前端)、Go 锚点注释清零。

**Architecture:** 四波推进,一次只动一个轴。A 波纯内部拆分(沿分段注释边界拆神类为"门面 + 同包协作者",零契约变化);B 波分层归位与 package-info 导览(纯移动);C 波契约 Java 化(approval 载荷、agent_steps 落库、agentm 端点,**每项同批带前端**);E 波注释与命名卫生收尾。每任务独立全绿、独立提交。

**Tech Stack:** Java 21 / Spring Boot 3.3.5 / MyBatis-Plus / Jackson / jakarta.validation；前端 Vue 3 + TS（C 波同批改动）。

**Spec:** `HANDOFF.md` §0（总目标）、§2 第 4/11/12/13/14 条（契约标准/落库/宽松读/包结构/命名）、§3（红线）、§11（边界清单）、§14（逐包重构范式与验收判据）。

---

## 全局约束

1. **行为不变底线**：4,670 后端用例是安全网。每任务收尾 `./gradlew :server:test` 全绿 + `./gradlew :server:spotlessCheck` 绿（**test ≠ 闸门**，§13.14）；触前端契约的任务加 `vue-tsc --build --force` 0 错误 + `npm test` 全绿。
2. **红线 #1 一次只动一个轴**：A 波不动任何 Controller 端点形态与序列化；B 波不改任何代码语义；C 波不拆类；E 波不动逻辑。
3. **同包协作者模式（A 波核心决策）**：所有从神类拆出的协作者类放在**原包**（`com.ragagent.agent` / `com.ragagent.agent.tools` 等），package-private，持 `private final Xxx门面 engine` 回引。理由：AgentEngine 的字段级测试 seam（`knowledgeBasesInfo`/`lastUsage` 等包内可见 + `*ForTest()` 方法，AgentEngine.java:276）依赖同包可见性，同包协作者保证 **59 个 agent 测试文件与 38 个跨包注入点零改动**（knowledge 同款手法）。
4. **门面保留全部 public 成员**：AgentEngine 的 public 面 = 构造器 + `withSkills` + 9 个 setter/getter + 2 个 `execute` 重载 + `renderUserTurnContent` + `ImageDescriberFunc` 接口，一个不动。
5. **落库/宽松读**：新写的 jsonb 读写 mapper 必须接 `JsonMappers.lenient()`（§2 第 12 条）；agent/domain 落库类型去 snake 时**不加别名**（§2 第 11 条，产品未上线无包袱）。
6. **Go 序列化器注解随 DTO 迁移保留**（`GoTimeSerializer` 等）：阶段 3 的序列化器本体删除是全仓一次性动作，**不在本计划内**，本计划不得产生半删。
7. **命名政策（§2 第 14 条）**：新类型全词命名；Go 术语标识符清零；抽象接口后缀 `Gateway`。拆分段落时**禁止**用 `think.go`/`act.go` 等 Go 文件名做类名——按 ReAct 阶段职责命名（见 Task 1 命名表）。
8. **注释标准**：类 javadoc = 一句话职责 + 非显然约束 ≤10 行；拆分时原段分割注释（`// ===== xx.go：… =====`）**必须**改写为新类型的职责描述（先摘不变量、删 Go 锚点与 L 行号）；**禁止**写批次/阶段代号（§13.11）。getter/POJO 访问器 0 javadoc（§13.13）。
9. **边界清单（§11，勿误改）**：agent 域契约 fixture（`ag-*`）在 C 波触及对应端点前保持 snake 原样；工具输出自有 schema（`faq_id`/`knowledge_title` 属性）不动；检索引擎索引文档不动；租户配置 jsonb 不动。
10. **提交信息**：`refactor(agent): …` / `refactor(agentm): …` / `docs(handoff): …`，沿用仓库惯例。

## Java 惯例标准（全任务统一）

1. 单类目标 ≤500 行、硬顶 800 行（状态机/门面例外须 javadoc 注明）；方法 ≤60 行；嵌套 ≤3 层。
2. Controller 薄层：绑定 → 委托 service → 返回 DTO；不允许私有绑定/解析助手。
3. 一类型一文件；禁 `*Dtos`/`*Enums`/`*Util` 容器命名（§2 第 13 条）。已有容器类型（如 `agentm/dto/AgentResponses` 308 行、`InitResponses`）在 C 波触及时拆为一类型一文件。
4. 移动/改名类之后必须 `git grep '{@link 旧名}'` 清死链（§13.10）。

## Review Focus（契约暗礁，各任务红外验证必查）

1. **approval Redis 双侧一致性**：审批事件载荷（`agent/approval/*` ↔ `mcp/controller/AgentToolApprovalController`）经 Redis pubsub，engine 侧与 controller 侧必须**同一提交**内键一致——半新半旧 = 审批流静默挂死。验证：Task 10 全量测试 + 审批流契约用例点名。
2. **agent_steps 消息响应体**：`messages.agent_steps` jsonb 会出现在消息 API 响应里（AgentStepListTypeHandler javadoc 明示）。Task 11 改键后：grep 全仓旧键读取清零（§11 ② 教训，knowledge 曾揪出 4 处真实缺陷）+ 前端渲染 steps 的组件同批适配 + 受影响 `ag-*` fixture 重录。
3. **AgentEngine 包内 seam**：每完成一个协作者抽取，跑 `AgentEngine*Test` 全部用例——seam 直改字段（`setUsageBaselineForTest` 等）若失效立即暴露，不许留"测试改用反射"之类的补丁。
4. **agentm 信封与半成功**：agentm 端点现信封形态与 `InitializationController` 自有契约的内层 snake 键，在 Task 12 换锚前一个字节不动；§7 第 5 条已查明的 `KnowledgeBaseEditorModal.vue` legacy 装配块静默默认值缺陷在 Task 12 一并修（同 PR 带前端）。
5. **chat SSE 不在本计划**：`event/*Data`（AgentThoughtData 等 chat 流载荷）的换锚归 **session 域切片**（`wip/chat-sse-slice2` 在途，合并后统一做）。本计划任何任务不得触碰 event 包 payload 键名。

---

## A 波：神类拆分（纯内部，零契约）

### Task 1: AgentEngine 拆分（3,235 行 → 门面 + 6 协作者）

**Files:**
- Modify: `server/src/main/java/com/ragagent/agent/AgentEngine.java`（3,235 → 目标 <800）
- Create: `server/src/main/java/com/ragagent/agent/{ThinkPhase,ActPhase,ObservePhase,FinalizePhase,SteerIntake,ContextDebugEmitter}.java`（同包 package-private）
- Test: 既有 `AgentEngine*Test` 零改动即为通过标准

**Interfaces:**
- Produces: 门面保留全部 public 成员；协作者仅由门面构造与调用，package-private。
- 命名表（钉死，禁 go 文件名）：L976-1501 `think` 段 → `ThinkPhase`；L1502-2172 `act` 段 → `ActPhase`；L2173-2965 `observe` 段 → `ObservePhase`（若超 800 行再沿"上下文窗口管理 / 响应分析"切一刀，第二类型名 `ContextWindowManager`）；L2966-3100 `finalize` 段 → `FinalizePhase`；L3101-3178 `steer` 段 → `SteerIntake`；L3179-3235 `context_debug` 段 → `ContextDebugEmitter`。`engine.go：系统提示词与预算`（L307-434）与 `工具结果图片 VLM 描述`（L913-975）两段留在门面或并入相邻协作者，以"门面 <800 行且协作者单一职责"为准。

- [ ] **Step 1**: 通读 L435-975（Execute 主入口 + executeLoop + VLM 段），确认门面保留范围：`execute`/`executeLoop`/`runReActIteration` 编排骨架 + 构造 + seam 留门面。
- [ ] **Step 2**: 按"一段一协作者、每抽一段编译一次"推进：先抽 `ThinkPhase`（段最大、边界最清晰）→ `mv compile` → 抽 `ActPhase` → 编译 → `ObservePhase`（检查是否需二切）→ 编译 → `FinalizePhase` + `SteerIntake` + `ContextDebugEmitter`（三个小段一并）→ 编译。协作者构造注入门面，原方法体整体搬移（本任务不改语句）。
- [ ] **Step 3**: `./gradlew :server:test` 全绿（重点看 AgentEngine 族 + session 域 QA 用例）+ `:server:spotlessCheck` 绿。
- [ ] **Step 4**: Commit `refactor(agent): AgentEngine 沿段界拆为门面 + 六协作者（3235 → <800，包内 seam 零改动）`。

### Task 2: SqlGuard 拆分（1,590 行，tools/）

**Files:**
- Modify: `server/src/main/java/com/ragagent/agent/tools/SqlGuard.java`
- Create: `server/src/main/java/com/ragagent/agent/tools/{SqlTokenizer,SqlSelectDeepChecker,SqlInjectionAnalyzer}.java`

**Interfaces:** 命名表：L334-536 手写 tokenizer 段 → `SqlTokenizer`；L537-1121 Phase 5 深检查段 → `SqlSelectDeepChecker`；L1122-1211 Phase 7 注入风险段 + L1348-末尾 注入重写段 → `SqlInjectionAnalyzer`（同属注入防护一职责）。L86-333 Phase 1-7 校验入口与 L1212-1347 白名单/黑名单常量留在 `SqlGuard`（目标 ~700 行）。

- [ ] **Step 1**: 抽 `SqlTokenizer` → 编译；抽 `SqlSelectDeepChecker` → 编译；抽 `SqlInjectionAnalyzer` → 编译。原段分割注释改写为新类型 javadoc（摘不变量、删"对照 ValidateSQL"类锚点）。
- [ ] **Step 2**: `./gradlew :server:test` + `:server:spotlessCheck` 全绿（DataAnalysisTool/SqlGuard 用例点名）。
- [ ] **Step 3**: Commit `refactor(agent): SqlGuard 拆出 tokenizer/深检查/注入分析三协作者`。

### Task 3: KnowledgeSearchTool 拆分（1,178 行，tools/）

- [ ] **Step 1**: 先盘点分段注释（`grep -nE '// ={5,}|// ──|// ----'`）与职责聚类，定拆出类型名单（命名全词、同包 package-private、复用协作者模式）；无段界处按职责聚类，不按行数硬切。
- [ ] **Step 2**: 抽取 → 每抽一个编译一次 → 全量测试 + 闸门绿。
- [ ] **Step 3**: Commit `refactor(agent): KnowledgeSearchTool 沿段界拆分（1178 → <800）`。

### Task 4: WikiSupport 拆分（1,148 行，tools/）

- [ ] **Step 1-3**: 同 Task 3 流程（先盘点段界 → 抽取 → 全绿 → Commit `refactor(agent): WikiSupport 沿段界拆分`）。

### Task 5: McpCatalog 拆分（923 行，tools/）

- [ ] **Step 1-3**: 同 Task 3 流程。注意与 `McpToolWrapper`/`McpExposure`/`McpRegisteredTool` 的既有分工，不重复造重叠类型。

### Task 6: GrepChunksTool 拆分（897 行，tools/）

- [ ] **Step 1-3**: 同 Task 3 流程。

### Task 7: agentm InitializationController 拆分（1,981 行）

**Files:**
- Modify: `server/src/main/java/com/ragagent/agentm/controller/InitializationController.java`（目标 <800，端点薄层化）
- Create: `server/src/main/java/com/ragagent/agentm/service/*`（按职责聚类的服务协作者，命名全词）

- [ ] **Step 1**: 盘点端点清单与私有方法聚类；端点签名与 URL **一个不动**（契约属 Task 12）。
- [ ] **Step 2**: 非端点逻辑抽为 service 协作者（构造注入）→ 每抽一个编译 → 全量测试 + 闸门绿。
- [ ] **Step 3**: Commit `refactor(agentm): InitializationController 端点薄层化，逻辑下沉 service`。

### Task 8: agentm CustomAgentService 整理（882 行）

- [ ] **Step 1-3**: 同 Task 7 流程（882 行略超硬顶，优先抽聚类而非强行对半；若拆后仍 800±50，javadoc 注明理由）。Commit `refactor(agentm): CustomAgentService 职责聚类整理`。

## B 波：分层与导览（纯移动）

### Task 9: agent 包结构归位 + package-info 全覆盖

**Files:**
- Modify: agent 根包 17 文件中非引擎核心类的 package 语句与其全部 import 方
- Create: `agent/{prompt,...}/package-info.java`、`agent/tools|approval|compaction|domain|skills/package-info.java`、`agentm/{controller,service,dto,mapper,domain}/package-info.java`

- [ ] **Step 1**: 归位方案：`agent/` 根只留引擎门面族（AgentEngine、AgentEngineException、AgentConfig、AgentConsts）；提示词族（AgentPrompts、AgentPromptTemplates、AgentPromptPlaceholders、PromptTemplateCatalog、PromptInstructions、GroundingPrompt）→ 新子包 `agent/prompt/`；其余支撑类（TokenEstimator、ContextDiagnostics、ToolImages、SteerSink、AgentToolNames、SkillMetadata、AgentBudgets）按使用方归入既有子包或 `agent/prompt`/`agent/support`，逐一判断。移动用 `git mv`，import 修复用编译器枚举（§13.9），**禁全仓文本替换**（§13.2）。
- [ ] **Step 2**: 每个 agent/agentm 子包补 `package-info.java` 职责地图（对齐 §12 样式）；`git grep -n ' {@link AgentPrompts}'` 类死链清查。
- [ ] **Step 3**: 全量测试 + 闸门绿 → Commit `refactor(agent): 包结构归位 + 全部子包 package-info 导览`。

## C 波：契约 Java 化（同批带前端，一次一个域面）

### Task 10: approval 审批载荷去 snake（后端内部 wire，无前端）

**Files:**
- Modify: `agent/approval/` 全部带 `@JsonProperty` 的载荷类型（ToolApprovalRequiredData 15 处、ResolveMessage 11、McpOauthRequiredData 11、McpOauthResolvedData 6、ToolApprovalResolvedData 5、ResolveAck 4 等）
- Modify: `event/ToolApprovalRequiredData`、`event/MCPOAuthRequiredData`（SSE/事件侧同键类型）
- 两侧消费点：`mcp/controller/AgentToolApprovalController`、`session/controller/SessionController`、`AgentStreamBridge`

- [ ] **Step 1**: 去类型上全部逐字段 `@JsonProperty`（字段名即 JSON 名，§2 第 4 条）；**engine 侧与 controller/bridge 侧同一提交**。
- [ ] **Step 2**: grep 全仓旧 snake 键的字符串读取点清零；审批流测试全绿（Review Focus #1 点名）。
- [ ] **Step 3**: 全量测试 + 闸门绿 → Commit `refactor(agent): approval 审批载荷键名 Java 本位化（双侧同批）`。

### Task 11: agent/domain 落库类型去 snake + agent_steps 前端适配

**Files:**
- Modify: `agent/domain/{AgentStep,ToolCall,ToolResult,AgentState,ToolCallTarget}.java`（去 30 处 `@JsonProperty`，不加别名）
- Modify: 前端渲染 steps/tool-calls 的组件（先 `git grep -n` snake 键定位）
- Test: 受影响 `ag-*` fixture 重录

- [ ] **Step 1**: 去注解 → `grep` 全仓旧键字符串读取（含测试辅助、mask、contract 断言）清零。
- [ ] **Step 2**: 前端同批适配；重录受影响 fixture；消息 API 契约用例全绿。
- [ ] **Step 3**: 后端全量 + 前端 vue-tsc/npm test 三绿 → Commit `refactor(agent): 落库类型 agent_steps 族键名 Java 本位化（同批前端）`。

### Task 12: agentm 契约换锚（DTO 化 + 去信封 + legacy 缺陷修复）

**Files:**
- Modify: `agentm/controller/{InitializationController,AgentController,SkillsCatalogController}`、`agentm/dto/{AgentResponses,InitResponses}`（拆一类型一文件）
- Modify: 前端 agent 配置/初始化向导/技能选择器消费点；`KnowledgeBaseEditorModal.vue` ~1415-1430 legacy 装配块（§7 第 5 条缺陷修复）

- [ ] **Step 1**: DTO 去 snake、去逐字段注解；信封形态按 §2 第 4 条（去 `{data,success}`、删除返 204、可空显式 null、分页 `{"items","page","pageSize","total"}`）。
- [ ] **Step 2**: 前端同批；`KnowledgeBaseEditorModal.vue` legacy 块改读新键（消除静默默认值）。
- [ ] **Step 3**: 三绿 + 受影响 fixture 重录 → Commit `refactor(agentm): 契约换锚 + DTO 拆分（同批前端含 legacy 装配块修复）`。

## E 波：卫生收尾

### Task 13: agent 域 Go 锚点注释清零 + Go 术语标识符改名

**Files:** agent/agentm 全部仍含锚点的文件（基线 466 处，A 波触碰文件已在拆分时清洗）；`tools/{GoJsonCodec,GoPath,GoQuoting}` 改名（全词 Java 名，定名时参考其职责：JSON 修复编解码 / 路径工具 / 标识符引号规则）。

- [ ] **Step 1**: `git grep -cE '对照 Go|GORM|Go 的|\.go '` 列剩余清单，逐文件"摘不变量 → 中性重写 → 删锚点"；`GoJsonCodec` 等改名后 grep `{@link 旧名}` 与全仓引用（编译器兜底）。
- [ ] **Step 2**: 注释掉的代码 0、坏 `{@link}` 0、批次代号 0 核对（§14.5）；`spotlessApply` 收尾。
- [ ] **Step 3**: 全量测试 + 闸门绿 → Commit `refactor(agent): Go 锚点注释清零 + Go 术语标识符改名`。

### Task 14: 终检审计 + HANDOFF 回填

- [ ] **Step 1**: 按 §14.5 逐项核对：≥800 行类清零或 javadoc 注明例外；请求侧无 `@JsonNaming`/逐字段注解（C 波范围）;每子包有 package-info；数据回填（域文件数/行数/最大类/锚点数新基线）。
- [ ] **Step 2**: HANDOFF 四处同步：§4 数据、§11 追加执行记录、§13 新踩坑、§14.3 候选表 agent/agentm 行改 ✅。
- [ ] **Step 3**: Commit `docs(handoff): agent 域重构完成回填（§4/§11/§13/§14.3）`。

## 明确不在本计划内（防止顺手扩散）

1. **Go 序列化器本体删除**（408 处引用/94 文件）——阶段 3 全仓一次性动作（§3 红线）。
2. **chat SSE `event/*Data` 载荷换锚**——归 session 域切片（`wip/chat-sse-slice2` 在途，合并后做；见 Review Focus #5）。
3. **datasource/im 删留**——待用户决策（§6.2）。
4. **wiki/session/memory 等其余域**——各自按 §14.3 顺序另立计划。
5. **`AgentStepListTypeHandler` 的 `[]` 空列表写库语义**——行为不变底线覆盖，不改。
