/**
 * 知识库访问守卫：KnowledgeRouteGuards（路由级租户/成员校验）+
 * KnowledgeAccessGuard（知识库读写权限）+ ChunkAccessGuard（chunk 写守卫）+
 * FaqGuard（FAQ 域校验与批量写计划）。守卫只做判定与装载，不落库。
 */
package com.ragagent.knowledge.security;
