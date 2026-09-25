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

import com.ragagent.knowledge.service.VectorStoreService;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.EngineRegistry;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.PgVectorEngineRepository;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import com.ragagent.retrieval.engine.RetrieverEngineParams;

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
    void unportedDriversAreSkipped() throws Exception {
        EngineRegistry registry = new EngineRegistry(null, null);
        assertThatCode(() -> RetrievalEngineWiringConfig.registerEnvStores(registry,
                new String[] {"qdrant", "milvus", "weaviate", "doris", "tencent_vectordb",
                        "sqlite"}, newAdapter(), null, null)).doesNotThrowAnyException();
        // 全部未落地 → 无注册、装配不炸
        assertThat(registry.getAllRetrieveEngineServices()).isEmpty();
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
