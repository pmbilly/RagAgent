package com.ragagent.datasource.connector.feishu.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFile;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFileListFailure;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFileListResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFolderMetaResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveShortcutInfo;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.PartialDriveFileListException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书云盘（Drive）的文件列举：单页列举、跨页收全、文件夹元信息，以及
 * "某文件夹及其全部后代"的递归遍历（带 visited 去重与部分失败收集）。
 *
 * <p>持有 {@link FeishuClient} 回引以借用其请求能力；本类不得独立实例化。</p>
 */
final class FeishuDriveOps {

    private static final Logger log = LoggerFactory.getLogger(FeishuDriveOps.class);

    private final FeishuClient client;

    FeishuDriveOps(FeishuClient client) {
        this.client = client;
    }

    /**
     * 列一个云盘文件夹的直接子项（单页）。
     *
     * <p>{@code folderToken == ""} 直接拒绝：根文件夹不可分页、也不返回快捷方式
     * （飞书 API 限制），静默放行会丢内容且可能产出无界响应。</p>
     */
    public FeishuClient.DriveFilePage listDriveFiles(String folderToken, String pageToken) {
        if (folderToken == null || folderToken.isEmpty()) {
            throw new ConnectorException("root folder not supported; specify a concrete folder_token "
                    + "(root folder is not paginated and does not return shortcuts)");
        }

        String path = "/open-apis/drive/v1/files?folder_token=" + FeishuSupport.queryEscape(folderToken);
        path += "&page_size=200"; // 上限
        path += "&order_by=EditedTime&direction=DESC";
        if (pageToken != null && !pageToken.isEmpty()) {
            path += "&page_token=" + FeishuSupport.queryEscape(pageToken);
        }

        DriveFileListResponse resp = client.doRequest("GET", path, null, DriveFileListResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("list drive files error: code=" + code + " msg=" + msg);
        }

        List<DriveFile> files = resp.data() == null ? List.of() : FeishuClient.nvl(resp.data().files());
        String next = resp.data() == null ? "" : FeishuClient.nvl(resp.data().nextPageToken());
        log.info("[FeishuDrive] listDriveFiles: folder={} got {} files, has_more={}",
                folderToken, files.size(), resp.data() != null && resp.data().hasMore());
        return new FeishuClient.DriveFilePage(files, next);
    }

    /**
     * 取单个云盘文件夹的元数据（名字、所有者…）。
     * 用来解析根文件夹的人类可读名字——列表 API 只返回子项，不返回它自己。
     */
    public DriveFolderMetaResponse getDriveFolderMeta(String folderToken) {
        if (folderToken == null || folderToken.isEmpty()) {
            throw new ConnectorException("root folder not supported; specify a concrete folder_token");
        }
        String path = "/open-apis/drive/explorer/v2/folder/" + FeishuSupport.queryEscape(folderToken) + "/meta";
        DriveFolderMetaResponse resp = client.doRequest("GET", path, null, DriveFolderMetaResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("get drive folder meta error: code=" + code + " msg=" + msg);
        }
        return resp;
    }

    /** 翻页取完一个文件夹的全部直接子项。 */
    public List<DriveFile> listDriveFilesAllPages(String folderToken) {
        List<DriveFile> all = new ArrayList<>();
        String pageToken = "";
        while (true) {
            FeishuClient.DriveFilePage page = listDriveFiles(folderToken, pageToken);
            all.addAll(page.files());
            if (page.nextPageToken().isEmpty()) {
                break;
            }
            pageToken = page.nextPageToken();
        }
        return all;
    }

    /**
     * 深度优先走一个云盘文件夹子树，
     * 返回全部<b>非文件夹</b>文件。
     *
     * <ul>
     *   <li>{@code folder} → 递归（{@code visited} 纯属防御性环路保护；云盘文件夹没有
     *       快捷方式概念，理论上不成环）；</li>
     *   <li>{@code shortcut} → 展开成目标（target_type 不可能是 folder，已实测），
     *       把目标当普通文件纳入，不需要额外 API 调用（{@code shortcut_info} 就在列表响应里）；</li>
     *   <li>其它 → 直接收下。</li>
     * </ul>
     * <p>部分失败（某个子文件夹列举报错）收集进
     * {@link PartialDriveFileListException}，遍历<b>继续</b>——镜像 wiki 的同语义。</p>
     */
    public List<DriveFile> listDriveFilesRecursiveFrom(String folderToken) {
        Set<String> visited = new HashSet<>();
        List<DriveFile> all = new ArrayList<>();
        List<DriveFileListFailure> failures = new ArrayList<>();
        walkDriveFolder(client, folderToken, visited, all, failures);
        if (!failures.isEmpty()) {
            throw new PartialDriveFileListException(all, failures);
        }
        return all;
    }

    private static void walkDriveFolder(FeishuClient client, String folderToken, Set<String> visited,
                                        List<DriveFile> all, List<DriveFileListFailure> failures) {
        if (visited.contains(folderToken)) {
            return;
        }
        visited.add(folderToken);

        List<DriveFile> files;
        try {
            files = client.listDriveFilesAllPages(folderToken);
        } catch (RuntimeException e) {
            RuntimeException wrapped = new ConnectorException(
                    "list children of " + folderToken + ": " + e.getMessage(), e);
            failures.add(new DriveFileListFailure(folderToken, wrapped));
            log.warn("[FeishuDrive] partial drive file listing failure: folder={} err={}",
                    folderToken, e.getMessage());
            return;
        }

        for (DriveFile f : files) {
            switch (f.getType()) {
                case "folder" -> walkDriveFolder(client, f.getToken(), visited, all, failures);
                case "shortcut" -> {
                    DriveShortcutInfo info = f.getShortcutInfo();
                    if (info != null && info.targetToken() != null && !info.targetToken().isEmpty()) {
                        DriveFile expanded = new DriveFile();
                        expanded.setToken(info.targetToken());
                        expanded.setName(f.getName());
                        expanded.setType(info.targetType());
                        expanded.setParentToken(f.getParentToken());
                        expanded.setUrl(f.getUrl());
                        expanded.setCreatedTime(f.getCreatedTime());
                        expanded.setModifiedTime(f.getModifiedTime());
                        expanded.setOwnerId(f.getOwnerId());
                        all.add(expanded);
                    }
                }
                default -> all.add(f);
            }
        }
    }
}
