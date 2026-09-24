package com.ragagent.im.runtime;

import java.io.IOException;

/**
 * IM 平台适配器接口族（对照 Go internal/im/adapter.go L125-182）。
 * 每个平台一个实现（γ3）；γ2 的 Service 只依赖这些接口。
 */
public final class AdapterInterfaces {

    private AdapterInterfaces() {
    }

    /** 每个平台必须实现的接口（对照 Go {@code im.Adapter}，L126-144）。 */
    public interface Adapter {

        /** 平台标识。 */
        String platform();

        /**
         * 校验回调签名/token；通过返回 null，失败<b>返回</b>异常对象
         * （对照 Go 的 {@code error} 返回值约定——调用点 {@code ImCallbackController}
         * 判空即折 401/403，不要改成抛出）。
         */
        Exception verifyCallback(CallbackExchange exchange);

        /**
         * 把回调请求解析成统一消息；非消息事件（如 URL verification）返回 null。
         */
        IncomingMessage parseCallback(CallbackExchange exchange) throws Exception;

        /** 把回复发回平台。 */
        void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception;

        /** 处理平台的 URL verification 挑战；是验证请求且已处理返回 true。 */
        boolean handleURLVerification(CallbackExchange exchange);
    }

    /**
     * 验签失败（对照 Go 的 error 返回；文案由各平台适配器给出）。
     *
     * <p>{@code public}：各平台适配器在 {@code com.ragagent.im.<platform>} 包下
     * （γ3），要能构造它。</p>
     */
    public static class VerifyException extends RuntimeException {
        public VerifyException(String message) {
            super(message);
        }
    }

    /**
     * 可选：流式回复（对照 Go {@code im.StreamSender}，L150-164）。stream 输出模式
     * 下实时推送分片；full 模式可把同一可替换消息当进度占位、完成后一次性替换。
     */
    public interface StreamSender {
        /** 初始化流式回复（如创建 streaming card），返回平台流 ID。 */
        String startStream(IncomingMessage incoming) throws Exception;

        /** 用目前为止的全文替换用户可见的流文本（替换语义平台整条展示）。 */
        void updateStreamContent(IncomingMessage incoming, String streamId,
                String fullContent) throws Exception;

        /** 最终替换：answer-only（思考/工具行已剥离）。 */
        void finalizeStream(IncomingMessage incoming, String streamId,
                String finalContent) throws Exception;

        /** 结束流式回复。 */
        void endStream(IncomingMessage incoming, String streamId) throws Exception;
    }

    /**
     * 可选：流以可见占位开头、可安全一次替换为完整答案的适配器能力
     * （对照 Go {@code im.FullOutputProgressSender}，L169-172）。
     * full 输出模式永不调 updateStreamContent。
     */
    public interface FullOutputProgressSender extends StreamSender {
        boolean supportsFullOutputProgress();
    }

    /**
     * 可选：从平台下载文件附件（对照 Go {@code im.FileDownloader}，L178-182）。
     * 文件/图片消息由此供 QA 作附件；配置了 knowledge_base_id 时也支撑异步入库。
     */
    public interface FileDownloader {
        /** 返回文件内容字节、解析出的文件名。 */
        DownloadedFile downloadFile(IncomingMessage msg) throws IOException, Exception;

        record DownloadedFile(byte[] content, String fileName) {
        }
    }
}
