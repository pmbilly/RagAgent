/**
 * 记忆模块对外 HTTP 响应/请求的 DTO。
 *
 * <p>只放"线格式"类型：控制器的分页响应等。领域类型（{@code memory.domain}）
 * 保持可序列化，但不再携带任何 {@code @JsonProperty}——JSON 字段名即 Java 字段名。</p>
 */
package com.ragagent.memory.dto;
