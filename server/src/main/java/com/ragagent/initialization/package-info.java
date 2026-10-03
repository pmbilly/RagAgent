/**
 * 系统初始化与模型能力域：{@code /api/v1/initialization/*} 的初始化配置、Ollama 管理、模型连通性测试、
 * 文本抽取试验与语音识别（ASR）。2026-09-30 由 {@code agentm} 拆出——原包是"智能体管理 + 初始化能力"的混装，
 * 拆分后本域只对"把模型/语音/抽取这些设备跑起来"负责；智能体自身的定义与管理在 {@code agent.management}。
 * <p>自带契约：内层 snake 键（{@code agent config jsonb} 族为跨包登记冻结边界）。</p>
 */
package com.ragagent.initialization;
