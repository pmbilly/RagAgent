package com.ragagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * agent 配置树的**键名守卫**：主代码里不许再出现"以 snake 字面量读写 agent 配置"的地方。
 *
 * <p><b>为什么需要它</b>：B18 把 {@code custom_agents.config} 的键迁到 camel（= Java 字段名）后，
 * 仍有读取点按 snake 取值——{@code ModelService.agentBindings} 就是漏网的一个（它扫 agent 配置算
 * "模型被谁引用"，键名不对 ⇒ 绑定**静默丢失**，而当时 4711 条测试全绿、毫无提示）。这类缺陷只有
 * 源码级扫描能兜住。</p>
 *
 * <p><b>键表来源</b>：直接解析 {@code migrations/versioned/V3__agent_config_keys_camel.sql} 的映射
 * ——迁移与守卫共用一份键表，避免两处各自维护而漂移。</p>
 *
 * <p><b>白名单（合法使用 snake 的面，非"agent 配置键"）</b>：追踪/span 载荷、LLM 请求体、DB 列名与
 * 表名、提示词占位符名、第三方协议面、租户配置 jsonb、事件载荷等——见 {@link #ALLOWED_PREFIXES}。</p>
 */
class AgentConfigKeyUsageTest {

    private static final Path MAIN = Path.of("src/main/java/com/ragagent");
    private static final Path MIGRATION = Path.of("../migrations/versioned/V3__agent_config_keys_camel.sql");

    /** 合法 snake 面（按路径前缀/片段排除）。 */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "auth/domain/tenantconfig/", "event/payload", "llm/", "common/pipeline/SearchParams",
            "tracing/langfuse", "datasource/connector", "stream/", "mcp/oauth", "mcp/domain/McpService",
            "retrieval/engine/doris", "retrieval/domain/ImageInfo", "rerank/RankResult",
            "common/wiki/ExtractedItem", "chatpipeline/plugin", "agent/AgentEngine", "agent/ReActIteration",
            "retrieval/HybridSearchService", "retrieval/vlm/VlmClient", "knowledge/service/ChunkExtractService",
            "knowledge/task/KnowledgeProcessWorker", "memory/mapper", "agent/tools/",
            "common/prompt/AgentPromptPlaceholders", "knowledge/domain/KnowledgeBase",
            // HTTP 面占位符定义：此处的 snake 是**模板令牌**（数据值，如 {{knowledge_bases}}），
            // 不是 agent 配置键；两面一致性由 AgentPlaceholdersTest 单独守。
            "agent/management/service/AgentPlaceholders",
            "session/mapper/MessageSuggestionRepository", "session/mapper/MessageMapper",
            "session/mapper/MessageRepository");

    @Test
    @DisplayName("主代码不许再以 snake 字面量读写 agent 配置键（键表取自 V3 迁移）")
    void noSnakeAgentConfigKeysInMainCode() throws Exception {
        List<String> keys = mappingKeys();
        assertThat(keys).as("V3 迁移里应解析出 agent 配置键表").hasSizeGreaterThan(50);

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString();
                if (ALLOWED_PREFIXES.stream().anyMatch(rel::startsWith)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(p);
                String text = String.join("\n", lines);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().startsWith("//") || line.trim().startsWith("*")) {
                        continue;
                    }
                    for (String key : keys) {
                        if (!line.contains("\"" + key + "\"")) {
                            continue;
                        }
                        // 同文件内自己写入过该键（put/putObject/putArray/set）⇒ 属该文件自有载荷
                        // （如 ModelService 的 model-usage 响应），不是"读 agent 配置"，跳过。
                        if (text.contains("put(\"" + key + "\"") || text.contains("putObject(\"" + key + "\"")
                                || text.contains("putArray(\"" + key + "\"") || text.contains("set(\"" + key + "\"")) {
                            continue;
                        }
                        offenders.add(rel + ":" + (i + 1) + "  \"" + key + "\"");
                    }
                }
            }
        }
        assertThat(offenders)
                .as("agent 配置树自 B18 起键名 = Java 字段名（camel）；下列位置仍按 snake 读写 ⇒ 会静默取不到值")
                .isEmpty();
    }

    /** 从 V3 迁移的 {@code mapping jsonb := '{...}'} 里取出 snake 键集合。 */
    private static List<String> mappingKeys() throws Exception {
        String sql = Files.readString(MIGRATION);
        Matcher m = Pattern.compile("mapping jsonb := '(\\{.*?\\})'::jsonb", Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).as("V3 迁移里应能找到 mapping 映射").isTrue();
        String json = m.group(1);
        List<String> keys = new ArrayList<>();
        Matcher k = Pattern.compile("\"([a-z][a-z0-9_]*)\"\\s*:").matcher(json);
        while (k.find()) {
            keys.add(k.group(1));
        }
        return keys;
    }
}
