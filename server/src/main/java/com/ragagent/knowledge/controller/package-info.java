/**
 * 知识库 HTTP 面：薄绑定层（@Valid DTO → service → 裸资源视图），线格式 camelCase、无信封；
 * 无手搓解析与信封拼装。控制器分组：KnowledgeController（文档主面）+
 * KnowledgeOpsController（搜索/批量/搬移/文件夹）+ KnowledgeBaseController（知识库 CRUD 与
 * 混合检索）+ FaqController（FAQ 三服务分流）+ ChunkController（chunk 编辑）+
 * KnowledgeTagController（标签）；KnowledgeRouteGuards 承载两个知识控制器共用的守卫链。
 * 参数校验异常统一由 GlobalExceptionHandler 产出 400（details 为字段级中文文案）。
 */
package com.ragagent.knowledge.controller;
