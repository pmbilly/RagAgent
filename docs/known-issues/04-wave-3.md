# 已确认细节与坑 · 波 3（sandbox / skill / 协作 / agents / browserskill）

> 本文件是原 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 2026-09-25 起 conventions 已拆分：规范正文并入 `docs/HANDOFF.md` §7，日志/细则见 `docs/translation-log.md`。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-log.md`](../translation-log.md) §9 索引 ｜
> 同目录兄弟文件：00 基础 / 01 阶段 4.1–5.2 / 02 波 0–1 / 03 波 2 / 04 波 3 / 05 波 4 / 06 W5。


- **波 3 sandbox 子批 1（配置 CRUD 面）补充**：
  - **依赖真相（本批立项依据）**：sandbox 包 14.7k 行里，配置 CRUD 的 HTTP 面完全不依赖
    provider 可用性——Create 纯校验+落库；Update 的旧凭据盘点失败 → WARN 后**继续保存**
    （Go 注释原文：堵保存会把管理员困在正要修的 key 上）；Delete 盘点失败 →
    `!force` 409 `sandbox_inventory_unverifiable` / `force` 继续删。错误文案全是
    固定串，dev 无 provider 两侧同形。**provider 执行面（templates/skills/exec）才需要
    真客户端**——接缝 `SandboxClientFactory`/`ConfigSandboxClient#list` 本批以
    Unwired 占位，子批 2 换真实现。
  - **URL 守卫先于必填校验**（Sanitize 链实测顺序）：endpoint 的
    ValidateOutboundURLWithPolicy 在 ResolveEffectiveConfig（必填）**之前**——
    golden sbx-cube-incomplete 落在 "address 127.0.0.1 is private" 而非 missing-fields。
    录 golden 前先实测分支顺序，别按源码阅读顺序想当然。
  - **三种 unsupported-type 文案并存**：named 配置保存路径
    "named sandbox configs only support cube, e2b and docker backends"；
    templates/query 路径 "sandbox template catalog only supports …"；ParseSandboxType
    底层还有 `sandbox: unsupported sandbox type "xxx"`（带引号值）。同语义不同层不同
    文案，golden 各钉各的。
  - **特殊拒绝体是"信封形但字符串 code"**：409/423 的
    `{"error":{"code":"sandbox_inventory_unverifiable",…}}` code 是**字符串**且
    **无 details 键**——与 AppError 信封的数字 code+恒输出 details 两处都不同，
    controller 里用裸 LinkedHashMap 直写，不走 GlobalExceptionHandler。
  - **Inventory 的 provider 失败形态是 200 不是 500**：
    `{"data":{"sandbox_count":0,"unverifiable":true},"success":true}`（Go 的
    SandboxInventory 把不可核实编码进响应体而非错误）。
  - **config 列的字段级 AES**（与模型凭证 whole-payload 加密不同）：Go 在 Value()
    钩子里对 Cube/E2B 的 APIKey、EnvVars 值、Network secret **逐字段**加密后混入
    jsonb；Scan 侧 DecryptStoredSecretLenient 失败→置空（不抛）。Java 在
    TypeHandler 里做同样的事；enc:v1: 前缀与阶段 2 互操作同族。
  - **api_key/env_vars 的响应掩码与存储加密无关**：SandboxConfigForResponse 恒把
    非空密文掩成 "***"（未配置留空串），MergeSandboxConfigForUpdate 用
    PreserveIfRedacted（与 ct 批 tenantconfig 同族）把 "***" 回填成存量值。
    契约测试/契约断言只依赖掩码形态，H2 无 AES key 也能全链路（服务层的
    「拒绝明文落密钥」检查用固定 key 替身放行，真加密路径由 A/B 真 server 覆盖）。
  - **docker 后端开关是三层解析**（DB system_settings > env WEKNORA_SANDBOX_DOCKER_ENABLED
    > false），Java 侧 SandboxBackendPolicy.setDockerBackendEnabled 是 system_settings
    的推送口，本批只有 env+false 两层（dev 等价）；**将来接 SystemSettingRegistry 时
    记得把 sandbox.docker_enabled 推进来**。
  - **时区形态在同一 Go 进程内都不稳定**：sbx-create 响应的 created_at 是
    `…Z`（GORM 内存对象）、sbx-list 读回是 `…+08:00`（PG 驱动回读）——**同一行
    同一轮录制两种形态**。时间戳掩码从「跨轮稳定」升级为「必需」，任何新 golden
    都别赌 Go 侧时区形态。
  - **墙钟脆弱测试第三变种**（§5 陷阱 9 续）：RedisStreamManagerTest 的 TTL 续期
    用例（5s TTL + sleep(2s) + 秒级精度前后对比）在全量慢跑下两种假红——键过期
    （读数 -2）与 renew 前后同秒（after==before）。修法：毫秒精度（PTTL）读数 +
    管理器 TTL 拉到 2min，把续期可见性与机器速度解耦。
  - **全量时长 6.7min→14.5min**：sandbox 批 +2~3 个 @SpringBootTest 上下文变体后
    （每变体整份上下文驻留堆），3g 堆开始 OOM（GC 死亡螺旋特征：全量耗时翻倍），
    提到 4g 恢复稳定。治本方向是收敛上下文变体数量（TestConfiguration/不同
    @MockBean 组合各算一个变体），堆只买时间。
- **波 3 sandbox 子批 2（sandbox-check + templates provider 面）补充**：
  - **sandboxCheckReason 是 dev A/B 的字节稳定锚**：provider 传输错误按
    RemoteError.Kind 归一成固定中文文案（拒连→"服务不可用：端点拒绝连接"、
    超时→"请求超时：…"、认证→"认证失败：…"、404→"资源不存在：请检查模板 ID"、
    INVALID_REQUEST→"参数无效："+msg、docker 的 Unavailable 走
    dockerUnavailableCheckReason 从消息里抠 host）。原始拨号措辞永不进响应体。
  - **分类器契约**（remote_errors.go）：httpErrorKind（400/422→invalid_request、
    401/403→authentication、404→not_found（**Create 特判 invalid_request**）、
    408/504→timeout、409→conflict、410→terminal、429/507→capacity、5xx→
    unavailable、其它→internal）+ 传输层（net.Error 非超时→unavailable、
    超时→timeout；Java 的 ConnectException/UnknownHostException/SSLException 全落
    unavailable 分支）。
  - **sandbox-check 的三个形态并存**：400 老式 `{"code":1,"msg":…}`（系统组遗留，
    非 AppError 信封）、200 结构化探测结果、checks[].ok 三态（null=跳过）。
    **capabilities 是 gin.H：键字母序**（pause_resume<reconnect<volumes）——
    struct 序会假红。三方具名后端的探测面能力同表（volumes=false，
    pause_resume/reconnect=true）。
  - **latency_ms 的粒度差**：Go 拒连 0ms → omitempty 整键省略；Java HttpClient
    同场景 1ms+ → 键出现。掩码把 `,"latency_ms":N` 整片段从两侧移除，别只掩数字。
  - **plain-500 分支**：Go 全局 ErrorHandler 对非 AppError 是
    `{"error":{"code":1007,"message":"Internal server error"},"success":false}`
    （**无 details 键**），与 AppError 信封的 "details":null 刻意不同。处理方式
    沿用 FAQ 批先例：**controller-local @ExceptionHandler**（列 RemoteError/
    IllegalStateException/DataAccessException，刻意不列 BizException——它必须
    继续走全局信封），common 全局文件零改动。
  - **agent 中断接力的边界**（本批实测）：agent 撞用量上限时可能**还没有写任何
    文件**（全部预算耗在按纪律读文档+Go 源码上）——接力前先 `git status` 盘点，
    别假设有半成品。主会话接力时优先做"自包含+验收闭环"的切片，把大面留给
    配额恢复后的下一个 agent。
  - **fmt 的 %q**：Go `"sandbox: provider %q cannot be probed"` 的 %q 输出双引号
    包裹——Java 手拼 `"\"" + type + "\""` 时别漏。
- **波 3 sandbox 子批 3（skills 子资源）补充**：
  - **任务书会写错，golden+Go 源才是准绳**：任务书断言 is_set 读
    tenant_user_env_vars（按主体存储），实际 Go 读 tenant_skills.envs 列内 value
    非空——agent 按 Go 源纠正并回报。派发任务书的"事实段"只是路标，落地以源码+实录为准。
  - **类级 @JsonInclude(NON_DEFAULT) 会吞布尔 false**：skillResponse 的
    enabled:false、skillEnvResponse 的 is_set:false 是合法输出（无 omitempty），
    类级注解会把它们整个吞掉——**布尔/数字字段逐字段挂 NON_DEFAULT，宁可啰嗦**。
  - **202 异步受理是 provider 无关契约**：upload 合法 zip → 202
    {"data":{"skill_id":…}}，install 管线在后台走（首个 provider 调用失败置
    failed）。golden/A-B 只对受理响应字节；**异步终态（installing→failed）不进
    golden**——两侧状态机语义一致即可，A/B 掩码面不含行状态。
  - **SSE 单帧 + JSON 前置 404**：install-events 的 resolveSkill 404 在 SSE 头
    写出前以 JSON 返回（Go 注释原文：refusal 在任何 SSE 头之前仍可渲染为 JSON）；
    ready 技能单帧 {"percent":100,"stage":"done","status":"ready","done":true}。
  - **envs jsonb 列逐字段 AES**（value 字段 json:"-" 恒不出响应）：
    nil→NULL、空→[]、解不开置空——TypeHandler 三态与配置列同族。
  - **Redis 键名契约**：进度键 weknora-skill-install:<tenant>:<config>:<skill>
    （TTL 30min）与 Go 逐字一致——跨语言互操作的键空间。
- **波 3 sandbox 子批 4（/skills 家族 + /me/env-vars）补充**：
  - **catalog 列表是三段合并投影**：catalog 行 → 孤儿 catalog_id 技能行 → 无
    catalog_id 同名行并入 installations[]——Go catalogProjectionFromSkill 把
    tenant_skills 行也当目录条目输出，别只查 catalog 表。
  - **bundle_sha256 的 A/B 掩码**：python zipfile.writestr 用当前 localtime 做
    zip 内嵌 mtime → 每次构建 sha 不同；契约测试与 ab 脚本都要掩
    `"bundle_sha256":"<sha64>"`。要根治可在录制脚本用固定 date_time 的 ZipInfo。
  - **DELETE 也吃 JSON body**：/me/env-vars 的 DELETE 与 PUT 同构（body 绑定），
    query 传参得 400 "EOF"——录制脚本里用 `printf … > file` + `-d @file` 避免
    shell 转义地狱（本轮在 delete-skill 上踩了两轮）。
  - **mev 投影 source 三态** unset/workspace/user：dev 只出现 unset/user
    （workspace 来源随 system_settings 管理面）；updated_at 仅 user 态输出。
  - **install 受理映射**：202 {"data":{"installs":{configId:installId}}}，
    per-config 吞错、全败重抛首个；config 不存在 → **404**（1003）非 403。
  - **删除钉住语义**：catalog delete 在存在引用行时 409 code 1005（install 的
    后台失败不删行——与子批 3 的 failSkill 语义衔接）。
- **波 3 协作面（organizations + shares）补充**：
  - **【跨横切真缺陷，待专项】emoji 转义**：jackson-core 2.17.2 + GoJsonEscapes
    （自定义 CharacterEscapes）对**补充字符**（emoji，UTF-32 > 0xFFFF）按 UTF-16
    代理对分段转义成 \uD83E\uDD16；Go/encoding/json 输出 raw UTF-8。任何含 emoji
    的响应都会字节 DIFF。修法候选：升级 jackson-core（新版 escape 实现合并代理对）
    或 JacksonConfig 层对补充字符直通。修复时必须用 Go 实录（含 emoji 的响应）
    钉字节，并回归全部既有 golden。本批以 ASCII avatar 种子规避。
  - **org 模块的响应投影是"Go struct 序 + gin.H 字母序"混合作业**：外层 gin.H
    字母序、内层 struct 声明序、CustomAgent 的 jsonb 列还要**重排回 struct 序**
    （jsonb 读出是 PG 规范化键序的 LinkedHashMap，直接序列化会字节 DIFF）。
  - **Go 文案错配也是契约**：RequestRoleUpgrade 的 handler 文案与 service 文案
    不匹配 → 实际真录出 500（"tenant is not a member..." 等）——不要"修好"成
    自洽文案，逐字复刻（§8 如果遇到不确定的：实测优先）。
  - **共享 KB 读面走 Preload raw**（无 EnsureDefaults）：indexing NULL → 三 false
    结构、storage_backend_id omitempty 缺键——与 KB 常规读路径刻意不同。
  - **守卫映射**：OwnedKBOrAdmin/OwnedAgentOrAdmin 在 Go 无角色门（下限 VIEWER，
    判定在 handler 内、API-Key 主体短路路由 guard）——Java 侧 rbac 规则登记
    VIEWER 下限 + 控制器内所有权判定，shares 组 API key 仅 fullAccess。
- **波 3 agents 批补充（含 emoji 修复的完整因果链）**：
  - **emoji 缺陷根因（升级不可解）**：协作批上报后，本批定位为 Jackson
    `UTF8JsonGenerator._outputMultiByteChar` 对补充字符（>0xFFFF）硬编码按
    UTF-16 代理对写 `\uD83E\uDD16` 形态——2.17/2.18/2.19/2.20 四版实测一致，
    升级依赖救不了；`CharacterEscapes` 只能"加"转义不能"免"转义，是死代码。
  - **修法：改道 Writer**。新增 `common.web.GoWriterJsonFactory`：HTTP ObjectMapper
    的 jsonGenerator 工厂换成 WriterBasedJsonGenerator（HttpServletResponse 的
    Writer 按 UTF-8 编码，代理对经 Writer 输出 raw UTF-8 字节，与 Go 一致）。
    `& < > &`、控制字符、中文的转义两路径本就相同，GoJsonEscapes 的
    CharacterEscapes 继续挂（Writer 路径下 escape 代码仍生效）。**修复被
    golden 直接钉住**（内建 agent avatar 📚/📊），org/knowledge/session/wiki
    全部逐字节套件回归全绿——任何字节形态改动都会被既有 golden 抓住。
  - **教训**：上报"跨横切缺陷"时若给出根因定位与修法候选，下一个批次就能
    顺手修掉而不是无限期推迟——缺陷闭环时间 = 上报质量 × 有人接。
  - **initialization 的 Go 既有行为照抄未修**：POST initialize 建的 model 行
    **tenant_id=0**（GET config 按租户回读找不到 → llm/embedding 键缺席——
    疑似 Go 的 bug，但 golden 钉的是行为不是意图）；404 是守卫层
    "knowledge base not found"（handler 文案不可达，陷阱 §5.5 复发）。
  - **vendor yaml 的启动装载**：builtin_agents/prompt_templates/type_presets
    三个 yaml 自 Go config vendor 进 resources，YAML 未知键按 Go 强类型
    Unmarshal 语义丢弃（Jackson FAIL_ON_UNKNOWN_PROPERTIES=false 等价）。
- **波 3 browserskill 批补充**：
  - **引擎级路由的中间件链**：local-browser 三条注册在 Auth 之前（router.go 引擎级），
    Java 侧 AuthFilter 的 NO_AUTH_API 放行清单承载同一语义（X-API-Key 同样被忽略）；
    /me/browser 三条是 v1 标准链但**无角色守卫**（RbacInterceptor pattern 不含 /me/**）。
  - **真实服务器状态跨请求持久 → 契约测试必须单方法**：pair/authorize/revoke 是
    有状态收敛链，拆 @Test 会被 @BeforeEach 的 resetData 清状态造成假红——
    首轮实测教训；用注释固化原因再合并流程。
  - **录制部署形态进脚本头注释**：BROWSERSKILL_BINARY=/usr/bin/false +
    CLUSTER_SECRET + EXTENSION_PATH 是 golden 的前提（禁用态在更早分支 503，
    任务书预期的"无票据失败族"需要 Enabled=true 才可达——由 manager 级单测钉）。
  - **download golden 的字节锚在 dev PG 外部文件**（/tmp/weknora-bs-ext/*.zip，
    Last-Modified 头）：删除该文件需重录 download golden。
  - **既有容器噪音备案**（非本批引入）：Content-Type 的 OWS 被 Tomcat 规范化、
    Vary×3、status line 无 reason phrase（ab 脚本归一化，g5 批先例）、OPTIONS
    预检无 Origin 时 Java 200 vs Go 404。
  - **堆曲线**：4g 撑了协作/agents 两批，browserskill 批后 4g 在 MyBatis XML
    解析处 OOM → 5g。治本仍是收敛上下文变体。
