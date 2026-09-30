# 后端分包地图与体检结论（2026-09-30）

> **用途**：新人 30 分钟建立"哪个功能在哪个包"的全局观；后续会话按本文的 **P0–P3 待办**推进，
> 不用重新摸索。**复测**：`python3 scripts/pkg-audit.py`（口径见脚本 docstring）。
> **基线数据**：**31 个顶层包**（2026-09-30：`apikey`→`auth`、`agentm` 拆出 `initialization`、`searchutil`→`retrieval/support`、`storageurl`→`storage/support`、`webfetch`→`agent/support`）/ **1634 文件 / 284k 行**（`com.ragagent` 子树）；顶层 `package-info` **33/34**（仅 `session` 待其批次补）。

## 1. 三类包（先分清性质，再判断"合理与否"）

| 性质 | 包 | 判定 |
|---|---|---|
| **业务域（20）** | knowledge、agent、agentm、initialization、wiki、session、datasource、memory、mcp、auth（含 `apikey/` 子域）、audit、im、storage、model、system、websearch、embed、vectorstore、favorite、evaluation | 有 `controller/service/domain/dto/mapper/repository` 六件套，HTTP 面明确 |
| **库式域（4）** | `llm`、`retrieval`、`chatpipeline`、`event` | **无 controller 是对的**——被其他域调用的引擎/管线（`retrieval` 被 chatpipeline 19 文件、knowledge 10、session 7 消费） |
| **基础设施（7）** | `common`、`config`、`stream`、`modelcontext`、`tracing`、`embedding`、`rerank`（`searchutil`/`storageurl`/`webfetch` 已并入宿主域，见 §3.5）| 横切能力；`common` 被 **30 个包**依赖（位置正确） |

**分层约定**（§2.13）：`mapper/` 只放 MyBatis-Plus 接口；`repository/` 放仓储门面（软删/乐观锁/方言）；
`service/` 放 Spring 服务；`support/` 放无状态算法；其余按 `task/client/storage/security` 角色；
**一类型一文件**，禁 `*Dtos/*Util` 类容器。`knowledge` 为范本（12 子包、零容器类）。

## 2. 已达标（别动）

- 分层方向正确：`common` 在底（30 包依赖）、`retrieval`/`llm` 是库式域不暴露 HTTP；
- `im` 一渠道一子包（feishu/wechat/dingtalk/slack/mattermost/telegram/qqbot/wecom/yunzhijia + runtime）；
- 真分层倒挂**极少**：`controller → mapper/repository` 仅 **5 个**（apikey/memory/session×2/storage）；
  `service → controller` 仅 **2 个**（wiki/service 的 Ingest 两个类）；`domain/dto → service/controller` 仅 1 个；
- `controller/` 包里放错的文件只有 **2 个**（`audit/AuditLogListResponse`、`wiki/WikiActivityAudit`）。

## 3. 待办（按优先级；**一次只动一个轴**，别混批）

### P0 包间成环：34 组 —— 实测后**大部分比想象中便宜**

> ⚠️ **别用"成环数量"估成本**：决定成本的是**背边规模**（环里文件数较少的那一侧要切几刀）。
> 2026-09-30 画像：**背边 ≤2 文件 = 22 组**（多数是**单个类型越界**造成）、4–7 文件 ≈ 7 组、**≥8 文件 = 5 组**（贵重）。

**便宜的环长什么样**（一个类型造成的整组环）：

| 环 | 背边类型（切它即可） | 现用途 |
|---|---|---|
| `common ⇄ config` | `TenantProperties ×1` | `RbacInterceptor` 读租户属性 |
| `auth ⇄ memory` | `MemoryConfig ×1` | 租户目录页读记忆配置 |
| `audit ⇄ auth` | `TenantRole ×1` | 审计控制器判角色 |
| `llm ⇄ retrieval` | `SearchResult ×1` | 流式响应里放检索结果 |
| `storage ⇄ session` | `Message ×1` | 判断"消息是否引用该文件" |
| `embedding`/`rerank`/`llm ⇄ model` | `Model ×1` | provider 只要**配置值**却拿了模型实体 |

**五种手法**（22 组归为 5 类，不是 22 个独立难题）：

| 手法 | 约解 | 例 |
|---|---|---|
| A 配置/常量类归位到最低层 | 6 组 | `TenantProperties`、`ConversationProperties`、`MemoryConfig` |
| B 无状态工具下沉（→`common`/中立 support） | 5 组 | `SearchTextUtil`、`AgentPromptPlaceholders`、`ExtractPrompts` |
| C 端口化：上层不直连下层 mapper/service | 6 组 | `audit`/`apikey` 直查 `KnowledgeBaseMapper`；`auth` 直用 `KnowledgeBaseService` |
| D 传值不传实体 | 4 组 | provider 客户端只取模型配置值 |
| E 引擎伴生类型归位 | 3 组 | `Gate`/`Decision`/`ApprovalException`、`Registry`/`StreamDecoder` |

**批次**：批 1 = A+B（约 11 组）｜批 2 = C（约 6 组）｜批 3 = D+E（约 5 组）｜**5 组贵重的留阶段 4**
（`knowledge ⇄ wiki`、`chatpipeline ⇄ session`、`chatpipeline ⇄ knowledge`、`knowledge ⇄ retrieval`、`agent ⇄ mcp`：
要么是领域实质耦合，要么绑定仍待重构的域——现在硬解会返工）。
**更新（2026-09-30 批 4 系列）**：这 5 组里 **4 组已解**（`knowledge ⇄ wiki`、`chatpipeline ⇄ knowledge`、
`knowledge ⇄ retrieval`、`agent ⇄ mcp`），只剩 `chatpipeline ⇄ session`。

**守卫（已入库）**：`python3 scripts/check-package-cycles.py` —— **环只许减不许增**（基线
`scripts/package-cycles.baseline.json`：环 34 / 依赖 config 5 包 / L2→L3 19 条）；解掉后跑 `--write` 刷新基线。
当前基线（2026-09-30 批 4k 后）：**环 4 组 / 依赖 `config` 的包 1 个 / 能力层→业务层直连 9 条**（拆分使 `agentm ⇄ knowledge`/`agentm ⇄ model` 改名为 `initialization ⇄ …`，净数不变）。

### P1 扁平包 10 个（无子包，靠文件名找东西）

`chatpipeline`(44)、`event`(40)、`embedding`(21)、`rerank`(14)、`stream`(11)、`modelcontext`(11)、
`storageurl`(10)、`config`(10)、`searchutil`(8)、`webfetch`(4)。

- [ ] **拆解点已存在**（沿命名即可分）：`chatpipeline` 的 `Plugin*`(19) + 管线骨架；`event` 的 `Agent*`(11) + `Event*`(10)；
- [ ] 顺带处理命名遗留：`searchutil` → `retrieval/support`；`webfetch` 是 agent 能力 → `agent/support`。

### P2 超大单层（≥70 文件）—— 内部已有天然族

| 层 | 数量 | 内部族 |
|---|---|---|
| `agent/tools` | 95 | `Wiki*` 19、`Mcp*` 10、`Sql*` 4、`Search*` 4 |
| `wiki/service` | 81 | `Wiki*` 60、`Ingest*` 6 |
| `datasource/connector` | 76 | `Notion*` 22、`Ima*`/`Yuque*`/`Feishu*`/`Rss*` 各 6–7 |
| `knowledge/dto` | 74 | `Faq*` 17、`Knowledge*` 8 |

- [ ] 按族建二级子包（纯移动 + 全绿，走 §14 手法）。

### P3 命名与文档

- [x] **顶层 `package-info` 补齐至 33/34**（2026-09-30，`809115c`）：新增 28 个（apikey/audit/auth/chatpipeline/common/config/datasource/embed/embedding/
      evaluation/favorite/im/llm/mcp/memory/model/modelcontext/rerank/retrieval/searchutil/storage/storageurl/stream/system/
      tracing/vectorstore/webfetch/websearch）。**`session` 故意留空**——该域批次正在进行（步骤 2 半程），由该批次一并补，避免撞车；
- [ ] `model` 既是顶层域又是层名（`model/domain` vs `auth/domain`）→ 至少在文档里点名，改名后议；
- [ ] 四个近邻包易混：`embed`(12，HTTP 叶子域，0 包引用) / `embedding`(21，provider 客户端) / `vectorstore`(12) / `rerank`(14)；
- [ ] 2 个放错包的文件归位；5 个控制器改走服务层；wiki 2 处反向依赖反转。

## 3.5 目标结构（重组后）

### 分层规则（`scripts/check-package-cycles.py` 可校验其一）

```
L4  config                      组合根：Spring 装配；**只出不进**（任何域不得依赖它）
L3  业务域                       knowledge agent agentm session wiki datasource im memory mcp auth
                                 audit model storage system websearch embedchannel favorite evaluation
     └ 同级之间：禁直连对方 mapper/实体；跨域走**窄接口（port）或事件**
L2  能力层                       llm retrieval embedding rerank chatpipeline
     └ 不得依赖 L3；只接受**配置值**而非业务实体
L1  平台                         common event stream tracing
```

### 顶层包 34 → 约 29（并入/改名 5 处 + 1 处待定）

| 现在 | 重组后 | 理由 |
|---|---|---|
| `searchutil` | `retrieval/support` | ✅ **已并入（2026-09-30，批 1）**：纯检索工具（消 `retrieval ⇄ searchutil` 环）|
| `storageurl` | `storage/support` | ✅ **已并入（2026-09-30，批 1）**：存储 URL 重写（消 `storage ⇄ storageurl` 与 `session ⇄ storageurl`）|
| `webfetch` | `agent/support` | ✅ **已并入（2026-09-30，批 1）**：agent 的抓取能力 |
| `apikey` | `auth/apikey` | ✅ **已并入（2026-09-30）**：一次消掉 `apikey ⇄ auth` 与 `apikey ⇄ knowledge` 两组环 |
| `embed` | `embedchannel` | 与 `embedding` 名字太近，语义不同（业务渠道 vs provider 客户端）|
| `modelcontext` | **仍待定**：并入 `agent/modelcontext` 或保留顶层 | 目前只被 agent 用；`agent ⇄ modelcontext` 环也可用"伴生类型归位"解 |
| `agentm` | ✅ **已拆分（2026-09-30）**：`agentm`（智能体管理）+ `initialization`（初始化/模型能力）| 原为混装（Go 期同组）；拆后各域职责单一，`ExtractPrompts` 随 init 半区（它引用 `chatpipeline`，未下沉）|

其余域**保留顶层**：`knowledge agent session wiki datasource im memory mcp auth audit model storage
system websearch favorite evaluation common config event stream tracing`。

### 域内标准骨架（§2.13）+ 二级子包阈值

```
<domain>/
  controller/ 仅 *Controller      service/  Spring 服务        domain/  实体与值对象（jsonb 落库类型）
  dto/        请求/响应           mapper/   MyBatis-Plus 接口   repository/ 仓储门面（软删/乐观锁/方言）
  support/    无状态算法与规则    task/ client/ storage/ security/   按角色
```

**阈值**：一层 **>50 文件 或 ≥3 个自然族 → 建二级子包**（命中者：`agent/tools` 95、`wiki/service` 81、
`datasource/connector` 76、`knowledge/dto` 74；扁平包 `chatpipeline` 44、`event` 40）。

| 目标内部分组（沿已有命名族，纯移动） |
|---|
| `agent/tools/` → `tools/{knowledge,wiki,web,mcp,sql,data}/`（`Wiki*` 19、`Mcp*` 10、`Sql*` 4）|
| `wiki/service/` → `service/{ingest,page,link,folder}/`（`Wiki*` 60、`Ingest*` 6）|
| `knowledge/dto/` → `dto/{request,response,view}/`（或按族 `faq`17/`knowledge`8/`chunk`）|
| `datasource/connector/` → 已按供应商分子包，只需 14 个根级文件归位 |
| `chatpipeline/`（扁平 44）→ `plugin/`(19) + `pipeline/`(7) + `search/` + `payload/` |
| `event/`（扁平 40）→ `bus/` + `payload/` + `agent/`(11) |
| `embedding/`(21) `rerank/`(14) → 可选 `provider/` + `support/`（未超阈值，非必须）|

### 批次 → 结构变化的对应

| 批次 | 结构结果 |
|---|---|
| 批 1 解环·配置/工具归位（A+B） | ✅ **已执行（2026-09-30）：环 32 → 24（−8）**；顶层 −3；依赖 `config` 的包 5 → 1；L2→L3 直连 18 → 14 |
| 批 2 解环·端口化（C） | **已完成（环 17，批 2 全部收口）**：已完成 `AgentPromptPlaceholders`→`common/prompt`（消 `agent ⇄ knowledge`，环 24→23）；③-c `auth ⇄ storage`（`StorageAllowList`→`common/storage`；`StorageBackendProvisioner` 命令端口，106 行 env→实体映射收回存储域）；③-d `auth ⇄ knowledge`（`KnowledgeBaseProvisioner` 命令端口：隐藏 KB 的实体语义收回知识域）；余下：① `session ⇄ storage`——✅ **已完成（2026-09-30，环 22）**：端口载荷收窄 + `Rewriter` 消息段搬到会话侧；原「前半已完成」：`FileAccessResolver` 的端口载荷已收窄为 storage 侧 `MessageFileFacts`（会话侧 `factsOf` 映射）；**后半待做**：`storage/support/Rewriter` 的消息段（第 334–437 行）仍 import `Message`/`MessageImage`，建议整段搬到会话侧（见 HANDOFF §11.9）；② `audit ⇄ knowledge` **已完成**（`KnowledgeBaseGateway` 只读端口）；`auth ⇄ knowledge/storage` 各加窄接口（**`auth ⇄ memory` 已完成**：`MemoryConfig`/`MemoryKinds`/`MemoryKeys` 下沉 `common/settings`）（**`auth ⇄ system` 已完成**：`SystemSettingRegistry` 下沉 `common/settings` + 新增只读端口 `SystemSettingGateway`，由 system 侧实现）|
| 批 4 大项解环（④） | **进行中（环 4）**：④-a `agent ⇄ mcp` **已完成**——`ResponseType`（18 文件共享的事件契约枚举）→ `common/llm`；`agent/approval`（共享审批机制，1,861 行）→ `common/approval`，MCP 专用的 `Adapter`/`McpToolPolicySource` 下沉 `mcp/service`；④-b **能力层配置去实体化**：5 个配置类的 `fromModel(Model)` 映射收回 `model/service/ModelRuntimeConfigs`（消 `embedding`/`llm`/`model⇄rerank` 三组环，直连 14→11）；④-c **model 域边界收口**：`ModelGateway` 只读端口（消 `model ⇄ retrieval`）+ `UploadLimits` 纯规则搬 common（消 `knowledge ⇄ model`）；④-d **实体归位**：`StorageBackend`（storage_backends 表）从 `knowledge.domain` → `storage.domain`（10 文件引用）+ 清 `FileAccessResolver` 的死注入（消 `knowledge ⇄ storage`）；④-e **契约类型搬迁第一批**：`ChatManage` 的 4 个图形值类型 → `common/graph`；`SearchParams`/`ChunkTypes` → `common/pipeline`；`RetrievalObs` → `retrieval/obs`；`RetrieveGraphRepository` 端口 → `retrieval/graph`；`MessageAttachmentsPrompt` → `session`（消 `chatpipeline ⇄ retrieval`）；④-f-① `EntityExtraction`+`PipelineConfig` → `llm/extract`、`GoJsonMarshal`+`GoValueStr` → `common/web`（消 `chatpipeline ⇄ knowledge`）；④-g wiki 小簇（`WikiImageMarkup`/`WikiLanguageSupport`/`SlugUpdate`/`ExtractedItem`/**wiki 自己的 `GoStrings`**）→ `common/wiki` + `WikiIngestPort`/`WikiFinalizePort` 端口（消 `knowledge ⇄ wiki`）；④-h `SearchResult`（SSE 契约载荷、零域依赖）`retrieval.domain` → `common/retrieval`（消 `llm ⇄ retrieval`）；④-i `memory ⇄ session`：只读端口 `SessionMessagePort`（2 方法 + 视图，`MessageRepository` 实现）+ 删纯转发 `MemoryMessageReader` + `MemoryUsedMemories` 归位 chatpipeline（消 `memory ⇄ session`）；④-k `knowledge ⇄ retrieval` **已完成（环 5 → 4）**——先归位（`VectorStoreService` → `retrieval/engine`，引擎写面与读面合流；`ImageInfoEnricher`/`SearchChunkMerge` → `knowledge/support`），再端口组（`common/knowledge` 的 `KnowledgeBaseSearchGateway`/`KnowledgeDocumentGateway`/`ChunkSearchGateway` + `common/embedding` 的 `EmbeddingGateway`，全部由知识域实现；`HybridSearchService` 不再 import 知识域）；余下 `chatpipeline ⇄ session`、`initialization ⇄ knowledge/model`、`agent ⇄ modelcontext` |
| 批 3 解环·传值 + 伴生类型（D+E） | provider 客户端只依赖配置值；引擎伴生类型归位 |
| P1/P2 分包子包 | 上表的域内二级结构 |
| P3 小修 | 2 个放错包的文件归位；5 个控制器改走服务层；wiki 2 处反向依赖反转 |
| 阶段 4 | 5 组贵重环 + 模块边界固化（`config`/L1 的物理模块化） |

## 4. 明确"别动"

- ~~`im` / `datasource`~~：**用户已定：保留**（2026-09-30，不再考虑删除）——其结构可按 §14 正常重构；
- `evaluation`：§2.5 待排期可选项；
- `agent` 根级 26 个文件：此前已裁定"扰动/收益比不划算"；
- §11 边界清单（租户配置 jsonb、auth 域、agent fixture、工具输出自有 schema、检索引擎索引文档、Go 工具面 5 类）。

## 5. 风险提示

**P1–P3 是可读性问题**（改动低风险、每步全绿可验证）；**P0 成环是架构问题**（动面最大）。
按 §3 红线"一次只动一个轴"，两者**不要混批**。
