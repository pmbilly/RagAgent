/**
 * 知识库持久化实体与 JSON 结构（MyBatis-Plus 注解 + Jackson 序列化注解）。
 *
 * <p>实体被部分端点直出（Knowledge/Chunk 响应体），字段名即线上契约：按仓库契约政策用 Java
 * 字段名（camelCase）、不写逐字段 {@code @JsonProperty}、可空字段**显式输出 {@code null}**。
 * {@code @JsonInclude}（Go {@code omitempty} 的直译）属**分批清理面**，不要新增。
 *
 * <p>jsonb 列必须逐字段声明 {@code @TableField(typeHandler = PgJsonTypeHandler.class)}，
 * 且所在实体要带 {@code @TableName(autoResultMap = true)}——只写 typeHandler 会出现
 * 「写得进、查出来是 null」的静默故障（wrapper 的 {@code set()} 亦不套实体 typeHandler，
 * 需三参写法，见 ChunkMapper 注释）。
 *
 * <p>FaqChunkMetadata/DocumentChunkMetadata 等 metadata 结构有独立序列化契约
 * （见 JsonContractRoundTripTest）。
 */
package com.ragagent.knowledge.domain;
