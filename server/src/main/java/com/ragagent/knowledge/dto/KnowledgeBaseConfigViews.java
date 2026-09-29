package com.ragagent.knowledge.dto;

import java.util.List;

import com.ragagent.knowledge.domain.KbAsrConfig;
import com.ragagent.knowledge.domain.KbChunkingConfig;
import com.ragagent.knowledge.domain.KbImageProcessingConfig;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.KbVlmConfig;

/**
 * 知识库配置项的**对外视图**（camelCase 契约形态）。
 *
 * <p>为什么单独建视图而不是直接复用领域类：领域类（{@code Kb*Config}）上的 Jackson
 * 注解同时决定 <b>jsonb 落库格式</b>（{@code PgJsonTypeHandler} 走同一套注解）——
 * 直接改注解会连带改写数据库存储格式。视图层让"接口契约"与"落库格式"解耦，
 * 这也是本仓 DTO 化的既定方向（请求侧 DTO 化时复用同一批视图）。</p>
 *
 * <p>契约规则：JSON 字段名 = Java 字段名；可空字段显式输出 {@code null}
 * （不再有"有时出现有时消失"的键）；不下发凭据类字段（VLM 的 {@code apiKey} 已剔除）。</p>
 */
public final class KnowledgeBaseConfigViews {

    private KnowledgeBaseConfigViews() {
    }

    /** 分块配置。 */
    public record ChunkingConfigView(
            int chunkSize,
            int chunkOverlap,
            List<String> separators,
            List<ParserEngineRuleView> parserEngineRules,
            boolean enableParentChild,
            int parentChunkSize,
            int childChunkSize,
            String strategy,
            int tokenLimit,
            List<String> languages,
            String tableMetadataInstructions) {

        /** 按扩展名指定解析引擎的规则（如 xlsx 用哪个引擎、首行是否表头）。 */
        public record ParserEngineRuleView(List<String> fileTypes, String engine,
                                           Boolean xlsxFirstRowAsHeader) {
        }

        /** 请求侧反向映射（视图 → 领域）。 */
        public KbChunkingConfig toDomain() {
            KbChunkingConfig c = new KbChunkingConfig();
            c.setChunkSize(chunkSize);
            c.setChunkOverlap(chunkOverlap);
            c.setSeparators(separators);
            if (parserEngineRules != null) {
                c.setParserEngineRules(parserEngineRules.stream().map(r -> {
                    KbChunkingConfig.ParserEngineRule rule = new KbChunkingConfig.ParserEngineRule();
                    rule.setFileTypes(r.fileTypes());
                    rule.setEngine(r.engine());
                    rule.setXlsxFirstRowAsHeader(r.xlsxFirstRowAsHeader());
                    return rule;
                }).toList());
            }
            c.setEnableParentChild(enableParentChild);
            c.setParentChunkSize(parentChunkSize);
            c.setChildChunkSize(childChunkSize);
            c.setStrategy(strategy);
            c.setTokenLimit(tokenLimit);
            c.setLanguages(languages);
            c.setTableMetadataInstructions(tableMetadataInstructions);
            return c;
        }

        public static ChunkingConfigView from(KbChunkingConfig c) {
            if (c == null) {
                return null;
            }
            List<ParserEngineRuleView> rules = c.getParserEngineRules() == null ? null
                    : c.getParserEngineRules().stream()
                            .map(r -> new ParserEngineRuleView(r.getFileTypes(), r.getEngine(),
                                    r.getXlsxFirstRowAsHeader()))
                            .toList();
            return new ChunkingConfigView(c.getChunkSize(), c.getChunkOverlap(), c.getSeparators(),
                    rules, c.isEnableParentChild(), c.getParentChunkSize(), c.getChildChunkSize(),
                    c.getStrategy(), c.getTokenLimit(), c.getLanguages(),
                    c.getTableMetadataInstructions());
        }
    }

    /** 图像处理配置（多模态描述用的 VLM 模型）。 */
    public record ImageProcessingConfigView(String modelId) {

        public static ImageProcessingConfigView from(KbImageProcessingConfig c) {
            return c == null ? null : new ImageProcessingConfigView(c.getModelId());
        }

        public KbImageProcessingConfig toDomain() {
            KbImageProcessingConfig c = new KbImageProcessingConfig();
            c.setModelId(modelId);
            return c;
        }
    }

    /**
     * 多模态（VLM）配置视图。
     *
     * <p>⚠️ 有意剔除 {@code apiKey}：凭据只进不出，前端如需展示"是否已配置"应改读
     * {@code enabled}/{@code modelId}（见契约文档 本仓约定 第 12 条）。</p>
     */
    public record VlmConfigView(
            boolean enabled,
            String modelId,
            String descriptionLanguage,
            String customInstructions,
            String modelName,
            String baseUrl,
            String interfaceType) {

        public static VlmConfigView from(KbVlmConfig c) {
            if (c == null) {
                return null;
            }
            return new VlmConfigView(c.isEnabled(), c.getModelId(), c.getDescriptionLanguage(),
                    c.getCustomInstructions(), c.getModelName(), c.getBaseUrl(), c.getInterfaceType());
        }
    }

    /** 语音识别（ASR）配置。 */
    public record AsrConfigView(boolean enabled, String modelId, String language) {

        public static AsrConfigView from(KbAsrConfig c) {
            return c == null ? null : new AsrConfigView(c.isEnabled(), c.getModelId(), c.getLanguage());
        }

        public KbAsrConfig toDomain() {
            KbAsrConfig c = new KbAsrConfig();
            c.setEnabled(enabled);
            c.setModelId(modelId);
            c.setLanguage(language);
            return c;
        }
    }

    /** 索引策略：向量 / 关键词 / wiki / 图谱 四路开关。 */
    public record IndexingStrategyView(boolean vectorEnabled, boolean keywordEnabled,
                                       boolean wikiEnabled, boolean graphEnabled) {

        public static IndexingStrategyView from(KbIndexingStrategy s) {
            return s == null ? null
                    : new IndexingStrategyView(s.isVectorEnabled(), s.isKeywordEnabled(),
                            s.isWikiEnabled(), s.isGraphEnabled());
        }

        public KbIndexingStrategy toDomain() {
            KbIndexingStrategy s = new KbIndexingStrategy();
            s.setVectorEnabled(vectorEnabled);
            s.setKeywordEnabled(keywordEnabled);
            s.setWikiEnabled(wikiEnabled);
            s.setGraphEnabled(graphEnabled);
            return s;
        }
    }
}
