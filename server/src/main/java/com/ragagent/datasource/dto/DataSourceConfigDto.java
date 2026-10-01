package com.ragagent.datasource.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.datasource.domain.DataSourceMapSerializer;

/**
 * {@link com.ragagent.datasource.domain.DataSourceConfig} 去掉 Credentials 之后的响应形状
 * （对照 Go {@code dto.DataSourceConfigDTO}，internal/handler/dto/datasource.go L48-52）。
 *
 * <p><b>按构造剥离</b>：本类上压根没有 credentials 字段，所以密钥值不可能漏出去
 * ——与 MCP 的"结构体上就没有 api_key"是同一条不变式。</p>
 *
 * <h2>omitempty 逐字段</h2>
 * <ul>
 *   <li>{@code type}：<b>没有</b> omitempty → 空串照输出
 *       （Go 的 {@code NewDataSourceResponse} 只在 ParseConfig 成功时才构造本对象，
 *       而 Type 可能仍是空串——照抄）；</li>
 *   <li>{@code resource_ids} / {@code settings}：带 omitempty → nil 与空都省略。
 *       Java 用 {@code NON_EMPTY}（Go 对 slice/map 的 omitempty 判 {@code len==0}，
 *       与 NON_EMPTY 等价）。</li>
 * </ul>
 *
 * <h2>settings 为什么挂 DataSourceMapSerializer</h2>
 * <p>{@code settings} 装的是连接器私有配置（文件夹 token、分页大小、条目上限……），
 * 里面<b>必然</b>有数字。Go 的 {@code encoding/json} 对 map 恒按 key 排序、
 * 且 {@code float64} 走专用编码器（整数值不补 {@code .0}）；Jackson 两样都不做。
 * 而这里的 settings 是从 jsonb 读回来的、键序是 PG 的（长度,字节序）规范化序，
 * 与 Go 的字母序并不相同——所以这条序列化器是**必须**的，不是锦上添花。</p>
 */
public class DataSourceConfigDto {

    private String type = "";

    private List<String> resourceIds;

    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, Object> settings;

    public DataSourceConfigDto() {
    }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }

    public List<String> getResourceIds() { return resourceIds; }
    public void setResourceIds(List<String> v) { resourceIds = v; }

    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> v) { settings = v; }
}
