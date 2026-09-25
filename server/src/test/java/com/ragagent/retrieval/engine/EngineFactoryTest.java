package com.ragagent.retrieval.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.EngineFactory.EngineNotSupportedException;
import com.ragagent.vectorstore.domain.ConnectionConfig;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.ragagent.vectorstore.domain.VectorStore;
import com.sun.net.httpserver.HttpServer;

/**
 * 引擎工厂（W5γ4.4b）对照 Go {@code container/engine_factory.go}：createEngineServiceFromStore
 * 的 ES v7/v8 分支（版本前缀判定、索引配置取值、Basic Auth、驱动自举）与
 * validateRuntimeVectorStoreAddresses 的逐引擎地址策略；未落地引擎走诚实 XDEP。
 */
class EngineFactoryTest {

    private record Captured(String method, String path, String body, String auth) {
    }

    private HttpServer server;
    private String base;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            captured.add(new Captured(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(raw, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            String path = exchange.getRequestURI().getPath();
            String body;
            int status;
            if (exchange.getRequestMethod().equals("HEAD")) {
                body = "";
                status = 404;
            } else if (path.endsWith("/_mapping")) {
                body = "{\"xwrag_kb\":{\"mappings\":{\"properties\":"
                        + "{\"chunk_id\":{\"type\":\"keyword\"}}}}}";
                status = 200;
            } else {
                body = "{\"acknowledged\":true}";
                status = 200;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private VectorStore store(String engineType, ConnectionConfig cc, IndexConfig idx) {
        VectorStore store = new VectorStore();
        store.setEngineType(engineType);
        store.setConnectionConfig(cc);
        store.setIndexConfig(idx);
        return store;
    }

    private ConnectionConfig esConnection(String addr, String version) {
        ConnectionConfig cc = new ConnectionConfig();
        cc.addr = addr;
        cc.username = "elastic";
        cc.password = "pwd";
        cc.version = version;
        return cc;
    }

    @Test
    @DisplayName("ES v8：版本空 → v8；索引配置生效（settings 值转字符串）+ Basic Auth + 驱动自举")
    void buildsElasticsearchV8() {
        IndexConfig idx = new IndexConfig();
        idx.indexName = "xwrag_kb";
        idx.numberOfShards = 2;
        idx.numberOfReplicas = 1;

        KeywordsVectorHybridRetrieveEngineService svc = EngineFactory.createFromStore(
                store("elasticsearch", esConnection(base, ""), idx), null);

        assertEquals("elasticsearch", svc.engineType());
        assertEquals(List.of("keywords", "vector"), svc.support(), "v8 支持关键词+向量");
        Captured put = captured.stream().filter(c -> c.method().equals("PUT"))
                .findFirst().orElseThrow();
        assertEquals("/xwrag_kb", put.path());
        assertEquals("{\"settings\":{\"number_of_shards\":\"2\",\"number_of_replicas\":\"1\"}}",
                put.body(), "v8 的 settings 是字符串");
        assertTrue(put.auth() != null && put.auth().startsWith("Basic "), "Basic Auth 带上");
        assertEquals("GET", captured.get(captured.size() - 1).method(), "构造尾部做 _mapping 探测");
    }

    @Test
    @DisplayName("ES v7：version=7.x → v7 驱动（数字 settings、Support 只有 keywords）")
    void buildsElasticsearchV7() {
        IndexConfig idx = new IndexConfig();
        idx.indexName = "xwrag_kb";
        idx.numberOfShards = 1;
        idx.numberOfReplicas = 0;

        KeywordsVectorHybridRetrieveEngineService svc = EngineFactory.createFromStore(
                store("elasticsearch", esConnection(base, "7.17.0"), idx), null);

        assertEquals(List.of("keywords"), svc.support(), "v7 不带向量");
        Captured put = captured.stream().filter(c -> c.method().equals("PUT"))
                .findFirst().orElseThrow();
        assertEquals("{\"settings\":{\"number_of_shards\":1}}", put.body(),
                "v7 的 settings 是数字；replicas=0 → 视为未设置（照 GetNumberOfReplicas(-1)）");
    }

    @Test
    @DisplayName("地址策略：未知类型报 no SSRF address policy；ES 检 addr；qdrant 检 host:port；weaviate 检两处")
    void validatesAddressPolicy() {
        ConnectionConfig cc = new ConnectionConfig();
        assertEquals("vector store engine \"foo\" has no SSRF address policy",
                assertThrows(EngineNotSupportedException.class,
                        () -> EngineFactory.validateRuntimeVectorStoreAddresses(
                                store("foo", cc, null), recordingGuard(new CopyOnWriteArrayList<>())))
                        .getMessage());

        List<String> checked = new CopyOnWriteArrayList<>();
        SsrfGuard record = recordingGuard(checked);

        cc.addr = "http://127.0.0.1:9200";
        EngineFactory.validateRuntimeVectorStoreAddresses(store("elasticsearch", cc, null), record);
        assertEquals(List.of("http://127.0.0.1:9200"), checked, "ES 只检 addr");

        checked.clear();
        ConnectionConfig qdrant = new ConnectionConfig();
        qdrant.host = "[::1]";
        qdrant.port = 6334;
        EngineFactory.validateRuntimeVectorStoreAddresses(store("qdrant", qdrant, null), record);
        assertEquals(List.of("[::1]:6334"), checked, "qdrant 检 host:port（去方括号后拼）");

        checked.clear();
        ConnectionConfig weaviate = new ConnectionConfig();
        weaviate.host = "http://127.0.0.1:8080";
        weaviate.grpcAddress = "127.0.0.1:50051";
        EngineFactory.validateRuntimeVectorStoreAddresses(store("weaviate", weaviate, null), record);
        assertEquals(List.of("http://127.0.0.1:8080", "127.0.0.1:50051"), checked,
                "weaviate 检 HTTP + gRPC 两处");

        checked.clear();
        ConnectionConfig pg = new ConnectionConfig();
        pg.addr = "http://127.0.0.1:1";
        EngineFactory.validateRuntimeVectorStoreAddresses(store("postgres", pg, null), record);
        EngineFactory.validateRuntimeVectorStoreAddresses(store("sqlite", pg, null), record);
        assertTrue(checked.isEmpty(), "postgres/sqlite 免检");
    }

    @Test
    @DisplayName("地址策略：SSRF 拒绝 → <label> failed SSRF validation；空地址放行")
    void rejectsBadAddress() {
        SsrfGuard rejecting = new SsrfGuard() {
            @Override
            public void validateURLForSSRF(String rawURL) {
                throw new IllegalArgumentException("blocked by SSRF policy: " + rawURL);
            }
        };
        ConnectionConfig cc = new ConnectionConfig();
        cc.addr = "http://169.254.169.254";
        assertEquals("vector store address failed SSRF validation: blocked by SSRF policy:"
                        + " http://169.254.169.254",
                assertThrows(EngineNotSupportedException.class,
                        () -> EngineFactory.validateRuntimeVectorStoreAddresses(
                                store("elasticsearch", cc, null), rejecting)).getMessage());

        ConnectionConfig empty = new ConnectionConfig();
        EngineFactory.validateRuntimeVectorStoreAddresses(store("elasticsearch", empty, null),
                rejecting);
    }

    @Test
    @DisplayName("未落地引擎：诚实 XDEP（postgres 指引既有 JDBC 件；其余 driver 未落地）")
    void unportedEnginesFailHonestly() {
        ConnectionConfig cc = new ConnectionConfig();
        cc.addr = "http://127.0.0.1:9200";
        ConnectionConfig pg = new ConnectionConfig();

        assertEquals("postgres retriever is served by the existing JDBC pieces"
                        + " (PgVectorRetrieveRepository / VectorStoreService), not by this factory",
                assertThrows(EngineNotSupportedException.class,
                        () -> EngineFactory.createFromStore(store("postgres", pg, null), null))
                        .getMessage());

        for (String engine : List.of("tencent_vectordb", "sqlite")) {
            String message = assertThrows(EngineNotSupportedException.class,
                    () -> EngineFactory.createFromStore(store(engine, cc, null), null))
                    .getMessage();
            assertTrue(message.contains("driver not ported in this batch"), engine + " → " + message);
            assertTrue(message.contains(engine), engine + " 文案要含引擎名");
        }
    }

    @Test
    @DisplayName("Doris：addr/database 必填（Go 原文）；齐备时建成 doris 引擎（构造不拨号）")
    void buildsDoris() {
        ConnectionConfig doris = new ConnectionConfig();
        doris.addr = "127.0.0.1:9030";
        doris.database = "weknora";

        assertEquals("doris connection requires addr (host:port)",
                assertThrows(EngineNotSupportedException.class,
                        () -> EngineFactory.createFromStore(store("doris", new ConnectionConfig(),
                                null), null)).getMessage());
        ConnectionConfig noDatabase = new ConnectionConfig();
        noDatabase.addr = "127.0.0.1:9030";
        assertEquals("doris connection requires database",
                assertThrows(EngineNotSupportedException.class,
                        () -> EngineFactory.createFromStore(store("doris", noDatabase, null), null))
                        .getMessage());

        KeywordsVectorHybridRetrieveEngineService svc = EngineFactory.createFromStore(
                store("doris", doris, null), null);
        assertEquals("doris", svc.engineType());
        assertEquals(List.of("keywords", "vector"), svc.support());
    }

    @Test
    @DisplayName("Milvus：REST v2 自持口径（addr 缺省 localhost:19530、username/password/database）；构造不拨号")
    void buildsMilvus() {
        ConnectionConfig milvus = new ConnectionConfig();
        milvus.addr = "127.0.0.1:19530";
        KeywordsVectorHybridRetrieveEngineService svc = EngineFactory.createFromStore(
                store("milvus", milvus, null), null);
        assertEquals("milvus", svc.engineType());
        assertEquals(List.of("keywords", "vector"), svc.support());
    }

    @Test
    @DisplayName("Weaviate：REST 自持口径（host/scheme 缺省见 Go）；构造不拨号")
    void buildsWeaviate() {
        ConnectionConfig weaviate = new ConnectionConfig();
        weaviate.host = "127.0.0.1:9035";
        KeywordsVectorHybridRetrieveEngineService svc = EngineFactory.createFromStore(
                store("weaviate", weaviate, null), null);
        assertEquals("weaviate", svc.engineType());
        assertEquals(List.of("keywords", "vector"), svc.support());
    }

    @Test
    @DisplayName("Qdrant：REST 自持口径（host/port 缺省 6334、api_key、use_tls）；构造不拨号")
    void buildsQdrant() {
        ConnectionConfig qdrant = new ConnectionConfig();
        qdrant.host = "127.0.0.1";
        KeywordsVectorHybridRetrieveEngineService svc = EngineFactory.createFromStore(
                store("qdrant", qdrant, null), null);
        assertEquals("qdrant", svc.engineType());
        assertEquals(List.of("keywords", "vector"), svc.support());
    }

    /** 只记录、不拒绝的守卫（观察被检查的地址）。 */
    private static SsrfGuard recordingGuard(List<String> checked) {
        return new SsrfGuard() {
            @Override
            public void validateURLForSSRF(String rawURL) {
                checked.add(rawURL);
            }
        };
    }
}
