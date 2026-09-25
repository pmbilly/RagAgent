# 检索批收官报告（W5γ4.1 ~ W5γ4.17，2026-09-25）

> 本文是**一次性交付**的收敛性报告：范围、总表、逐店差异备案、共性（接线批）、待真机联调清单、
> 复现命令与遗留。日常推进仍以 `HANDOFF.md` 为准；坑的明细在 `known-issues/06-wave-5.md`。

---

## 0. 一页结论

- **范围**：Go `internal/application/repository/retriever/` 下的 **10 个目录**
  （doris / elasticsearch / milvus / neo4j / opensearch / postgres / qdrant / sqlite /
  tencentvectordb / weaviate）。
- **结论**：
  - **9 个向量店全部落地**（ES v7/v8、OpenSearch、Doris、Qdrant、Weaviate、Milvus、
    腾讯 VectorDB、SQLite）；
  - **postgres/pgvector** 不走本批驱动族（读 `PgVectorRetrieveRepository` + 写
    `VectorStoreService`，既有 golden 锁定的 JDBC 专用件）；
  - **neo4j 不是向量店**：Go 侧实现的是 `interfaces.RetrieveGraphRepository`
    （Labels/Label/AddGraph/DelGraph/SearchNode，236 行），**在更早的图管线批已落地**
    （Java `retrieval/graph/Neo4jGraphRepository implements PipelinePorts.RetrieveGraphRepository`，
    方法面一一对应，由 `QaWiring` 装配）。
  - ⇒ **本批范围全部收官**；五批验收（`scripts/acceptance.sh`）PASS。
- **提交链**（`main`）：

| 批 | 提交 | 主题 |
|---|---|---|
| W5γ4.1 | `59a361e` | ES v8 驱动（第 1 支，纯 HTTP 打样） |
| W5γ4.2 | `469a5a7` | ES v7 落地 + 补 v8 的 move |
| W5γ4.3 | `0f52bc7` | **修复三处 Go 侧向量缺陷**（有意偏离，见 §3） |
| W5γ4.4~6 | `5ccc7e1` `82f36b2` `1a0de3f` | 接线批：端口/工厂 → 注册表/复合引擎 → ChunkService 接线 + HybridSearch 路由 + normalizer |
| W5γ4.7/8 | `b2138ee` `f4ce345` | 写链改道 + 启动恢复；管家清扫 + move reparse（follow-up 清零） |
| W5γ4.9 | `d3b51fa` | OpenSearch k-NN（HTTP 族收官） |
| W5γ4.10 | `3dc4a0e` | Doris（SQL 族收官） |
| W5γ4.11 | `cfcc851` | Qdrant（gRPC 族首支，REST 自持） |
| W5γ4.13 | `53d1fa4` | Weaviate（REST 自持 + **真服务端 IT**） |
| W5γ4.14 | `79bf009` | Milvus（REST v2 自持 + **真服务端 IT**） |
| W5γ4.15 | `693ff2d` | 腾讯 VectorDB（HTTP 自持 + 客户端 BM25） |
| W5γ4.16/17 | `233707a` | SQLite（九店收官）+ 备案小账批 |

- **规模**：Go 侧 9 店非测试 **12,299 行**（+ postgres/neo4j 不计）；Java 侧新增驱动 **≈ 12,850 行**（main）+ 测试 **≈ 6,100 行**（test；8 个驱动目录合计
  181 条测试方法，另加接线/工厂/契约/回归批）。

---

## 1. 九店对照总表

| 店 | Go 规模 | Go 协议 → 本仓口径 | 索引/写入模型 | 关键词面 | 验证方式 | 真服务端 IT |
|---|---|---|---|---|---|---|
| **ES v8** | 2457 行 / 5 文件（v7+v8 同一目录） | 官方 typed client（HTTP） → 自持 HTTP/JSON | 单索引 + dense_vector | BM25（`match` on `content`，照 Go 口径） | stub HTTP 逐请求 + 契约测试（21 条） | 无（留待部署） |
| **ES v7** | 同上 | 同 v8（版本化 API 面） | 同上 | 同上 | 同上 | 无 |
| **OpenSearch** | 2380 行 / 15 文件 | 自有 transport（HTTP） → 自持 HTTP/JSON | 单索引 + k-NN | BM25 | stub HTTP（14 条）+ 构造期版本/k-NN 插件探针 | 无 |
| **Doris** | 2108 行 / 7 文件 | `go-sql-driver/mysql`（SQL）+ FE HTTP Stream Load → **MySQL JDBC + 自持 Stream Load** | 按维度分表 `<base>_<dim>`；UNIQUE KEY(MoW) 或 DUPLICATE KEY | `MATCH_ANY`（SQL） | stub SQL 执行口（39 条）；**DDL 双制表符照抄** | 无（legacy partial update 待实机） |
| **Qdrant** | 1068 行 / 3 文件 | gRPC → **REST 自持** | 按维度分集合 + payload 索引六件 | `scroll` + `match.text`（分词接缝） | stub REST（18 条） | 无 |
| **Weaviate** | 1174 行 / 3 文件 | v5 客户端（REST+GraphQL，batch 走 gRPC） → **REST 自持** | 类 + 命名向量 | 服务端 BM25（需 `ENABLE_TOKENIZER_GSE=true`） | stub REST 25 条 + **真服务端 IT** 1 条（容器命令见 known-issues） | ✅ Weaviate 1.28.4 |
| **Milvus** | 1562 行 / 4 文件 | milvus-sdk-go v2（gRPC） → **REST v2 自持** | 按维度分集合；BM25 函数 + 稀疏列 | 服务端 BM25 全文（文本进 `data`） | stub REST 26 条 + **真服务端 IT** 1 条 | ✅ Milvus 2.6.11 |
| **腾讯 VectorDB** | 872 行 / 3 文件 | SDK RpcClient（集合/文档走 gRPC） → **HTTP 面自持** | 单集合或维度后缀（开关）；sparse_vector | **客户端 BM25**（murmur3 + 语料统计 85 MB 参数表） | stub HTTP（20 条）+ **BM25 对 Go SDK 基准逐值对照** | 无（云服务，待凭据） |
| **SQLite** | 678 行 / 2 文件 | GORM + sqlite-vec（CGO，**挂在产品库**） → **独立 SQLite 文件** + xerial JDBC | 三表（元数据 / FTS5 / 向量） | FTS5 + **CJK 二元切分** | **真实 SQLite 文件** 11 条 + 纯函数 5 条 | ✅ 本地文件即真机 |
| **postgres/pgvector** | （不计入本批） | pgvector + ParadeDB BM25（SQL） | 单表 + partial HNSW | ParadeDB `|||` | golden 锁定 + A/B（既有） | ✅ 产品库 |

---

## 2. 逐店差异备案（**跨店最容易踩的都在这里**）

### ES v7 / v8
- **有意偏离 3 处**（W5γ4.3，修的是 Go 侧缺陷，不把 bug 抄进 Java，详见 §3）。
- 其余照 Go：索引命名/mapping/写入/检索/拷贝/move 全链；真机联调留待部署。

### OpenSearch
- 自持 HTTP 与构造期**版本 + k-NN 插件探针**；索引名 ≥16 字符规则由驱动强制（照 Go）。
- 无真例（stub 面全绿，同 ES 口径）。

### Doris
1. **SSRF 姿态**：Go 注册全局 MySQL dialer 逐连接校验；本仓**构造期校验一次**（同 ES/OpenSearch）。
2. **`Expect: 100-continue` 不可设**（JDK HttpClient 禁设头；Doris 不依赖）。
3. **只跟随 307/308**（Go 会跟随 301/302/303 并改写 GET——Stream Load 无观测面）。
4. 浮点字面量按 Go `FormatFloat('g',-1,32)` 形态；`keywordsRetrieve` 的 `TopK≤0` Go 会 panic → 本仓 clamp 0。
5. **DDL 里的双制表符是 Go 原文形状**（"顺手对齐缩进"就会分叉）。
6. `Publish Timeout` 是**成功**状态；legacy 的 partial update 需实机验证。
7. env-path **不做探针**（照 Go：只注册，不建表不通连）。

### Qdrant
1. gRPC → REST（端点映射见 known-issues；不可等价点已列）。
2. `wait` 只在 move 上带（照 Go）；Upsert/Delete 不带。
3. 分词降级（jieba 缝 + 二次空白切分补丁）；`TopK≤0` clamp 0。
4. 无真例。

### Weaviate
1. **`tokenization:"gse"` 需服务端 `ENABLE_TOKENIZER_GSE=true`**（1.28.4 默认关，否则建类 422）——~~**Go 仓 compose 缺这一项**（跨仓提案，见 §5）~~ ✅ **已回填并提交（Go 仓 `0277521f`，2026-09-25）**。
2. `after`+`where` 被服务端拒 → 本仓改 `where + limit + offset`；命名向量类下 `_additional{vector}` 恒空 → 改 `vectors{embedding}`。
3. **无 merge 的 PUT 会清掉未提供属性与向量**（Go 两处批量更新是数据丢失缺陷）→ 本仓改 **PATCH merge**。
4. **BM25 的 score 是字符串**（Go 的 float64 断言恒失败 → 关键词分恒 0）→ 本仓按代码意图取 1.0。
5. 真服务端 IT 已跑（容器命令在 known-issues W5γ4.13 段）。

### Milvus
1. 稀疏列 REST 拼写是 `SparseFloatVector`（SDK 叫 SparseVector）。
2. **REST v2 无模板参数**（`filterParams` 被忽略）→ 过滤值**内联**（算子/括号/转义照 Go）。
3. `dbName` 走**请求体**；**`shardsNum` 被服务端忽略**（照传保留配置面，已钉测试）；`load` 是同步调用。
4. **enabled 更新失败聚合冒泡**（`errors.Join`）vs **tag 更新失败只 WARN**——两条别统一。
5. **move 的"失败换重试"是设计**（重复 ID = 更新未可见，抛错让调用方重试）。
6. **中文关键词按 CJK 连段切分**（标准分析器，非 jieba；Go 同款）——查询词形要与文本分段一致。
7. 真服务端 IT 已跑（Milvus 2.6.11）。

### 腾讯 VectorDB
1. Go 的 RpcClient 集合/文档走 **gRPC**；本仓自持 **HTTP 面**（`Bearer account=…&api_key=…`）。
2. **BM25 是客户端算的**：murmur3 32 位 + tf/idf 归一 + 85 MB 语料统计（运行时下载缓存于
   `/tmp/tencent/vectordatabase/data/`）；**哈希与权重已对 Go SDK 基准逐值一致**。
3. **分词接缝**：默认分词非 jieba → **稀疏向量与 Go 存量数据不互通**（同集合需同一实现；
   Go 迁移来的数据需**重导入**；接真实 jieba 后即互通）。
4. 过滤语法 `key in ("a","b")` 且 `is_enabled=1`（uint64）；集合命名有**开关**（单集合 vs 维度后缀）。
5. 批量更新走 **Update API**（任一集合失败即抛）；关键词**全部集合失败**才报错（提示重导入）。
6. 拷贝第 3 态 SourceID = **sha256 前 16 hex**（Milvus/Doris 是"新 UUID"——各店不同）。

### SQLite
1. **介质不同**：Go 挂产品库（单二进制模式）；本仓独立 SQLite 文件（产品库是 PG）。
2. `vec0` → 普通表 + Java 标量函数 `vec_distance_cosine`（**平面扫描**；cosine 的 KNN 结果相同）。
3. 写入 **`INSERT OR IGNORE`**（重复 source 二次写**静默忽略**，与其它店的覆盖写相反）。
4. 过滤**只有三个 IN**（无排除项）；检索分派特例（**空类型两条都跑**、**未知类型不报错**）。
5. contentless FTS5 **不存原文**（`SELECT content` 恒 NULL）。
6. **WAL 快照坑**：写事务里必须**同连接建表**。

---

## 3. 有意偏离：三处 Go 侧缺陷的修复（W5γ4.3）

按"发现的问题需要修复"的指示，本仓**不复制 bug**：

| # | 缺陷（ES v7/v8） | 修法 |
|---|---|---|
| ① | v7 `CopyIndices`：`embeddingMap` 是新建空 map（收集的向量被丢弃）→ 复制过去的文档**不带向量** | 向量随 `CopiedHit` 回到 copyIndices，按**目标 SourceID** 为键写进 `additionalParams.embedding` |
| ② | v7 `processHit`：恒传 `MatchTypeKeywords` → 向量结果也标 1 | 按实际检索类型给（vector → embedding） |
| ③ | v8 `CopyIndices`：以**目标 chunkID** 为键、而查表按 **SourceID** → 生成型问题取不到向量、同 chunk 多文档互相覆盖 | 键改目标 SourceID |

测试上三条断言从"照抄缺陷"翻转为"修复后行为"。

---

## 4. 接线批（驱动之外的那一半）

| 步 | 内容 | 批 |
|---|---|---|
| 1 | 引擎端口 + KV 包装层 + 引擎工厂 | W5γ4.4 |
| 2 | 注册表 + 复合引擎 + 工厂函数 | W5γ4.5 |
| 3 | `ChunkService` 引擎创建接线 + Embedder 适配器 | W5γ4.6 |
| 4 | `HybridSearch` store-group 路由 + normalizer + KB 绑定校验 | W5γ4.6 |
| 5 | 写链改道引擎口（syncChunkIndex/updateChunkVector/FAQ/删除/clone-move） | W5γ4.7 |
| 6 | 启动恢复（`reset_pending_tasks`）+ 知识管家清扫 + move reparse | W5γ4.7/8 |

**验收面**：`scripts/acceptance.sh` 五批（B1a/B1b/B2/B3/B4）——W5γ4.10 起
`com.ragagent.retrieval.*` 与 `com.ragagent.config.*` 已并入 B4（此前"全量"不含检索引擎域，
见 known-issues W5γ4.10 第 9 条）。

---

## 5. 待真机联调清单（部署前必做）

| 店 | 待验证 | 前置/操作 |
|---|---|---|
| ES v7/v8 | 建索引/mapping、写入、检索、拷贝（含修复后的向量回填）、move | 真 ES 集群（v7 与 v8 各一） |
| OpenSearch | k-NN 检索、版本/插件探针、索引命名规则 | 真 OpenSearch 集群（含 k-NN 插件） |
| Doris | **legacy partial update（Stream Load merge_type=APPEND）**、ANN 索引就绪轮询、DUPLICATE KEY 分支 | 真 Doris（FE 8030 + MySQL 9030） |
| Qdrant | REST 与 gRPC 行为差异面（scroll/search/过滤）、`wait` 语义 | 真 Qdrant 实例 |
| Weaviate | ✅ 已跑 IT；**部署项：给 Weaviate 容器加 `ENABLE_TOKENIZER_GSE=true`** | 容器命令见 known-issues W5γ4.13 |
| Milvus | ✅ 已跑 IT；**业务决策：中文召回是否配自定义分析器/写入时空格化**（当前按 CJK 连段切分） | 容器命令见 known-issues W5γ4.14 |
| 腾讯 VectorDB | 全链（建集合/写入/检索/拷贝/move）；**存量 Go 数据需重导入**（分词接缝） | 云实例 `TENCENT_VECTORDB_ADDR/USERNAME/API_KEY/DATABASE` |
| SQLite | ✅ 本地文件即真机；**若要迁移 Go 存量**：写一次搬迁（Go 的 `lite_embeddings` → 本仓独立文件，含 FTS 二元回填与向量 BLOB 转换） | — |
| 全店 | `RETRIEVE_DRIVER` 环境变量注册面 + 绑定 store 的 KB 读写路由 | dev 栈 + 真 PG |

---

## 6. 复现命令合集

```bash
# 五批验收（必跑）
cd /Users/billy/ragagent-java && ./scripts/acceptance.sh

# Weaviate 真服务端 IT
docker run -d --name WeKnora-weaviate-local -p 9035:8080 -p 50052:50051 \
  -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true -e ENABLE_TOKENIZER_GSE=true \
  cr.weaviate.io/semitechnologies/weaviate:1.28.4
WEKNORA_WEAVIATE_IT=true ./gradlew :server:test --tests "*Weaviate*IT*"

# Milvus 真服务端 IT
docker run -d --name WeKnora-milvus-local --security-opt seccomp=unconfined \
  -p 19530:19530 -p 9091:9091 -e ETCD_USE_EMBED=true \
  -e ETCD_DATA_DIR=/var/lib/milvus/etcd -e COMMON_STORAGETYPE=local \
  -e DEPLOY_MODE=STANDALONE milvusdb/milvus:v2.6.11 milvus run standalone
WEKNORA_MILVUS_IT=true ./gradlew :server:test --tests "*MilvusDriverLocalIT*"

# SQLite（真机即文件；测试自带临时库，无需外部依赖）
./gradlew :server:test --tests "com.ragagent.retrieval.engine.sqlite.*"
```

---

## 7. 遗留（不属本批范围）

1. **备案小账**（W5γ4.17）：weknoracloud VLM（云 API，需凭据）、E2E 两个观察项
   （早错 SSE 不收流 / `list_sandbox_files` 注册时机，**待复跑抓现象**）、
   ~~跨仓提案（Go 仓 compose 的 `ENABLE_TOKENIZER_GSE`）~~ ✅ 已提交（`0277521f`）、~~jieba 真实分词接缝~~ ✅ 已落地（W5γ5.7）
   （接入后腾讯稀疏向量与 Go 存量互通、Qdrant/Weaviate/Milvus 关键词面同步改善）。
2. **provider-XDEP 族**：W5δ 终端 PTY、`initialize` 契约对齐、存储三条备案。
3. **产品库引擎（postgres）**：读路径 golden 已锁；若今后要改 SQL，先跑 A/B。
