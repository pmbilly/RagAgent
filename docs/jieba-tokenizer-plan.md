# jieba 真实分词：移植方案 → **落地记录**（2026-09-25，W5γ5.7）

> 来源：`known-issues/06` 的"腾讯分词接缝 / jieba"备案 + W5γ5.6 的成本勘察。
> 勘察时间 2026-09-25，两侧逐处对账（证据带 `file:line`）。
>
> ✅ **本方案已被实测推翻并落地**（正文见 `known-issues/06-wave-5.md` 的 W5γ5.7、HANDOFF §0.-43）：
> 第 2 节"资产策略 a/b/c"与第 4 节"J1/J2/J3 切分"**作废**——Go 的 `LoadDict("")` 实为**空词典**
> （非空 varargs 走错分支 ⇒ 8.3MB 词典是死重量），移植只剩 HMM 一段：
> `JiebaTokenizer`（1 类）+ `resources/jieba/hmm_model.json`（1.15MB，脚本从 gse 源码提取），
> **零词典资产、无 BM25 基准重录**；验证改为**逐 token 差分**（Go 探针 20 句全一致）。
> 下文保留原始勘察供追溯。

## 0. 结论速览

| 项 | 事实 | 影响 |
|---|---|---|
| Go 用什么 | `go-ego/gse` v0.80.3（**3392 行**，含 `hmm/prob_emit.go` **1.1MB** 发射表）+ 内嵌词典 `zh/s_1.txt`+`zh/t_1.txt`（**8.3MB**） | 稀疏向量的 token 集由它决定 |
| 本仓现状 | 接缝齐备：`TencentVectorDbBm25.Tokenizer` + `SeamTokenizer`（`SearchTextUtil.segmenter()` 近似：空白 + CJK 二字滑窗）；停用词已从 COS 下载并过滤（同 Go 语义） | 走出**与 Go 不同源**的稀疏向量 → 同集合不可互通（Go 存量需重导入） |
| **隐蔽差异（本次新发现）** | Java 接缝用的是 **`cutForSearch`**（搜索模式 = 交叠子词），而 Go 侧 `forSearch=false` → 走 **`Cut(sentence, true)`** | **即使换成真 jieba，不一起改切分模式仍不同源** |
| 建议 | 走 **J1→J2→J3 三段**（下表），词典/表走"下载+缓存 + 一次性生成资源"，**缺资产时回退近似接缝**（行为零回归） | — |

## 1. Go 侧的精确算法面（要复刻的最小面）

`tcvdbtext/tokenizer/jieba_tokenizer.go:38-46,126-133` 的配置与调用：

| 参数 | 值 | 含义 |
|---|---|---|
| `LoadNoFreq` | **true** | 词典不带词频（`gse` 的 no-freq 分支——`calc` 的取路语义要按此复刻，别用带频版） |
| `useHmm` | **true** | 单字串（DAG 未命中的连续片段）走 HMM Viterbi（`dag.go:242,262` 的 `seg.hmm(...)`） |
| `forSearch` / `cutAll` | **false / false** | 走 `Cut(sentence, true)`（**不是** `cutForSearch`/`CutAll`） |
| `StopWordsEnable` | true | `IsStop(word)` 过滤 + `len(word)==0 \|\| word==" "` 过滤（`jieba_tokenizer.go:133`） |
| 词典 | `LoadDict("")` → 内嵌 `loadZh()` = `zhS + zhT`（`dict_1.16.go:34-36`） | `s_1.txt` 4.9MB + `t_1.txt` 3.4MB |
| 停用词 | COS 的 `default_stopwords.txt` | **本仓已实现**（`TencentVectorDbBm25.java:69,339`）✓ |

移植面（gse 逐个文件的行数）：`dag.go` 430（`getDag`/`calc`/`cutDAG`/`cutDAGNoHMM`）+ `segmenter.go` 286（状态/选项）+ `dict_util.go` 503（词典装载，含 no-freq）+ `seg_utils.go` 244（runes/正则/字母数字判定）+ `stop.go` 108 + `hmm/`（`viterbi.go` 134 + `prob_trans.go` 21 + **`prob_emit.go` 1.1MB 表**）。
**估算**：Java 主体 **约 1000~1500 行**（`Cut`+DAG+no-freq+HMM 子集）+ 资产。

## 2. 资产策略（**唯一待决项**）——❌ 作废：实测词典恒空，无需任何词典资产

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **a（推荐）** | 词典**下载 + 缓存**：URL 取 gse 仓库 raw（`data/dict/zh/{s_1,t_1}.txt`），落到既有缓存目录 `/tmp/tencent/vectordatabase/data/`（照 `bm25_zh_default.json` 85MB 的同款机制，`TencentVectorDbBm25.java:38-40`）；**拉不到 → 回退近似接缝** | 与既有机制一致、仓内零大文件、离线环境行为不变（回退） | 首次运行需网络；需 pin 版本+校验（哈希） |
| b | 词典 vendor 进仓（8.3MB 文本） | 离线可用 | 仓体积 +8.3MB，与"仓内零二进制/大资产"惯例冲突 |
| c | 运营提供路径（env），缺则回退 | 最保守 | 部署负担；默认路径下仍不同源 |

**HMM 表**（`prob_emit.go` 1.1MB Go 字面量）另有一次性处理：写脚本从 gse 源码**机械提取**成紧凑资源（`resources/jieba/hmm_*.txt`，估 <1MB），随代码走（它没有可下载的独立 URL）。
**停用词**沿用现有 COS 下载 ✓ 不改。

## 3. 验证方案（★差分测试，强于"逐值对照"）

1. **Go 侧基准**：`scripts/jieba-diff-probe/`（小 Go module，依赖已在 module cache，`GOPROXY=off` 可跑）——用 SDK 的 `JiebaTokenizer`（同参数）对语料输出 **token 序列 JSON**，存进 `server/src/test/resources/jieba/`。
   语料覆盖：纯中文短句 / 中英混合 / 数字+单位 / 专名与 OOV（HMM 分支）/ 标点与空白 / emoji / 长文档段落。
2. **Java 侧断言**：`JiebaTokenizerDiffTest` —— 逐 token 相等（含顺序与切分模式）；**词典缺失时跳过并打印回退提示**（与运行期行为一致）。
3. **端到端一条**：同一文档的 BM25 稀疏向量（token→murmur3→权重）与 Go 基准逐值一致（复用 `TencentVectorDbBm25Test` 的对照骨架）。

## 4. 批次切分与验收

| 批 | 内容 | 验收 |
|---|---|---|
| **J1** | 资产脚本（HMM 表提取 + 词典下载缓存）+ `Seg` 词典装载（no-freq）+ `getDag`/`calc` + **`cutDAGNoHMM` 分支** + 停用词/空白过滤 | 差分测试：**无 HMM 命中的语料子集**逐 token 一致 |
| **J2** | HMM Viterbi（`prob_emit`/`prob_trans`）→ 完整 `Cut(s, true)` | 差分测试**全集**逐 token 一致 |
| **J3** | 接入 `TencentVectorDbBm25`（`SeamTokenizer` → `JiebaTokenizer`，**同时把 `cutForSearch` 改回 `Cut`**）；`SearchTextUtil` 之外不动 | BM25 端到端逐值一致 + `--changed`（域=retrieval → B4）全绿；文档划账 |

**受影响测试**：`TencentVectorDbBm25Test`（"逐值对照 Go"里的 token 相关期望）、`TencentVectorDbRetrieveRepositoryTest`（注入表，预期不动）、`known-issues/06` 备案行 + `translation-log`。

## 5. 待决（1 项）

- **资产策略选 a / b / c？**（推荐 **a**：与 85MB 参数文件同款机制；词典 URL 与哈希我会 pin 在常量里，拉不到即回退近似接缝、行为零回归。）
- HMM 表资源随代码走（不可下载）——若不接受仓内 ~1MB 资源，则整个方案退化为"只做 J1（无 HMM）"，**不推荐**（OOV 仍不同源）。
