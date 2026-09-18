package com.ragagent.memory.mapper;

/**
 * 排名查询返回的一对 {@code (item_id, score)}
 * （对照 Go {@code internal/application/repository/memory_vector.go} 的私有类型
 * {@code vectorHitRow}，L58-61）。
 *
 * <p>不是实体，也不出响应——MyBatis 需要一个带无参构造与 setter 的普通类来映射裸查询结果，
 * 所以它在这里是 public（Go 的同一个类型是包私有的，Java 没有包私有可见性的等价物）。</p>
 */
public class VectorHitRow {

    private String itemId = "";
    private double score;

    public String getItemId() { return itemId; }
    public void setItemId(String v) { this.itemId = v == null ? "" : v; }

    public double getScore() { return score; }
    public void setScore(double v) { this.score = v; }
}
