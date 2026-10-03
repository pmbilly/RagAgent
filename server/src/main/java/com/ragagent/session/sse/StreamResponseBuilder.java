package com.ragagent.session.sse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.stream.StreamEvent;

/**
 * 把流事件翻成 SSE 响应体。
 *
 * <h2>这是本项目最关键的 emit 点</h2>
 * <p>本类是 {@code StreamEvent → StreamResponse} 的唯一转换点，下游的
 * 帧写出只是把它写到线缆上。
 * 因此<b>这里决定了字节</b>，逐个字段如下：</p>
 *
 * <table border="1">
 *   <caption>buildStreamResponse 的字段映射</caption>
 *   <tr><th>StreamResponse 字段</th><th>来源</th><th>备注</th></tr>
 *   <tr><td>{@code id}</td><td>入参 {@code requestId}</td><td>不是事件的 id</td></tr>
 *   <tr><td>{@code response_type}</td><td>{@code evt.type}</td><td>恒输出</td></tr>
 *   <tr><td>{@code content}</td><td>{@code evt.content}</td><td>恒输出</td></tr>
 *   <tr><td>{@code done}</td><td>{@code evt.done}</td><td>恒输出</td></tr>
 *   <tr><td>{@code data}</td><td>{@code evt.data}</td><td><b>同一引用</b>，不拷贝</td></tr>
 *   <tr><td>{@code usage}</td><td>{@code evt.usage}</td><td>omitempty</td></tr>
 *   <tr><td>{@code session_id}</td><td>{@code evt.data["session_id"]}</td>
 *       <td><b>仅</b> {@code response_type == agent_query} 时取，且必须是字符串类型</td></tr>
 *   <tr><td>{@code assistant_message_id}</td><td>{@code evt.data["assistant_message_id"]}</td>
 *       <td>同上</td></tr>
 *   <tr><td>{@code knowledge_references}</td><td>{@code evt.data["references"]}</td>
 *       <td><b>仅</b> {@code response_type == references} 时取，见下</td></tr>
 *   <tr><td>{@code tool_calls} / {@code finish_reason}</td>
 *       <td>—</td><td><b>从不设置</b></td></tr>
 * </table>
 *
 * <h2>{@code references} 事件的三态</h2>
 * <ol>
 *   <li>{@code data["references"]} 缺席 / {@code null} → <b>不设</b>该字段（omitempty → 整键省略）。</li>
 *   <li>值是 {@code []*SearchResult}（活路径，本进程内刚构建）→ 原样赋上。</li>
 *   <li>值是 {@code []interface{}}（<b>从 Redis 回放</b>，元素已退化成 {@code map}）→
 *       逐个交给 {@link #searchResultFromMap} 重建；<b>非 map 的元素直接跳过</b>，不报错。</li>
 *   <li>其它类型（如字符串）→ 一条分支都不命中，同样不设该字段。</li>
 * </ol>
 * <p>第 3 条是 {@code continue-stream} 能回放历史引用的关键：事件经
 * {@code StreamJson} 落进 Redis 再读回来，静态类型已经丢了，只剩 map。</p>
 *
 * <h2>为什么 {@code searchResultFromMap} 只填部分字段</h2>
 * <p>只恢复 17 个字段，{@code match_type} / {@code sub_chunk_id} /
 * {@code metadata} / {@code chunk_metadata} / {@code matched_content} /
 * {@code knowledge_custom_metadata} 都不恢复（除 {@code metadata} 外）。
 * 于是重建出来的结果里：{@code match_type} 是 {@code 0}、
 * {@code sub_chunk_id} 是 {@code null}、为空即省略的那几个键直接消失。
 * <b>这是既定行为，不要"顺手补全"</b>——补了字节就不一样了。</p>
 */
public final class StreamResponseBuilder {

    private StreamResponseBuilder() {
    }

    /** 事件 → 响应体的字段映射（见类注释的字段表）。 */
    public static StreamResponse build(StreamEvent evt, String requestId) {
        StreamResponse response = new StreamResponse();
        response.setId(requestId);
        response.setResponseType(evt.getType());
        response.setContent(evt.getContent());
        response.setDone(evt.isDone());
        response.setData(evt.getData());
        response.setUsage(evt.getUsage());

        // agent_query 事件携带会话与助手消息 ID，供前端把"正在生成的这一轮"挂到正确的会话上。
        if (evt.getType() == ResponseType.AGENT_QUERY) {
            Map<String, Object> data = evt.getData();
            if (data != null) {
                if (data.get("session_id") instanceof String sid) {
                    response.setSessionId(sid);
                }
                if (data.get("assistant_message_id") instanceof String amid) {
                    response.setAssistantMessageId(amid);
                }
            }
        }

        if (evt.getType() == ResponseType.REFERENCES) {
            Map<String, Object> data = evt.getData();
            Object refsData = data == null ? null : data.get("references");
            if (refsData == null) {
                return response;
            }
            response.setKnowledgeReferences(toSearchResults(refsData));
        }

        return response;
    }

    /**
     * 断言链的等效实现：{@code References} / {@code []*SearchResult} 直接透传，
     * 其余（Redis 回放得到的 {@code []interface{}}）按 map 重建。
     * 类型完全不认识时返回 {@code null}（= 不设字段）。
     */
    private static List<SearchResult> toSearchResults(Object refsData) {
        if (refsData instanceof List<?> refs) {
            List<SearchResult> results = new ArrayList<>(refs.size());
            for (Object ref : refs) {
                if (ref instanceof SearchResult sr) {
                    results.add(sr);
                } else if (ref instanceof Map<?, ?> refMap) {
                    results.add(searchResultFromMap(refMap));
                }
                // 其余元素直接跳过，不报错。
            }
            return results;
        }
        return null;
    }

    /**
     * 从"过了 JSON 序列化往返"的 map 重建检索结果。
     *
     * <p>只有 {@code metadata} 的存在性被保留（且只收 string→string 的键值对，
     * 非 string 的值被丢弃）；其余未列出的字段一律留在零值上，见类注释。</p>
     */
    private static SearchResult searchResultFromMap(Map<?, ?> refMap) {
        SearchResult sr = new SearchResult();
        sr.setId(getString(refMap, "id"));
        sr.setContent(getString(refMap, "content"));
        sr.setKnowledgeId(getString(refMap, "knowledge_id"));
        sr.setChunkIndex((int) getFloat64(refMap, "chunk_index"));
        sr.setKnowledgeTitle(getString(refMap, "knowledge_title"));
        sr.setStartAt((int) getFloat64(refMap, "start_at"));
        sr.setEndAt((int) getFloat64(refMap, "end_at"));
        sr.setSeq((int) getFloat64(refMap, "seq"));
        sr.setScore(getFloat64(refMap, "score"));
        sr.setChunkType(getString(refMap, "chunk_type"));
        sr.setParentChunkId(getString(refMap, "parent_chunk_id"));
        sr.setImageInfo(getString(refMap, "image_info"));
        sr.setKnowledgeFilename(getString(refMap, "knowledge_filename"));
        sr.setKnowledgeSource(getString(refMap, "knowledge_source"));
        sr.setKnowledgeDescription(getString(refMap, "knowledge_description"));
        sr.setKnowledgeBaseId(getString(refMap, "knowledge_base_id"));

        if (refMap.get("metadata") instanceof Map<?, ?> meta) {
            Map<String, String> metadata = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : meta.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    metadata.put(key, value);
                }
            }
            sr.setMetadata(metadata);
        }
        return sr;
    }

    /** 类型不符或缺席一律给 {@code ""}。 */
    private static String getString(Map<?, ?> m, String key) {
        Object val = m.get(key);
        return val instanceof String s ? s : "";
    }

    /**
     * 认不出来就给 {@code 0.0}。
     *
     * <p>Jackson 反序列化会按大小给出 {@code Integer}/{@code Long}/{@code Double}，
     * 故统一按 {@link Number} 收，覆盖全部数值形态。</p>
     */
    private static double getFloat64(Map<?, ?> m, String key) {
        Object val = m.get(key);
        return val instanceof Number n ? n.doubleValue() : 0.0;
    }
}
