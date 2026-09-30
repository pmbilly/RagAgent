# 后端分包地图与体检结论（2026-09-30）

> **用途**：新人 30 分钟建立"哪个功能在哪个包"的全局观；后续会话按本文的 **P0–P3 待办**推进，
> 不用重新摸索。**复测**：`python3 scripts/pkg-audit.py`（口径见脚本 docstring）。
> **基线数据**：34 个顶层包 / **1,599 文件 / 284,033 行**（`com.ragagent` 子树）；顶层 `package-info` **33/34**（仅 `session` 待其批次补）。

## 1. 三类包（先分清性质，再判断"合理与否"）

| 性质 | 包 | 判定 |
|---|---|---|
| **业务域（20）** | knowledge、agent、agentm、wiki、session、datasource、memory、mcp、auth、apikey、audit、im、storage、model、system、websearch、embed、vectorstore、favorite、evaluation | 有 `controller/service/domain/dto/mapper/repository` 六件套，HTTP 面明确 |
| **库式域（4）** | `llm`、`retrieval`、`chatpipeline`、`event` | **无 controller 是对的**——被其他域调用的引擎/管线（`retrieval` 被 chatpipeline 19 文件、knowledge 10、session 7 消费） |
| **基础设施（10）** | `common`、`config`、`stream`、`modelcontext`、`searchutil`、`storageurl`、`tracing`、`webfetch`、`embedding`、`rerank` | 横切能力；`common` 被 **30 个包**依赖（位置正确） |

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

### P0 包间成环：32 组 —— 唯一"架构级"问题

挡着路线图**阶段 4（多模块边界固化）**：有环就画不出无环依赖图。代表：`agent ⇄ knowledge`、
`auth ⇄ knowledge`、`knowledge ⇄ wiki`、`agent ⇄ mcp`、`chatpipeline ⇄ session`、`session ⇄ storage`、
`llm ⇄ model`、`memory ⇄ session`、`config ⇄ common`…

- [ ] 先出**环的拓扑 + 最小解环方案**（依赖倒置 / 借 `event` 解耦 / 抽共享内核），评审后再动刀；
- [ ] 建议与阶段 4 合并为一个专门批次（动面最大，不与 P1–P3 混做）。

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

- [x] **顶层 `package-info` 补齐至 33/34**（2026-09-30，`f9` 批）：新增 28 个（apikey/audit/auth/chatpipeline/common/config/datasource/embed/embedding/
      evaluation/favorite/im/llm/mcp/memory/model/modelcontext/rerank/retrieval/searchutil/storage/storageurl/stream/system/
      tracing/vectorstore/webfetch/websearch）。**`session` 故意留空**——该域批次正在进行（步骤 2 半程），由该批次一并补，避免撞车；
- [ ] `model` 既是顶层域又是层名（`model/domain` vs `auth/domain`）→ 至少在文档里点名，改名后议；
- [ ] 四个近邻包易混：`embed`(12，HTTP 叶子域，0 包引用) / `embedding`(21，provider 客户端) / `vectorstore`(12) / `rerank`(14)；
- [ ] 2 个放错包的文件归位；5 个控制器改走服务层；wiki 2 处反向依赖反转。

## 4. 明确"别动"

- `im` / `datasource`：文档记着"可裁 / 可纯删"——**先由用户定删留**再谈结构；
- `evaluation`：§2.5 待排期可选项；
- `agent` 根级 26 个文件：此前已裁定"扰动/收益比不划算"；
- §11 边界清单（租户配置 jsonb、auth 域、agent fixture、工具输出自有 schema、检索引擎索引文档、Go 工具面 5 类）。

## 5. 风险提示

**P1–P3 是可读性问题**（改动低风险、每步全绿可验证）；**P0 成环是架构问题**（动面最大）。
按 §3 红线"一次只动一个轴"，两者**不要混批**。
