package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.ragagent.agent.tools.GrepChunksTool.GrepChunkView;

/**
 * grep 检索结果的评分协作者：内容签名去重、正则命中评分（含标题命中）、
 * MMR 多样性重排，以及轻量分词与 Jaccard 相似度。
 */
final class GrepChunksScoring {

    private GrepChunksScoring() {
    }

    /** 对照 deduplicateChunks。 */
    static List<GrepChunkView> deduplicateChunks(List<GrepChunkView> results) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<String> contentSig = new LinkedHashSet<>();
        List<GrepChunkView> uniqueResults = new ArrayList<>();

        for (GrepChunkView r : results) {
            List<String> keys = new ArrayList<>();
            keys.add(GrepChunkView.nz(r.id));
            if (!GrepChunkView.nz(r.parentChunkId).isEmpty()) {
                keys.add("parent:" + GrepChunkView.nz(r.parentChunkId));
            }
            if (!GrepChunkView.nz(r.knowledgeId).isEmpty()) {
                keys.add("kb:" + GrepChunkView.nz(r.knowledgeId) + "#" + r.chunkIndex);
            }

            boolean dup = false;
            for (String k : keys) {
                if (seen.contains(k)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }

            String sig = buildContentSignature(GrepChunkView.nz(r.content));
            if (!sig.isEmpty() && !contentSig.add(sig)) {
                continue;
            }

            seen.addAll(keys);
            uniqueResults.add(r);
        }

        LinkedHashSet<String> seenByID = new LinkedHashSet<>();
        List<GrepChunkView> deduplicated = new ArrayList<>();
        for (GrepChunkView r : uniqueResults) {
            if (seenByID.add(GrepChunkView.nz(r.id))) {
                deduplicated.add(r);
            }
        }
        return deduplicated;
    }

    /** 对照 searchutil.BuildContentSignature（小写+TrimSpace+折叠空白后 MD5 hex）。 */
    static String buildContentSignature(String content) {
        String c = GrepChunkView.nz(content).toLowerCase(Locale.ROOT).trim();
        if (c.isEmpty()) {
            return "";
        }
        // 对照 strings.Join(strings.Fields(c), " ")
        c = String.join(" ", c.trim().split("\\s+"));
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(c.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte h : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", h & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 scoreChunks（含 title 命中 +0.5 cap 1.0、patternCount 0 时置 1）。 */
    static List<GrepChunkView> scoreChunks(List<GrepChunkView> results, List<Pattern> compiled) {
        List<GrepChunkView> scored = new ArrayList<>(results.size());
        for (GrepChunkView r : results) {
            double score;
            int patternCount;
            String content = r.content == null ? "" : r.content;
            if (content.isEmpty() || compiled == null || compiled.isEmpty()) {
                score = 0.0;
                patternCount = 0;
            } else {
                int matchCount = 0;
                int earliestPos = content.length();
                for (Pattern p : compiled) {
                    if (p == null) {
                        continue;
                    }
                    java.util.regex.Matcher m = p.matcher(content);
                    if (m.find()) {
                        matchCount++;
                        if (m.start() < earliestPos) {
                            earliestPos = m.start();
                        }
                    }
                }
                if (matchCount == 0) {
                    score = 0.0;
                    patternCount = 0;
                } else {
                    double baseScore = matchCount / (double) compiled.size();
                    double positionBonus = 0.0;
                    if (earliestPos < content.length()) {
                        double positionRatio = 1.0 - earliestPos / (double) content.length();
                        positionBonus = positionRatio * 0.1;
                    }
                    score = Math.min(baseScore + positionBonus, 1.0);
                    patternCount = matchCount;
                }
            }
            String title = GrepChunkView.nz(r.knowledgeTitle);
            if (FaqSnippet.regexMatchesAny(title, compiled)) {
                r.titleMatch = true;
                score = Math.min(score + 0.5, 1.0);
                if (patternCount == 0) {
                    patternCount = 1;
                }
            }
            r.matchScore = score;
            r.matchedPatterns = patternCount;
            scored.add(r);
        }
        return scored;
    }

    /** 对照 applyMMR：swap-remove，并列取先出现者（严格 &gt;）。 */
    static List<GrepChunkView> applyMMR(List<GrepChunkView> results, int k, double lambda) {
        if (k <= 0 || results.isEmpty()) {
            return List.of();
        }

        List<GrepChunkView> selected = new ArrayList<>(k);
        List<Map<String, Boolean>> selectedTokenSets = new ArrayList<>(k);

        List<GrepChunkView> candidates = new ArrayList<>(results);
        List<Map<String, Boolean>> tokenSets = new ArrayList<>(candidates.size());
        for (GrepChunkView r : candidates) {
            tokenSets.add(tokenizeSimple(GrepChunkView.nz(r.content)));
        }

        while (selected.size() < k && !candidates.isEmpty()) {
            int bestIdx = 0;
            double bestScore = -1.0;

            for (int i = 0; i < candidates.size(); i++) {
                double relevance = candidates.get(i).matchScore;
                double redundancy = 0.0;
                for (Map<String, Boolean> selectedTS : selectedTokenSets) {
                    redundancy = Math.max(redundancy, jaccard(tokenSets.get(i), selectedTS));
                }
                double mmr = lambda * relevance - (1.0 - lambda) * redundancy;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }

            selected.add(candidates.get(bestIdx));
            selectedTokenSets.add(tokenSets.get(bestIdx));

            int last = candidates.size() - 1;
            candidates.set(bestIdx, candidates.get(last));
            tokenSets.set(bestIdx, tokenSets.get(last));
            candidates.remove(last);
            tokenSets.remove(last);
        }

        return selected;
    }

    /**
     * 对照 searchutil.TokenizeSimple。已知差异：Go 对含中文文本用 jieba 分词，
     * Java 无对应物，一律走空白分词（英文/纯空白场景逐位一致）。
     */
    static Map<String, Boolean> tokenizeSimple(String text) {
        String t = GrepChunkView.nz(text).toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> set = new LinkedHashMap<>();
        for (String w : t.split("\\s+")) {
            w = w.trim();
            // 对照：len([]rune(w)) > 1 && !isAllPunct(w)
            if (w.codePointCount(0, w.length()) > 1 && !isAllPunct(w)) {
                set.put(w, Boolean.TRUE);
            }
        }
        return set;
    }

    /** 对照 searchutil.isAllPunct（Punct/Space/Symbol 全占）。 */
    static boolean isAllPunct(String s) {
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isWhitespace(cp) && Character.getType(cp) != Character.OTHER_PUNCTUATION
                    && Character.getType(cp) != Character.DASH_PUNCTUATION
                    && Character.getType(cp) != Character.START_PUNCTUATION
                    && Character.getType(cp) != Character.END_PUNCTUATION
                    && Character.getType(cp) != Character.CONNECTOR_PUNCTUATION
                    && Character.getType(cp) != Character.INITIAL_QUOTE_PUNCTUATION
                    && Character.getType(cp) != Character.FINAL_QUOTE_PUNCTUATION
                    && Character.getType(cp) != Character.OTHER_SYMBOL
                    && Character.getType(cp) != Character.MATH_SYMBOL
                    && Character.getType(cp) != Character.CURRENCY_SYMBOL
                    && Character.getType(cp) != Character.MODIFIER_SYMBOL) {
                return false;
            }
        }
        return true;
    }

    /** 对照 searchutil.Jaccard。 */
    static double jaccard(Map<String, Boolean> a, Map<String, Boolean> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        if (a.size() > b.size()) {
            return jaccard(b, a);
        }
        int inter = 0;
        for (String k : a.keySet()) {
            if (b.containsKey(k)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        if (union == 0) {
            return 0;
        }
        return inter / (double) union;
    }
}
