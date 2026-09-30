package com.ragagent.wiki.service;



import java.time.Duration;

import java.util.ArrayList;





import java.util.List;

import java.util.Map;


import java.util.concurrent.CompletableFuture;


import java.util.concurrent.TimeUnit;



import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ArrayNode;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.ragagent.common.context.TenantContext;




import com.ragagent.llm.LlmChatClient;

import com.ragagent.llm.chat.PromptCache;

import com.ragagent.llm.domain.ChatMessage;

import com.ragagent.llm.domain.ChatOptions;

import com.ragagent.llm.domain.ChatResponse;









import com.ragagent.wiki.prompt.WikiPromptTemplate;

import com.ragagent.wiki.prompt.WikiPrompts;


import org.slf4j.Logger;

import org.slf4j.LoggerFactory;
import com.ragagent.common.wiki.GoStrings;
import com.ragagent.common.wiki.WikiImageMarkup;





/**

 * 摄取用 LLM 调用协作者:模板化生成、请求序列化与提示词预热。

 *

 * <p>持有 {@link WikiIngestService} 回引以访问其依赖与队列原语;本类不得独立实例化。</p>

 */

final class WikiIngestLlmSupport {



    private static final Logger log = LoggerFactory.getLogger(WikiIngestLlmSupport.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();



    private final WikiIngestService service;



    WikiIngestLlmSupport(WikiIngestService service) {

        this.service = service;

    }



    /**

     * 执行一个 prompt 模板，

     * 并对瞬时基础设施错误做<b>有界的指数退避重试</b>。

     *

     * <h2>重试策略</h2>

     * <ul>

     *   <li>总计最多 {@code LLM_MAX_ATTEMPTS}(3) 次尝试（首次 + 重试）；</li>

     *   <li>只重试 {@link WikiLlmRetryPolicy#isTransientLlmError} 判为瞬时的错误：

     *       HTTP 408/429/5xx、父作用域仍存活时的 context deadline exceeded、

     *       以及通用的 "timeout"/"connection reset" 措辞。4xx（除 408/429）

     *       是调用方自己的问题，快速失败；</li>

     *   <li>退避指数基数 2 秒：2s、4s、8s（{@code base << (attempt-1)}）；

     *       可被线程中断打断，让任务能及时退出。</li>

     * </ul>

     *

     * <p><b>存在理由</b>：wiki ingest 每篇文档要发好几次独立 LLM 调用

     * （抽取、摘要、去重、引用、导语），上游网关一次瞬时 504 过去会<b>永久</b>丢掉该文档的

     * 摘要页。重试加上 failedOps 重排队（见 {@code requeueFailedOps}）把这类事件

     * 变成至多几分钟的抖动。</p>

     *

     * <h2>消息布局（provider 前缀缓存的关键）</h2>

     * <ul>

     *   <li>{@code WikiPageModifyUserPrompt} 走<b>两条消息</b>：稳定的规则做 system、

     *       逐页数据做 user——规则因此可跨 reduce 批次缓存；</li>

     *   <li>其余模板只有一条 user 消息，业务指引追加在其后；</li>

     *   <li>两者的业务指引都由 {@link WikiPromptInstructions} 以同样的措辞追加。</li>

     * </ul>

     *

     * <h2>图片脱敏</h2>

     * {@code data} 的每个字段在渲染<b>之前</b>统一脱敏，跨字段共享同一份 URL→token 映射，

     * 因此同一个 URL 出现在多个字段里也拿到同一个占位符；返回内容统一还原、

     * 并丢弃模型编造或弄坏的占位符。

     */

    public String generateWithTemplate(LlmChatClient chatModel, String promptTpl,

                                       Map<String, String> data) {

        Map<String, String> safeData = data == null ? Map.of() : data;



        WikiImageMarkup.MaskedTemplateData maskedData =

                WikiImageMarkup.maskTemplateDataImageURLs(safeData);

        Map<String, String> fields = maskedData.masked();

        String prompt = WikiPromptTemplate.render(promptTpl, fields);



        String purpose = WikiPrompts.purposeOf(promptTpl);

        List<ChatMessage> messages = new ArrayList<>(2);

        if (WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT.equals(promptTpl)) {

            String systemPrompt = WikiPromptInstructions.appendCustomPromptInstructions(

                    WikiPrompts.WIKI_PAGE_MODIFY_SYSTEM_PROMPT,

                    fields.get("CustomInstructions"), fields.get("InstructionScope"));

            messages.add(ChatMessage.system(systemPrompt));

            messages.add(ChatMessage.user(prompt));

        } else {

            messages.add(ChatMessage.user(WikiPromptInstructions.appendCustomPromptInstructions(

                    prompt, fields.get("CustomInstructions"), fields.get("InstructionScope"))));

        }



        ChatOptions opts = new ChatOptions();

        opts.setTemperature(0.3);

        opts.setThinking(Boolean.FALSE);

        opts.setMaxTokens(WikiIngestConstants.LLM_MAX_TOKENS);



        String prefixFingerprint = PromptCache.promptPrefixFingerprint(messages, opts);

        String warmupKey = "";

        Long tenantId = TenantContext.currentTenantId();

        boolean tenantScoped = tenantId != null;

        if (WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT.equals(promptTpl)) {

            // 页面修改走"system 消息 + 共享源上下文"作为缓存前缀：

            // 同一源文档产出的所有页面共享它，页面元数据在它之后才分叉。

            prefixFingerprint = PromptCache.fingerprintPromptPrefix(

                    messages.get(0).getContent(), fields.getOrDefault("SharedSourceContexts", ""));

            if (tenantScoped) {

                warmupKey = PromptCache.buildPromptCacheKey(

                        tenantId, chatModel.getModelId(), purpose, prefixFingerprint);

            }

        }



        // 把 LLM 记账元数据挂到执行线程上

        String effectivePrefixFingerprint = prefixFingerprint;

        final String resolvedWarmupKey = warmupKey;

        String requestKey = PromptCache.buildPromptCacheKey(

                tenantScoped ? tenantId : 0L,

                chatModel.getModelId(),

                "wiki_exact_request",

                PromptCache.fingerprintPromptPrefix(serializeRequest(messages, opts)));



        java.util.concurrent.Callable<Object> execute = () -> {

            WikiLlmCallMetadata.set(purpose, effectivePrefixFingerprint);



            // 用长度为 1 的数组承载 release 句柄：lambda 里不能给局部变量重新赋值

            Runnable[] warmupHolder = { () -> { } };

            boolean holdsWarmup = tenantScoped

                    && WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT.equals(promptTpl)

                    && !GoStrings.trimSpace(fields.getOrDefault("SharedSourceContexts", "")).isEmpty();

            if (holdsWarmup) {

                warmupHolder[0] = awaitWikiPromptWarmup(resolvedWarmupKey);

            }

            try {

                Exception lastErr = null;

                for (int attempt = 1; attempt <= WikiIngestConstants.LLM_MAX_ATTEMPTS; attempt++) {

                    ChatResponse response = null;

                    Exception callErr = null;

                    try {

                        response = chatModel.chat(messages, opts);

                    } catch (Exception e) {

                        callErr = e;

                    }

                    if (callErr == null && response != null) {

                        return response.getContent() == null ? "" : response.getContent();

                    }

                    if (callErr == null) {

                        callErr = new IllegalStateException("LLM returned nil response");

                    }

                    lastErr = callErr;



                    if (!WikiLlmRetryPolicy.isTransientLlmError(

                            Thread.currentThread().isInterrupted(), callErr)) {

                        throw new IllegalStateException("LLM call failed: " + callErr.getMessage(), callErr);

                    }

                    if (attempt == WikiIngestConstants.LLM_MAX_ATTEMPTS) {

                        break;

                    }

                    Duration backoff = WikiIngestConstants.llmBackoff(attempt);

                    log.warn("wiki ingest: LLM call failed (attempt {}/{}), retrying in {}s: {}",

                            attempt, WikiIngestConstants.LLM_MAX_ATTEMPTS,

                            backoff.toSeconds(), callErr.getMessage());

                    try {

                        Thread.sleep(backoff.toMillis());

                    } catch (InterruptedException ie) {

                        // 等待期间被中断：任务正在取消，不再退避

                        Thread.currentThread().interrupt();

                        throw new IllegalStateException(

                                "LLM call aborted during backoff: interrupted", ie);

                    }

                }

                throw new IllegalStateException("LLM call failed after "

                        + WikiIngestConstants.LLM_MAX_ATTEMPTS + " attempts: "

                        + (lastErr == null ? "" : lastErr.getMessage()), lastErr);

            } finally {

                WikiLlmCallMetadata.clear();

                warmupHolder[0].run();

            }

        };



        String content;

        if (!tenantScoped) {

            // 缺少租户上下文对生产 wiki 工作是异常情况。安全起见<b>跳过跨调用合并</b>，

            // 而不是把不相关的请求塞进一个合成的 tenant-0 桶里。

            try {

                content = (String) execute.call();

            } catch (Exception e) {

                throw new IllegalStateException(e.getMessage(), e);

            }

            return WikiImageMarkup.unmaskImageURLs(content, maskedData.tokenToUrl());

        }



        try {

            CompletableFuture<Object> result = service.llmRequests.doChan(requestKey, execute);

            content = (String) SingleFlight.await(result);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException("LLM call aborted: interrupted", e);

        } catch (Exception e) {

            throw new IllegalStateException(e.getMessage(), e);

        }

        return WikiImageMarkup.unmaskImageURLs(content, maskedData.tokenToUrl());

    }



    /**

     * 把消息与选项序列化成"精确请求"指纹的输入。

     *

     * <p>字段序是 {@code messages, options}；由 {@code ChatMessage} / {@code ChatOptions}
     * 自身的 {@code @JsonPropertyOrder} 与 NON_EMPTY 注解决定形状。
     * 该键只用于<b>进程内</b>的跨调用合并，不落库、不外泄。</p>

     */

    static String serializeRequest(List<ChatMessage> messages, ChatOptions opts) {

        ObjectNode root = MAPPER.createObjectNode();

        ArrayNode messagesNode = root.putArray("messages");

        for (ChatMessage m : messages) {

            messagesNode.add(MAPPER.valueToTree(m));

        }

        root.set("options", MAPPER.valueToTree(opts));

        try {

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {

            return "";

        }

    }



    /**

     * 只串行化同一个可复用

     * Wiki 页面前缀的<b>首个</b>请求。

     *

     * <p>leader（第一个到达的调用）拿到一个 release 句柄，<b>必须</b>在它的 LLM 调用

     * 结束后调用；跟随者会阻塞到 leader 释放。

     * 释放后本地的"已预热"标记保留 4 分钟（覆盖并行的 reduce 突发），

     * 然后被回收——它不该变成常驻的应用级缓存。</p>

     *

     * @return release 句柄
     * @throws InterruptedException 等待期间线程被中断

     */

    public Runnable awaitWikiPromptWarmup(String key) throws InterruptedException {

        if (key == null || key.isEmpty()) {

            return () -> { };

        }

        WikiIngestService.PromptWarmup candidate = new WikiIngestService.PromptWarmup();

        WikiIngestService.PromptWarmup existing = service.promptWarmups.putIfAbsent(key, candidate);

        if (existing == null) {

            // leader

            return () -> {

                if (candidate.closed.compareAndSet(false, true)) {

                    candidate.done.complete(null);

                }

                // 保持本地"已预热"标记足够久以覆盖并行的 reduce 突发，

                // 又不至于变成常驻应用缓存

                service.warmupReaper.schedule(() -> service.promptWarmups.remove(key, candidate),

                        4, TimeUnit.MINUTES);

            };

        }

        // 跟随者：等 leader 完成（可被中断）

        try {

            existing.done.get();

        } catch (InterruptedException e) {

            // 等待期间被中断：让调用方感知取消

            Thread.currentThread().interrupt();

            throw e;

        } catch (java.util.concurrent.ExecutionException e) {

            // leader 的 future 不会异常完成（只 complete(null)），走到这里说明装配错了

            throw new IllegalStateException("prompt warmup gate failed", e.getCause());

        }

        return () -> { };

    }



    /** 供测试/可观测：当前的预热标记数 */

    public int promptWarmupCount() {

        return service.promptWarmups.size();

    }

}
