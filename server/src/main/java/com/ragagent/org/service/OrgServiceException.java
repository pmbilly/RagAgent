package com.ragagent.org.service;

/**
 * 组织域 service 层错误（对照 Go service/organization.go、kbshare.go、agent_share.go 的
 * sentinel errors）。message 逐字对照 Go 的错误字符串——部分 handler 分支按
 * err.Error() 文本匹配，文案即契约。
 */
public class OrgServiceException extends RuntimeException {

    public enum Kind {
        ORG_NOT_FOUND("organization not found"),
        ORG_PERMISSION_DENIED("permission denied for this organization"),
        CANNOT_REMOVE_OWNER("cannot remove organization owner tenant"),
        CANNOT_CHANGE_OWNER_ROLE("cannot change organization owner tenant role"),
        TENANT_NOT_IN_ORG("tenant is not a member of this organization"),
        INVALID_ROLE("invalid role"),
        INVITE_CODE_EXPIRED("invite code has expired"),
        INVALID_VALIDITY_DAYS("invite_code_validity_days must be 0, 1, 7, or 30"),
        MEMBER_LIMIT_REACHED("organization member limit reached"),
        MEMBER_LIMIT_TOO_LOW("member limit cannot be lower than current member count"),
        PENDING_REQUEST_EXISTS("pending request already exists"),
        JOIN_REQUEST_NOT_FOUND("join request not found"),
        CANNOT_UPGRADE_TO_SAME_ROLE("cannot request upgrade to same or lower role"),
        ALREADY_ADMIN("tenant is already an admin"),
        /** 原样携带 Go 文本的自定义错误（reviewed / member_limit >= 0 等）。 */
        PLAIN("");

        public final String defaultMessage;

        Kind(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }
    }

    private final Kind kind;
    private final String message;

    public OrgServiceException(Kind kind) {
        super(kind.defaultMessage);
        this.kind = kind;
        this.message = kind.defaultMessage;
    }

    public OrgServiceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
        this.message = message;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public String getMessage() {
        return message;
    }

    // ── 共享域 sentinel（kbshare.go / agent_share.go）──

    public static OrgServiceException shareNotFound() {
        return new OrgServiceException(Kind.PLAIN, "share not found");
    }

    public static OrgServiceException kbNotFound() {
        return new OrgServiceException(Kind.PLAIN, "knowledge base not found");
    }

    public static OrgServiceException notKbOwner() {
        return new OrgServiceException(Kind.PLAIN, "only knowledge base owner can share");
    }

    public static OrgServiceException orgRoleCannotShare() {
        return new OrgServiceException(Kind.PLAIN,
                "only editors and admins can share knowledge bases to this organization");
    }

    public static OrgServiceException agentNotFoundForShare() {
        return new OrgServiceException(Kind.PLAIN, "agent not found");
    }

    public static OrgServiceException notAgentOwner() {
        return new OrgServiceException(Kind.PLAIN, "only agent owner can share");
    }

    public static OrgServiceException orgRoleCannotShareAgent() {
        return new OrgServiceException(Kind.PLAIN,
                "only editors and admins can share agents to this organization");
    }

    public static OrgServiceException agentNotConfigured() {
        return new OrgServiceException(Kind.PLAIN,
                "agent is not fully configured (missing required chat model, or rerank model when the knowledge_search tool is enabled)");
    }

    public static OrgServiceException agentSharePermission() {
        return new OrgServiceException(Kind.PLAIN, "permission denied for this share operation");
    }

    /** 对照 agent_share.go ErrAgentShareNotFound（与 kbshare 的 "share not found" 不同文案）。 */
    public static OrgServiceException agentShareNotFound() {
        return new OrgServiceException(Kind.PLAIN, "agent share not found");
    }
}
