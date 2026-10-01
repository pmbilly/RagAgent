package com.ragagent.session.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.session.domain.MessageArtifact;

/**
 * 产物元数据的一行（会话/消息两个 artifacts 端点的元素）。
 *
 * <p>它**不含存储 URL**——客户端不能绕过 download 端点直接读 {@code provider://} 路径；
 * 可取的那个句柄由 {@code handle} 表达（{@code resource://<22 位>}，不可取时为 {@code null}）。</p>
 *
 * <p>{@code createdAt}/{@code modTime} 都是可空时间：Go 用指针，未取到就是 {@code null}
 * （§1.5 显式 null）。</p>
 *
 * <p>⚠️ <b>过渡期</b>：线格式键名暂时仍是下划线，因为同一批产物元数据还出现在
 * {@code messages.artifacts}（消息面的 jsonb，{@link MessageArtifact}）里，前端抽屉同时消费两处
 * ——改名必须与消息面同批（§14.9l S2），否则前端会拿到两种形状。S2 落地时把这里的
 * {@code @JsonProperty} 一并去掉。</p>
 */
public record ArtifactView(
        int index,
        String handle,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("file_type") String fileType,
        @JsonProperty("file_size") long fileSize,
        @JsonProperty("source_path") String sourcePath,
        @JsonProperty("mod_time") OffsetDateTime modTime,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    /**
     * 对照 Go {@code artifactListItem}：{@code index} 是**列表下标**（不是产物自己的 id），
     * {@code handle} 只在 URL 是规范 {@code resource://<22 位>} 形态时才有值。
     */
    public static ArtifactView of(int index, MessageArtifact artifact) {
        return new ArtifactView(index, artifactHandle(artifact.getUrl()), artifact.getFileName(),
                artifact.getFileType(), artifact.getFileSize(), artifact.getSourcePath(),
                artifact.getModTime(), artifact.getCreatedAt());
    }

    /** 对照 Go {@code artifactHandle}：合法句柄规范化成 {@code resource://<handle>}，否则 null。 */
    private static String artifactHandle(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("resource://")) {
            return null;
        }
        String handle = trimmed.substring("resource://".length());
        if (handle.length() != 22) {
            return null;
        }
        for (int i = 0; i < handle.length(); i++) {
            char c = handle.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }
        return "resource://" + handle;
    }
}
