package com.ragagent.session.support;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.session.PipelineMessageAttachmentView;
import com.ragagent.common.session.PipelineMessageImageView;
import com.ragagent.common.session.PipelineMessageView;
import com.ragagent.common.session.PipelineUsedMemoryView;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.UsedMemory;

/**
 * 会话实体 ↔ 聊天管线载荷（{@code common.session.Pipeline*}）的映射。
 *
 * <p>跨域端口两端只认载荷：出域（实体 → 载荷）在端口实现与调用点做，回写
 * （载荷 → 实体）也在会话侧收敛到本类，避免同一份字段映射散落多处。</p>
 *
 * <p>空值语义与实体一致：列表为 null 时映射结果也是 null（消费方本来就按 null 判空）。</p>
 */
public final class PipelineViews {

    private PipelineViews() {
    }

    /** 消息 → 管线视图（实体为 null 时为 null，与端口 {@code getMessage} 的"找不到返回 null"一致）。 */
    public static PipelineMessageView ofMessage(Message m) {
        if (m == null) {
            return null;
        }
        return new PipelineMessageView(
                m.getRequestId(),
                m.getRole(),
                m.getContent(),
                m.getCreatedAt(),
                ofImages(m.getImages()),
                ofAttachments(m.getAttachments()),
                m.getKnowledgeReferences());
    }

    /** 消息列表 → 管线视图列表（null 列表原样返回 null）。 */
    public static List<PipelineMessageView> ofMessages(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        List<PipelineMessageView> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            out.add(ofMessage(m));
        }
        return out;
    }

    public static List<PipelineMessageImageView> ofImages(List<MessageImage> images) {
        if (images == null) {
            return null;
        }
        List<PipelineMessageImageView> out = new ArrayList<>(images.size());
        for (MessageImage img : images) {
            out.add(new PipelineMessageImageView(img.getUrl(), img.getCaption()));
        }
        return out;
    }

    public static List<PipelineMessageAttachmentView> ofAttachments(List<MessageAttachment> atts) {
        if (atts == null) {
            return null;
        }
        List<PipelineMessageAttachmentView> out = new ArrayList<>(atts.size());
        for (MessageAttachment a : atts) {
            out.add(new PipelineMessageAttachmentView(
                    a.getFileName(),
                    a.getFileType(),
                    a.getFileSize(),
                    a.getContentMode(),
                    a.getContent(),
                    a.isTruncated(),
                    a.getLineCount(),
                    a.getSelectedChunks(),
                    a.getTotalChunks()));
        }
        return out;
    }

    /** 图片载荷列表 → 实体列表（回写用户消息的图片描述用）。 */
    public static List<MessageImage> toImages(List<PipelineMessageImageView> views) {
        if (views == null) {
            return null;
        }
        List<MessageImage> out = new ArrayList<>(views.size());
        for (PipelineMessageImageView v : views) {
            MessageImage img = new MessageImage();
            img.setUrl(v.url());
            img.setCaption(v.caption());
            out.add(img);
        }
        return out;
    }

    /** 用到记忆的载荷列表 → 实体列表（落库用）。 */
    public static List<UsedMemory> toUsedMemories(List<PipelineUsedMemoryView> views) {
        if (views == null) {
            return null;
        }
        List<UsedMemory> out = new ArrayList<>(views.size());
        for (PipelineUsedMemoryView v : views) {
            if (v == null) {
                continue;
            }
            UsedMemory um = new UsedMemory();
            um.setId(v.id());
            um.setKind(v.kind());
            um.setContent(v.content());
            out.add(um);
        }
        return out;
    }
}
