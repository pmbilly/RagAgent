package com.ragagent.storage.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ragagent.common.retrieval.SearchResult;

/**
 * {@link Rewriter} / {@link FileServiceResolver} 的对等测试
 * （对照 Go {@code internal/storageurl/storageurl_test.go} 与 {@code request_test.go}）。
 *
 * <p>断言文案尽量照抄 Go 的注释或断言消息——它们记录的是"为什么这条规则存在"。</p>
 */
class RewriterTest {

    // ── 测试替身（对照 Go 的 stubFileService / fixedResolver / stubResolver） ──

    /** 只关心 {@code getFileURL} 的 FileService 替身；{@code calls} 用于钉"只解析一次"。 */
    static final class StubFileService implements FileService {
        private final java.util.function.Function<String, String> behavior;
        final AtomicInteger calls = new AtomicInteger();

        StubFileService() {
            this(filePath -> "https://cdn.example.com/" + filePath);
        }

        StubFileService(java.util.function.Function<String, String> behavior) {
            this.behavior = behavior;
        }

        @Override
        public String getFileURL(String filePath) {
            calls.incrementAndGet();
            return behavior.apply(filePath);
        }
    }

    /** 对每条引用都返回同一个 FileService。 */
    record FixedResolver(FileService svc) implements Resolver {
        @Override
        public FileService resolveFileService(String ref) {
            return svc;
        }
    }

    static Rewriter stubRewriter(String url) {
        return new Rewriter(new FixedResolver(new StubFileService(ignored -> url)), "TEST");
    }

    // ── Rewriter.String ──

    @Test
    void rewritesEveryReferenceForm() {
        StubFileService svc = new StubFileService(ignored -> "https://cdn.example.com/signed.png");
        Rewriter w = new Rewriter(new FixedResolver(svc), "TEST");

        String in = "handle ![a](resource://xifDo7NTSL300Lp1goVutw) "
                + "legacy ![b](minio://bucket/10000/exports/b.png) "
                + "scoped ![c](storage://backend-a/cos://bucket/ap/10000/exports/c.png)";
        String out = w.rewrite(in);

        assertThat(out).doesNotContain("resource://");
        assertThat(out).doesNotContain("minio://");
        assertThat(out).doesNotContain("storage://");
        assertThat(svc.calls.get()).isEqualTo(3);
    }

    /** 回答里已经是公开 URL 的引用必须原样留着。 */
    @Test
    void leavesHttpUrlsAlone() {
        Rewriter w = stubRewriter("https://cdn.example.com/x.png");
        String in = "![a](https://example.com/a.png) and ![b](http://example.com/b.png)";
        assertThat(w.rewrite(in)).isEqualTo(in);
    }

    /**
     * 发出一个取不到的 URL 比留着 handle 更糟：客户端还能退回认证过的 {@code /files} 代理。
     */
    @Test
    void nonHttpResultIsNoOp() {
        Rewriter w = stubRewriter("storage://7cb970a6/oss://bucket/10000/exports/a.png");
        String in = "![img](resource://xifDo7NTSL300Lp1goVutw)";
        assertThat(w.rewrite(in)).isEqualTo(in);
    }

    @Test
    void resolveFailureIsNoOp() {
        Rewriter w = new Rewriter(new FixedResolver(new StubFileService(ignored -> {
            throw new IllegalStateException("backend unreachable");
        })), "TEST");
        String in = "![img](resource://xifDo7NTSL300Lp1goVutw)";
        assertThat(w.rewrite(in)).isEqualTo(in);
    }

    @Test
    void unknownBackendIsNoOp() {
        Rewriter w = new Rewriter(new FixedResolver(null), "TEST");
        String in = "![img](resource://xifDo7NTSL300Lp1goVutw)";
        assertThat(w.rewrite(in)).isEqualTo(in);
    }

    /**
     * 大写 scheme 按 RFC 3986 §3.1 是合法的（例如把 {@code OBS_PROXY_DOMAIN} 配成
     * {@code HTTPS://…}），必须被替换掉而不是丢弃。
     */
    @Test
    void uppercaseSchemeIsSubstituted() {
        Rewriter w = stubRewriter("HTTPS://cdn.example.com/x.png");
        String out = w.rewrite("![img](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out).contains("HTTPS://cdn.example.com/x.png");
        assertThat(out).doesNotContain("resource://");
    }

    /** 每次 {@code resource://} 解析都要写一行 access-grant，所以重复的图每请求只解析一次。 */
    @Test
    void memoisesRepeatedReferences() {
        StubFileService svc = new StubFileService();
        Rewriter w = new Rewriter(new FixedResolver(svc), "TEST");

        String ref = "resource://xifDo7NTSL300Lp1goVutw";
        String first = w.rewrite("![a](" + ref + ")");
        String second = w.rewrite("![b](" + ref + ")");

        assertThat(svc.calls.get()).as("the same reference must resolve once per Rewriter").isEqualTo(1);
        assertThat(first).isEqualTo("![a](https://cdn.example.com/" + ref + ")");
        assertThat(second).contains("https://cdn.example.com/" + ref);
    }

    @Test
    void disabledWithoutResolver() {
        Rewriter w = new Rewriter(null, "TEST");
        String in = "![img](resource://xifDo7NTSL300Lp1goVutw)";
        assertThat(w.enabled()).isFalse();
        assertThat(w.rewrite(in)).isEqualTo(in);
        assertThat(w.rewriteRef(in)).isEqualTo(in);
    }

    /**
     * {@code rewriteRef} 处理"本身就是一条引用"的值（如 {@code MessageImage.URL}），
     * 而且不得碰根本不是引用的值。
     */
    @Test
    void rewriteRefHandlesBareReferences() {
        Rewriter w = stubRewriter("https://cdn.example.com/x.png");

        assertThat(w.rewriteRef("resource://xifDo7NTSL300Lp1goVutw"))
                .isEqualTo("https://cdn.example.com/x.png");
        assertThat(w.rewriteRef("")).isEmpty();
        assertThat(w.rewriteRef("data:image/png;base64,AAAA")).isEqualTo("data:image/png;base64,AAAA");
    }

    @Test
    void isHttpUrl() {
        for (String s : List.of("http://a", "https://a", "HTTP://a", "HTTPS://a")) {
            assertThat(Rewriter.isHttpUrl(s)).as(s).isTrue();
        }
        for (String s : List.of("", "ftp://a", "resource://abc", "local://1/a.png", "http:/")) {
            assertThat(Rewriter.isHttpUrl(s)).as(s).isFalse();
        }
    }

    // ── CopyReferences / CopyData ──

    /**
     * SSE 的 references 载荷与流的重放缓冲、以及正在落库的助手消息**共享同一批指针**，
     * 所以重写不能就地改。
     */
    @Test
    void copyReferencesDoesNotMutateOriginals() {
        Rewriter w = stubRewriter("https://cdn.example.com/x.png");
        SearchResult original = new SearchResult();
        original.setContent("chunk ![c](resource://xifDo7NTSL300Lp1goVutw)");
        original.setMatchedContent("match ![m](resource://xifDo7NTSL300Lp1goVutw)");
        original.setImageInfo("[{\"url\":\"resource://xifDo7NTSL300Lp1goVutw\"}]");

        List<SearchResult> refs = new ArrayList<>();
        refs.add(original);
        refs.add(null);

        List<SearchResult> out = w.copyReferences(refs);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isNotSameAs(original);
        assertThat(original.getContent())
                .as("the replay buffer's copy must be untouched")
                .isEqualTo("chunk ![c](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out.get(0).getContent()).isEqualTo("chunk ![c](https://cdn.example.com/x.png)");
        assertThat(out.get(0).getMatchedContent()).isEqualTo("match ![m](https://cdn.example.com/x.png)");
        assertThat(out.get(0).getImageInfo())
                .isEqualTo("[{\"url\":\"https://cdn.example.com/x.png\"}]");
        assertThat(out.get(1)).isNull();
    }

    @Test
    void copyReferencesDisabledReturnsInput() {
        Rewriter w = new Rewriter(null, "TEST");
        SearchResult ref = new SearchResult();
        ref.setContent("![a](resource://xifDo7NTSL300Lp1goVutw)");
        List<SearchResult> refs = List.of(ref);
        assertThat(w.copyReferences(refs)).isSameAs(refs);
    }

    /**
     * agent 工具元数据由工具定义形状，所以**每个字符串叶子**都要重写——
     * 而且重放缓冲持有的那份源 map 不能被改。
     */
    @Test
    void copyDataRewritesNestedStringsWithoutMutating() {
        Rewriter w = stubRewriter("https://cdn.example.com/x.png");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool_name", "chart_export");
        data.put("duration_ms", 42);
        data.put("output", "![chart](resource://xifDo7NTSL300Lp1goVutw)");
        data.put("nested", Map.of("images",
                List.of("resource://xifDo7NTSL300Lp1goVutw", "http://example.com/x.png")));

        Map<String, Object> out = w.copyData(data);

        assertThat(data.get("output"))
                .as("the replay buffer's map must be untouched")
                .isEqualTo("![chart](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out.get("output")).isEqualTo("![chart](https://cdn.example.com/x.png)");
        assertThat(out.get("tool_name")).isEqualTo("chart_export");
        assertThat(out.get("duration_ms")).isEqualTo(42);

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) out.get("nested");
        @SuppressWarnings("unchecked")
        List<Object> images = (List<Object>) nested.get("images");
        assertThat(images.get(0)).isEqualTo("https://cdn.example.com/x.png");
        assertThat(images.get(1)).isEqualTo("http://example.com/x.png");
    }

    /**
     * references 事件把检索结果带了两遍：{@code StreamResponse.KnowledgeReferences} 与
     * {@code Data}。内存流管理器下 {@code Data} 里保留的是**有类型的切片**，
     * 所以 {@code copyData} 必须能走进去，否则 {@code Data} 那份会漏出句柄。
     */
    @Test
    void copyDataRewritesTypedReferenceSlices() {
        Rewriter w = stubRewriter("https://cdn.example.com/x.png");
        SearchResult original = new SearchResult();
        original.setContent("figure ![f](resource://xifDo7NTSL300Lp1goVutw)");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("references", List.of(original));
        data.put("tags", List.of("resource://xifDo7NTSL300Lp1goVutw", "plain"));
        data.put("metadata", Map.of("thumb", "resource://xifDo7NTSL300Lp1goVutw"));

        Map<String, Object> out = w.copyData(data);

        @SuppressWarnings("unchecked")
        List<SearchResult> refs = (List<SearchResult>) out.get("references");
        assertThat(refs.get(0).getContent()).isEqualTo("figure ![f](https://cdn.example.com/x.png)");
        assertThat(original.getContent()).isEqualTo("figure ![f](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(out.get("tags")).isEqualTo(List.of("https://cdn.example.com/x.png", "plain"));
        assertThat(out.get("metadata")).isEqualTo(Map.of("thumb", "https://cdn.example.com/x.png"));
    }

    /** 每个 SSE 事件都复制一遍元数据 map 纯属浪费，所以没变时必须原样返回。 */
    @Test
    void copyDataReturnsInputWhenNothingChanges() {
        Rewriter w = stubRewriter("https://cdn.example.com/x.png");
        Map<String, Object> data = Map.of("tool_name", "chart_export", "duration_ms", 42);
        assertThat(w.copyData(data)).isSameAs(data);
    }

    @Test
    void copyDataNilAndDisabled() {
        assertThat(stubRewriter("https://x/y.png").copyData(null)).isNull();
        Map<String, Object> data = Map.of("output", "![a](resource://xifDo7NTSL300Lp1goVutw)");
        assertThat(new Rewriter(null, "TEST").copyData(data)).isSameAs(data);
    }

    // ── forRequest / 模式 ──

    /** 默认模式不得解析任何东西。 */
    @Test
    void forRequestHandleModeIsDisabled() {
        Rewriter w = Rewriter.forRequest(Mode.HANDLE, null, new StubFileService(), null);
        assertThat(w.enabled()).isFalse();
    }

    @Test
    void forRequestPublicModeIsEnabled() {
        Rewriter w = Rewriter.forRequest(Mode.PUBLIC, null, new StubFileService(), null);
        assertThat(w.enabled()).isTrue();
        assertThat(w.rewriteRef("resource://xifDo7NTSL300Lp1goVutw"))
                .isEqualTo("https://cdn.example.com/resource://xifDo7NTSL300Lp1goVutw");
    }

    /**
     * <b>未翻译的存储后端在此处显形</b>：既没有 provider 级 FileService、
     * 也没有进程级默认服务时，{@code minio://} 之类引用解析不出 HTTP URL，
     * 于是被原样保留成 handle——正是 {@link Rewriter} "绝不发出取不到的 URL"的降级。
     * 与 Go 未配置 {@code APP_EXTERNAL_URL} 的部署表现一致。
     */
    @Test
    void unresolvableProviderReferenceIsLeftAsHandle() {
        Rewriter w = Rewriter.forRequest(Mode.PUBLIC, null, null, null);
        String in = "![a](minio://bucket/10000/exports/a.png)";
        assertThat(w.rewrite(in)).isEqualTo(in);
    }

    /**
     * 有进程级默认服务时，provider 引用**会**走它——这是 Go {@code BuildFileServiceForProvider}
     * 的回落分支（{@code NewFileServiceFromStorageConfig} 建不出来就回落到 {@code defaultSvc}），
     * 不是 Java 的自作主张。注意 {@code resource://} 手柄也走同一个默认服务。
     */
    @Test
    void providerReferenceFallsBackToProcessDefaultService() {
        Rewriter w = Rewriter.forRequest(Mode.PUBLIC, null, new StubFileService(), null);
        assertThat(w.rewrite("![a](minio://bucket/10000/exports/a.png)"))
                .isEqualTo("![a](https://cdn.example.com/minio://bucket/10000/exports/a.png)");
    }

    // ── 消息历史（对照 request_test.go 的 TestRewriteMessages*） ─────────────

    /** 响应形态必须**不改原对象**——原对象可能与 service 缓存共享。 */

    // ── FileServiceResolver 的引用解析 ──

    @Test
    void resourceHandleResolvesThroughDefaultService() {
        StubFileService defaultSvc = new StubFileService();
        FileServiceResolver resolver = new FileServiceResolver(null, defaultSvc, null);
        assertThat(resolver.resolveFileService("resource://xifDo7NTSL300Lp1goVutw")).isSameAs(defaultSvc);
    }

    /** 22 位以外的 handler 不是合法 {@code resource://} 手柄，走不到默认服务。 */
    @Test
    void malformedResourceHandleIsNotATransportHandle() {
        StubFileService defaultSvc = new StubFileService();
        FileServiceResolver resolver = new FileServiceResolver(null, defaultSvc, null);
        assertThat(resolver.resolveFileService("resource://short")).isNull();
    }

    @Test
    void providerSchemeWinsOverTenantDefault() {
        StubFileService defaultSvc = new StubFileService();
        FileServiceResolver resolver = new FileServiceResolver(null, defaultSvc, null);
        assertThat(resolver.resolveFileService("minio://bucket/10000/a.png")).isSameAs(defaultSvc);
    }

    @Test
    void storageBackendPrefixIsStrippedBeforeProviderDetection() {
        StubFileService defaultSvc = new StubFileService();
        FileServiceResolver resolver = new FileServiceResolver(null, defaultSvc, null);
        assertThat(resolver.resolveFileService("storage://backend-a/cos://bucket/ap/10000/c.png"))
                .isSameAs(defaultSvc);
    }

    /** 没有 scheme、租户也没配默认 provider → 认不出来。 */
    @Test
    void unknownSchemeWithoutTenantConfigIsNull() {
        FileServiceResolver resolver = new FileServiceResolver(null, new StubFileService(), null);
        assertThat(resolver.resolveFileService("/var/data/files/a.png")).isNull();
    }

    /** 同一个 (backend, provider) 只解析一次。 */
    @Test
    void fileServiceIsCachedPerBackendAndProvider() {
        AtomicInteger resolved = new AtomicInteger();
        StorageBackendResolver backendResolver = (tenantId, backendId, provider, baseDir) -> {
            resolved.incrementAndGet();
            return new StorageBackendResolver.Resolved(new StubFileService());
        };
        com.ragagent.auth.domain.Tenant tenant = new com.ragagent.auth.domain.Tenant();
        tenant.setId(10002L);

        FileServiceResolver resolver = new FileServiceResolver(tenant, null, backendResolver);
        FileService first = resolver.resolveFileService("minio://bucket/10000/a.png");
        FileService second = resolver.resolveFileService("minio://bucket/10000/b.png");

        assertThat(second).isSameAs(first);
        assertThat(resolved.get()).isEqualTo(1);
    }
}
