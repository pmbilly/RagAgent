package com.ragagent.common.error;

/**
 * 对照 Go internal/errors/errors.go 的错误码表（数值一致，前端按 code 分支）。
 */
public enum ErrorCode {
    // 通用 (1000-1999)
    BAD_REQUEST(1000),
    UNAUTHORIZED(1001),
    FORBIDDEN(1002),
    NOT_FOUND(1003),
    METHOD_NOT_ALLOWED(1004),
    CONFLICT(1005),
    TOO_MANY_REQUESTS(1006),
    INTERNAL_SERVER(1007),
    SERVICE_UNAVAILABLE(1008),
    TIMEOUT(1009),
    VALIDATION(1010),

    // 租户 (2000-2099)
    TENANT_NOT_FOUND(2000),
    TENANT_ALREADY_EXISTS(2001),
    TENANT_INACTIVE(2002),
    TENANT_NAME_REQUIRED(2003),
    TENANT_INVALID_STATUS(2004),
    TENANT_CREATION_DISABLED(2005),

    // Agent (2100-2199)
    AGENT_MISSING_THINKING_MODEL(2100),
    AGENT_MISSING_ALLOWED_TOOLS(2101),
    AGENT_INVALID_MAX_ITERATIONS(2102),
    AGENT_INVALID_TEMPERATURE(2103),

    // 向量库绑定 (2200-2299)
    VECTOR_STORE_BINDING_INVALID(2200),
    VECTOR_STORE_UNAVAILABLE(2201),

    // 模型生命周期 (2300-2399)
    MODEL_IN_USE(2300),

    // 知识库域文档重复 (2400-2499)
    /** 上传内容与库内已有文档重复。 */
    KNOWLEDGE_DUPLICATE_FILE(2400),
    /** URL 与库内已有文档重复。 */
    KNOWLEDGE_DUPLICATE_URL(2401);

    private final int value;

    ErrorCode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
