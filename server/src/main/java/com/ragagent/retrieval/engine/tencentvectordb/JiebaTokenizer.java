package com.ragagent.retrieval.engine.tencentvectordb;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 腾讯 VectorDB 稀疏向量的默认分词器——对齐腾讯 SDK（tcvdbtext）内置 jieba 分词的
 * <b>实际行为</b>（逐 token 等价）。
 *
 * <h2>SDK 侧到底跑了什么（实测结论，反直觉但已被实证）</h2>
 *
 * <p>SDK 的默认构造是 {@code NewJiebaTokenizer(nil)}，
 * 它做的三件事是：{@code seg.LoadNoFreq = true}、{@code seg.LoadStop(default_stopwords.txt)}、
 * {@code seg.LoadDict("")}。</p>
 *
 * <p><b>而 {@code LoadDict("")} 什么词典都不加载</b>：SDK 传的是<b>非空 varargs</b>
 * （{@code files = [""]}）→ 走 {@code len(files) > 0} 分支 → {@code DictPaths(dictDir, "")} 返回 nil
 * → 日志打出 {@code Warning: dict files is nil.}，且 {@code len(files) == 0} 的兜底分支也不会走。
 * 实证：{@code seg.Dict.TotalFreq() == 0 && NumTokens() == 0}，
 * {@code Find("向量")} → {@code (0, "", false)}（基准见 {@code jieba_baseline.json} 的 {@code dict} 字段）。</p>
 *
 * <p>词典为空 ⇒ {@code calc()} 的 DAG 全是自环（{@code dag[k] == [k]}）⇒ {@code cutDAG} 把所有
 * 单字累积进 buf，最后一次 {@code seg.hmm(bufString, buf)}：{@code Find} 必失败 ⇒ 直接在<b>整串</b>上
 * 跑 HMM Viterbi。所以本仓不需要 jieba 词典，也不需要 HMM 之外的
 * 词典路径——SDK 内嵌的那 8.3MB 词典文件在该路径下<b>是死重量</b>。</p>
 *
 * <h2>对齐的三段</h2>
 *
 * <ol>
 *   <li><b>小写化</b>：SDK 侧 {@code ToLower = true} 恒开 ⇒ {@code cutDAG}
 *       入口整串小写化。Java 用逐码点 {@link Character#toLowerCase(int)}（简单映射，
 *       与 jieba 的逐字符小写化语义一致）。</li>
 *   <li><b>HMM 切分</b>：{@code \p{Han}+} 的每段连字
 *       走 Viterbi 定 B/M/E/S；非连字段用
 *       {@code (\d+\.\d+|[a-zA-Z0-9]+)} 整段直出；两者之间的填充文本按"就近切"整块吐。</li>
 *   <li><b>停用词过滤</b>：SDK {@code Tokenize} 尾部——
 *       {@code len(word)==0 || word==" " || IsStop(word)} 丢弃；{@code IsStop} 只读停用词表
 *       （文件行原样入表、不 trim）。</li>
 * </ol>
 *
 * <h2>Viterbi 的等价点（易错处）</h2>
 *
 * <ul>
 *   <li>发射/转移缺失时取 {@code minFloat = -3.14e100}；</li>
 *   <li>状态转移是 jieba 的 {@code prevStatus}（B←{E,S}、M←{M,B}、S←{S,E}、E←{B,M}），
 *       <b>不是</b>朴素 HMM 的全连接；</li>
 *   <li>并列时按 <b>状态字节降序</b>取胜者（{@code (prob, state byte)} 升序再反转）——即
 *       {@code S(0x53) > M(0x4D) > E(0x45) > B(0x42)}；末尾只在 {@code E}/{@code S} 之间选。</li>
 * </ul>
 *
 * <h2>回归来源</h2>
 *
 * <p>逐 token 基线由 {@code scripts/jieba-diff-probe/}（同参数）生成，落在
 * {@code server/src/test/resources/jieba/jieba_baseline.json}；回归测试
 * {@code JiebaTokenizerDiffTest} 对 {@code cutHmmOn}（裸切分）与 {@code sdkTokenize}（含停用词）
 * 两份基准逐句逐 token 断言。HMM 表由 {@code scripts/gen-jieba-hmm.py} 从上游 jieba 分词库源码机械提取成
 * {@code resources/jieba/hmm_model.json}（发射概率等是上游包内非导出变量，源码是唯一权威表示）。</p>
 */
final class JiebaTokenizer implements TencentVectorDbBm25.Tokenizer {

    private static final Logger log = LoggerFactory.getLogger(JiebaTokenizer.class);

    /** 分词的两条正则；索引语义与字节/字符无关，只作切点。 */
    private static final Pattern REG_HAN = Pattern.compile("\\p{IsHan}+");
    private static final Pattern REG_SKIP = Pattern.compile("(\\d+\\.\\d+|[a-zA-Z0-9]+)");

    /** 状态序（B/M/E/S，与 jieba 约定一致）。 */
    private static final char[] STATES = {'B', 'M', 'E', 'S'};
    private static final int I_B = 0;
    private static final int I_M = 1;
    private static final int I_E = 2;
    private static final int I_S = 3;

    private final Set<String> stopWords;

    JiebaTokenizer(Set<String> stopWords) {
        this.stopWords = stopWords == null ? Set.of() : stopWords;
    }

    @Override
    public List<String> tokens(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String word : cut(text)) {
            // 与 SDK 一致：空串 / 单空格 / 停用词丢弃（只查停用词表）
            if (word.isEmpty() || word.equals(" ") || stopWords.contains(word)) {
                continue;
            }
            out.add(word);
        }
        return out;
    }

    /**
     * 裸切分，等价 SDK 的 {@code seg.Cut(sentence, true)}——含小写化，<b>不含</b>停用词过滤。
     * 供差分测试与排障使用。
     */
    List<String> cut(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String lowered = goToLower(text);
        int[] codePoints = lowered.codePoints().toArray();
        // 空词典时 cutDAG 的两个出口：单字符直接原样，多字符整串进 HMM
        if (codePoints.length <= 1) {
            return List.of(lowered);
        }
        return hmmCut(lowered);
    }

    /** 逐码点小写化（简单映射；与 SDK 的 ToLower 恒开一致）。 */
    private static String goToLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(Character.toLowerCase(cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** HMM 切分（无自定义 reg）。 */
    private static List<String> hmmCut(String text) {
        List<String> result = new ArrayList<>();
        String rest = text;
        while (true) {
            int[] cutLoc = find(REG_HAN, rest);
            if (cutLoc == null && rest.isEmpty()) {
                break;
            } else if (cutLoc != null && cutLoc[0] == 0) {
                result.addAll(internalCut(rest.substring(0, cutLoc[1])));
                rest = rest.substring(cutLoc[1]);
                continue;
            }

            int[] nonCutLoc = find(REG_SKIP, rest);
            if (nonCutLoc == null && rest.isEmpty()) {
                break;
            } else if (nonCutLoc != null && nonCutLoc[0] == 0) {
                String nonCuts = rest.substring(0, nonCutLoc[1]);
                rest = rest.substring(nonCutLoc[1]);
                if (!nonCuts.isEmpty()) {
                    result.add(nonCuts);
                    continue;
                }
            }

            int[] loc = locJudge(rest, cutLoc, nonCutLoc);
            if (loc == null) {
                result.add(rest);
                break;
            }
            result.add(rest.substring(0, loc[0]));
            rest = rest.substring(loc[0]);
        }
        return result;
    }

    private static int[] find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? new int[] {matcher.start(), matcher.end()} : null;
    }

    /** 就近切判定：两边都没有 → null（调用方吐剩余整块）。 */
    private static int[] locJudge(String str, int[] cutLoc, int[] nonCutLoc) {
        if (cutLoc == null && nonCutLoc == null) {
            return null;
        }
        if (cutLoc == null) {
            return nonCutLoc;
        }
        if (nonCutLoc == null || cutLoc[0] < nonCutLoc[0]) {
            return cutLoc;
        }
        return nonCutLoc;
    }

    /** 一段连字 → Viterbi 定 B/M/E/S → 按 B/E/S 切词。 */
    private static List<String> internalCut(String text) {
        int[] codePoints = text.codePoints().toArray();
        byte[] posList = viterbi(codePoints);
        List<String> result = new ArrayList<>();
        int begin = 0;
        int next = 0;
        for (int i = 0; i < codePoints.length; i++) {
            char pos = (char) posList[i];
            if (pos == 'B') {
                begin = i;
            } else if (pos == 'E') {
                result.add(new String(codePoints, begin, i - begin + 1));
                next = i + 1;
            } else if (pos == 'S') {
                result.add(new String(codePoints, i, 1));
                next = i + 1;
            }
        }
        if (next < codePoints.length) {
            result.add(new String(codePoints, next, codePoints.length - next));
        }
        return result;
    }

    /** Viterbi 解码（含并列取胜规则：prob 降序，再状态字节降序）。 */
    private static byte[] viterbi(int[] obs) {
        Model model = model();
        int n = obs.length;
        double[][] vtb = new double[n][STATES.length];
        byte[][] path = new byte[STATES.length][];

        for (int s = 0; s < STATES.length; s++) {
            vtb[0][s] = emit(model, obs[0], s) + model.probStart[s];
            path[s] = new byte[] {(byte) STATES[s]};
        }

        for (int t = 1; t < n; t++) {
            byte[][] newPath = new byte[STATES.length][];
            for (int s = 0; s < STATES.length; s++) {
                double emitProb = emit(model, obs[t], s);
                int best = -1;
                double bestProb = 0;
                char bestState = 0;
                for (int prev : model.prevStatus[s]) {
                    double prob = vtb[t - 1][prev] + model.probTrans[prev][s] + emitProb;
                    char stateChar = STATES[prev];
                    if (best < 0 || prob > bestProb || (prob == bestProb && stateChar > bestState)) {
                        best = prev;
                        bestProb = prob;
                        bestState = stateChar;
                    }
                }
                vtb[t][s] = bestProb;
                byte[] prevPath = path[best];
                byte[] joined = new byte[prevPath.length + 1];
                System.arraycopy(prevPath, 0, joined, 0, prevPath.length);
                joined[prevPath.length] = (byte) STATES[s];
                newPath[s] = joined;
            }
            path = newPath;
        }

        int best = -1;
        double bestProb = 0;
        char bestState = 0;
        for (int s : new int[] {I_E, I_S}) {
            double prob = vtb[n - 1][s];
            char stateChar = STATES[s];
            if (best < 0 || prob > bestProb || (prob == bestProb && stateChar > bestState)) {
                best = s;
                bestProb = prob;
                bestState = stateChar;
            }
        }
        return path[best];
    }

    private static double emit(Model model, int codePoint, int state) {
        double[] byState = model.emit.get(codePoint);
        return byState == null ? model.minFloat : byState[state];
    }

    private static Model model() {
        return ModelHolder.INSTANCE;
    }

    /** HMM 模型（由 {@code scripts/gen-jieba-hmm.py} 提取，见类注释"回归来源"）。 */
    private static final class Model {

        private final double minFloat;
        private final double[] probStart = new double[STATES.length];
        private final int[][] prevStatus = new int[STATES.length][];
        private final double[][] probTrans = new double[STATES.length][STATES.length];
        private final Map<Integer, double[]> emit;

        private Model(double minFloat, double[] probStart, int[][] prevStatus,
                      double[][] probTrans, Map<Integer, double[]> emit) {
            this.minFloat = minFloat;
            System.arraycopy(probStart, 0, this.probStart, 0, probStart.length);
            for (int s = 0; s < STATES.length; s++) {
                this.prevStatus[s] = prevStatus[s];
                System.arraycopy(probTrans[s], 0, this.probTrans[s], 0, probTrans[s].length);
            }
            this.emit = emit;
        }
    }

    private static final class ModelHolder {

        private static final Model INSTANCE = loadModel();
    }

    private static Model loadModel() {
        try (InputStream in = JiebaTokenizer.class.getResourceAsStream("/jieba/hmm_model.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "jieba hmm model resource missing: /jieba/hmm_model.json"
                                + " (regenerate with scripts/gen-jieba-hmm.py)");
            }
            JsonNode root = new ObjectMapper().readTree(in);
            double minFloat = root.path("minFloat").asDouble();

            double[] probStart = new double[STATES.length];
            JsonNode startNode = root.path("probStart");
            for (int s = 0; s < STATES.length; s++) {
                probStart[s] = startNode.path(String.valueOf(STATES[s])).asDouble();
            }

            int[][] prevStatus = new int[STATES.length][];
            JsonNode prevNode = root.path("prevStatus");
            for (int s = 0; s < STATES.length; s++) {
                JsonNode list = prevNode.path(String.valueOf(STATES[s]));
                int[] prev = new int[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    prev[i] = stateIndex(list.get(i).asText().charAt(0));
                }
                prevStatus[s] = prev;
            }

            double[][] probTrans = new double[STATES.length][STATES.length];
            for (double[] row : probTrans) {
                Arrays.fill(row, minFloat);
            }
            JsonNode transNode = root.path("probTrans");
            for (int from = 0; from < STATES.length; from++) {
                JsonNode row = transNode.path(String.valueOf(STATES[from]));
                Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    probTrans[from][stateIndex(entry.getKey().charAt(0))] = entry.getValue().asDouble();
                }
            }

            Map<Integer, double[]> emit = new HashMap<>();
            JsonNode emitNode = root.path("probEmit");
            for (int s = 0; s < STATES.length; s++) {
                Iterator<Map.Entry<String, JsonNode>> fields =
                        emitNode.path(String.valueOf(STATES[s])).fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    int codePoint = Integer.parseInt(entry.getKey());
                    double[] byState = emit.get(codePoint);
                    if (byState == null) {
                        byState = new double[STATES.length];
                        Arrays.fill(byState, minFloat);
                        emit.put(codePoint, byState);
                    }
                    byState[s] = entry.getValue().asDouble();
                }
            }

            log.info("[TencentVectorDB] jieba HMM model loaded: {} runes (source: {})",
                    emit.size(), root.path("source").asText(""));
            return new Model(minFloat, probStart, prevStatus, probTrans, emit);
        } catch (IOException e) {
            throw new IllegalStateException("jieba hmm model load failed: " + e.getMessage(), e);
        }
    }

    private static int stateIndex(char state) {
        for (int s = 0; s < STATES.length; s++) {
            if (STATES[s] == state) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown hmm state: " + state);
    }
}
