package com.ragagent.knowledge.textconv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 繁体转简体（对照 Go internal/textconv/simplified.go，FAQ 归一化专用）。
 *
 * <p>两本 Apache-2.0 词典（longbridge/opencc v0.3.13 原样数据，已随包内嵌：
 * {@code resources/textconv/TSPhrases.txt} + {@code TSCharacters.txt}，SHA-256 与
 * Go 仓 data/README.md 记录一致）构造两个 dictionary；转换时<b>先词组表后单字表</b>、
 * 每张表内<b>最长匹配</b>（窗口上限 min(10, maxRunes)）、取<b>第一个候选值</b>——
 * 与 Go 的 historical FAQ conversion 逐条对应：</p>
 *
 * <pre>
 * for pos := 0; pos &lt; len(runes); {
 *     for _, d := range c {                     // phrases 先于 characters
 *         limit := min(10, d.maxRunes, len(runes)-pos)
 *         for size := limit; size &gt; 0; size-- { // 表内最长匹配
 *             if replacement, ok := d.values[...]; ok { ... }
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>词典行格式 {@code key\tvalue1 value2 ...}，取 {@code values[0]}；空行/缺列跳过。
 * 词典更新会改变 FAQ 归一化与 content_hash——Go 侧把它与代码变更分开 review，
 * Java 侧同理：更新数据文件前先跑 {@code TextConvTest} 的 Go 实录语料。</p>
 */
public final class TextConv {

    private static final int MAX_WINDOW = 10;

    private static final class Dictionary {
        final Map<String, String> values = new HashMap<>();
        int maxRunes;
    }

    private static final List<Dictionary> DICTIONARIES = load();

    private TextConv() {
    }

    private static List<Dictionary> load() {
        List<Dictionary> result = new ArrayList<>();
        result.add(loadDictionary("/textconv/TSPhrases.txt"));
        result.add(loadDictionary("/textconv/TSCharacters.txt"));
        return result;
    }

    private static Dictionary loadDictionary(String resource) {
        Dictionary d = new Dictionary();
        try (InputStream in = TextConv.class.getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                int tab = trimmed.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, tab);
                String[] alternatives = trimmed.substring(tab + 1).trim().split("\\s+");
                if (key.isEmpty() || alternatives.length == 0 || alternatives[0].isEmpty()) {
                    continue;
                }
                d.values.put(key, alternatives[0]);
                d.maxRunes = Math.max(d.maxRunes, key.codePointCount(0, key.length()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load textconv dictionary " + resource, e);
        }
        return d;
    }

    /** 对照 Go {@code textconv.ToSimplified}：词组表 → 单字表，表内最长匹配，取第一候选。 */
    public static String toSimplified(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        int[] runes = text.codePoints().toArray();
        StringBuilder result = new StringBuilder(text.length());
        int pos = 0;
        while (pos < runes.length) {
            boolean matched = false;
            for (Dictionary d : DICTIONARIES) {
                int limit = Math.min(MAX_WINDOW, Math.min(d.maxRunes, runes.length - pos));
                for (int size = limit; size > 0; size--) {
                    String candidate = new String(runes, pos, size);
                    String replacement = d.values.get(candidate);
                    if (replacement != null) {
                        result.append(replacement);
                        pos += size;
                        matched = true;
                        break;
                    }
                }
                if (matched) {
                    break;
                }
            }
            if (!matched) {
                result.appendCodePoint(runes[pos]);
                pos++;
            }
        }
        return result.toString();
    }
}
