/**
 * 知识库持久化实体与 JSON 结构（MyBatis-Plus 注解 + Jackson 序列化注解）。
 * 实体的 JSON 形状即部分 HTTP 契约（Knowledge/Chunk 直出响应体），字段注解
 * {@code @JsonProperty}/{@code @JsonInclude} 与键序锁定是契约面，不可随手调整；
 * FaqChunkMetadata/DocumentChunkMetadata 等 metadata 结构有独立序列化契约
 * （见 JsonContractRoundTripTest）。
 */
package com.ragagent.knowledge.domain;
