package com.ragagent.session.controller;

import java.util.List;
import com.ragagent.session.domain.Message;
import java.io.IOException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.storage.controller.FileProxyController;
import com.ragagent.storage.fileserve.FileAccess;
import com.ragagent.storage.fileserve.FileAccessException;
import com.ragagent.storage.fileserve.FileAccessResolver;
import com.ragagent.storage.fileserve.FileProxyService;
import com.ragagent.storage.fileserve.FileAccessResolver.MessageFileLookup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 消息作用域的资源代理。
 *
 * <p>聊天渲染器加载 assistant 消息内嵌的资源用。消息服务先证明调用者拥有所在会话
 * （{@code MessageService.getMessage} 的 loadSessionForRead 可见性判定，含 Admin
 * 回退），持久化消息必须<b>逐字引用</b>该文件（content / artifacts[].url /
 * knowledge_references / images / agent_steps[].tool_calls[].result 五处持久化字段，
 * MessageReferencesFile 的整 token 匹配）。</p>
 *
 * <p>中间件链：APIKeyGate（chat+fullAccess 策略，见 APIKeyRoutePolicies）
 * → RBAC viewer 规则（RbacInterceptor）→ handler。</p>
 *
 * <p><b>已知收紧</b>：跨租户消息文件的 shared-agent / org-shared KB 两条授予路径
 * 未实现——owner ≠ caller 恒 403，方向偏保守。</p>
 */
@RestController
public class MessageFileProxyController {

    private final com.ragagent.session.service.MessageService messageService;
    private final FileAccessResolver accessResolver;
    private final FileProxyService proxy;

    public MessageFileProxyController(com.ragagent.session.service.MessageService messageService,
            FileAccessResolver accessResolver, FileProxyService proxy) {
        this.messageService = messageService;
        this.accessResolver = accessResolver;
        this.proxy = proxy;
    }

    /** 只有 GET 注册了真实处理：HEAD 落 NoRoute 响应（见 FileProxyController 类注释）。 */
    @RequestMapping(value = "/api/v1/sessions/{id}/messages/{message_id}/files",
            method = RequestMethod.HEAD)
    public void filesHead(@PathVariable("id") String id,
            @PathVariable("message_id") String messageId, jakarta.servlet.http.HttpServletResponse response)
            throws IOException {
        FileProxyController.writeGinNoRoute(response);
    }

    @GetMapping("/api/v1/sessions/{id}/messages/{message_id}/files")
    public void files(@PathVariable("id") String id,
            @PathVariable("message_id") String messageId, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String reference = FileProxyService.requireFilePathQuery(request, response);
        if (reference == null) {
            return;
        }
        MessageFileLookup lookup = (sid, mid) -> factsOf(messageService.getMessage(sid, mid));
        FileAccess file;
        try {
            file = accessResolver.resolveMessageFile(id, messageId, reference, lookup);
        } catch (FileAccessException e) {
            FileProxyService.fileAccessError(response, e);
            return;
        }
        proxy.serveAuthorizedFile(response, request, file, "message files");
    }

    /**
     * 端口载荷映射：只取 storage 侧授权/匹配实际读取的字段
     * （见 {@link FileAccessResolver.MessageFileFacts}）——避免 storage 依赖会话实体。
     */
    private static FileAccessResolver.MessageFileFacts factsOf(Message message) {
        if (message == null) {
            return null;
        }
        List<String> artifactUrls = new java.util.ArrayList<>();
        if (message.getArtifacts() != null) {
            for (var artifact : message.getArtifacts()) {
                if (artifact != null) {
                    artifactUrls.add(artifact.getUrl());
                }
            }
        }
        List<Object> toolResults = new java.util.ArrayList<>();
        if (message.getAgentSteps() != null) {
            for (var step : message.getAgentSteps()) {
                if (step == null || step.getToolCalls() == null) {
                    continue;
                }
                for (var call : step.getToolCalls()) {
                    if (call != null && call.getResult() != null) {
                        toolResults.add(call.getResult());
                    }
                }
            }
        }
        return new FileAccessResolver.MessageFileFacts(message.getContent(), artifactUrls,
                message.getKnowledgeReferences(), message.getImages(), toolResults,
                message.getAgentTenantId(), message.getRole());
    }
}
