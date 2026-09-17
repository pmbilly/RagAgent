# Go → Java 翻译约定（AI 翻译会话必读）

> 本文件是每一个翻译任务的上下文入口。开始任何翻译前，先读本文件全文。
> 源仓库：`/Users/billy/WeKnora`（Go，对照参考，只读）。目标仓库：`/Users/billy/ragagent-java`。
> 铁律：**前端零改动**——所有 HTTP 响应 JSON 逐字段一致，错误格式一致，状态码一致。

## 1. 技术栈映射

| Go | Java | 备注 |
|---|---|---|
| Gin handler | `@RestController` | 路由路径逐字符相同 |
| `gin.Context` | 方法参数按需组合：`HttpServletRequest`/`@RequestBody`/`@PathVariable` 等 | 不要注入裸 `HttpServletResponse` 除非必要（SSE 除外） |
| GORM | MyBatis-Plus | Mapper 接口 + `@TableName` 实体；见 §3 |
| `context.Context` | `TenantContext`（ThreadLocal，见 §5）+ 显式传参 | 不要把 Context 当参数层层传 |
| goroutine | 虚拟线程：`Thread.ofVirtual().start(...)` / `Executors.newVirtualThreadPerTaskExecutor()` | 已在 application.yml 开启 `spring.threads.virtual.enabled` |
| channel | `BlockingQueue` / `CompletableFuture` | |
| `error` 返回值 | 抛 `BizException`（见 §4） | Go 的 `if err != nil` 日志后返回 → 抛异常，由全局 handler 记日志 |
| `time.Time` | `java.time.Instant`（DB 存 `OffsetDateTime`） | JSON 序列化格式必须与 Go 的 RFC3339 一致（`yyyy-MM-dd'T'HH:mm:ss'Z'`） |
| `json:"xxx,omitempty"` | `@JsonInclude(NON_NULL)` | 字段级：`@JsonInclude(NON_NULL)` 放字段上 |
| `int64`/`float64` | `Long`/`Double` | JSON 数字精度对齐 |
| option 配置结构体 | `@ConfigurationProperties` record/class | |
| `sync.Mutex`/RWMutex | `ReentrantLock`/`ReentrantReadWriteLock` 或 `ConcurrentHashMap` | |
| `defer` | try-finally 或 try-with-resources | |

## 2. 包结构约定

- Go `internal/handler/x.go` → `com.ragagent.x.XController`
- Go `internal/application/service/x.go` → `com.ragagent.x.XService`
- Go `internal/application/repository/x.go` → `com.ragagent.x.mapper.XMapper`（MyBatis-Plus 接口）
- Go `internal/types/x.go` 中的领域类型 → `com.ragagent.x.domain.X`（实体）+ `com.ragagent.x.dto.XxxRequest/XxxResponse`（传输对象）
- Go `internal/middleware/` → `com.ragagent.common.filter.*`（Servlet Filter 链，顺序 = Go 中间件顺序）

## 3. GORM → MyBatis-Plus 规则（翻译 model 前必做清单）

翻译每个 Go model 前，先扫描并**显式列出**以下隐式行为，翻成 MyBatis-Plus 等效配置：

1. **钩子**：`BeforeCreate`/`AfterFind`/`BeforeUpdate` 等 → MyBatis-Plus `@TableField(fill = ...)` + `MetaObjectHandler`，或在 service 层显式赋值。**列出每个钩子等效为哪段 Java 代码。**
2. **关联预加载**：`Preload(...)` → 是额外查询就在 service 层 join 查询，标清 N+1 风险。
3. **软删除**：`gorm.DeletedAt` / `gorm:"softDelete"` → `@TableLogic` 注解。
4. **默认排序**：`gorm:"default:..."` 和代码里隐式的 `Order(...)` → 显式出现在查询构造中。
5. **唯一索引/外键**：struct tag 里的 `uniqueIndex`/`index` → 在 `@TableField` 注释或迁移 SQL 核对（**迁移不改**，索引以迁移为准）。
6. **自动时间戳**：`CreatedAt/UpdatedAt` 自动写 → `MetaObjectHandler`。

翻译一个 model 不出这张清单 = 任务未完成。

## 4. 错误与响应格式（逐字段锁定）

Go 的成功响应统一为：

```json
{"data": ..., "success": true}
```

Go 的错误响应有两种形态，**按源文件实际使用的翻译**（看 handler 里写的是 `c.JSON(status, gin.H{"error": ...})` 还是统一 error handler）：
- `{"error": "消息"}` + 对应 HTTP 状态码（handler 直接写的）
- 统一 `{"success": false, "message": "...", "code": ...}`（若走全局 error handler——翻译 router/middleware 时确认实际形态，记录到本文件 §8）

Java 实现：`com.ragagent.common.R<T>`（`{data, success}`）+ `@RestControllerAdvice` 全局异常处理器按 Go 实际形态输出。状态码必须一致（400/401/403/404/409/500）。

## 5. TenantContext（对照 Go context.Context）

Go 用 `context.Context` 传递 tenant/principal/visitor。Java：

- `com.ragagent.common.context.TenantContext`：ThreadLocal 持有 `tenantId`、`principalType`、`principalId`、`embedVisitorId`
- 由 Filter 链（对应 Go middleware 链）填充：auth → rbac → principal 解析
- service 层需要租户隔离的每个查询必须带 `TenantContext.currentTenantId()`，等效 GORM 的 `Where("tenant_id = ?")`
- **虚拟线程下 ThreadLocal 安全**（虚拟线程也是线程），但跨虚拟线程传递（异步任务）必须显式传递值，禁止共享 ThreadLocal

## 6. SSE 流式翻译纪律（agent/chat 模块最严格）

翻译任何涉及 `stream_emit` / SSE 的代码时：

1. **先把 Go 侧所有 emit 点列成表**：文件、行号、事件类型、payload 字段。
2. Java 侧逐一对照：每个 emit 点编号对应，事件顺序一致，`data:` 的 JSON 字段名和结构一致。
3. `SseEmitter` 用完必须 `complete()`；异常路径必须 `completeWithError()`。
4. 客户端断开检测：Go 的 `c.Stream` 断流 → `SseEmitter.onCompletion/onTimeout` 注册清理。
5. 心跳/keepalive 间隔与 Go 一致。

## 7. 测试翻译规则

- Go 表测试（table-driven）→ JUnit 5 `@ParameterizedTest`
- `testify/assert` → AssertJ
- Go mock（gomock/mockery）→ Mockito
- httptest → `@WebMvcTest` + MockMvc
- ** golden 契约测试**：`server/src/test/resources/contracts/` 下按端点存 Go 版实际响应，Java 集成测试逐字段比对
- 翻译完成的定义：Go 测试语义对应的 Java 测试全部通过 + golden 通过

## 8. 翻译日志（每完成一个模块更新）

| 模块 | Go 源 | Java 目标 | 状态 | 备注（踩坑/GORM 清单/SSE emit 表位置） |
|---|---|---|---|---|
| （示例）骨架 | internal/router/ | com.ragagent.common | ⬜ | |

## 9. 当前确认过的细节

- Go 全局错误形态：待阶段 0 翻译 router/middleware 时确认后更新 §4
- DB：schema 与 98 个迁移一字不改；Flyway baseline-on-migrate 兼容已有 Go 数据的库
- 端口：后端 8080（前端 dev 代理默认值）；dev 库 localhost:15432
