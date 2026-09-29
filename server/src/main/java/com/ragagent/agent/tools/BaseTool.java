package com.ragagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具基类。
 * 提供 name/description/schema 三件套与共享的输出格式化函数。
 */
public abstract class BaseTool implements AgentTool {

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    private final String name;
    private final String description;
    private final JsonNode schema;

    protected BaseTool(String name, String description, JsonNode schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }

    /** schema 以原始 JSON 字符串给出（解析保序，字节形态不变）。 */
    protected BaseTool(String name, String description, String schemaJson) {
        this(name, description, parse(schemaJson));
    }

    private static JsonNode parse(String schemaJson) {
        try {
            return SCHEMA_MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid tool schema JSON", e);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public JsonNode getParameters() {
        return schema;
    }

    /**
     * 相关度分级：≥0.8→High Relevance、≥0.6→Medium Relevance、
     * ≥0.4→Low Relevance，其余→Weak Relevance。
     */
    public static String getRelevanceLevel(double score) {
        if (score >= 0.8) {
            return "High Relevance";
        }
        if (score >= 0.6) {
            return "Medium Relevance";
        }
        if (score >= 0.4) {
            return "Low Relevance";
        }
        return "Weak Relevance";
    }

    /**
     * MatchType → 人读文案。MatchType 为 int 枚举：
     * 0..6 → Vector/Keyword/Adjacent Chunk/History/Parent Chunk/Relation Chunk/Graph
     * Match；其余 {@code Unknown Type(%d)}。
     */
    public static String formatMatchType(int matchType) {
        return switch (matchType) {
            case 0 -> "Vector Match";
            case 1 -> "Keyword Match";
            case 2 -> "Adjacent Chunk Match";
            case 3 -> "History Match";
            case 4 -> "Parent Chunk Match";
            case 5 -> "Relation Chunk Match";
            case 6 -> "Graph Match";
            default -> "Unknown Type(" + matchType + ")";
        };
    }
}
