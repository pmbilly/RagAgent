# 交接文档（新会话接手用）

> 最后更新：2026-09-18 · **3161 测试全绿** · golden 188 个
> **端点覆盖：Go 412 条 → Java 已注册 150 条（约 36%）**

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

### 2.2 波次路线（**2026-09-18 实测重排，已废弃原「阶段 6/7/8」**）

| 波 | 内容 | 规模 | 状态 |
|---|---|---|---|
| 0 | `memory`(7.9k) · `datasource`(14k) | 33 条路由 | ✅ **完成** |
| **1** | **会话/消息面剩余**（CRUD/附件/产物/追问建议/消息历史/steer） | 27 条 | ✅ **完成**（真 PG A/B 全 MATCH） |
| 2 | 其余未被 agent 阻塞的端点群（~~chunk ✅~~/knowledge 剩余/faq/members/invitations/api-principal/system/admin/providers/stores/backends/evaluation/webSearch） | ~135 条 | 🔄 chunk(10) 完成 |
| 3 | **关键路径前置**：`sandbox` → `infrastructure` → `browserskill` → `modelcontext` | ~32k | ⏳ |
| 4 | **agent 核心** + `agent/tools`（20k，全局咽喉） | ~25k | ⏳ |
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

## 3. 下一步：波 2（管理面与知识库外围）

### 3.1 做什么

**波 1 全部收官**；**波 2 已开工，chunk 编辑面 10 条路由完成**（2026-09-18：46 golden +
真 PG A/B 全 MATCH，节奏见 conventions §8「chunk 编辑面」行、坑见 §9「波 2 chunk」）。
波 2 剩余（约 135 条，都不被 agent 阻塞），建议下一批从 **knowledge 剩余 24 条**
（download/preview/search/stages/spans/image/tags/batch/move/copy 等）或 **FAQ 11 条**
（与 chunk 同在 routes_knowledge.go，复用 ChunkAccessGuard 模式与 `faq.go` 类型资产）接着做；
再往后 members/invitations(14)、api-principal(3)、system/admin(14)、providers/stores/backends(26)。

⚠️ 例外：`POST /sessions/:id/knowledge-chat`、`POST /sessions/:id/agent-chat`、
`POST /knowledge-search` 这三条要等**波 4**（它们真的走 agent 引擎）。

### 3.2 已经就位的组件（直接用，别重造）

- `com.ragagent.session.domain` + `mapper`：`Session` / `Message` / 五个 jsonb List 处理器 /
  `SessionRepository` / `MessageRepository` / `MessageSuggestionRepository`
- `com.ragagent.session.service`：`SessionService`（读路径 + **写路径已全**：create/list/
  setPinned/update/delete/batchDelete/deleteAll + generateTitle）/ `MessageService` /
  `MessageSuggestionService` / `TemporaryDocumentService` + `AttachmentFileStore`
  （Go 布局落盘 `local://{tenant}/exports/…`，异步解析 executor；agent 门控/VLM/asynq
  为已知差异，见 conventions §9「波 1 G5」）
- `com.ragagent.session.controller`：Session/Message/MessageSuggestion/TemporaryDocument/Steer
  五个 controller（含 Go 风格 JSON 绑定语义 `GoJsonBindError`）
- `com.ragagent.common.web`：`GoTimeSerializer`（timestamptz 列）/
  `GoNaiveOffsetDateTimeTypeHandler`（naive 列，双形态）`PgJsonTypeHandler`（jsonb，
  **update 用 `set(col,val,"typeHandler=…")` 三参重载**）
- `com.ragagent.session.sse`：`SseContract` / `SseFrameWriter` / `StreamEventEmitter` /
  `StreamResponseBuilder`（SSE 的整条线，已 A/B 验过）
- `com.ragagent.storageurl`：引用重写 + 扣留缓冲（已 A/B 验过）
- `com.ragagent.stream`：流管理器

**波 2 的起点**：先读 `docs/translation-conventions.md` §8（已完成模块台账）与 §9
（各波实测的坑——**动手前必读**，多数坑会复发）；再按 §4 的标准验收流程推进。
判依赖看调用点不看包名（§2.2 的教训）。chunk 模块刚趟出一条「知识库域内小模块」的
完整路径：契约类型 + ChunkAccessGuard（ownership/KB 访问守卫分层）+ 仓储 + service +
controller，golden 录制与 A/B 脚本（`record-chunk-golden.sh` / `ab-chunk.sh`）可直接改造成
同域模块用。⚠️ A/B 前先确认 8082 上没有旧 Java server 进程（§9「波 2 chunk」第 10 条）。

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
./gradlew test                     # ⚠️ 必须跑一次；约 3 分钟（测试堆已提到 2g）

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

## 7. 关键文件索引

| 用途 | 路径 |
|---|---|
| 翻译约定（必读） | `docs/translation-conventions.md` |
| 交接文档（本文） | `docs/HANDOFF.md` |
| 契约 golden（142 个） | `server/src/test/resources/contracts/` |
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
