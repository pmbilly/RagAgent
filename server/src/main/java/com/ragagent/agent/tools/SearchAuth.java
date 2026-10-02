package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.ragagent.agent.tools.SearchTarget.SearchTargets;

/**
 * scope 授权。
 *
 * <p>每个接受模型可见 dN/knowledge_id 的 Agent 工具的共享授权边界：句柄解码必要但不充分，
 * 文档必须落在服务端持有的检索作用域内。错误以 {@link ScopeAuthException} 抛出，
 * {@code getMessage()} 即对模型的错误文案（含包装层）。</p>
 *
 * <p>依赖两个接缝（装配期接 knowledge 模块真实实现）：{@link KnowledgeFetcher}
 * （按 ID 取知识）与 {@link KnowledgeTagsFetcher}（取知识标签）。
 * fetcher 契约：返回 null = "empty result"；抛异常 = 失败。</p>
 */
public final class SearchAuth {

    private SearchAuth() {
    }

    /** 授权失败的错误（message 即对模型的文案）。 */
    public static final class ScopeAuthException extends RuntimeException {
        public ScopeAuthException(String message) {
            super(message);
        }

        public ScopeAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 知识视图（被用字段）。 */
    public record KnowledgeView(String id, String knowledgeBaseId, String title, String fileName) {
    }

    /** chunk 视图（被用字段）。 */
    public record ChunkView(String id, String knowledgeId, String knowledgeBaseId, boolean enabled) {
    }

    /** 标签视图（被用字段）。 */
    public record TagView(String id) {
    }

    /** 按 ID 取知识。 */
    @FunctionalInterface
    public interface KnowledgeFetcher {
        /** 返回 null = 不存在（"empty result"）；抛异常 = 服务错误。 */
        KnowledgeView byIdOnly(String knowledgeId);
    }

    /** 按 ID 取 chunk。 */
    @FunctionalInterface
    public interface ChunkFetcher {
        ChunkView byIdOnly(String chunkId);
    }

    /** 取知识的标签（knowledgeID → 其标签）。 */
    @FunctionalInterface
    public interface KnowledgeTagsFetcher {
        Map<String, List<TagView>> fetchTags(List<String> knowledgeIds);
    }

    /**
     * 知识域读取接缝（一个对象同时提供按 ID 取知识与取标签）。
     */
    public interface KnowledgeScopeReader extends KnowledgeFetcher, KnowledgeTagsFetcher {
    }

    // ---- 去重非空字符串 ----

    public static List<String> dedupNonEmptyStrings(List<String> values) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.isEmpty() || seen.containsKey(value)) {
                continue;
            }
            seen.put(value, Boolean.TRUE);
            out.add(value);
        }
        return out;
    }

    /** target 的 TagIDs + ScopeTagIDs 去重非空。 */
    static List<String> effectiveSearchTargetTagIds(SearchTarget target) {
        if (target == null) {
            return null;
        }
        List<String> all = new ArrayList<>();
        if (target.tagIds() != null) {
            all.addAll(target.tagIds());
        }
        if (target.scopeTagIds() != null) {
            all.addAll(target.scopeTagIds());
        }
        return dedupNonEmptyStrings(all);
    }

    /**
     * 单个目标授权什么。KnowledgeIDs 与标签是交集不是并集；
     * 有文档白名单时标签不参与授权。
     */
    public static Scope searchTargetScope(SearchTarget target) {
        if (target == null) {
            return new Scope(null, null);
        }
        List<String> knowledgeIds = dedupNonEmptyStrings(target.knowledgeIds());
        if (!knowledgeIds.isEmpty()) {
            return new Scope(knowledgeIds, null);
        }
        return new Scope(null, effectiveSearchTargetTagIds(target));
    }

    public record Scope(List<String> knowledgeIds, List<String> tagIds) {
    }

    /** 是否整库目标。 */
    public static boolean searchTargetIsWholeKb(SearchTarget target) {
        if (target == null) {
            return false;
        }
        Scope scope = searchTargetScope(target);
        return SearchTarget.TYPE_KNOWLEDGE_BASE.equals(target.type())
                && (scope.knowledgeIds() == null || scope.knowledgeIds().isEmpty())
                && (scope.tagIds() == null || scope.tagIds().isEmpty());
    }

    /** 校验 knowledge_id 在检索作用域内。 */
    public static KnowledgeView authorizeKnowledgeInSearchTargets(
            SearchTargets searchTargets, String knowledgeId, KnowledgeScopeReader knowledgeService) {
        knowledgeId = knowledgeId == null ? "" : knowledgeId.trim();
        if (knowledgeId.isEmpty()) {
            throw new ScopeAuthException("knowledge_id is required");
        }
        if (knowledgeService == null) {
            throw new ScopeAuthException("knowledge service is unavailable");
        }
        KnowledgeView knowledge;
        try {
            knowledge = knowledgeService.byIdOnly(knowledgeId);
        } catch (RuntimeException e) {
            throw new ScopeAuthException("document " + knowledgeId + " not found: " + e.getMessage(), e);
        }
        if (knowledge == null) {
            throw new ScopeAuthException("document " + knowledgeId + " not found: empty result");
        }
        // searchTargets 为 null 时 containsKb 返回 false（空范围语义），不抛 NPE。
        if (searchTargets == null || !searchTargets.containsKb(knowledge.knowledgeBaseId())) {
            throw new ScopeAuthException(
                    "knowledge base " + knowledge.knowledgeBaseId() + " is not within the current Agent scope");
        }
        boolean allowed;
        try {
            allowed = searchTargetsAllowKnowledgeId(
                    searchTargets, knowledge.id(), knowledge.knowledgeBaseId(), knowledgeService);
        } catch (ScopeAuthException e) {
            throw new ScopeAuthException("failed to validate document scope: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ScopeAuthException("failed to validate document scope: " + e.getMessage(), e);
        }
        if (!allowed) {
            throw new ScopeAuthException("document " + knowledge.id() + " is not within the current @mention scope");
        }
        return knowledge;
    }

    /** 校验 chunk 在检索作用域内。 */
    public static ChunkView authorizeChunkInSearchTargets(
            SearchTargets searchTargets, String chunkId, ChunkFetcher chunkFetcher,
            KnowledgeScopeReader knowledgeService) {
        ChunkIdentity identity = authorizeChunkIdentity(searchTargets, chunkId,
                () -> {
                    if (chunkFetcher == null) {
                        return null;
                    }
                    ChunkView c = chunkFetcher.byIdOnly(chunkId);
                    if (c == null) {
                        return null;
                    }
                    return new ChunkIdentity(c.id(), c.knowledgeId(), c.knowledgeBaseId(), c.enabled());
                },
                chunkFetcher == null, knowledgeService);
        return new ChunkView(identity.id(), identity.knowledgeId(), identity.knowledgeBaseId(), identity.enabled());
    }

    /**
     * chunk 授权（knowledge 域 Chunk 重载；FAQ 元数据等
     * 需要完整 chunk 的工具用）。chunkById 返回 null = 未找到（empty result）。
     */
    public static com.ragagent.knowledge.domain.Chunk authorizeDomainChunkInSearchTargets(
            SearchTargets searchTargets, String chunkId,
            java.util.function.Function<String, com.ragagent.knowledge.domain.Chunk> chunkById,
            KnowledgeScopeReader knowledgeService) {
        com.ragagent.knowledge.domain.Chunk[] holder = new com.ragagent.knowledge.domain.Chunk[1];
        authorizeChunkIdentity(searchTargets, chunkId,
                () -> {
                    if (chunkById == null) {
                        return null;
                    }
                    com.ragagent.knowledge.domain.Chunk c = chunkById.apply(chunkId);
                    if (c == null) {
                        return null;
                    }
                    holder[0] = c;
                    return new ChunkIdentity(c.getId(), c.getKnowledgeId(), c.getKnowledgeBaseId(),
                            c.isIsEnabled());
                },
                chunkById == null, knowledgeService);
        return holder[0];
    }

    private record ChunkIdentity(String id, String knowledgeId, String knowledgeBaseId, boolean enabled) {
    }

    /** chunk 授权的共享核心（错误文案固定）。 */
    private static ChunkIdentity authorizeChunkIdentity(
            SearchTargets searchTargets, String chunkId,
            java.util.function.Supplier<ChunkIdentity> fetch,
            boolean serviceUnavailable, KnowledgeScopeReader knowledgeService) {
        chunkId = chunkId == null ? "" : chunkId.trim();
        if (chunkId.isEmpty()) {
            throw new ScopeAuthException("chunk_id is required");
        }
        if (serviceUnavailable) {
            throw new ScopeAuthException("chunk service is unavailable");
        }
        ChunkIdentity chunk;
        try {
            chunk = fetch.get();
        } catch (RuntimeException e) {
            throw new ScopeAuthException("chunk " + chunkId + " not found: " + e.getMessage(), e);
        }
        if (chunk == null) {
            throw new ScopeAuthException("chunk " + chunkId + " not found: empty result");
        }
        if (!chunk.enabled()) {
            throw new ScopeAuthException("chunk " + chunk.id() + " is disabled");
        }
        if (!searchTargets.containsKb(chunk.knowledgeBaseId())) {
            throw new ScopeAuthException(
                    "knowledge base " + chunk.knowledgeBaseId() + " is not within the current Agent scope");
        }
        boolean allowed;
        try {
            allowed = searchTargetsAllowKnowledgeId(
                    searchTargets, chunk.knowledgeId(), chunk.knowledgeBaseId(), knowledgeService);
        } catch (RuntimeException e) {
            throw new ScopeAuthException("failed to validate chunk scope: " + e.getMessage(), e);
        }
        if (!allowed) {
            throw new ScopeAuthException("chunk " + chunk.id() + " is not within the current @mention scope");
        }
        return chunk;
    }

    /** 校验用户指定的 KB ID 都在检索作用域内。 */
    public static void validateKnowledgeBaseIdsInSearchTargets(SearchTargets searchTargets, List<String> kbIds) {
        for (String kbId : dedupNonEmptyStrings(kbIds)) {
            if (!searchTargets.containsKb(kbId)) {
                throw new ScopeAuthException(
                        "knowledge base " + kbId + " is not within the current Agent scope");
            }
        }
    }

    /** 重建 "uuid|title"（标题取服务端，不信模型给的）。 */
    public static List<String> resolveAuthorizedSourceRefs(
            SearchTargets searchTargets, List<String> refs, KnowledgeScopeReader knowledgeService) {
        List<String> resolved = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String ref : refs) {
            if (ref == null) {
                continue;
            }
            String knowledgeId = ref.split("\\|", 2)[0].trim();
            if (knowledgeId.isEmpty()) {
                continue;
            }
            KnowledgeView knowledge = authorizeKnowledgeInSearchTargets(searchTargets, knowledgeId, knowledgeService);
            if (seen.containsKey(knowledge.id())) {
                continue;
            }
            seen.put(knowledge.id(), Boolean.TRUE);
            String title = knowledge.title() == null ? "" : knowledge.title().trim();
            if (title.isEmpty()) {
                title = knowledge.fileName() == null ? "" : knowledge.fileName().trim();
            }
            if (title.isEmpty()) {
                resolved.add(knowledge.id());
            } else {
                resolved.add(knowledge.id() + "|" + title);
            }
        }
        return resolved;
    }

    /** knowledge_id 是否被任一 target 授权。knowledgeFetcher 为 null 时退化为 false。 */
    public static boolean searchTargetsAllowKnowledgeId(
            SearchTargets searchTargets, String knowledgeId, String kbId, KnowledgeScopeReader knowledgeService) {
        if (knowledgeId == null || knowledgeId.isEmpty() || kbId == null || kbId.isEmpty()) {
            return false;
        }

        List<String> tagIds = new ArrayList<>();
        boolean matchedKB = false;
        for (SearchTarget target : searchTargets.list()) {
            if (target == null || !kbId.equals(target.knowledgeBaseId())) {
                continue;
            }
            matchedKB = true;
            if (searchTargetIsWholeKb(target)) {
                return true;
            }
            Scope scope = searchTargetScope(target);
            if (scope.knowledgeIds() != null) {
                for (String allowedId : scope.knowledgeIds()) {
                    if (allowedId.equals(knowledgeId)) {
                        return true;
                    }
                }
            }
            if (scope.tagIds() != null) {
                tagIds.addAll(scope.tagIds());
            }
        }
        if (!matchedKB || tagIds.isEmpty() || knowledgeService == null) {
            return false;
        }

        Map<String, Boolean> matches = knowledgeIdsMatchingAnyTag(
                List.of(knowledgeId), tagIds, knowledgeService);
        return matches.getOrDefault(knowledgeId, false);
    }

    /**
     * 按检索作用域过滤检索结果（知识图谱用）。
     * 结果条目视图 {@code id/knowledgeId/knowledgeBaseId} 之外的数据原样保留，由调用方映射。
     */
    public static <T> List<T> filterSearchResultsInSearchTargets(
            SearchTargets searchTargets, String kbId, List<T> results,
            Function<T, String> knowledgeIdOf, Function<T, String> knowledgeBaseIdOf,
            KnowledgeTagsFetcher tagsFetcher) {
        List<String> explicitIds = new ArrayList<>();
        List<String> tagIds = new ArrayList<>();
        boolean matchedKB = false;
        for (SearchTarget target : searchTargets.list()) {
            if (target == null || !kbId.equals(target.knowledgeBaseId())) {
                continue;
            }
            matchedKB = true;
            if (searchTargetIsWholeKb(target)) {
                return results;
            }
            Scope scope = searchTargetScope(target);
            if (scope.knowledgeIds() != null) {
                explicitIds.addAll(scope.knowledgeIds());
            }
            if (scope.tagIds() != null) {
                tagIds.addAll(scope.tagIds());
            }
        }
        if (!matchedKB) {
            throw new ScopeAuthException(
                    "knowledge base " + kbId + " is not within the current Agent scope");
        }

        Map<String, Boolean> explicitSet = new LinkedHashMap<>();
        for (String id : dedupNonEmptyStrings(explicitIds)) {
            explicitSet.put(id, Boolean.TRUE);
        }
        List<String> remainingIds = new ArrayList<>();
        for (T result : results) {
            if (result == null || knowledgeIdOf.apply(result).isEmpty()) {
                continue;
            }
            String resultKb = knowledgeBaseIdOf.apply(result);
            if (!resultKb.isEmpty() && !resultKb.equals(kbId)) {
                throw new ScopeAuthException("graph result document " + knowledgeIdOf.apply(result)
                        + " belongs to knowledge base " + resultKb + ", expected " + kbId);
            }
            if (!explicitSet.containsKey(knowledgeIdOf.apply(result))) {
                remainingIds.add(knowledgeIdOf.apply(result));
            }
        }
        Map<String, Boolean> tagMatches = new LinkedHashMap<>();
        if (!tagIds.isEmpty()) {
            if (tagsFetcher == null) {
                throw new ScopeAuthException("knowledge service is unavailable for tag-scoped graph filtering");
            }
            try {
                tagMatches = knowledgeIdsMatchingAnyTag(remainingIds, tagIds, tagsFetcher);
            } catch (RuntimeException e) {
                throw new ScopeAuthException("failed to validate graph result scope: " + e.getMessage(), e);
            }
        }

        List<T> filtered = new ArrayList<>();
        for (T result : results) {
            if (result == null || knowledgeIdOf.apply(result).isEmpty()) {
                continue;
            }
            boolean explicit = explicitSet.containsKey(knowledgeIdOf.apply(result));
            if (explicit || tagMatches.getOrDefault(knowledgeIdOf.apply(result), false)) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    /** 与任一标签匹配的 knowledge ID 集。 */
    public static Map<String, Boolean> knowledgeIdsMatchingAnyTag(
            List<String> knowledgeIds, List<String> tagIds, KnowledgeTagsFetcher fetchTags) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (knowledgeIds == null || knowledgeIds.isEmpty() || tagIds == null || tagIds.isEmpty() || fetchTags == null) {
            return result;
        }

        List<String> uniqueKnowledgeIds = dedupNonEmptyStrings(knowledgeIds);
        List<String> uniqueTagIds = dedupNonEmptyStrings(tagIds);
        if (uniqueKnowledgeIds.isEmpty() || uniqueTagIds.isEmpty()) {
            return result;
        }

        Map<String, Boolean> tagSet = new LinkedHashMap<>();
        for (String tagId : uniqueTagIds) {
            tagSet.put(tagId, Boolean.TRUE);
        }

        Map<String, List<TagView>> tagMap = fetchTags.fetchTags(uniqueKnowledgeIds);
        for (String knowledgeId : uniqueKnowledgeIds) {
            List<TagView> tags = tagMap == null ? null : tagMap.get(knowledgeId);
            if (tags == null) {
                continue;
            }
            for (TagView tag : tags) {
                if (tag != null && tagSet.containsKey(tag.id())) {
                    result.put(knowledgeId, Boolean.TRUE);
                    break;
                }
            }
        }
        return result;
    }
}
