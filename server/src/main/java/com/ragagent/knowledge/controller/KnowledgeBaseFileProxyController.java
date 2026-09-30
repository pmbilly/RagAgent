package com.ragagent.knowledge.controller;

import java.io.IOException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.security.ChunkAccessGuard;
import com.ragagent.storage.controller.FileProxyController;
import com.ragagent.storage.fileserve.FileAccess;
import com.ragagent.storage.fileserve.FileAccessException;
import com.ragagent.storage.fileserve.FileAccessResolver;
import com.ragagent.storage.fileserve.FileProxyService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * newKBScopedFileServeHandlerWithResources + access.ResolveKBFile）。
 * <p>渲染知识库内容（chunk / wiki 页）内嵌图片用。与租户级 /files 不同，本路由
 * 消费 RequireKBAccess 的<b>精确 KB grant</b>：文件必须属于 KB 属主租户、在
 * {@code exports/} 命名空间下、且有该 KB 内存活文档的显式绑定
 * （IsReferencedByKnowledgeBase——文本提到 handle 不算所有权证据）。</p>
 * → AllowFileServeAPIKey → g.Viewer()（RbacInterceptor 规则）→ KBAccessRead
 * → ResolveKBFile → serveAuthorizedFile。</p>
 */
@RestController
public class KnowledgeBaseFileProxyController {

    private final ChunkAccessGuard kbGuard;
    private final FileAccessResolver accessResolver;
    private final FileProxyService proxy;

    public KnowledgeBaseFileProxyController(ChunkAccessGuard kbGuard, FileAccessResolver accessResolver,
            FileProxyService proxy) {
        this.kbGuard = kbGuard;
        this.accessResolver = accessResolver;
        this.proxy = proxy;
    }

    @RequestMapping(value = "/api/v1/knowledge-bases/{id}/files", method = RequestMethod.HEAD)
    public void filesHead(@PathVariable("id") String id, HttpServletResponse response)
            throws IOException {
        FileProxyController.writeGinNoRoute(response);
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/files")
    public void files(@PathVariable("id") String id, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        // AllowFileServeAPIKey 由 AllowFileServeAPIKeyInterceptor 承担（WebConfig 接线，
        // found" / 403 "Permission denied..." 信封（ChunkAccessGuard 内逐字实现）
        KnowledgeBase kb = kbGuard.requireKbAccess(id);
        String reference = FileProxyService.requireFilePathQuery(request, response);
        if (reference == null) {
            return;
        }
        FileAccess file;
        try {
            file = accessResolver.resolveKbFile(kb == null ? null : kb.getTenantId(), id, reference);
        } catch (FileAccessException e) {
            FileProxyService.fileAccessError(response, e);
            return;
        } catch (BizException e) {
            // IsAppError 支路：{"error": message}（非全局信封）。requireKbAccess
            FileProxyService.writeErrorJson(response, e.appError().httpCode(),
                    e.appError().message());
            return;
        }
        proxy.serveAuthorizedFile(response, request, file, "KB files");
    }
}
