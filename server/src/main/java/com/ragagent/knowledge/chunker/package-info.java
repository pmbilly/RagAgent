/**
 * 分块器：Chunker.split 主入口按 SplitterConfig 分派策略链
 * （LegacySplitter 递归字符切分 / HeadingSplitter 标题感知 / HeuristicSplitter 启发式
 * 合并），HeaderTracker 维护跨块标题上下文，DocumentProfiler 产出文档画像
 * （语言/结构统计），Tokens 提供 token 近似计数。切分是纯函数（不落库不出网）。
 */
package com.ragagent.knowledge.chunker;
