package com.ragagent.im.yunzhijia;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 云之家（Yunzhijia）协议载荷类型——对照 Go {@code internal/im/yunzhijia/types.go} L6-108。
 *
 * <p>字段名与 JSON 键逐字对应；{@code msgParam} 里的 {@code notifyType} 在真实流量里
 * <b>数字/字符串两形态都有</b>（照 Go 用 {@code json.RawMessage} 容忍）——Java 侧用
 * {@link JsonNode} 承接，不参与判定。</p>
 */
public final class YunzhijiaTypes {

    private YunzhijiaTypes() {
    }

    /** 对照 {@code callbackMessage}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CallbackMessage {
        public int type;
        public int msgType;
        public String eid = "";
        public String clientId = "";
        public String robotId = "";
        public String robotName = "";
        public String openId = "";
        public String groupId = "";
        public String operatorOpenid = "";
        public String operatorOid = "";
        public String operatorId = "";
        public String operatorUserId = "";
        public String operatorName = "";
        public String senderId = "";
        public String senderName = "";
        public long time;
        public String msgId = "";
        public String content = "";
        public int groupType;
        public String msgParam = "";
    }

    /** 对照 {@code messageParam}（{@code notifyType} 容忍两种形态）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MessageParam {
        public List<MessageParamDesc> desc;
        public List<String> notifyTo;
        public JsonNode notifyType;
        public String replyMsgId = "";
        public String replyRootMsgId = "";

        /** 对照 {@code firstImage}：首个 {@code type=image} 且 data 非空。 */
        public MessageParamDesc firstImage() {
            if (desc == null) {
                return null;
            }
            for (MessageParamDesc item : desc) {
                if ("image".equals(item.type) && item.data != null && !item.data.isEmpty()) {
                    return item;
                }
            }
            return null;
        }
    }

    /** 对照 {@code messageParamDesc}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MessageParamDesc {
        public String type = "";
        public String data = "";
        public int start;
        public int length;
        public int w;
        public int h;
    }

    /** 对照 {@code notifyParam}。 */
    public static final class NotifyParam {
        public String type;
        public List<String> values;

        public NotifyParam(String type, List<String> values) {
            this.type = type;
            this.values = values;
        }
    }

    /** 对照 {@code sendMessageParam}。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class SendMessageParam {
        public String formatType;
        public String replyMsgId;
        public boolean isReference;
        public String replySummary;
        public String replyPersonName;
    }

    /** 对照 {@code sendMessagePayload}。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class SendMessagePayload {
        public int msgtype;
        public String content;
        public List<NotifyParam> notifyParams;
        public int paramType;
        public SendMessageParam param;
    }

    /** 对照 {@code appAccessTokenResponse} / {@code appAccessTokenData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class AppAccessTokenResponse {
        public AppAccessTokenData data = new AppAccessTokenData();
        public JsonNode error;
        public int errorCode;
        public boolean success;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class AppAccessTokenData {
        public String accessToken = "";
        public long expireIn;
        public String refreshToken = "";
    }
}
