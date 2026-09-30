package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.common.context.TenantContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 知识检索。
 */
@Service
public class KnowledgeSearchService {

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;

    public KnowledgeSearchService(KnowledgeMapper knowledgeMapper,
            KnowledgeBaseMapper kbMapper) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
    }

    /** 搜索结果。 */
    public record SearchOutcome(List<Knowledge> knowledges, boolean hasMore, long total) {}
    /** 检索作用域：租户 ID + 目标知识库 ID。 */
    public record KnowledgeSearchScope(long tenantId, String kbId) {}

    // ── 搜索与移动/复制批：搜索与移动/复制（8 条路由的服务面） ──────────────────

    /**
     * * <b>已知差异</b>：org-share（kbShareService）未翻译——共享库的补捞分支恒空，
     * 与 ChunkAccessGuard/KnowledgeAccessGuard 的既有收紧同源；本租户文档库路径完整翻译
     * （含 keyword LIKE 转义、file_types 别名、offset/limit+has_more、knowledge_base_name 回填）。
     */
    public SearchOutcome searchKnowledge(String keyword, int offset, int limit, List<String> fileTypes) {
        long tid = TenantContext.currentTenantId();
        List<KnowledgeSearchScope> scopes = new ArrayList<>();
        for (KnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tid)
                .isNull(KnowledgeBase::getDeletedAt))) {
            if ("document".equals(kb.getType())) {
                scopes.add(new KnowledgeSearchScope(tid, kb.getId()));
            }
        }
        return searchKnowledgeInScopes(scopes, keyword, offset, limit, fileTypes);
    }

    /**
     * JOIN knowledge_bases 限定 (tenant,kb) 对 +
     * {@code knowledge_bases.type='document'}，keyword 对 LOWER(file_name)/LOWER(title)
     * （xlsx↔xls / docx↔doc / jpg↔jpeg↔png，url/html → type='url'），
     * created_at DESC + limit+1 探测 has_more，total 是过滤后的全量计数。
     */
    public SearchOutcome searchKnowledgeInScopes(List<KnowledgeSearchScope> scopes, String keyword,
                                                 int offset, int limit, List<String> fileTypes) {
        if (scopes == null || scopes.isEmpty()) {
            return new SearchOutcome(null, false, 0);
        }
        // JOIN 语义：KB 行必须存在（同租户）且 type=document
        List<KnowledgeSearchScope> valid = new ArrayList<>();
        for (KnowledgeSearchScope s : scopes) {
            KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getId, s.kbId())
                    .eq(KnowledgeBase::getTenantId, s.tenantId())
                    .isNull(KnowledgeBase::getDeletedAt)
                    .last("LIMIT 1"));
            if (kb != null && "document".equals(kb.getType())) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) {
            return new SearchOutcome(null, false, 0);
        }
        LambdaQueryWrapper<Knowledge> qw = new LambdaQueryWrapper<Knowledge>().isNull(Knowledge::getDeletedAt);
        qw.and(w -> {
            for (KnowledgeSearchScope s : valid) {
                w.or(i -> i.eq(Knowledge::getTenantId, s.tenantId())
                        .eq(Knowledge::getKnowledgeBaseId, s.kbId()));
            }
        });
        String kw = keyword == null ? "" : keyword;
        if (!kw.isEmpty()) {
            String pat = "%" + escapeLikeKeyword(kw.toLowerCase()) + "%";
            qw.and(w -> w.apply("LOWER(file_name) LIKE {0}", pat)
                    .or()
                    .apply("LOWER(title) LIKE {0}", pat));
        }
        List<String> patterns = fileTypePatterns(fileTypes);
        boolean includeUrl = patterns.remove("<<url>>");
        if (!patterns.isEmpty() || includeUrl) {
            qw.and(w -> {
                for (int i = 0; i < patterns.size(); i++) {
                    if (i > 0) {
                        w.or();
                    }
                    w.apply("LOWER(file_name) LIKE {0}", patterns.get(i));
                }
                if (includeUrl) {
                    if (!patterns.isEmpty()) {
                        w.or();
                    }
                    w.eq(Knowledge::getType, "url");
                }
            });
        }
        long total = knowledgeMapper.selectCount(qw);
        List<Knowledge> rows = knowledgeMapper.selectList(qw
                .orderByDesc(Knowledge::getCreatedAt)
                .last("LIMIT " + (limit + 1) + " OFFSET " + offset));
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        // knowledge_base_name 回填
        Map<String, String> names = new HashMap<>();
        for (KnowledgeSearchScope s : valid) {
            KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getId, s.kbId())
                    .eq(KnowledgeBase::getTenantId, s.tenantId())
                    .isNull(KnowledgeBase::getDeletedAt)
                    .last("LIMIT 1"));
            if (kb != null) {
                names.put(kb.getId(), kb.getName());
            }
        }
        for (Knowledge row : rows) {
            row.setKnowledgeBaseName(names.getOrDefault(row.getKnowledgeBaseId(), ""));
        }
        return new SearchOutcome(rows, hasMore, total);
    }

    /** \、%、_ 前加反斜杠（LIKE 默认转义符，H2/PG 一致）。 */
    static String escapeLikeKeyword(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 小写、去前导点、保序去重、别名互认
     * （xlsx↔xls / docx↔doc / jpg↔jpeg↔png）；url/html 折成 type='url' 条件（哨兵 &lt;&lt;url&gt;&gt;）。
     */
    static List<String> fileTypePatterns(List<String> fileTypes) {
        List<String> patterns = new ArrayList<>();
        if (fileTypes == null) {
            return patterns;
        }
        Set<String> seen = new HashSet<>();
        for (String ft : fileTypes) {
            String f = ft == null ? "" : ft.toLowerCase();
            while (f.startsWith(".")) {
                f = f.substring(1);
            }
            if ("url".equals(f) || "html".equals(f)) {
                if (!seen.contains("<<url>>")) {
                    seen.add("<<url>>");
                    patterns.add("<<url>>");
                }
                continue;
            }
            String pat = "%." + f;
            if (!seen.contains(pat)) {
                seen.add(pat);
                patterns.add(pat);
            }
            List<String> aliases = switch (f) {
                case "xlsx" -> List.of("%.xls");
                case "xls" -> List.of("%.xlsx");
                case "docx" -> List.of("%.doc");
                case "doc" -> List.of("%.docx");
                case "jpg" -> List.of("%.jpeg", "%.png");
                case "jpeg" -> List.of("%.jpg", "%.png");
                case "png" -> List.of("%.jpg", "%.jpeg");
                default -> List.<String>of();
            };
            for (String alias : aliases) {
                if (!seen.contains(alias)) {
                    seen.add(alias);
                    patterns.add(alias);
                }
            }
        }
        return patterns;
    }

}
