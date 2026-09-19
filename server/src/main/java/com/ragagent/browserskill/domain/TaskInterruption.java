package com.ragagent.browserskill.domain;

/**
 * 对照 Go {@code browserskill.TaskRecord}（store.go L63-70）：标记被打断、需要显式
 * resume 的会话任务。复合主键 (scope_key, session)——MyBatis-Plus 不支持复合
 * @TableId，本类型不注册 Mapper，全部走 BrowserSkillStore 的显式 SQL。
 */
public class TaskInterruption {

    private final String scopeKey;
    private final String session;

    public TaskInterruption(String scopeKey, String session) {
        this.scopeKey = scopeKey;
        this.session = session;
    }

    public String getScopeKey() { return scopeKey; }
    public String getSession() { return session; }
}
