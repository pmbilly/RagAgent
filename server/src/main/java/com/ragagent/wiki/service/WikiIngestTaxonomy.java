package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.WikiCategoryPaths;
import com.ragagent.wiki.prompt.WikiPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 批次目录规划与 embedding 选夹。
 *
 * <h2>为什么是"整批一次规划"而不是逐页并行发明目录</h2>
 * <p>这取代了逐页、并行的 CATEGORY 发明——后者无法收敛，
 * 在 KB 还没有任何目录可锚定的首批文档上尤其糟。整批一次规划让整个集合落在
 * <b>同一棵连贯的树</b>上，并复用既有目录。</p>
 *
 * <h2>两段式：规划 → 实体化</h2>
 * <ol>
 *   <li>{@link #planBatchTaxonomy} 产出 slug → 目录路径（纯字符串，还没有 ID）；</li>
 *   <li>{@link #resolvePlannedFolders} 把这些路径<b>顺序地</b>（在并行 reduce 之前）
 *       落实成 {@code wiki_folders} 行，返回 slug → folder id。</li>
 * </ol>
 * <p>这样并行的 reduce 阶段只"写入已解析好的 id"，永远不会有两个协程抢着创建
 * 同一个目录。</p>
 */
@Service
public class WikiIngestTaxonomy {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestTaxonomy.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WikiPageService wikiService;
    private final WikiModelResolver modelResolver;

    public WikiIngestTaxonomy(WikiPageService wikiService, WikiModelResolver modelResolver) {
        this.wikiService = wikiService;
        this.modelResolver = modelResolver;
    }

    /**
     * 待归档进目录的一个
     * entity/concept 页面。
     */
    public record TaxonomyItem(String slug, String title, String pageType, String about) { }

    // ═══════════════════════════════════════════════════════════════
    // 规划
    // ═══════════════════════════════════════════════════════════════

    /**
     * <b>一次</b>规划出一整批
     * entity/concept slug 的目录路径（大批次会分块），让整个集合落在同一棵连贯的树上、
     * 并复用既有目录。返回的 map 以 slug 为键；某个条目不可分类时值可以是空列表。
     * Reduce 只会把这些应用到<b>尚无目录</b>的页面上。
     */
    public Map<String, List<String>> planBatchTaxonomy(LlmChatClient chatModel,
                                                       KnowledgeBase kb,
                                                       Map<String, List<SlugUpdate>> slugUpdates,
                                                       String lang,
                                                       WikiIngestService ingestService) {
        if (kb == null) {
            return null;
        }
        List<TaxonomyItem> items = collectTaxonomyItems(slugUpdates);
        if (items.isEmpty()) {
            return null;
        }

        // 既有目录用于锚定复用。查询出错（例如方言不支持该语句）只意味着
        // 规划会设计一棵全新的树——不是致命依赖。
        List<List<String>> pool = new ArrayList<>();
        if (wikiService != null) {
            try {
                List<List<String>> paths = wikiService.listDistinctCategoryPaths(
                        kb.getId(), WikiBatchConstants.TAXONOMY_FOLDER_POOL_MAX);
                if (paths != null) {
                    pool = paths;
                }
            } catch (Exception e) {
                log.warn("wiki ingest: list category paths for plan failed: {}", e.getMessage());
            }
        }

        // 把目录池预处理成本批次真正相关的那些，让 planner 复用既有目录的同时，
        // 不必让每个 prompt 都携带整个目录。小/健康的目录体系直接整体喂入
        // （召回最好、零成本）。
        List<List<String>> existing = selectRelevantFolders(kb, items, pool);

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int start = 0; start < items.size(); start += WikiBatchConstants.TAXONOMY_PLAN_CHUNK_SIZE) {
            int end = Math.min(start + WikiBatchConstants.TAXONOMY_PLAN_CHUNK_SIZE, items.size());
            List<TaxonomyItem> chunk = items.subList(start, end);

            String tree = WikiIngestService.formatExistingTaxonomyForPrompt(existing);
            if (tree.trim().isEmpty()) {
                tree = WikiBatchConstants.TAXONOMY_EMPTY_TREE_HINT;
            }

            StringBuilder itemsBlock = new StringBuilder();
            for (TaxonomyItem it : chunk) {
                itemsBlock.append("- slug: ").append(it.slug())
                        .append(" | title: ").append(it.title())
                        .append(" | type: ").append(it.pageType())
                        .append(" | about: ").append(WikiTextUtils.previewText(it.about(), 120))
                        .append('\n');
            }

            String raw;
            try {
                raw = ingestService.llm.generateWithTemplate(chatModel, WikiPrompts.WIKI_TAXONOMY_PLAN_PROMPT,
                        Map.of(
                                "ExistingTaxonomy", tree,
                                "Items", itemsBlock.toString(),
                                "Language", lang == null ? "" : lang));
            } catch (RuntimeException e) {
                log.warn("wiki ingest: taxonomy plan call failed ({} items): {}",
                        chunk.size(), e.getMessage());
                continue;
            }

            Map<String, List<String>> assignments = parseTaxonomyAssignments(raw);
            if (assignments == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> e : assignments.entrySet()) {
                List<String> clean = WikiCategoryPaths.cleanCategoryPath(e.getValue());
                result.put(e.getKey(), clean);
                if (!clean.isEmpty()) {
                    // 向前喂入，让后续分块收敛到同一棵树
                    existing.add(clean);
                }
            }
        }
        return result;
    }

    /**
     * 把 planner 的
     * 逐 slug 路径落实成真实的 {@code wiki_folders} 行，返回 slug → folder id。
     *
     * <p>目录创建在这里、在并行 reduce 阶段<b>之前</b>、顺序完成，所以 reduce 只分配
     * 已解析好的 id，永远不会有两个协程抢着创建同一个目录。不同路径只解析一次并缓存。
     * 空路径（以及任何解析失败）映射到根目录，直接省略。</p>
     */
    public Map<String, String> resolvePlannedFolders(KnowledgeBase kb,
                                                     Map<String, List<String>> planned) {
        if (kb == null || planned == null || planned.isEmpty() || wikiService == null) {
            return null;
        }
        Map<String, String> pathCache = new LinkedHashMap<>(); // "a/b" -> folder id
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : planned.entrySet()) {
            List<String> clean = WikiCategoryPaths.cleanCategoryPath(e.getValue());
            if (clean.isEmpty()) {
                continue;
            }
            String key = String.join("/", clean);
            String fid = pathCache.get(key);
            if (fid == null) {
                try {
                    WikiPageService.FindOrCreateResult resolved =
                            wikiService.findOrCreateFolderPath(kb.getId(), kb.getTenantId(), clean);
                    fid = resolved == null ? "" : resolved.folderId();
                    pathCache.put(key, fid);
                } catch (Exception err) {
                    log.warn("wiki ingest: resolve folder {} failed: {}", key, err.getMessage());
                    // 负缓存：避免为每个 slug 反复重试同一条坏路径
                    pathCache.put(key, "");
                    continue;
                }
            }
            if (fid != null && !fid.isEmpty()) {
                out.put(e.getKey(), fid);
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 相关目录选夹
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把既有目录池收窄成
     * <b>本批次</b>值得给 planner 看的子集。健康的导航目录很小，整体喂入
     * （完美的复用召回、零 embedding 成本）。只有目录多起来之后，相似度预处理才介入：
     * 所有一级目录作为粗锚点<b>永远保留</b>，每个条目再按 embedding 相似度拉进
     * 它最近的深层目录。没有 embedding 模型的 KB（纯 wiki）回落到"限量喂全部"。
     */
    public List<List<String>> selectRelevantFolders(KnowledgeBase kb,
                                                    List<TaxonomyItem> items,
                                                    List<List<String>> pool) {
        if (pool == null || pool.size() <= WikiBatchConstants.TAXONOMY_FEED_ALL_MAX_FOLDERS) {
            return pool == null ? List.of() : pool;
        }

        // 拆成"永远保留的一级锚点"与"由相似度在其中挑选的深层候选目录"。
        Set<String> l1Seen = new LinkedHashSet<>();
        List<List<String>> l1Paths = new ArrayList<>();
        List<List<String>> deeper = new ArrayList<>();
        for (List<String> p : pool) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (l1Seen.add(p.get(0))) {
                l1Paths.add(List.of(p.get(0)));
            }
            if (p.size() >= 2) {
                deeper.add(p);
            }
        }

        // 判据<b>纯粹</b>是"是否配置了 embedding 模型"——<b>不是</b>
        // KB 的"内容抽取需要 embedding"开关，后者对纯 wiki KB 为 false，而那些 KB 仍可能
        // 专门为目录/分类相似度选配一个 embedding 模型。
        String embeddingModelId = kb.getEmbeddingModelId();
        if (embeddingModelId == null || embeddingModelId.trim().isEmpty() || deeper.isEmpty()) {
            return capFolders(pool, WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS);
        }

        WikiModelResolver.WikiEmbeddingModel embedder;
        try {
            embedder = modelResolver.getEmbeddingModel(embeddingModelId);
        } catch (Exception e) {
            log.warn("wiki ingest: taxonomy plan embed model unavailable, feeding all folders: {}",
                    e.getMessage());
            return capFolders(pool, WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS);
        }

        List<String> folderTexts = new ArrayList<>(deeper.size());
        for (List<String> p : deeper) {
            folderTexts.add(String.join(" / ", p));
        }
        List<String> itemTexts = new ArrayList<>(items.size());
        for (TaxonomyItem it : items) {
            itemTexts.add((it.title() + " " + WikiTextUtils.previewText(it.about(), 120)).trim());
        }

        List<float[]> folderVecs;
        try {
            folderVecs = embedder.batchEmbed(folderTexts);
        } catch (Exception e) {
            log.warn("wiki ingest: taxonomy plan folder embed failed, feeding all folders: {}",
                    e.getMessage());
            return capFolders(pool, WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS);
        }
        List<float[]> itemVecs;
        try {
            itemVecs = embedder.batchEmbed(itemTexts);
        } catch (Exception e) {
            log.warn("wiki ingest: taxonomy plan item embed failed, feeding all folders: {}",
                    e.getMessage());
            return capFolders(pool, WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS);
        }
        if (folderVecs == null || itemVecs == null) {
            return capFolders(pool, WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS);
        }

        List<List<String>> selected = new ArrayList<>(l1Paths);
        selected.addAll(selectFoldersByVectors(deeper, folderVecs, itemVecs,
                WikiBatchConstants.TAXONOMY_RELEVANT_TOP_K));
        return capFolders(selected, WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS);
    }

    /**
     * 返回在<b>任意</b>
     * 条目的 top-K 余弦相似度里排上号的深层目录，为确定性而<b>保留输入顺序</b>。
     *
     * <p>向量类型与 embedding 客户端的返回类型（{@code List<float[]>}）对齐。</p>
     */
    public static List<List<String>> selectFoldersByVectors(List<List<String>> deeper,
                                                            List<float[]> folderVecs,
                                                            List<float[]> itemVecs,
                                                            int topK) {
        if (deeper == null || folderVecs == null
                || deeper.size() != folderVecs.size()
                || itemVecs == null || itemVecs.isEmpty()
                || topK <= 0) {
            return List.of();
        }
        Set<Integer> chosen = new LinkedHashSet<>();
        for (float[] iv : itemVecs) {
            record Scored(int idx, double sim) { }
            List<Scored> ranking = new ArrayList<>(folderVecs.size());
            for (int fi = 0; fi < folderVecs.size(); fi++) {
                ranking.add(new Scored(fi, cosineSimilarity(iv, folderVecs.get(fi))));
            }
            // 降序排序：List.sort 是稳定排序，并列时保持输入顺序
            ranking.sort((a, b) -> Double.compare(b.sim(), a.sim()));
            for (int k = 0; k < topK && k < ranking.size(); k++) {
                chosen.add(ranking.get(k).idx());
            }
        }
        List<List<String>> out = new ArrayList<>(chosen.size());
        for (int i = 0; i < deeper.size(); i++) {
            if (chosen.contains(i)) {
                out.add(deeper.get(i));
            }
        }
        return out;
    }

    /**
     * 两个等长向量的余弦；
     * 空 / 长度不匹配 / 零范数输入返回 0。
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * (double) b[i];
            na += (double) a[i] * (double) a[i];
            nb += (double) b[i] * (double) b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 把目录列表截到最多 max 条
     * （{@code max <= 0} = 不限）。
     */
    public static List<List<String>> capFolders(List<List<String>> paths, int max) {
        if (paths == null) {
            return List.of();
        }
        if (max > 0 && paths.size() > max) {
            return new ArrayList<>(paths.subList(0, max));
        }
        return paths;
    }

    // ═══════════════════════════════════════════════════════════════
    // 条目收集与解析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从批次的 slugUpdates
     * 里抽出 entity/concept 页面，按<b>确定的 slug 顺序</b>排列，让分块边界稳定。
     * summary 与仅 retract 的 slug 被跳过（它们不携带目录分类）。
     */
    public static List<TaxonomyItem> collectTaxonomyItems(Map<String, List<SlugUpdate>> slugUpdates) {
        List<TaxonomyItem> items = new ArrayList<>();
        if (slugUpdates == null || slugUpdates.isEmpty()) {
            return items;
        }
        List<String> slugs = new ArrayList<>(slugUpdates.keySet());
        // 按码点序排序（不能用 String.compareTo，其对增补平面字符不友好）
        slugs.sort(GoStrings::compareByCodePoints);

        for (String slug : slugs) {
            List<SlugUpdate> updates = slugUpdates.get(slug);
            if (updates == null) {
                continue;
            }
            for (SlugUpdate u : updates) {
                if (!SlugUpdate.TYPE_ENTITY.equals(u.getType())
                        && !SlugUpdate.TYPE_CONCEPT.equals(u.getType())) {
                    continue;
                }
                String title = GoStrings.trimSpace(u.getItem() == null ? "" : u.getItem().getName());
                if (title.isEmpty()) {
                    title = slug;
                }
                String about = GoStrings.trimSpace(
                        u.getItem() == null ? "" : u.getItem().getDescription());
                items.add(new TaxonomyItem(slug, title, u.getType(), about));
                break; // 每个 slug 一条足矣
            }
        }
        return items;
    }

    /**
     * 把规划 LLM 的
     * JSON 解析成 slug → 路径。畸形输出返回 null；slug 为空的条目被丢弃。
     */
    public static Map<String, List<String>> parseTaxonomyAssignments(String raw) {
        String cleaned = WikiTextUtils.cleanLLMJSON(raw);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            return null;
        }
        if (parsed == null || !parsed.isObject()) {
            return null;
        }
        JsonNode assignments = parsed.get("assignments");
        if (assignments == null || !assignments.isArray()) {
            return null;
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (JsonNode a : assignments) {
            if (a == null || !a.isObject()) {
                continue;
            }
            JsonNode slugNode = a.get("slug");
            String slug = slugNode == null || slugNode.isNull()
                    ? "" : GoStrings.trimSpace(slugNode.asText(""));
            if (slug.isEmpty()) {
                continue;
            }
            List<String> path = new ArrayList<>();
            JsonNode pathNode = a.get("path");
            if (pathNode != null && pathNode.isArray()) {
                for (JsonNode seg : pathNode) {
                    if (seg == null || seg.isNull()) {
                        path.add("");
                        continue;
                    }
                    path.add(seg.isTextual() ? seg.textValue() : seg.asText(""));
                }
            }
            out.put(slug, path);
        }
        return out;
    }

}
