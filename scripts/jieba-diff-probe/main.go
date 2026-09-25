// jieba-diff-probe：用 Go 侧（gse 内嵌词典 + SDK 同参数）对语料产出 token 基准，
// 供 Java 侧 jieba 移植做**逐 token 差分**（语料 = 同目录 corpus.txt）。
//
// 只用 gse（不带 SDK）：SDK 的 `JiebaTokenizer.Tokenize` 就是
// `seg.Cut(sentence, useHmm=true)` + `len(word)==0 || word==" " || IsStop(word)` 过滤
// （tcvdbtext/tokenizer/jieba_tokenizer.go:38-46,122-135），本探针**等价复刻**该 8 行，
// 以免探针引入 murmur3 等无关依赖（那条链在离线 module cache 里缺 zip）。
//
// 参数面照 SDK：LoadNoFreq=true / useHmm=true / forSearch=false / cutAll=false /
// 停用词 = tcvdbtext.DefaultStopWordsFileName（COS，本地缓存于 DefaultStorageDir）。
//
// 用法（离线可跑：gse/cedar 的 zip 在 module cache 里）：
//
//	GOFLAGS=-mod=mod GOSUMDB=off GOPROXY="file://$(go env GOMODCACHE)/cache/download" \
//	go run . -out ../../server/src/test/resources/jieba/jieba_baseline.json
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"

	"github.com/go-ego/gse"
)

type dictStats struct {
	TotalFreq   float64 `json:"totalFreq"`
	NumTokens   int     `json:"numTokens"`
	MaxTokenLen int     `json:"maxTokenLen"`
}

type findProbe struct {
	Freq float64 `json:"freq"`
	Pos  string  `json:"pos"`
	Ok   bool    `json:"ok"`
}

type baseline struct {
	Probe      string               `json:"probe"`
	Versions   string               `json:"versions"`
	Dict       dictStats            `json:"dict"`
	FindProbes map[string]findProbe `json:"findProbes"`
	SdkToken   map[string][]string  `json:"sdkTokenize"`
	CutHmmOn   map[string][]string  `json:"cutHmmOn"`
	CutHmmOff  map[string][]string  `json:"cutHmmOff"`
}

func readCorpus(path string) []string {
	f, err := os.Open(path)
	if err != nil {
		panic(err)
	}
	defer f.Close()

	var out []string
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 1<<20), 1<<20)
	for sc.Scan() {
		line := sc.Text()
		if strings.TrimSpace(line) == "" {
			continue
		}
		out = append(out, line)
	}
	if err := sc.Err(); err != nil {
		panic(err)
	}
	return out
}

func main() {
	corpusPath := flag.String("corpus", "corpus.txt", "语料文件（每行一句，空行跳过）")
	outPath := flag.String("out", "", "基准 JSON 输出路径（空则打 stdout）")
	stopPath := flag.String("stopwords", "/tmp/tencent/vectordatabase/data/default_stopwords.txt", "停用词文件")
	flag.Parse()

	sentences := readCorpus(*corpusPath)

	// —— SDK 同参数（jieba_tokenizer.go:38-46）——
	seg := new(gse.Segmenter)
	seg.LoadNoFreq = true
	if err := seg.LoadDict(""); err != nil {
		panic(err)
	}
	if _, err := os.Stat(*stopPath); err == nil {
		if err := seg.LoadStop(*stopPath); err != nil {
			panic(err)
		}
	}

	b := baseline{
		Probe:      "scripts/jieba-diff-probe（gse 内嵌词典，SDK 同参数；Tokenize 等价复刻）",
		Versions:   "go-ego/gse v0.80.3（+ 内嵌 data/dict/zh/{s_1,t_1}.txt）",
		FindProbes: map[string]findProbe{},
		SdkToken:   map[string][]string{},
		CutHmmOn:   map[string][]string{},
		CutHmmOff:  map[string][]string{},
	}
	b.Dict = dictStats{
		TotalFreq:   seg.Dict.TotalFreq(),
		NumTokens:   seg.Dict.NumTokens(),
		MaxTokenLen: seg.Dict.MaxTokenLen(),
	}

	for _, w := range []string{
		"我们", "我们每", "向量", "向量数据库", "数据库", "的", "腾讯", "腾讯云",
		"混合检索", "混合", "检索", "1号店", "RagaGenT", "知识图谱", "图谱",
	} {
		freq, pos, ok := seg.Find(w)
		b.FindProbes[w] = findProbe{Freq: freq, Pos: pos, Ok: ok}
	}

	// SDK Tokenize 等价复刻（jieba_tokenizer.go:122-135）
	tokenize := func(s string) []string {
		if len(s) == 0 {
			return []string{}
		}
		words := make([]string, 0, len(s)/2)
		for _, w := range seg.Cut(s, true) {
			if len(w) == 0 || w == " " || seg.IsStop(w) {
				continue
			}
			words = append(words, w)
		}
		return words
	}

	for _, s := range sentences {
		b.SdkToken[s] = tokenize(s)
		b.CutHmmOn[s] = seg.Cut(s, true)
		b.CutHmmOff[s] = seg.Cut(s, false)
	}

	raw, err := json.MarshalIndent(b, "", "  ")
	if err != nil {
		panic(err)
	}
	raw = append(raw, '\n')

	if *outPath == "" {
		fmt.Print(string(raw))
		return
	}
	if err := os.WriteFile(*outPath, raw, 0o644); err != nil {
		panic(err)
	}
	fmt.Printf("wrote %s（%d 句；dict totalFreq=%v tokens=%d maxLen=%d）\n",
		*outPath, len(sentences), b.Dict.TotalFreq, b.Dict.NumTokens, b.Dict.MaxTokenLen)
}
