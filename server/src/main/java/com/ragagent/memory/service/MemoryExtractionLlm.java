package com.ragagent.memory.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemoryTombstone;
import com.ragagent.memory.domain.MemoryTopicStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽取的提示词与模型调用：把片段渲染成抽取 prompt、取既有记忆做对照、调抽取模型、
 * 截断判定与响应解析，以及模型输出的反序列化形状。
 *
 * <p>持有 {@link MemoryExtractionService} 回引以访问其依赖；本类不得独立实例化。</p>
 */
final class MemoryExtractionLlm {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionLlm.class);

    private final MemoryExtractionService service;

    MemoryExtractionLlm(MemoryExtractionService service) {
        this.service = service;
    }

    /**
     * 对照 Go {@code buildExtractionPrompt}：渲染调用的用户侧——
     * 前置上下文、编号过的待抽取行、已知的东西、用户已经拒绝过的东西。
     */
    static String buildExtractionPrompt(MemoryExtractionService.TranscriptSegment segment, List<MemoryItem> existing,
                                        List<MemoryTombstone> forgotten,
                                        List<MemoryTopicStat> knownTopics, String instructions) {
        StringBuilder builder = new StringBuilder();

        if (segment.context != null && !segment.context.isEmpty()) {
            builder.append("Earlier in this conversation (context only, do not record from these):\n");
            for (String line : segment.context) {
                builder.append("- ").append(line).append('\n');
            }
            builder.append('\n');
        }

        builder.append("Existing notes:\n");
        if (existing == null || existing.isEmpty()) {
            builder.append("(none)\n");
        }
        if (existing != null) {
            for (int index = 0; index < existing.size(); index++) {
                MemoryItem item = existing.get(index);
                if (item == null) {
                    continue;
                }
                builder.append('[').append(index).append("] [").append(item.getKind())
                        .append("] (topic: ").append(item.getTopic()).append(") ")
                        .append(item.getContent()).append('\n');
            }
        }

        if (forgotten != null && !forgotten.isEmpty()) {
            // 把被拒绝的主题点名，让模型避开重新推导一个改了说法的版本——
            // 那个是精确指纹检查抓不到的。
            builder.append("\nThe user deleted notes about these topics. Do not re-add them ")
                    .append("unless this transcript says something genuinely new about them:\n");
            for (MemoryTombstone tombstone : forgotten) {
                if (tombstone == null || tombstone.getTopic().isEmpty()) {
                    continue;
                }
                builder.append("- ").append(tombstone.getTopic()).append('\n');
            }
        }

        if (knownTopics != null && !knownTopics.isEmpty()) {
            // 展示词汇表是话题解析最便宜的那一层：复述一个已有标签的模型几乎零成本就能匹配上，
            // 而被放任自行命名的模型每次都会发明一个不同的名字。
            //
            // 措辞必须考的是**同一性**，不是相关性。
            builder.append("\nSubjects already tracked for this user:\n");
            int shown = 0;
            for (MemoryTopicStat stat : knownTopics) {
                if (stat == null || stat.getTopic().isEmpty()) {
                    continue;
                }
                builder.append("- ").append(stat.getTopic()).append('\n');
                // 一份长列表会招来"从里面挑一个"。解析器仍然考虑每一个被跟踪的主体；
                // 这里只是抽取调用看得见的那部分。
                if (++shown >= MemoryExtractionService.EXTRACT_SHOWN_TOPICS) {
                    break;
                }
            }
            builder.append("Reuse one of these labels EXACTLY only when the transcript is about ")
                    .append("the SAME subject,\n")
                    .append("just worded differently. Being in the same domain is not enough: if\n")
                    .append("\"门店排班管理\" is tracked and the user asks how a shift swap gets approved, that\n")
                    .append("is a different subject (\"排班审批流程\") — building the roster and approving\n")
                    .append("changes to it are different things this person does.\n")
                    .append("When nothing above names the same subject, write a new label at the same level of\n")
                    .append("generality as these. Do not force a fit, and do not name the individual question.\n");
        }

        if (instructions != null && !instructions.isEmpty()) {
            builder.append("\nWorkspace rules (follow these in addition to the above):\n<rules>\n")
                    .append(instructions)
                    .append("\n</rules>\n");
        }

        builder.append("\nWhat the user said:\n<transcript>\n");
        for (int index = 0; index < segment.lines.size(); index++) {
            MemoryExtractionService.TranscriptLine line = segment.lines.get(index);
            builder.append('[').append(index + 1).append("] (")
                    .append(formatLineTime(line.at)).append(") ").append(line.content).append('\n');
        }
        builder.append("</transcript>\n");
        return builder.toString();
    }

    static String formatLineTime(OffsetDateTime at) {
        if (at == null) {
            return "0001-01-01 00:00";
        }
        return at.atZoneSameInstant(ZoneId.systemDefault()).format(MemoryExtractionService.LINE_TIME);
    }

    /**
     * 对照 Go {@code relevantExisting}：加载这段对话**可能**与之有关的已存记忆
     * ——给模型看的那些，好让它去更新或取代它们，而不是写一条几乎重复的。
     *
     * <p>选取是整个仓库上的一次语义查找。它以前会列出最重要的 200 条再在其中排序，
     * 那给抽取能注意到的东西设了天花板：第 200 条之后，一句与已存记忆矛盾的陈述是隐形的，
     * 于是模型写了它的第二份，两条都还在生效。去重不能被重要度封顶，
     * 因为一句新话撞上的那条记忆并不特别可能是一条重要的。</p>
     *
     * <p>小到可以整个展示的主体跳过查找：那次嵌入调用什么都决定不了，
     * 而抽取本来就已经付了一次模型调用的钱。</p>
     */
    List<MemoryItem> relevantExisting(MemoryScope scope, MemoryConfig cfg, MemoryExtractionService.TranscriptSegment segment) {
        List<MemoryItem> existing;
        try {
            // 比放得下的多要一条，这样"全都放得下"与"还有更多"不用第二次查询就能分辨。
            existing = service.repo.listActiveByKinds(scope, MemoryKinds.ALL,
                    MemoryExtractionService.EXTRACT_RELEVANT_CANDIDATES + 1);
        } catch (RuntimeException e) {
            throw new IllegalStateException("load existing memories: " + e.getMessage(), e);
        }
        if (existing == null || existing.size() <= MemoryExtractionService.EXTRACT_RELEVANT_CANDIDATES) {
            return existing;
        }

        StringBuilder query = new StringBuilder();
        for (MemoryExtractionService.TranscriptLine line : segment.lines) {
            query.append(line.content).append('\n');
        }

        MemoryVectorService.VectorSearchOutcome search = service.vectorService.vectorSearch(
                scope, cfg, query.toString(), MemoryKinds.ALL, MemoryExtractionService.EXTRACT_RELEVANT_CANDIDATES);
        if (search.hits() == null || search.hits().isEmpty()) {
            // 按重要度排序的前缀是诚实的回落：没有语义打分就没有东西可以排序，
            // 而最可能重要的记忆正是这个人一直留着的那些。
            log.info("memory: no semantic ranking available ({}), showing the {} most important memories",
                    search.skipReason(), MemoryExtractionService.EXTRACT_RELEVANT_CANDIDATES);
            return new ArrayList<>(existing.subList(0, MemoryExtractionService.EXTRACT_RELEVANT_CANDIDATES));
        }

        List<MemoryItem> relevant = new ArrayList<>(search.hits().size());
        for (var hit : search.hits()) {
            if (hit.item() != null) {
                relevant.add(hit.item());
            }
        }
        log.info("memory: showing extraction {} memories closest to this segment", relevant.size());
        return relevant;
    }

    /** 对照 Go {@code callExtractionModel}：写路径上唯一的一次 LLM 调用。 */
    ExtractionResponse callExtractionModel(MemoryConfig cfg, MemoryExtractPayload payload,
                                          MemoryExtractionService.TranscriptSegment segment, List<MemoryItem> existing,
                                          List<MemoryTombstone> forgotten,
                                          List<MemoryTopicStat> knownTopics, MemoryRunBudget budget) {
        String modelId = service.memoryService.extractionModelId(cfg, payload);
        if (modelId.isEmpty()) {
            // 返回错误让水位线留在原地。在这里跳过会悄悄消费掉这次运行拿到的每一条消息：
            // 蒸馏报成功、越过了它们，而没有任何模型看过它们——一个默认设置下的工作区
            // 就是这样落到"开着记忆功能却什么都没学到"的。
            throw new IllegalStateException(
                    "no chat model available for memory extraction; "
                            + "configure one under workspace memory settings");
        }
        LlmChatClient chatModel;
        try {
            chatModel = service.memoryService.modelResolver().getChatModel(modelId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("get extraction model: " + e.getMessage(), e);
        }

        String userPrompt = buildExtractionPrompt(segment, existing, forgotten, knownTopics,
                cfg.getExtractInstructions());

        ChatResponse response = completeExtraction(chatModel, userPrompt, MemoryExtractionService.EXTRACT_BUDGET_TOKENS, budget);
        if (response == null) {
            throw new MemoryExtractionService.InvalidExtractionOutputException("invalid memory extraction output: no response");
        }

        // 截断的调用会用宽裕得多的预算重试一次。无视"关思考"开关的推理模型会把整份预算
        // 花在思考上、返回空串，而那与"没什么可记的"无从分辨——除非看 finish reason。
        if (isTruncated(response)) {
            log.warn("memory: extraction hit the token ceiling with {} chars of content, "
                            + "retrying with {} tokens",
                    MemoryScopes.trimSpace(response.getContent()).length(), MemoryExtractionService.EXTRACT_BUDGET_RETRY_TOKENS);
            response = completeExtraction(chatModel, userPrompt, MemoryExtractionService.EXTRACT_BUDGET_RETRY_TOKENS, budget);
            if (response == null || isTruncated(response)) {
                throw new MemoryExtractionService.InvalidExtractionOutputException(
                        "invalid memory extraction output: no usable output within "
                                + MemoryExtractionService.EXTRACT_BUDGET_RETRY_TOKENS + " tokens; "
                                + "if this is a reasoning model, its thinking is consuming the budget");
            }
        }

        try {
            return parseExtractionResponse(response.getContent());
        } catch (RuntimeException e) {
            throw new MemoryExtractionService.InvalidExtractionOutputException(
                    "invalid memory extraction output: " + e.getMessage());
        }
    }

    /**
     * 对照 Go {@code completeExtraction}：发一次抽取调用。
     *
     * <p>思考是关掉的。这个代码库里其它每一次结构化输出调用都出于同样的理由关掉它：
     * 这是一个有固定 schema 的分类任务，推理什么都买不到，而在一个默认就会推理的模型上
     * 它会吃掉整份补全预算并返回空串。</p>
     */
    ChatResponse completeExtraction(LlmChatClient chatModel, String userPrompt, int budget,
                                            MemoryRunBudget runBudget) {
        ChatOptions options = new ChatOptions();
        options.setTemperature(0);
        options.setMaxCompletionTokens(budget);
        options.setThinking(Boolean.FALSE);
        options.setFormat(MemoryTopicResolver.readTree(MemoryExtractionService.EXTRACTION_SCHEMA));
        try {
            return runBudget.chat(chatModel,
                    List.of(ChatMessage.system(MemoryExtractionService.EXTRACTION_SYSTEM_PROMPT), ChatMessage.user(userPrompt)),
                    options);
        } catch (MemoryRunBudget.RunExpiredException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("extraction model call: " + e.getMessage(), e);
        }
    }

    /**
     * 对照 Go {@code isTruncated}：这次响应是不是还没说出任何有用的东西就把空间用完了。
     * 空正文即便没有 finish reason 也算截断，因为有些 provider 两个都不报。
     */
    static boolean isTruncated(ChatResponse response) {
        if (response == null) {
            return true;
        }
        if (MemoryScopes.trimSpace(response.getContent()).isEmpty()) {
            return true;
        }
        return "length".equals(response.getFinishReason());
    }

    /**
     * 对照 Go {@code parseExtractionResponse}：容忍模型常见的包装——
     * 围栏代码块与对象周围的散文。
     */
    static ExtractionResponse parseExtractionResponse(String content) {
        String trimmed = content == null ? "" : MemoryScopes.trimSpace(content);
        if (trimmed.isEmpty()) {
            return new ExtractionResponse();
        }
        int fence = trimmed.indexOf("```");
        if (fence >= 0) {
            String rest = trimmed.substring(fence + 3);
            int newline = rest.indexOf('\n');
            if (newline >= 0) {
                rest = rest.substring(newline + 1);
            }
            int end = rest.indexOf("```");
            if (end >= 0) {
                rest = rest.substring(0, end);
            }
            trimmed = MemoryScopes.trimSpace(rest);
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object in response");
        }
        try {
            return MemoryExtractionService.MAPPER.readValue(trimmed.substring(start, end + 1), ExtractionResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * 对照 Go {@code parseExpiry}：接受提示词要求的日期形式，别的一律忽略。
     *
     * <p>一个幻觉出来的、或者已经过去的日期会被丢掉而不是存下来——一条到达时就已经过期的
     * 条目会被写进去然后立刻归档。</p>
     */
    static OffsetDateTime parseExpiry(String value) {
        String trimmed = MemoryScopes.trimSpace(value);
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        // 布局顺序照抄 Go：先 "2006-01-02"，再 RFC3339。
        try {
            LocalDate date = LocalDate.parse(trimmed);
            OffsetDateTime parsed = date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
            return parsed.toInstant().isAfter(Instant.now()) ? parsed : null;
        } catch (RuntimeException ignored) {
            // 换下一种布局
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(trimmed);
            return parsed.toInstant().isAfter(Instant.now()) ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 对照 Go {@code extractionDecision}：抽取模型给出的一条指令。
     *
     * <p>字段名是**驼峰**——这里不是 HTTP 契约，而是模型输出的 JSON，
     * 键名与提示词里写给模型的一字不差（{@code expires_at} 是唯一的蛇形，
     * 因为提示词里就写的是它）。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ExtractionDecision {
        /** add / update / delete / none。别的值一律忽略——空 action 以前被当成 add，
         *  于是被截断的响应变成了一次静默写入。 */
        @JsonProperty("action")
        String action = "";
        @JsonProperty("kind")
        String kind = "";
        /** 已有笔记的下标，update 与 delete 必需。 */
        @JsonProperty("target")
        Integer target;
        @JsonProperty("topic")
        String topic = "";
        @JsonProperty("content")
        String content = "";
        @JsonProperty("importance")
        int importance;
        /** 陈述来自的行号（从 1 开始）。 */
        @JsonProperty("source")
        Integer source;
        /** {@code YYYY-MM-DD}。 */
        @JsonProperty("expires_at")
        String expiresAt = "";
        /** 标记这是关于用户的一个推断，而不是复述。 */
        @JsonProperty("inferred")
        boolean inferred;

        /**
         * 对照 Go {@code resolveSource}：把一条决定映射回它来自的那条消息。
         * 行号缺失或越界时回落到片段的第一条消息，那仍然在同一段对话里。
         */
        MemoryExtractionService.TranscriptLine resolveSource(MemoryExtractionService.TranscriptSegment segment) {
            if (segment.lines.isEmpty()) {
                return new MemoryExtractionService.TranscriptLine();
            }
            if (source != null && source >= 1 && source <= segment.lines.size()) {
                return segment.lines.get(source - 1);
            }
            return segment.lines.get(0);
        }
    }

    /** 对照 Go {@code extractionResponse}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ExtractionResponse {
        @JsonProperty("memories")
        List<ExtractionDecision> memories = new ArrayList<>();
        /** 用户问过的主题：只计数，复现之后才成为兴趣。 */
        @JsonProperty("topics")
        List<String> topics = new ArrayList<>();
    }
}
