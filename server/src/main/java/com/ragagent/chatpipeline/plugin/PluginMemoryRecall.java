package com.ragagent.chatpipeline.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.chatpipeline.support.MemoryUsedMemories;
import com.ragagent.event.Event;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.MemoryRecalledData;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.session.PipelineUsedMemoryView;
import com.ragagent.retrieval.obs.RetrievalObs;

/**
 * MEMORY_RECALL 阶段插件：
 * 常驻块 + 情境条目注入本轮；无模型调用；记忆信封进 MemoryPrompt，
 * 结构化 UsedMemories 走 memory.usedMemoriesFromItems 投影并 emit memory_recalled 事件。
 */
public final class PluginMemoryRecall implements Plugin {

    private final PipelinePorts.MemoryService memoryService;

    public PluginMemoryRecall(PipelinePorts.MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.MEMORY_RECALL};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        if (memoryService == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("reason", "no_service");
            PipelineLog.info("MemoryRecall", "skip", f);
            return next.next();
        }

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("query_len", chatManage.getQuery().length());
        in.put("query_preview", RetrievalObs.truncateRunes(chatManage.getQuery(), 200));
        PipelineLog.info("MemoryRecall", "input", in);

        var recall = memoryService.recall(chatManage.getQuery());
        String prompt = recall == null ? "" : recall.prompt();
        if (prompt.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("items", 0);
            f.put("injected", false);
            f.put("note", "interest memories apply in query_understand, not here");
            PipelineLog.info("MemoryRecall", "output", f);
            return next.next();
        }

        chatManage.setMemoryPrompt(prompt);
        List<MemoryItem> items = recall.items() == null ? List.of() : recall.items();
        List<PipelineUsedMemoryView> used = MemoryUsedMemories.usedMemoriesFromItems(items);
        chatManage.setUsedMemories(used);
        emitMemoryRecalled(chatManage.getEventBus(), chatManage.getSessionId(), used);

        List<String> memoryIDs = new ArrayList<>();
        for (MemoryItem item : items) {
            if (item != null && item.getId() != null && !item.getId().isEmpty()) {
                memoryIDs.add(item.getId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", chatManage.getSessionId());
        out.put("items", chatManage.getUsedMemories().size());
        out.put("injected", true);
        out.put("prompt_runes", PipelineLog.runeLength(prompt));
        out.put("memory_ids", memoryIDs);
        out.put("query_preview", RetrievalObs.truncateRunes(chatManage.getQuery(), 200));
        PipelineLog.info("MemoryRecall", "output", out);
        return next.next();
    }

    /**
     * 尽力而为的事件（emit 失败只告警不断流）。
     * EventMemoryRecalled 的 Data = MemoryRecalledData{Memories}。
     */
    static void emitMemoryRecalled(com.ragagent.event.EventBusInterface bus, String sessionID,
                                   List<PipelineUsedMemoryView> used) {
        if (bus == null || used == null || used.isEmpty()) {
            return;
        }
        Event evt = new Event();
        evt.setType(EventType.EVENT_MEMORY_RECALLED);
        evt.setSessionId(sessionID);
        evt.setData(new MemoryRecalledData(used));
        try {
            bus.emit(evt);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("error", e.getMessage());
            PipelineLog.warn("MemoryRecall", "emit_failed", f);
        }
    }
}
