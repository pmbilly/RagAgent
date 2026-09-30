package com.ragagent.wiki.service.page;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 真实 slug ⟷ 短句柄（{@code ref-1}、{@code ref-2}…）的双向映射。
 *
 * <p><b>存在理由</b>：ingest 的编辑 LLM 不该被要求逐字复现
 * 高熵 slug——尤其是 UUID 形态的摘要 slug（{@code summary/<knowledgeID>}），
 * 模型经常插/漏一位十六进制字符。模型只复制小句柄，我们在输出侧把句柄翻回真 slug。
 * 这是 {@code wiki_write_page} slug 校验的「生成期」对应物：与其事后修补被弄花的
 * slug，不如从源头取消弄花的机会。与 chunk 引用（c000/c001）的中介手法同构。</p>
 *
 * <h2>句柄表语义</h2>
 * <ul>
 *   <li>空 slug → 空句柄；</li>
 *   <li>首次使用才分配，之后稳定返回同一个句柄
 *       （条目<b>永不删除</b>，因此编号在表生命周期内稳定）；</li>
 *   <li>句柄不含 {@code '/'}，永不与带命名空间的真 slug（entity/…、summary/…）碰撞。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：一个 ingest 批次内该对象是
 * 批次私有的，但 handle/encode/decode 可能被扇出的并发 map worker 调用，故
 * 保留 {@code synchronized}。</p>
 *
 * <p><b>注意本类不属于 {@link WikiPageService}</b>：它是 ingest 管道的工具，供
 * ingest 侧直接复用。</p>
 */
public class WikiSlugHandles {

    /** 句柄前缀：ref-、无零填充、从 1 起编号 */
    public static final String HANDLE_PREFIX = "ref-";

    private final Map<String, String> handleByKey = new HashMap<>();
    private final Map<String, String> valueByHandle = new HashMap<>();
    private int next = 1;

    /**
     * 返回真 slug 的稳定句柄，
     * 首次使用时分配。空 slug 返回 ""。
     */
    public synchronized String handle(String realSlug) {
        if (realSlug == null || realSlug.isEmpty()) {
            return "";
        }
        String existing = handleByKey.get(realSlug);
        if (existing != null) {
            return existing;
        }
        String h = HANDLE_PREFIX + next;
        next++;
        handleByKey.put(realSlug, h);
        valueByHandle.put(h, realSlug);
        return h;
    }

    /** 还没分配过任何句柄 */
    public synchronized boolean isEmpty() {
        return valueByHandle.isEmpty();
    }

    /**
     * 句柄 → 真 slug 的反查。
     * 未知句柄返回 null。
     */
    public synchronized String resolve(String handle) {
        if (handle == null || handle.isEmpty()) {
            return null;
        }
        return valueByHandle.get(handle);
    }

    /** 已分配句柄数 */
    public synchronized int size() {
        return valueByHandle.size();
    }

    /**
     * 把
     * {@code [[realSlug|disp]] / [[realSlug]]} 改写成句柄形态，<b>但只针对
     * {@code known} 里的 slug</b>；未知链接原样保留。按需分配句柄，所以只出现在正文里
     * （不在清单里）的 slug 也能拿到一致的句柄。
     */
    public String encodeContent(String content, Set<String> known) {
        if (content == null || content.isEmpty() || known == null || known.isEmpty()) {
            return content;
        }
        return SlugFuzzy.rewriteDeadWikiLinks(content, (norm, display) -> {
            if (!known.contains(norm)) {
                return null;
            }
            return handle(norm);
        }).content();
    }

    /**
     * 把
     * {@code [[handle|disp]] / [[handle]]} 翻回真 slug。没有映射的句柄原样保留
     * ——它们会落到常规的解析/死链清理路径，这对「模型凭空编的、不是已知引用的东西」
     * 正是正确行为。
     */
    public String decodeContent(String content) {
        if (content == null || content.isEmpty() || isEmpty()) {
            return content;
        }
        return SlugFuzzy.rewriteDeadWikiLinks(content, (norm, display) -> {
            String real = resolve(norm);
            return real == null ? null : real;
        }).content();
    }

    /** 供测试/调试：当前句柄 → 真 slug 的只读快照（按句柄号保序） */
    public synchronized Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < next; i++) {
            String h = HANDLE_PREFIX + i;
            String v = valueByHandle.get(h);
            if (v != null) {
                out.put(h, v);
            }
        }
        return out;
    }

}
