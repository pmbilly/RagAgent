package com.ragagent.wiki.controller;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderCreateRequest;
import com.ragagent.wiki.domain.WikiFolderListResponse;
import com.ragagent.wiki.domain.WikiFolderNode;
import com.ragagent.wiki.domain.WikiFolderUpdateRequest;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageMoveRequest;
import com.ragagent.wiki.service.page.WikiPageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ragagent.wiki.controller.WikiPageController.RawJsonError;

import static com.ragagent.wiki.controller.WikiRequestSupport.currentTenantId;
import static com.ragagent.wiki.controller.WikiRequestSupport.errText;
import static com.ragagent.wiki.controller.WikiRequestSupport.internal;
import static com.ragagent.wiki.controller.WikiRequestSupport.requiredFieldErrors;
import static com.ragagent.wiki.controller.WikiRequestSupport.sanitize;
import static com.ragagent.wiki.controller.WikiRequestSupport.trimSpace;

/**
 * 文件夹资源端点的执行体：目录列表/新建/改名移动/删除，以及 move-page
 * （页面 slug 走请求体——层级 slug 会撞 catch-all 路由）。
 */
final class WikiFolderOps {

    private final WikiPageService wikiService;
    private final WikiKbAccessGuard kbGuard;
    private final ObjectMapper json;

    WikiFolderOps(WikiPageService wikiService, WikiKbAccessGuard kbGuard, ObjectMapper json) {
        this.wikiService = wikiService;
        this.kbGuard = kbGuard;
        this.json = json;
    }

    /**
     * 目录列表——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>空结果显式归一成 {@code []}，所以响应是 {@code "folders":[]} 而不是 null。</p>
     */
    ResponseEntity<?> listFolders(String kbId, HttpServletRequest request) {
        kbGuard.requireWikiKB(kbId, false);

        String parentId = trimSpace(request.getParameter("parentId"));
        List<String> pageTypes = new ArrayList<>();
        String raw = trimSpace(request.getParameter("pageTypes"));
        if (!raw.isEmpty()) {
            for (String part : raw.split(",", -1)) {
                String p = trimSpace(part);
                if (!p.isEmpty()) {
                    pageTypes.add(p);
                }
            }
        }

        List<WikiFolderNode> folders;
        try {
            folders = wikiService.listChildFolders(kbId, parentId, pageTypes);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        if (folders == null) {
            folders = new ArrayList<>();
        }

        WikiFolderListResponse resp = new WikiFolderListResponse();
        resp.setParentId(parentId);
        resp.setFolders(folders);
        return ResponseEntity.ok(resp);
    }

    /** 新建目录——写端点（创建者/Admin+ + KB 写权限）；201。 */
    ResponseEntity<?> createFolder(String kbId,
                                          String rawBody) {
        kbGuard.requireWikiKB(kbId, true);

        WikiFolderCreateRequest req = WikiRequestSupport.bind(json, rawBody, WikiFolderCreateRequest.class);
        WikiFolder folder;
        try {
            // 只 trim parentID，name 原样交给服务层（服务层自己 trim 并校验）
            folder = wikiService.createFolder(kbId, currentTenantId(), trimSpace(req.parentId()), req.name());
        } catch (RuntimeException e) {
            throw WikiRequestSupport.mapFolderError(e);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(folder);
    }

    /** 重命名/移动目录——写端点（创建者/Admin+ + KB 写权限）。 */
    ResponseEntity<?> updateFolder(String kbId,
                                          String folderIdParam,
                                          String rawBody) {
        kbGuard.requireWikiKB(kbId, true);

        String folderId = sanitize(folderIdParam);
        if (folderId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Folder ID is required");
        }
        WikiFolderUpdateRequest req = WikiRequestSupport.bind(json, rawBody, WikiFolderUpdateRequest.class);

        WikiFolder folder;
        try {
            // 只 trim parentID；name 原样（空串 = 不改名）
            folder = wikiService.renameOrMoveFolder(kbId, folderId, req.name(),
                    trimSpace(req.parentId()), req.moveParent());
        } catch (RuntimeException e) {
            throw WikiRequestSupport.mapFolderError(e);
        }
        return ResponseEntity.ok(folder);
    }

    /** 删除目录——写端点（创建者/Admin+ + KB 写权限）；204。 */
    ResponseEntity<?> deleteFolder(String kbId,
                                          String folderIdParam) {
        kbGuard.requireWikiKB(kbId, true);

        String folderId = sanitize(folderIdParam);
        if (folderId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Folder ID is required");
        }
        try {
            wikiService.deleteFolder(kbId, folderId);
        } catch (RuntimeException e) {
            throw WikiRequestSupport.mapFolderError(e);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 移动页面——写端点（创建者/Admin+ + KB 写权限）。
     * 页面 slug 在请求体里（层级 slug 会撞 catch-all 路由）。
     */
    ResponseEntity<?> movePage(String kbId,
                                      String rawBody) {
        kbGuard.requireWikiKB(kbId, true);

        JsonNode node = WikiRequestSupport.readJsonBody(json, rawBody);
        String bindingErrors = requiredFieldErrors(node, "WikiPageMoveRequest", "Slug");
        if (bindingErrors != null) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + bindingErrors);
        }
        WikiPageMoveRequest req = WikiRequestSupport.toType(json, node, WikiPageMoveRequest.class);

        String slug = trimSpace(req.slug());
        if (slug.isEmpty()) {
            // 必填校验已挡住空 slug，此分支实际不可达（防御性保留）
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        WikiPage page;
        try {
            page = wikiService.movePage(kbId, slug, trimSpace(req.folderId()));
        } catch (RuntimeException e) {
            throw WikiRequestSupport.mapFolderError(e);
        }
        return ResponseEntity.ok(page);
    }
}
