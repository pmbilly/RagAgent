package com.ragagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.VectorStoreService;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.EngineRegistry;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.PgVectorEngineRepository;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import com.ragagent.retrieval.engine.RetrieverEngineParams;
import com.ragagent.retrieval.engine.sqlite.SqliteRetrieveRepository;

/**
 * 检索引擎装配（对照 Go {@code initRetrieveEngineRegistry}）的钉子：
 * env-store 注册主体按 RETRIEVE_DRIVER 逐段生效、缺失驱动明确跳过不炸启动、
 * 重复类型注册失败只记日志（照 Go 的 Register error 分支）。
 */
class RetrievalEngineWiringConfigTest {

    /** 产品探测恒为 H2 的假 DataSource（不碰真库）。 */
    private PgVectorEngineRepository newAdapter() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn("H2");
        when(conn.getMetaData()).thenReturn(meta);
        when(ds.getConnection()).thenReturn(conn);
        return new PgVectorEngineRepository(mock(PgVectorRetrieveRepository.class),
                mock(VectorStoreService.class), ds);
    }

    @Test
    void postgresDriverRegistersEnvStoreEngine() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"postgres"}, newAdapter(), null, null);

        var svc = registry.getRetrieveEngineService(EngineTypes.ENGINE_POSTGRES);
        assertThat(svc).isNotNull();
        assertThat(svc.support()).containsExactly(EngineTypes.RETRIEVER_KEYWORDS,
                EngineTypes.RETRIEVER_VECTOR);
    }

    @Test
    void emptyDriverRegistersNothing() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        RetrievalEngineWiringConfig.registerEnvStores(registry, new String[] {""}, newAdapter(), null, null);

        assertThatThrownBy(() ->
                registry.getRetrieveEngineService(EngineTypes.ENGINE_POSTGRES))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void duplicateRegistrationIsLoggedNotThrown() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        PgVectorEngineRepository adapter = newAdapter();
        // 两次装配都注册 postgres → 第二次 Register 报"already registered"，
        // 装配路径吞掉只记日志（照 Go 的 Register ... failed 分支）
        RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"postgres"}, adapter, null, null);
        assertThatCode(() -> RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"postgres"}, adapter, null, null)).doesNotThrowAnyException();

        assertThat(registry.getRetrieveEngineService(EngineTypes.ENGINE_POSTGRES)).isNotNull();
    }

    @Test
    void sqliteDriverRegistersEnvStoreEngine() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        String previous = System.getProperty(SqliteRetrieveRepository.PROP_SQLITE_PATH);
        // 测试用临时文件（避免往仓库里写 ./data/*.sqlite）
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("sqlite-wiring");
        System.setProperty(SqliteRetrieveRepository.PROP_SQLITE_PATH,
                temp.resolve("wiring.sqlite").toString());
        try {
            RetrievalEngineWiringConfig.registerEnvStores(registry, new String[] {"sqlite"},
                    newAdapter(), null, null);
            var svc = registry.getRetrieveEngineService(EngineTypes.ENGINE_SQLITE);
            assertThat(svc).isNotNull();
            assertThat(svc.support()).containsExactly(EngineTypes.RETRIEVER_KEYWORDS,
                    EngineTypes.RETRIEVER_VECTOR);
        } finally {
            if (previous == null) {
                System.clearProperty(SqliteRetrieveRepository.PROP_SQLITE_PATH);
            } else {
                System.setProperty(SqliteRetrieveRepository.PROP_SQLITE_PATH, previous);
            }
        }
    }

    @Test
    void tencentVectorDbWithoutEnvConfigIsSkipped() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        // 照 Go：TENCENT_VECTORDB_ADDR/USERNAME/API_KEY 三者缺一 → 只记 "Missing Tencent
        // VectorDB configuration" 并跳过（本测试环境未配 env → 走该分支）
        assertThatCode(() -> RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"tencent_vectordb"}, newAdapter(), null, null))
                .doesNotThrowAnyException();
        assertThat(registry.getAllRetrieveEngineServices()).isEmpty();
    }

    @Test
    void milvusDriverRegistersEnvStoreEngine() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        // env 未配置 → Go 的缺省口径（localhost:19530）；REST 客户端构造不拨号
        RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"milvus"}, newAdapter(), null, null);
        var svc = registry.getRetrieveEngineService(EngineTypes.ENGINE_MILVUS);
        assertThat(svc).isNotNull();
        assertThat(svc.support()).containsExactly(EngineTypes.RETRIEVER_KEYWORDS,
                EngineTypes.RETRIEVER_VECTOR);
    }

    @Test
    void weaviateDriverRegistersEnvStoreEngine() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        // env 未配置 → Go 的缺省口径（weaviate:8080 / http）；REST 客户端构造不拨号
        RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"weaviate"}, newAdapter(), null, null);
        var svc = registry.getRetrieveEngineService(EngineTypes.ENGINE_WEAVIATE);
        assertThat(svc).isNotNull();
        assertThat(svc.support()).containsExactly(EngineTypes.RETRIEVER_KEYWORDS,
                EngineTypes.RETRIEVER_VECTOR);
    }

    @Test
    void qdrantDriverRegistersEnvStoreEngine() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        // env 未配置 → Go 的缺省口径（localhost:6334）；REST 客户端构造不拨号
        RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"qdrant"}, newAdapter(), null, null);
        var svc = registry.getRetrieveEngineService(EngineTypes.ENGINE_QDRANT);
        assertThat(svc).isNotNull();
        assertThat(svc.support()).containsExactly(EngineTypes.RETRIEVER_KEYWORDS,
                EngineTypes.RETRIEVER_VECTOR);
    }

    @Test
    void dorisDriverRegistersEnvStoreEngine() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        // env 未配置 → Go 的缺省口径（doris-fe:9030 / weknora / root）；构造不拨号
        // （Hikari initializationFailTimeout=-1 + Stream Load 客户端懒发请求）
        RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"doris"}, newAdapter(), null, null);
        var svc = registry.getRetrieveEngineService(EngineTypes.ENGINE_DORIS);
        assertThat(svc).isNotNull();
        assertThat(svc.support()).containsExactly(EngineTypes.RETRIEVER_KEYWORDS,
                EngineTypes.RETRIEVER_VECTOR);
    }

    @Test
    void elasticsearchDriverWithoutAddrIsSkipped() throws Exception {
        // ELASTICSEARCH_ADDR 未配置 → 建客户端失败 → 只记日志（与 Go 同形）
        EngineRegistry registry = new EngineRegistry(null, null);
        assertThatCode(() -> RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"elasticsearch_v8", "elasticsearch_v7"}, newAdapter(), null, null))
                .doesNotThrowAnyException();
        assertThat(registry.getAllRetrieveEngineServices()).isEmpty();
    }

    @Test
    void compositeCreateOverRegisteredEnvStoreWorks() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"postgres"}, newAdapter(), null, null);
        CompositeRetrieveEngine composite = CompositeRetrieveEngine.create(registry,
                List.of(new RetrieverEngineParams(EngineTypes.RETRIEVER_VECTOR,
                        EngineTypes.ENGINE_POSTGRES)));
        assertThat(composite.supportRetriever(EngineTypes.RETRIEVER_VECTOR)).isTrue();
        assertThat(composite.supportRetriever(EngineTypes.RETRIEVER_KEYWORDS)).isFalse();
    }
}
