package com.ragagent.memory.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.session.SessionMessagePort;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.memory.domain.MemoryExtractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话片段的收集：按水位线取消息、切片段、补前文上下文，以及转录行的取值工具。
 *
 * <p>持有 {@link MemoryExtractionService} 回引以访问其仓储；本类不得独立实例化。</p>
 */
final class MemoryTranscriptOps {

    private static final Logger log = LoggerFactory.getLogger(MemoryTranscriptOps.class);

    private final MemoryExtractionService service;

    MemoryTranscriptOps(MemoryExtractionService service) {
        this.service = service;
    }

    /** {@link #collectSessionSegments} 的双返回值（对照 Go 的 {@code (segments, more, err)}）。 */
    record CollectedSegments(List<MemoryExtractionService.TranscriptSegment> segments, boolean more) {
    }

    /**
     * 对照 Go {@code collectSessionSegments}：用一个会话内游标读有界的一页。
     *
     * <p>每一行，包括只有助手消息的那些页，都属于一个检查点。间隔在接纳下一条消息**之前**
     * 就被冲刷，所以水位线不会一步跨过它。</p>
     */
    CollectedSegments collectSessionSegments(MemoryExtractionSession session) {
        List<SessionMessagePort.SessionMessageView> rows;
        try {
            rows = service.sessionMessages.listAfterCursor(session.getSessionId(), session.getCursor().getAt(), session.getCursor().getId(),
                    MemoryExtractionService.EXTRACT_MAX_MESSAGES_PER_RUN + 1);
        } catch (RuntimeException e) {
            throw new IllegalStateException("load session messages: " + e.getMessage(), e);
        }
        boolean more = rows.size() > MemoryExtractionService.EXTRACT_MAX_MESSAGES_PER_RUN;
        if (more) {
            rows = rows.subList(0, MemoryExtractionService.EXTRACT_MAX_MESSAGES_PER_RUN);
        }

        List<MemoryExtractionService.TranscriptSegment> segments = new ArrayList<>();
        MemoryExtractionService.TranscriptSegment[] current = {new MemoryExtractionService.TranscriptSegment()};
        current[0].sessionId = session.getSessionId();
        OffsetDateTime[] lastAt = {null};
        boolean[] hasRows = {false};

        for (SessionMessagePort.SessionMessageView message : rows) {
            if (message == null) {
                continue;
            }
            String content = MemoryScopes.trimSpace(message.content());
            boolean isUser = "user".equals(message.role()) && !content.isEmpty();
            if (isUser && lastAt[0] != null && !GoTimeSerializer.isGoZero(lastAt[0])
                    && message.createdAt() != null
                    && message.createdAt().isAfter(lastAt[0])
                    && Duration.between(lastAt[0], message.createdAt()).compareTo(MemoryExtractionService.EXTRACT_SEGMENT_GAP) > 0) {
                if (hasRows[0]) {
                    segments.add(current[0]);
                    current[0] = new MemoryExtractionService.TranscriptSegment();
                    current[0].sessionId = session.getSessionId();
                    hasRows[0] = false;
                }
            }
            current[0].end = message.createdAt();
            current[0].endId = message.id();
            hasRows[0] = true;
            if (!isUser) {
                continue;
            }
            lastAt[0] = message.createdAt();
            content = runeSlice(content, MemoryExtractionService.EXTRACT_MAX_LINE_RUNES);
            current[0].lines.add(new MemoryExtractionService.TranscriptLine(session.getSessionId(), message.id(),
                    message.createdAt(), content));
        }
        if (hasRows[0]) {
            segments.add(current[0]);
        }

        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).lines.isEmpty()) {
                continue;
            }
            if (i == 0) {
                segments.get(i).context = priorContext(session.getSessionId(), segments.get(i).lines);
            } else {
                segments.get(i).context = tailContents(segments.get(i - 1).lines, MemoryExtractionService.EXTRACT_CONTEXT_LINES);
            }
        }
        return new CollectedSegments(segments, more);
    }

    /**
     * 对照 Go {@code priorContext}：取一个片段之前的那几条用户消息。
     *
     * <p>没有它，一次运行只看得到新东西，于是"就用前面那个吧"这样的回合到达时
     * 没有任何可供解析的东西，模型要么编一个主语，要么悄悄丢掉一个真实的偏好。
     * 上下文只展示给模型、**永不**从其中抽取，所以它不可能从水位线已经越过的消息里
     * 再产出记忆。</p>
     */
    List<String> priorContext(String sessionId, List<MemoryExtractionService.TranscriptLine> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        List<SessionMessagePort.SessionMessageView> before;
        try {
            before = service.sessionMessages.listBeforeTime(sessionId, lines.get(0).at, MemoryExtractionService.EXTRACT_CONTEXT_LINES * 4);
        } catch (RuntimeException e) {
            log.warn("memory: load prior context failed: {}", e.toString());
            return null;
        }
        List<MemoryExtractionService.TranscriptLine> previous = new ArrayList<>();
        for (SessionMessagePort.SessionMessageView message : before) {
            if (message == null || !"user".equals(message.role())) {
                continue;
            }
            String content = MemoryScopes.trimSpace(message.content());
            if (content.isEmpty()) {
                continue;
            }
            content = runeSlice(content, MemoryExtractionService.EXTRACT_MAX_LINE_RUNES);
            previous.add(new MemoryExtractionService.TranscriptLine("", "", null, content));
        }
        return tailContents(previous, MemoryExtractionService.EXTRACT_CONTEXT_LINES);
    }

    /** 对照 Go {@code tailContents}：取最后 limit 条的内容。 */
    static List<String> tailContents(List<MemoryExtractionService.TranscriptLine> lines, int limit) {
        if (limit <= 0 || lines == null || lines.isEmpty()) {
            return null;
        }
        List<MemoryExtractionService.TranscriptLine> window = lines;
        if (lines.size() > limit) {
            window = lines.subList(lines.size() - limit, lines.size());
        }
        List<String> out = new ArrayList<>(window.size());
        for (MemoryExtractionService.TranscriptLine line : window) {
            out.add(line.content);
        }
        return out;
    }

    /** 对照 Go {@code string([]rune(s)[:n])}。 */
    static String runeSlice(String s, int maxRunes) {
        return com.ragagent.common.settings.MemoryKeys.runeLength(s) > maxRunes
                ? com.ragagent.common.settings.MemoryKeys.runeSlice(s, maxRunes)
                : s;
    }
}
