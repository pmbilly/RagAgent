package com.ragagent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryKinds;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryTombstone;
import com.ragagent.memory.domain.MemoryTopicStat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * extract.go 的纯函数对等测试（{@code resolveSource} / {@code parseExpiry} /
 * {@code parseExtractionResponse} / {@code isTruncated} / {@code statusForWrite} /
 * {@code buildExtractionPrompt} / 负载 JSON）。
 *
 * <p><b>全部期望值是 Go 实录</b>（{@code go test -run TestTruthExtractHelpers} /
 * {@code TestTruthExtractionPrompt} / {@code TestTruthPayload}）。
 * {@code buildExtractionPrompt} 更是逐字节比对——它是喂给模型的契约，
 * 措辞松紧直接改变线上抽取质量。</p>
 */
class MemoryExtractionHelpersTest {

    private static OffsetDateTime at(String date, int hour, int minute) {
        return LocalDateTime.of(2026, 3, 2, hour, minute)
                .atZone(ZoneId.systemDefault())
                .toOffsetDateTime();
    }

    private static MemoryExtractionService.TranscriptLine line(String messageId, OffsetDateTime at,
                                                               String content) {
        return new MemoryExtractionService.TranscriptLine("s", messageId, at, content);
    }

    private static MemoryExtractionService.TranscriptSegment segment(int n) {
        MemoryExtractionService.TranscriptSegment s = new MemoryExtractionService.TranscriptSegment();
        s.sessionId = "s";
        for (int i = 0; i < n; i++) {
            s.lines.add(line("m" + (i + 1), null, "line" + (i + 1)));
        }
        return s;
    }

    private static MemoryItem item(String id, String kind, String topic, String content) {
        MemoryItem it = new MemoryItem();
        it.setId(id);
        it.setKind(kind);
        it.setTopic(topic);
        it.setContent(content);
        it.setValidFrom(OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        return it;
    }

    private static String lineOf(MemoryExtractionService.TranscriptLine l) {
        if (l.messageId.isEmpty() && l.content.isEmpty()) {
            return "<zero>";
        }
        return l.messageId + "|" + l.content;
    }

    @Nested
    @DisplayName("resolveSource")
    class ResolveSource {

        @Test
        void fallsBackToTheFirstLineWhenTheIndexIsMissingOrOutOfRange() {
            // Go 实录：nil→m1|line1、1→m1|line1、3→m3|line3、9→m1|line1、0→m1|line1
            assertThat(lineOf(decision(null).resolveSource(segment(3)))).isEqualTo("m1|line1");
            assertThat(lineOf(decision(1).resolveSource(segment(3)))).isEqualTo("m1|line1");
            assertThat(lineOf(decision(3).resolveSource(segment(3)))).isEqualTo("m3|line3");
            assertThat(lineOf(decision(9).resolveSource(segment(3)))).isEqualTo("m1|line1");
            assertThat(lineOf(decision(0).resolveSource(segment(3)))).isEqualTo("m1|line1");
            // 片段没有行时回零值（Go 实录："<zero>"）
            assertThat(lineOf(decision(1).resolveSource(
                    new MemoryExtractionService.TranscriptSegment()))).isEqualTo("<zero>");
        }

        private MemoryExtractionService.ExtractionDecision decision(Integer source) {
            MemoryExtractionService.ExtractionDecision d = new MemoryExtractionService.ExtractionDecision();
            d.source = source;
            return d;
        }
    }

    @Nested
    @DisplayName("parseExpiry")
    class ParseExpiry {

        @Test
        void dropsBlankNullPastAndUnparsableValues() {
            // Go 实录：这九种输入全部 null
            for (String v : List.of("", "  ", "null", "NULL", "not-a-date", "2026/08/15",
                    "2099-01-01 10:00", "2099-1-1", "2020-01-01")) {
                assertThat(MemoryExtractionService.parseExpiry(v))
                        .as("parseExpiry(%s)", v).isNull();
            }
            // 已经过去的日期也要丢掉（消息里的"下周五"被存下来的那种）
            assertThat(MemoryExtractionService.parseExpiry("2026-08-15")).isNull();
            assertThat(MemoryExtractionService.parseExpiry("2026-08-15T10:00:00Z")).isNull();
        }

        @Test
        void acceptsFutureDatesInBothLayoutsWithGoSemantics() {
            // Go 实录：日期布局解析成 **UTC 午夜**（time.Parse 的 location 是 UTC）
            assertThat(MemoryExtractionService.parseExpiry("2099-01-01").toInstant())
                    .isEqualTo(java.time.Instant.parse("2099-01-01T00:00:00Z"));
            // RFC3339 布局保留原偏移
            assertThat(MemoryExtractionService.parseExpiry("2099-01-01T10:00:00Z").toInstant())
                    .isEqualTo(java.time.Instant.parse("2099-01-01T10:00:00Z"));
            assertThat(MemoryExtractionService.parseExpiry("2099-01-01T10:00:00+08:00").toInstant())
                    .isEqualTo(java.time.Instant.parse("2099-01-01T02:00:00Z"));
        }
    }

    @Nested
    @DisplayName("parseExtractionResponse")
    class ParseResponse {

        @Test
        void emptyAndNoObjectCases() {
            // Go 实录："" → 零值（memories/topics 都空）
            MemoryExtractionService.ExtractionResponse parsed =
                    MemoryExtractionService.parseExtractionResponse("");
            assertThat(parsed.memories).isEmpty();
            assertThat(parsed.topics).isEmpty();

            assertThatThrownBy(() -> MemoryExtractionService.parseExtractionResponse("no json here"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no JSON object in response");
            assertThatThrownBy(() -> MemoryExtractionService.parseExtractionResponse("{"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no JSON object in response");
        }

        @Test
        void parsesAPlainObject() {
            // Go 实录：[{"action":"add",...,"content":"c","importance":0,"expires_at":"","inferred":false}]
            MemoryExtractionService.ExtractionResponse parsed = MemoryExtractionService
                    .parseExtractionResponse("{\"memories\":[{\"action\":\"add\",\"kind\":\"fact\","
                            + "\"topic\":\"t\",\"content\":\"c\"}],\"topics\":[\"x\"]}");
            assertThat(parsed.memories).hasSize(1);
            MemoryExtractionService.ExtractionDecision d = parsed.memories.get(0);
            assertThat(d.action).isEqualTo("add");
            assertThat(d.kind).isEqualTo("fact");
            assertThat(d.target).isNull();
            assertThat(d.topic).isEqualTo("t");
            assertThat(d.content).isEqualTo("c");
            assertThat(d.importance).isZero();
            assertThat(d.source).isNull();
            assertThat(d.expiresAt).isEmpty();
            assertThat(d.inferred).isFalse();
            assertThat(parsed.topics).containsExactly("x");
        }

        @Test
        void stripsFencesAndProse() {
            // Go 实录：两种包装都解出 {"memories":[],"topics":[]}
            MemoryExtractionService.ExtractionResponse fenced = MemoryExtractionService
                    .parseExtractionResponse("```json\n{\"memories\":[],\"topics\":[]}\n```");
            assertThat(fenced.memories).isEmpty();
            assertThat(fenced.topics).isEmpty();

            MemoryExtractionService.ExtractionResponse prose = MemoryExtractionService
                    .parseExtractionResponse(
                            "Sure! here you go:\n{\"memories\":[],\"topics\":[]}\nhope that helps");
            assertThat(prose.memories).isEmpty();
            assertThat(prose.topics).isEmpty();
        }

        @Test
        void missingKeysBecomeDefaults() {
            // Go 实录：[{"action":"add","kind":"","topic":"","content":"",...,"inferred":false}]、topics null
            MemoryExtractionService.ExtractionResponse parsed = MemoryExtractionService
                    .parseExtractionResponse("{\"memories\":[{\"action\":\"add\"}],\"topics\":null}");
            assertThat(parsed.memories).hasSize(1);
            assertThat(parsed.memories.get(0).kind).isEmpty();
            assertThat(parsed.memories.get(0).content).isEmpty();
            // Go 里显式 null 会把切片置成 nil；Java 侧 Jackson 同样写 null。
            assertThat(parsed.topics).isNull();
        }
    }

    @Nested
    @DisplayName("isTruncated / statusForWrite / residentItemsWithinBlock")
    class SmallHelpers {

        @Test
        void isTruncatedTreatsEmptyBodyAsTruncation() {
            // Go 实录：nil→true、空白→true、finish=length→true、finish=stop→false
            assertThat(MemoryExtractionService.isTruncated(null)).isTrue();
            ChatResponse blank = new ChatResponse();
            blank.setContent("  ");
            assertThat(MemoryExtractionService.isTruncated(blank)).isTrue();
            ChatResponse length = new ChatResponse();
            length.setContent("x");
            length.setFinishReason("length");
            assertThat(MemoryExtractionService.isTruncated(length)).isTrue();
            ChatResponse stop = new ChatResponse();
            stop.setContent("x");
            stop.setFinishReason("stop");
            assertThat(MemoryExtractionService.isTruncated(stop)).isFalse();
        }

        @Test
        void statusForWriteOnlyDefersInferredNonUserStatements() {
            // Go 实录：inferred+extracted→pending，inferred+explicit→active，
            // inferred+manual→active，not inferred→active
            assertThat(MemoryService.statusForWrite(
                    itemWith(true, MemoryKinds.ORIGIN_EXTRACTED))).isEqualTo(MemoryKinds.STATUS_PENDING);
            assertThat(MemoryService.statusForWrite(
                    itemWith(true, MemoryKinds.ORIGIN_EXPLICIT))).isEqualTo(MemoryKinds.STATUS_ACTIVE);
            assertThat(MemoryService.statusForWrite(
                    itemWith(true, MemoryKinds.ORIGIN_MANUAL))).isEqualTo(MemoryKinds.STATUS_ACTIVE);
            assertThat(MemoryService.statusForWrite(
                    itemWith(false, MemoryKinds.ORIGIN_EXTRACTED))).isEqualTo(MemoryKinds.STATUS_ACTIVE);
        }

        @Test
        void residentItemsWithinBlockFiltersByWhatActuallyFits() {
            List<MemoryItem> items = List.of(
                    item("i1", MemoryKinds.KIND_FACT, "在用的数据库", "生产库用的是 MySQL"),
                    item("i2", MemoryKinds.KIND_TASK, "在做的重构", "重构支付流程，计划本周完成"));
            // Go 实录：["i1"]
            assertThat(MemoryService.residentItemsWithinBlock(items, "- 生产库用的是 MySQL\n- 别的东西"))
                    .extracting(MemoryItem::getId).containsExactly("i1");
            // 空块 → 空（Go 实录：[]）
            assertThat(MemoryService.residentItemsWithinBlock(items, "")).isEmpty();
        }

        private MemoryItem itemWith(boolean inferred, String origin) {
            MemoryItem it = new MemoryItem();
            it.setInferred(inferred);
            it.setOrigin(origin);
            return it;
        }
    }

    @Nested
    @DisplayName("buildExtractionPrompt（逐字节）")
    class Prompt {

        @Test
        void minimalPrompt() {
            MemoryExtractionService.TranscriptSegment segment =
                    new MemoryExtractionService.TranscriptSegment();
            segment.sessionId = "s";
            segment.lines.add(line("m1", at("2026-03-02", 9, 5), "hi"));

            // Go 实录（逐字节）
            assertThat(MemoryExtractionService.buildExtractionPrompt(segment, null, null, null, ""))
                    .isEqualTo("Existing notes:\n(none)\n\nWhat the user said:\n<transcript>\n"
                            + "[1] (2026-03-02 09:05) hi\n</transcript>\n");
        }

        @Test
        void workspaceRulesOnly() {
            // Go 实录（逐字节）：零值时间印成 0001-01-01 00:00
            assertThat(MemoryExtractionService.buildExtractionPrompt(segment(1), null, null, null, "r"))
                    .isEqualTo("Existing notes:\n(none)\n\nWorkspace rules (follow these in addition to "
                            + "the above):\n<rules>\nr\n</rules>\n\nWhat the user said:\n<transcript>\n"
                            + "[1] (0001-01-01 00:00) line1\n</transcript>\n");
        }

        @Test
        void fullPrompt() {
            MemoryExtractionService.TranscriptSegment segment =
                    new MemoryExtractionService.TranscriptSegment();
            segment.sessionId = "s1";
            segment.context = List.of("就用前面那个吧", "另外帮我看看这个");
            segment.lines.add(line("m1", at("2026-03-02", 9, 5), "我在一家做医疗影像的公司写后端"));
            segment.lines.add(line("m2", at("2026-03-02", 9, 6), "主要用 Go"));

            List<MemoryItem> existing = new ArrayList<>();
            existing.add(item("i1", MemoryKinds.KIND_FACT, "在用的数据库", "生产库用的是 MySQL"));
            existing.add(null);
            existing.add(item("i2", MemoryKinds.KIND_TASK, "在做的重构", "重构支付流程，计划本周完成"));

            List<MemoryTombstone> forgotten = new ArrayList<>();
            forgotten.add(tombstone("在用的数据库"));
            forgotten.add(tombstone(""));
            forgotten.add(null);
            forgotten.add(tombstone("部署环境"));

            List<MemoryTopicStat> known = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                MemoryTopicStat stat = new MemoryTopicStat();
                stat.setTopic("主题" + i);
                known.add(stat);
            }
            known.add(null);
            MemoryTopicStat blank = new MemoryTopicStat();
            blank.setTopic("");
            known.add(blank);

            // Go 实录（逐字节）。注意三处容易写错的地方：
            // 1) 序号用的是**原切片下标**（nil 被跳过但占位 → [0] 与 [2]）；
            // 2) 话题只展示 12 条（extractShownTopics 的截断）；
            // 3) 空主题的墓碑被跳过。
            assertThat(MemoryExtractionService.buildExtractionPrompt(
                    segment, existing, forgotten, known, "本工作区的规矩"))
                    .isEqualTo("Earlier in this conversation (context only, do not record from these):\n"
                            + "- 就用前面那个吧\n"
                            + "- 另外帮我看看这个\n"
                            + "\n"
                            + "Existing notes:\n"
                            + "[0] [fact] (topic: 在用的数据库) 生产库用的是 MySQL\n"
                            + "[2] [task] (topic: 在做的重构) 重构支付流程，计划本周完成\n"
                            + "\n"
                            + "The user deleted notes about these topics. Do not re-add them unless this "
                            + "transcript says something genuinely new about them:\n"
                            + "- 在用的数据库\n"
                            + "- 部署环境\n"
                            + "\n"
                            + "Subjects already tracked for this user:\n"
                            + "- 主题0\n- 主题1\n- 主题2\n- 主题3\n- 主题4\n- 主题5\n"
                            + "- 主题6\n- 主题7\n- 主题8\n- 主题9\n- 主题10\n- 主题11\n"
                            + "Reuse one of these labels EXACTLY only when the transcript is about the "
                            + "SAME subject,\n"
                            + "just worded differently. Being in the same domain is not enough: if\n"
                            + "\"门店排班管理\" is tracked and the user asks how a shift swap gets approved, "
                            + "that\n"
                            + "is a different subject (\"排班审批流程\") — building the roster and approving\n"
                            + "changes to it are different things this person does.\n"
                            + "When nothing above names the same subject, write a new label at the same "
                            + "level of\n"
                            + "generality as these. Do not force a fit, and do not name the individual "
                            + "question.\n"
                            + "\n"
                            + "Workspace rules (follow these in addition to the above):\n<rules>\n"
                            + "本工作区的规矩\n</rules>\n"
                            + "\n"
                            + "What the user said:\n<transcript>\n"
                            + "[1] (2026-03-02 09:05) 我在一家做医疗影像的公司写后端\n"
                            + "[2] (2026-03-02 09:06) 主要用 Go\n"
                            + "</transcript>\n");
        }

        private MemoryTombstone tombstone(String topic) {
            MemoryTombstone t = new MemoryTombstone();
            t.setTopic(topic);
            t.setFingerprint(MemoryText.fingerprint(topic.isEmpty() ? "x" : topic));
            return t;
        }
    }

    @Nested
    @DisplayName("负载 JSON（对照 Go 的 omitempty）")
    class Payload {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        void omitsBlankChatModelAndLanguage() throws Exception {
            MemoryExtractPayload payload = new MemoryExtractPayload(
                    7, "web_user:u1", "s", "m", "", "");
            // Go 实录：{"tenant_id":7,"subject_id":"web_user:u1","session_id":"s","message_id":"m"}
            assertThat(mapper.writeValueAsString(payload)).isEqualTo(
                    "{\"tenant_id\":7,\"subject_id\":\"web_user:u1\",\"session_id\":\"s\","
                            + "\"message_id\":\"m\"}");
        }

        @Test
        void keepsPopulatedFields() throws Exception {
            MemoryExtractPayload payload = new MemoryExtractPayload(
                    7, "web_user:u1", "s", "m", "chat-1", "Chinese (Simplified)");
            // Go 实录：...{"chat_model_id":"chat-1","language":"Chinese (Simplified)"}
            assertThat(mapper.writeValueAsString(payload)).isEqualTo(
                    "{\"tenant_id\":7,\"subject_id\":\"web_user:u1\",\"session_id\":\"s\","
                            + "\"message_id\":\"m\",\"chat_model_id\":\"chat-1\","
                            + "\"language\":\"Chinese (Simplified)\"}");
            assertThat(payload.scope().valid()).isTrue();
            assertThat(payload.scope().tenantId()).isEqualTo(7);
        }

        @Test
        void roundTripsThroughTheQueueEncoding() {
            MemoryExtractPayload payload = new MemoryExtractPayload(
                    7, "web_user:u1", "s", "m", "chat-1", "");
            assertThat(MemoryExtractPayload.fromJson(payload.toJson())).isEqualTo(payload);
            // Go 的 json.Unmarshal 忽略未知字段；负载加了新键后旧消费者必须还能读。
            MemoryExtractPayload read =
                    MemoryExtractPayload.fromJson("{\"tenant_id\":1,\"subject_id\":\"a\",\"extra\":1}");
            assertThat(read.subjectId()).isEqualTo("a");
        }

        @Test
        void zeroPayloadIsInvalid() {
            assertThat(MemoryExtractPayload.empty().scope().valid()).isFalse();
            assertThat(new MemoryExtractPayload(0, "", "s", "m", "", "").scope().valid()).isFalse();
        }

        @Test
        void constantsMatchGo() {
            assertThat(MemoryExtractionService.EXTRACT_SEGMENT_GAP).isEqualTo(java.time.Duration.ofHours(1));
            assertThat(MemoryExtractionService.EXTRACT_IN_FLIGHT_GRACE)
                    .isEqualTo(java.time.Duration.ofMinutes(10));
            assertThat(MemoryExtractionService.EXTRACT_MAX_MESSAGES_PER_RUN).isEqualTo(40);
            assertThat(MemoryExtractionService.EXTRACT_MAX_ITEMS_PER_RUN).isEqualTo(8);
            assertThat(MemoryExtractionService.EXTRACT_MAX_SEGMENTS_PER_RUN).isEqualTo(3);
            assertThat(MemoryExtractionService.EXTRACT_CONTEXT_LINES).isEqualTo(4);
            assertThat(MemoryExtractionService.EXTRACT_MAX_LINE_RUNES).isEqualTo(1000);
            assertThat(MemoryExtractionService.EXTRACT_RELEVANT_CANDIDATES).isEqualTo(15);
            assertThat(MemoryExtractionService.EXTRACT_SHOWN_TOPICS).isEqualTo(12);
            assertThat(MemoryExtractionService.EXTRACT_BUDGET_TOKENS).isEqualTo(1200);
            assertThat(MemoryExtractionService.EXTRACT_BUDGET_RETRY_TOKENS).isEqualTo(4000);
            assertThat(MemoryExtractionService.EXTRACT_FOLLOW_UP_DELAY)
                    .isEqualTo(java.time.Duration.ofSeconds(15));
        }
    }

    @Test
    @DisplayName("formatLineTime：按服务器本地时区的墙上时间（对照 Go 的 Format）")
    void formatLineTimeUsesServerLocalZone() {
        assertThat(MemoryExtractionService.formatLineTime(
                LocalDateTime.of(2026, 3, 2, 9, 5).atZone(ZoneId.systemDefault()).toOffsetDateTime()))
                .isEqualTo("2026-03-02 09:05");
        // 同一个瞬时换一个偏移，印出来必须是转换后的墙上时间
        assertThat(MemoryExtractionService.formatLineTime(
                OffsetDateTime.of(2026, 3, 2, 9, 5, 0, 0, ZoneOffset.UTC)))
                .isEqualTo(LocalDateTime.ofInstant(java.time.Instant.parse("2026-03-02T09:05:00Z"),
                        ZoneId.systemDefault()).format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        assertThat(MemoryExtractionService.formatLineTime(null)).isEqualTo("0001-01-01 00:00");
    }
}
