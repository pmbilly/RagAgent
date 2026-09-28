package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.InputSanitizer;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识文件夹树 + 文件夹移动 / 重命名（对照 Go types.BuildKnowledgeFolderTree /
 * MoveKnowledgeToFolder / RenameKnowledgeFolder / loadKnowledgeWrite*；原
 * KnowledgeService「── folders（波 2 升级）」与「── 文件夹移动 / 重命名（波 2）」
 * 两段拆分独立）。
 *
 * <p>门面 helper（requireKb/findKb/tenantId/getKnowledgeBatch）经 {@code @Lazy}
 * 门面调用，不复制；{@link #rejectMovingKnowledge} 同包开放给批量面
 * （KnowledgeBatchOpsService）复用。</p>
 */
@Service
public class KnowledgeFolderService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeService facade;

    public KnowledgeFolderService(KnowledgeMapper knowledgeMapper,
                                  KnowledgeBaseMapper kbMapper,
                                  @Lazy KnowledgeService facade) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.facade = facade;
    }

    // ── folders（波 2 升级：对照 types.BuildKnowledgeFolderTree 完整树） ──

    /**
     * 对照 KnowledgeFolderTree：root_document_count/total_document_count/folders。
     * 计数只排除 parse_status='deleting'（draft 计入）+ 软删行；中间空目录会被
     * 具体化以保持层级连通；同名排序按 path 字节序（Go strings.&lt;）。
     */
    public JsonNode folderTree(String kbId) {
        facade.requireKb(kbId);
        // 对照 ListKnowledgeFolderCounts：GROUP BY folder_path（Java 侧取列后内存聚合，
        // 语义一致：tenant+kb+parse_status<>'deleting'+deleted_at IS NULL）
        List<Knowledge> docs = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .select(Knowledge::getFolderPath)
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getTenantId, KnowledgeService.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .ne(Knowledge::getParseStatus, Knowledge.PARSE_DELETING));
        Map<String, Long> counts = new TreeMap<>();
        for (Knowledge d : docs) {
            counts.merge(d.getFolderPath() == null ? "" : d.getFolderPath(), 1L, Long::sum);
        }

        long rootCount = 0;
        long totalCount = 0;
        Map<String, ObjectNode> nodes = new TreeMap<>();
        Map<String, List<ObjectNode>> children = new TreeMap<>();
        ArrayNode top = MAPPER.createArrayNode();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            long count = e.getValue();
            totalCount += count;
            String path = normalizeKnowledgeFolderPath(e.getKey());
            if (path.isEmpty()) {
                rootCount += count;
                continue;
            }
            ObjectNode node = ensureFolderNode(path, nodes, children, top);
            node.put("document_count", node.path("document_count").asLong() + count);
        }
        // 对照：深度优先回卷 total_count（深路径先算，父级累加子树）
        List<String> paths = new ArrayList<>(nodes.keySet());
        paths.sort((a, b) -> {
            int da = countChar(a, '/'), db = countChar(b, '/');
            return da != db ? Integer.compare(db, da) : a.compareTo(b);
        });
        for (String path : paths) {
            ObjectNode node = nodes.get(path);
            long total = node.path("document_count").asLong() + children
                    .getOrDefault(path, List.of()).stream()
                    .mapToLong(c -> c.path("total_count").asLong()).sum();
            node.put("total_count", total);
        }
        // 对照 sortNodes：children/Folders 都按 name 的小写序排；空 children 整键缺席
        //（KnowledgeFolderNode.Children omitempty）
        for (Map.Entry<String, List<ObjectNode>> e : children.entrySet()) {
            List<ObjectNode> list = e.getValue();
            list.sort(byNameLower);
            nodes.get(e.getKey()).set("children", MAPPER.createArrayNode().addAll(list));
        }
        List<ObjectNode> topList = new ArrayList<>();
        top.forEach(n -> topList.add((ObjectNode) n));
        topList.sort(byNameLower);
        top.removeAll();
        topList.forEach(top::add);
        ObjectNode tree = MAPPER.createObjectNode();
        tree.put("root_document_count", rootCount);
        tree.put("total_document_count", totalCount);
        tree.set("folders", top);
        return tree;
    }

    private static final java.util.Comparator<ObjectNode> byNameLower =
            java.util.Comparator.comparing(n -> n.path("name").asText("").toLowerCase(java.util.Locale.ROOT));

    /** 对照 ensure：节点 + 缺失祖先具体化；顶级挂 Folders，子级挂 parent.Children。 */
    private static ObjectNode ensureFolderNode(String path, Map<String, ObjectNode> nodes,
                                               Map<String, List<ObjectNode>> children, ArrayNode top) {
        ObjectNode existing = nodes.get(path);
        if (existing != null) {
            return existing;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("path", path);
        node.put("name", folderName(path));
        node.put("document_count", 0);
        node.put("total_count", 0);
        nodes.put(path, node);
        String parent = folderParent(path);
        if (parent.isEmpty()) {
            top.add(node);
        } else {
            ensureFolderNode(parent, nodes, children, top);
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
        }
        return node;
    }

    private static String folderName(String path) {
        if (path.isEmpty()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String folderParent(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * 对照 types.NormalizeKnowledgeFolderPath（knowledge_folder.go L38-82）：
     * \ → /、分段 trim、去尾部 ". "、跳过空/./.. 段、单段 ≤128 字节、深度 ≤16、总长 ≤1024。
     */
    public static String normalizeKnowledgeFolderPath(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        raw = raw.replace('\\', '/');
        List<String> segments = new ArrayList<>(8);
        for (String segment : raw.split("/", -1)) {
            segment = segment.trim();
            segment = stripTrailing(segment, ". ");
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                continue;
            }
            if (segment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) {
                byte[] bytes = segment.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int cut = 128;
                while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
                    cut--; // 回退到 rune 起点
                }
                segment = new String(bytes, 0, cut, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            if (segment.isEmpty()) {
                continue;
            }
            segments.add(segment);
            if (segments.size() >= 16) {
                break;
            }
        }
        String path = String.join("/", segments);
        while (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 && !segments.isEmpty()) {
            segments = segments.subList(0, segments.size() - 1);
            path = String.join("/", segments);
        }
        return path;
    }

    private static String stripTrailing(String s, String cutset) {
        while (!s.isEmpty() && cutset.indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ── 文件夹移动 / 重命名（波 2） ──────────────────────────────────────

    /**
     * 对照 MoveKnowledgeToFolder（service 层）。调用前 handler 已做
     * requireKnowledgeInKB，这里的 loadKnowledgeWriteBatch/kb 校验属 Go 的双保险，
     * Java 保留同序（writeResourceIDs 空 id → 400；跨 KB → 403 "knowledge outside target KB"）。
     *
     * @return affected 行数（UpdateKnowledgeFolderPath 的 RowsAffected）
     */
    @Transactional
    public long moveKnowledgeToFolder(String kbId, List<String> ids, String folderPath) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("knowledge_ids cannot be empty");
        }
        String normalized = normalizeTargetFolderPath(folderPath);
        List<Knowledge> rows = loadKnowledgeWriteBatch(ids, kbId);
        List<String> checkedIds = new ArrayList<>(rows.size());
        for (Knowledge row : rows) {
            if (!row.getKnowledgeBaseId().equals(kbId)) {
                throw BizException.forbidden("knowledge outside target KB");
            }
            checkedIds.add(row.getId());
        }
        long tenantId = rows.get(0).getTenantId();
        return knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("tenant_id", tenantId)
                .eq("knowledge_base_id", kbId)
                .in("id", checkedIds)
                .set("folder_path", normalized)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * 对照 RenameKnowledgeFolder（service 层）：source/target 规范化、同路径短路面、
     * 不能移进自身子目录、KB 缺失 → 404。@return affected 行数。
     */
    @Transactional
    public long renameKnowledgeFolder(String kbId, String from, String to) {
        String source = normalizeKnowledgeFolderPath(from);
        if (source.isEmpty()) {
            throw BizException.badRequest("源文件夹路径不能为空");
        }
        String target = normalizeTargetFolderPath(to);
        if (target.isEmpty()) {
            throw BizException.badRequest("目标文件夹路径不能为空");
        }
        if (target.equals(source)) {
            return 0;
        }
        if (target.startsWith(source + "/")) {
            throw BizException.badRequest("不能将文件夹移动到它自己的子目录下");
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null || !kb.getId().equals(kbId)) {
            throw BizException.notFound("knowledge base not found");
        }
        // 对照 RenameKnowledgeFolderPath：行级重写（folder_path = source 或 source+"/%"），
        // 目标 = Normalize(to + suffix)，按目标分组批量 UPDATE（Go 双重循环的净效果）
        List<Knowledge> rows = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .select(Knowledge::getId, Knowledge::getFolderPath)
                .eq(Knowledge::getTenantId, kb.getTenantId())
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .and(w -> w.eq(Knowledge::getFolderPath, source)
                        .or().likeRight(Knowledge::getFolderPath, source + "/")));
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, List<String>> byTarget = new TreeMap<>();
        for (Knowledge row : rows) {
            String suffix = row.getFolderPath().startsWith(source)
                    ? row.getFolderPath().substring(source.length()) : row.getFolderPath();
            byTarget.computeIfAbsent(normalizeKnowledgeFolderPath(target + suffix), k -> new ArrayList<>())
                    .add(row.getId());
        }
        long affected = 0;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Map.Entry<String, List<String>> e : byTarget.entrySet()) {
            affected += knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("tenant_id", kb.getTenantId())
                    .eq("knowledge_base_id", kbId)
                    .in("id", e.getValue())
                    .set("folder_path", e.getKey())
                    .set("updated_at", now));
        }
        return affected;
    }

    /** 对照 normalizeTargetFolderPath：trim → ValidateInput（非法 → 1010）→ Normalize。 */
    private static String normalizeTargetFolderPath(String folderPath) {
        String trimmed = folderPath == null ? "" : folderPath.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String safe = InputSanitizer.validateInput(trimmed);
        if (safe == null) {
            throw new BizException(AppError.validation("文件夹路径包含非法字符"));
        }
        return normalizeKnowledgeFolderPath(safe);
    }

    /**
     * 对照 loadKnowledgeWriteBatch（knowledge_write.go L101-145）：逐 id 校验存在性、
     * moving 状态、KB 绑定与 <b>requireKBWrite 授权</b>；缺行 → 404 "knowledge not
     * found"（小写，golden 钉住）；行落在授权 KB 之外 → 403 "无权修改该知识库"
     * （golden kg-tags-cross-kb 钉住——Go 的 grant 只覆盖进入 handler 时解析的那一个 KB）。
     *
     * @param grantedKbId 当前请求已授权的那个 KB（kb_id 路径 = 显式 kb_id；无 kb_id 路径 =
     *                    首条 knowledge 的 KB；单行 loadKnowledgeWrite 的调用方传 null）
     */
    public List<Knowledge> loadKnowledgeWriteBatch(List<String> ids, String grantedKbId) {
        List<String> cleaned = new ArrayList<>(ids.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            if (id == null || id.trim().isEmpty()) {
                throw BizException.badRequest("resource ID cannot be empty");
            }
            if (seen.add(id)) {
                cleaned.add(id);
            }
        }
        List<Knowledge> rows = facade.getKnowledgeBatch(KnowledgeService.tenantId(), cleaned);
        Map<String, Knowledge> byId = new java.util.HashMap<>();
        for (Knowledge row : rows) {
            byId.put(row.getId(), row);
        }
        List<Knowledge> result = new ArrayList<>(cleaned.size());
        java.util.Set<String> checkedKbs = new java.util.HashSet<>();
        for (String id : cleaned) {
            Knowledge row = byId.get(id);
            if (row == null) {
                throw BizException.notFound("knowledge not found");
            }
            rejectMovingKnowledge(row);
            if (checkedKbs.add(row.getKnowledgeBaseId())) {
                // knowledgeWriteKB：KB 行与 (id, tenant) 绑定一致，否则 403
                KnowledgeBase kb = facade.findKb(row.getKnowledgeBaseId());
                if (kb == null || !kb.getTenantId().equals(row.getTenantId())) {
                    throw BizException.forbidden("knowledge does not belong to its knowledge base");
                }
                // requireKBWrite：grant 只覆盖授权 KB（org-share 分支未翻译）
                if (grantedKbId == null || !grantedKbId.equals(row.getKnowledgeBaseId())) {
                    throw BizException.forbidden("无权修改该知识库");
                }
            }
            result.add(row);
        }
        return result;
    }

    /** 对照 loadKnowledgeWrite 的单行版（校验同上 + KB 绑定一致性）。 */
    public Knowledge loadKnowledgeWrite(String id) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, KnowledgeService.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw BizException.notFound("knowledge not found");
        }
        rejectMovingKnowledge(k);
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, k.getKnowledgeBaseId())
                .eq(KnowledgeBase::getTenantId, k.getTenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null || !kb.getId().equals(k.getKnowledgeBaseId())
                || !kb.getTenantId().equals(k.getTenantId())) {
            throw BizException.forbidden("knowledge does not belong to its knowledge base");
        }
        return k;
    }

    /** 对照 access.RejectMovingKnowledge：transfer metadata 里 operation=move 且
     *  phase=moving → 409（本批路由的固定状态防线）。同包开放（批量清空复用）。 */
    static void rejectMovingKnowledge(Knowledge k) {
        JsonNode metadata = k.getMetadata();
        if (metadata == null || !metadata.has("_knowledge_transfer")) {
            return;
        }
        JsonNode state = metadata.get("_knowledge_transfer");
        if ("move".equals(state.path("operation").asText(""))
                && "moving".equals(state.path("phase").asText(""))) {
            throw BizException.conflict("knowledge has an unfinished move; retry the move first");
        }
    }

}
