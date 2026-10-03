package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 一条被**刻意忘掉**的陈述的记录，免得后台蒸馏下次读到那条消息时又悄悄把它加回来。
 *
 * <p>它只存主题和一个指纹，**从不存原文**。这个取舍是明说的：换个说法重述可以回来，
 * 这就是不保留"用户要求丢弃的内容"的代价。</p>
 *
 * <h2>落库隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>自动时间戳</b>：只有 {@code created_at}，
 *       走字段名约定，落库时显式写。</li>
 *   <li><b>钩子</b>：无。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：无——{@code trimTombstones} 超上限时是**物理删**。</li>
 *   <li><b>默认排序</b>：{@code created_at DESC}（ListTombstones 与 trim 都是）。</li>
 *   <li><b>唯一索引</b>：{@code idx_mem_tomb_fp (tenant_id, subject_id, fingerprint)}
 *       ——模型 tag 与迁移都声明了，{@code AddTombstone} 的 {@code ON CONFLICT} 打的就是它。</li>
 *   <li><b>普通索引</b>：{@code source_message_id}（tag 里的 {@code index} 与迁移一致）。</li>
 *   <li><b>DEFAULT 列</b>：{@code topic}（{@code default:''}）带字面量 default tag →
 *       落库时仍显式写入。</li>
 * </ol>
 *
 * <p>本类型**不是**响应体（仓储层的 {@code ListTombstones} 目前只被抽取路径用，且只取
 * {@code getTopic()} 拼进提示词）。JSON 面只在测试里，键名＝Java 字段名、
 * 键序＝声明序：{@code sourceMessageId} 是 {@code ""}（恒输出）。</p>
 */
@TableName("memory_tombstones")
public class MemoryTombstone {

    @TableId(value = "id", type = IdType.INPUT)
    private String id = "";

    private Long tenantId = 0L;

    private String subjectId = "";

    /** 存的是**短主题名**而不是内容——告诉抽取模型哪些主题被否决，才是挡住改写版回来的关键。 */
    private String topic = "";

    /** 被忘掉那条陈述的 {@link MemoryText#fingerprint}。 */
    private String fingerprint = "";

    /**
     * 被否决的记忆来自哪条消息。
     *
     * <p>光有指纹不够：蒸馏几分钟后会重读同一条消息，通常把话说得略有不同
     * （"生产库是 X" vs "我们的生产库是 X"），哈希不同就溜过去了。
     * 记住消息这件事不带内容、又能精确堵住这条路；而用户之后说的任何话都来自更晚的消息，
     * 仍然放行。</p>
     */
    private String sourceMessageId = "";

    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { subjectId = v == null ? "" : v; }

    public String getTopic() { return topic; }
    public void setTopic(String v) { topic = v == null ? "" : v; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String v) { fingerprint = v == null ? "" : v; }

    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String v) { sourceMessageId = v == null ? "" : v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }
}
