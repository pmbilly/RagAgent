package com.ragagent.knowledge.textconv;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 繁转简转换器 + FAQ 归一化纯函数的对照测试。期望值全部是 <b>Go 实录</b>
 * （把 internal/textconv + types/faq.go 的纯函数原样抄进独立 Go 程序跑出来的
 * 输出，/tmp/faqconv/corpus.txt；SHA-256 校验过的词典 + Go 的贪心最长匹配）。
 *
 * <p>词典数据文件与 Go 仓 data/ 一字不差（SHA-256 见 data/README.md 记录、
 * 两个文件均已核对）——词典更新会改变 FAQ 归一化与 content_hash，
 * 更新前先更新这里的 Go 实录语料。</p>
 */
class TextConvTest {

    private static final String[] CONV_INPUTS = {
            "怎麼綁定手機？",
            "軟體怎麼下載",
            "簡體轉換：環境變量設置，人才招聘，皇后於是",
            "乾乾淨淨的乾隆年間",
            "面髮與頭髮",
    };

    private static final String[] CONV_EXPECTED = {
            "怎么绑定手机？",
            "软体怎么下载",
            "简体转换：环境变量设置，人才招聘，皇后于是",
            "干干净净的乾隆年间",
            "面发与头发",
    };

    /** NORM 语料：{输入 → NormalizeQuestion 输出}（Go 实录）。 */
    private static final String[][] NORM_CORPUS = {
            {"怎麼綁定手機？", "怎么绑定手机"},
            {"軟體怎麼下載", "软体怎么下载"},
            {"簡體轉換：環境變量設置，人才招聘，皇后於是", "简体转换:环境变量设置,人才招聘,皇后于是"},
            {"乾乾淨淨的乾隆年間", "干干净净的乾隆年间"},
            {"面髮與頭髮", "面发与头发"},
            {"  Hello World  ", "hello world"},
            {"怎麼 綁定 手機", "怎么绑定手机"},
            {"iphone 15 怎麼 激活", "iphone 15怎么激活"},
            {"访问 https://example.com/a?b=1 获取", "访问获取"},
            {"問題？！。，；、！?.,;!:'\"", "问题"},
            {"全角ＡＢＣ１２３：", "全角abc123"},
    };

    @Test
    void toSimplified_matchesGoCorpus() {
        for (int i = 0; i < CONV_INPUTS.length; i++) {
            assertThat(TextConv.toSimplified(CONV_INPUTS[i]))
                    .as("ToSimplified(%s)", CONV_INPUTS[i])
                    .isEqualTo(CONV_EXPECTED[i]);
        }
    }

    @Test
    void normalizeQuestion_matchesGoCorpus() {
        for (String[] pair : NORM_CORPUS) {
            assertThat(com.ragagent.knowledge.domain.FaqChunkMetadata.normalizeQuestion(pair[0]))
                    .as("NormalizeQuestion(%s)", pair[0])
                    .isEqualTo(pair[1]);
        }
    }

    @Test
    void contentHash_matchesGoCorpus() {
        // Go 实录：CalculateFAQContentHash(aa0f3822...)
        com.ragagent.knowledge.domain.FaqChunkMetadata meta =
                new com.ragagent.knowledge.domain.FaqChunkMetadata();
        meta.standardQuestion = "怎么 绑定 手机？";
        meta.similarQuestions = java.util.List.of("如何绑定手机", "How to bind phone");
        meta.negativeQuestions = java.util.List.of("怎么解绑手机");
        meta.answers = java.util.List.of("进入设置，选择设备，点击绑定。");
        meta.answerStrategy = "all";
        meta.version = 1;
        meta.source = "faq";
        assertThat(com.ragagent.knowledge.domain.FaqChunkMetadata.calculateContentHash(meta))
                .isEqualTo("aa0f3822df446266592d86568ac5440c3fcfb388161730e043132f306bbbc44e");

        // 相似问顺序不影响 hash（排序后进串）
        com.ragagent.knowledge.domain.FaqChunkMetadata reordered =
                new com.ragagent.knowledge.domain.FaqChunkMetadata();
        reordered.standardQuestion = "怎么 绑定 手机？";
        reordered.similarQuestions = java.util.List.of("How to bind phone", "如何绑定手机");
        reordered.answers = java.util.List.of("进入设置，选择设备，点击绑定。");
        reordered.answerStrategy = "all";
        reordered.version = 1;
        reordered.source = "faq";
        assertThat(com.ragagent.knowledge.domain.FaqChunkMetadata.calculateContentHash(reordered))
                .isEqualTo("4779365a6b2bd1f57353fee4c5eefd56485d10976062c73ae30aff3ed76fc5da");
    }

    @Test
    void sanitize_matchesGoSemantics() {
        com.ragagent.knowledge.domain.FaqChunkMetadata meta =
                new com.ragagent.knowledge.domain.FaqChunkMetadata();
        meta.standardQuestion = "  x  ";
        meta.similarQuestions = new java.util.ArrayList<>(java.util.List.of(" a ", "", " a ", "b"));
        meta.version = 0;
        meta.sanitize();
        assertThat(meta.standardQuestion).isEqualTo("x");
        assertThat(meta.similarQuestions).containsExactly("a", "b");
        assertThat(meta.version).isEqualTo(1);
    }
}
