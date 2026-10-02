package com.ragagent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DocReader（文档解析服务）的连接配置（B6 批 5）。
 *
 * <p>env 名保持原样：{@code DOCREADER_ADDR} → {@code docreader.addr}、
 * {@code DOCREADER_TRANSPORT} → {@code docreader.transport}（Spring 松散绑定）。</p>
 *
 * <p>字段是 {@code String} 原始值（不绑 host/port/枚举）：地址解析与兜底
 * （{@code localhost:50051}）、传输名归一（小写、缺省 {@code grpc}）都留在既有读点，
 * 语义与改前逐字一致。</p>
 */
@ConfigurationProperties(prefix = "docreader")
public record DocReaderProperties(String addr, String transport) {

    /** 连接地址：未配置/全空白 → {@code localhost:50051}（既有兜底）。 */
    public String addrOrDefault() {
        return addr == null || addr.isBlank() ? "localhost:50051" : addr;
    }

    /**
     * 是否显式配置了地址——对照既有的连接判据（「env 是否为空白」，不是真探活）。
     */
    public boolean addrConfigured() {
        return addr != null && !addr.isBlank();
    }

    /** 传输名：未配置/全空白 → {@code grpc}；否则 trim + 小写归一。 */
    public String transportOrDefault() {
        if (transport == null || transport.trim().isEmpty()) {
            return "grpc";
        }
        return transport.trim().toLowerCase();
    }
}
