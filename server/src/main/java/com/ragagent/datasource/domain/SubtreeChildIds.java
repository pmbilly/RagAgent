package com.ragagent.datasource.domain;

/**
 * 子树子项的 external_id 构造规则。
 *
 * <h2>为什么是独立的一个类而不是 {@link FetchedItem} 上的方法</h2>
 * <p>产出方（连接器）与消费方（子树清扫，
 * {@code FindByMetadataKeyPrefix}）都要用它——它描述的是
 * **跨模块的 id 契约**，不属于任何一侧。挂在 {@link FetchedItem} 上会让
 * "清扫方"为了拿前缀而去依赖产出方的类型，正是要避开的耦合。</p>
 *
 * <h2>契约</h2>
 * <pre>
 *   subTreeChildId("docx1", "file", "tok")  →  "docx1#file#tok"
 *   subTreeChildPrefix("docx1")             →  "docx1#"
 * </pre>
 * <p>两者必须**同步演进**：都编码同一个 {@code '#'} 分隔符。清扫逻辑删的是
 * "external_id 以该前缀开头、且不在 {@code SubtreeKeep} 里"的旧子项，
 * 而子项 id 若不由此函数构造（少了 {@code '#'} 前缀），就永远不会被清到
 * ——静默变成孤儿行。</p>
 */
public final class SubtreeChildIds {

    private SubtreeChildIds() {
    }

    /**
     * 构造一个子项的 external_id。
     *
     * @param parentExternalID 父节点的 external_id
     * @param kind             短判别串（{@code "file"} / {@code "image"}）
     * @param token            源系统给子项的 id（假定不含 {@code '#'}）
     * @return {@code "<parentExternalID>#<kind>#<token>"}
     */
    public static String subtreeChildId(String parentExternalID, String kind, String token) {
        return parentExternalID + "#" + kind + "#" + token;
    }

    /**
     * 匹配某个父节点**全部**子项的 external_id 前缀。
     */
    public static String subtreeChildPrefix(String parentExternalID) {
        return parentExternalID + "#";
    }
}
