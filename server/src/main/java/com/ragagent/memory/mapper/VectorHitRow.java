package com.ragagent.memory.mapper;

/**
 * 排名查询返回的一对 {@code (item_id, score)}。
 *
 * <p>不是实体，也不出响应——MyBatis 需要一个带无参构造与 setter 的普通类来映射裸查询结果，
 * 所以它在这里是 public。</p>
 */
public class VectorHitRow {

    private String itemId = "";
    private double score;

    public String getItemId() { return itemId; }
    public void setItemId(String v) { this.itemId = v == null ? "" : v; }

    public double getScore() { return score; }
    public void setScore(double v) { this.score = v; }
}
