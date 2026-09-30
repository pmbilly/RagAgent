package com.ragagent.im.service;

import com.ragagent.im.runtime.AdapterInterfaces.Adapter;
import com.ragagent.im.runtime.AdapterInterfaces.StreamSender;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.ThinkDisplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IM 出站内容整形与发送：XML/引用标签清洗、存储链接重写、最终答案兜底、
 * 流式整段回复与静默回复（发送失败只记日志，不反噬主流程）。
 */
final class ImOutboundFormatter {

    private static final Logger log = LoggerFactory.getLogger(ImOutboundFormatter.class);

    private final ImService service;

    ImOutboundFormatter(ImService service) {
        this.service = service;
    }

    /** 对照 sendStreamReply（service.go L2195-2216）。 */
    void sendStreamReply(IncomingMessage msg, StreamSender streamer, String content)
            throws Exception {
        String streamId = streamer.startStream(msg);
        streamer.updateStreamContent(msg, streamId, content);
        streamer.finalizeStream(msg, streamId, content);
        streamer.endStream(msg, streamId);
    }

    // ── 出站内容整形（service.go L144-201） ──────────────────────────────

    String cleanIMContent(String content) {
        content = ImFormat.stripImageXMLTags(content);
        content = ImFormat.stripImCitationTags(content);
        if (service.storageResolver != null) {
            content = new com.ragagent.storage.support.Rewriter(service.storageResolver, "IM")
                    .rewrite(content);
        }
        return content;
    }


    String formatIMOutboundAnswerOrFallback(String raw) {
        String content = cleanIMContent(ThinkDisplay.formatIMDisplayContent(raw,
                ThinkDisplay.STREAM_DISPLAY_FINAL));
        if (content.strip().isEmpty()) {
            return ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        return content;
    }


    void sendReplyQuiet(Adapter adapter, IncomingMessage msg, ReplyMessage reply) {
        try {
            adapter.sendReply(msg, reply);
        } catch (Exception e) {
            log.warn("[IM] Send reply failed: {}", e.getMessage());
        }
    }
}
