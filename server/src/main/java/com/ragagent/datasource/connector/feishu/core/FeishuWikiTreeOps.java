package com.ragagent.datasource.connector.feishu.core;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.PartialWikiNodeListException;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNodeInfoResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNodeListFailure;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNodeListResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiSpace;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiSpaceListResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书 wiki 空间的树遍历：空间列表、节点分页、单节点读取，以及"某节点及其全部后代"
 * 的深度优先遍历（部分子树失败时收集失败项并继续）。
 *
 * <p>持有 {@link FeishuClient} 回引以借用其请求能力；本类不得独立实例化。</p>
 */
final class FeishuWikiTreeOps {

    private static final Logger log = LoggerFactory.getLogger(FeishuWikiTreeOps.class);

    private final FeishuClient client;

    FeishuWikiTreeOps(FeishuClient client) {
        this.client = client;
    }

    /** 对照 Go {@code ListWikiSpaces}：列出应用可见的全部 wiki 空间（自动翻页）。 */
    public List<WikiSpace> listWikiSpaces() {
        List<WikiSpace> allSpaces = new ArrayList<>();
        String pageToken = "";
        while (true) {
            String path = "/open-apis/wiki/v2/spaces?page_size=50";
            if (!pageToken.isEmpty()) {
                path += "&page_token=" + pageToken;
            }

            WikiSpaceListResponse resp = client.doRequest("GET", path, null, WikiSpaceListResponse.class);
            if (resp == null || resp.code() != 0) {
                int code = resp == null ? -1 : resp.code();
                String msg = resp == null ? "" : resp.msg();
                log.error("[Feishu] ListWikiSpaces error: code={} msg={}", code, msg);
                throw new ConnectorException("list wiki spaces error: code=" + code + " msg=" + msg);
            }

            List<WikiSpace> items = resp.data() == null ? List.of() : FeishuClient.nvl(resp.data().items());
            log.info("[Feishu] ListWikiSpaces: got {} spaces, has_more={}",
                    items.size(), resp.data() != null && resp.data().hasMore());
            for (int i = 0; i < items.size(); i++) {
                WikiSpace s = items.get(i);
                log.info("[Feishu]   space[{}]: id={} name=\"{}\" visibility={}",
                        i, s.spaceId(), s.name(), s.visibility());
            }

            allSpaces.addAll(items);

            if (resp.data() == null || !resp.data().hasMore()
                    || resp.data().pageToken() == null || resp.data().pageToken().isEmpty()) {
                break;
            }
            pageToken = resp.data().pageToken();
        }

        log.info("[Feishu] ListWikiSpaces: total {} spaces", allSpaces.size());
        return allSpaces;
    }

    /**
     * 对照 Go {@code ListWikiNodes}：列出某空间下的全部节点（自动翻页）。
     * {@code parentNodeToken} 为空时返回顶层节点。
     */
    public List<WikiNode> listWikiNodes(String spaceId, String parentNodeToken) {
        List<WikiNode> allNodes = new ArrayList<>();
        String pageToken = "";
        String parent = parentNodeToken == null ? "" : parentNodeToken;

        while (true) {
            String path = "/open-apis/wiki/v2/spaces/" + spaceId + "/nodes?page_size=50";
            if (!parent.isEmpty()) {
                path += "&parent_node_token=" + parent;
            }
            if (!pageToken.isEmpty()) {
                path += "&page_token=" + pageToken;
            }

            WikiNodeListResponse resp = client.doRequest("GET", path, null, WikiNodeListResponse.class);
            if (resp == null || resp.code() != 0) {
                int code = resp == null ? -1 : resp.code();
                String msg = resp == null ? "" : resp.msg();
                throw new ConnectorException("list wiki nodes error: code=" + code + " msg=" + msg);
            }

            for (WikiNode node : resp.data() == null ? List.<WikiNode>of() : FeishuClient.nvl(resp.data().items())) {
                // 飞书对"列子节点"的响应有时不带 parent_node_token / space_id，就地补齐，
                // 否则下游的 ResolveResourceAncestors 与 picker 展开会丢层级。
                if (!parent.isEmpty() && node.getParentNodeId().isEmpty()) {
                    node.setParentNodeId(parent);
                }
                if (node.getSpaceId().isEmpty()) {
                    node.setSpaceId(spaceId);
                }
                allNodes.add(node);
            }

            if (resp.data() == null || !resp.data().hasMore()
                    || resp.data().pageToken() == null || resp.data().pageToken().isEmpty()) {
                break;
            }
            pageToken = resp.data().pageToken();
        }

        return allNodes;
    }

    /** 对照 Go {@code GetWikiNode}：取单个 wiki 节点的元数据。 */
    public WikiNode getWikiNode(String spaceId, String nodeToken) {
        String path = "/open-apis/wiki/v2/spaces/get_node?token="
                + FeishuSupport.queryEscape(nodeToken);

        WikiNodeInfoResponse resp = client.doRequest("GET", path, null, WikiNodeInfoResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("get wiki node error: code=" + code + " msg=" + msg);
        }
        if (resp.data() == null || resp.data().node() == null) {
            throw new ConnectorException("get wiki node error: code=0 msg=empty node");
        }

        WikiNode node = resp.data().node();
        if (node.getSpaceId().isEmpty()) {
            node.setSpaceId(spaceId);
        }
        return node;
    }

    /**
     * 对照 Go {@code listAllWikiNodesRecursive}：深度优先列出空间下全部节点。
     *
     * <p>部分子树列举失败时收集进 {@link PartialWikiNodeListException} 并<b>继续</b>——
     * 已经拿到的节点照样可用。</p>
     */
    public List<WikiNode> listAllWikiNodesRecursive(String spaceId) {
        List<WikiNode> topNodes = listWikiNodes(spaceId, "");

        List<WikiNode> allNodes = new ArrayList<>();
        List<WikiNodeListFailure> failures = new ArrayList<>();
        walkWikiNodes(client, spaceId, topNodes, allNodes, failures);

        if (!failures.isEmpty()) {
            throw new PartialWikiNodeListException(allNodes, failures);
        }
        return allNodes;
    }

    /**
     * 对照 Go {@code ListWikiNodesRecursiveFrom}：返回某个节点<b>及其全部后代</b>。
     * {@code nodeToken} 为空时等价于整空间遍历。
     */
    public List<WikiNode> listWikiNodesRecursiveFrom(String spaceId, String nodeToken) {
        if (nodeToken == null || nodeToken.isEmpty()) {
            return listAllWikiNodesRecursive(spaceId);
        }

        WikiNode root = getWikiNode(spaceId, nodeToken);

        List<WikiNode> out = new ArrayList<>();
        out.add(root);
        try {
            out.addAll(listWikiNodeDescendants(spaceId, root));
            return out;
        } catch (PartialWikiNodeListException e) {
            // Go: append([]WikiNode{root}, nodes...) 之后把同一个 partial 错误往上抛。
            // 部分结果里 root 仍要保留，所以重建一个携带 root 的异常。
            List<WikiNode> partial = new ArrayList<>();
            partial.add(root);
            partial.addAll(e.getNodes());
            throw new PartialWikiNodeListException(partial, e.getFailures());
        }
    }

    private static void walkWikiNodes(FeishuClient client, String spaceId, List<WikiNode> nodes,
                                      List<WikiNode> allNodes, List<WikiNodeListFailure> failures) {
        for (WikiNode node : nodes) {
            allNodes.add(node);
            if (!node.isHasChild()) {
                continue;
            }
            List<WikiNode> children;
            try {
                children = client.listWikiNodes(spaceId, node.getNodeToken());
            } catch (RuntimeException e) {
                RuntimeException wrapped = new ConnectorException(
                        "list children of " + node.getNodeToken() + ": " + e.getMessage(), e);
                failures.add(new WikiNodeListFailure(node, wrapped));
                log.warn("[Feishu] partial wiki node listing failure: space={} node={} err={}",
                        spaceId, node.getNodeToken(), e.getMessage());
                continue;
            }
            walkWikiNodes(client, spaceId, children, allNodes, failures);
        }
    }

    /** 对照 Go {@code listWikiNodeDescendants}（不含 root 本身）。 */
    private List<WikiNode> listWikiNodeDescendants(String spaceId, WikiNode root) {
        if (!root.isHasChild()) {
            return new ArrayList<>();
        }

        List<WikiNode> children;
        try {
            children = listWikiNodes(spaceId, root.getNodeToken());
        } catch (RuntimeException e) {
            RuntimeException wrapped = new ConnectorException(
                    "list children of " + root.getNodeToken() + ": " + e.getMessage(), e);
            log.warn("[Feishu] partial wiki node listing failure: space={} node={} err={}",
                    spaceId, root.getNodeToken(), e.getMessage());
            throw new PartialWikiNodeListException(List.of(), List.of(new WikiNodeListFailure(root, wrapped)));
        }

        List<WikiNode> allNodes = new ArrayList<>();
        List<WikiNodeListFailure> failures = new ArrayList<>();
        walkWikiNodes(client, spaceId, children, allNodes, failures);
        if (!failures.isEmpty()) {
            throw new PartialWikiNodeListException(allNodes, failures);
        }
        return allNodes;
    }
}
