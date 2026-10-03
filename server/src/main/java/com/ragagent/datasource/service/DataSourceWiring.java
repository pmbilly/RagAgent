package com.ragagent.datasource.service;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorRegistry;
import com.ragagent.datasource.DataSourceSyncTaskQueue;
import com.ragagent.datasource.Scheduler;
import com.ragagent.datasource.connector.feishu.core.FeishuRegion;
import com.ragagent.datasource.connector.feishu.drive.DriveConnector;
import com.ragagent.datasource.connector.feishu.wiki.WikiConnector;
import com.ragagent.datasource.connector.gitlab.GitLabConnector;
import com.ragagent.datasource.connector.ima.ImaConnector;
import com.ragagent.datasource.connector.notion.NotionConnector;
import com.ragagent.datasource.connector.rss.RssConnector;
import com.ragagent.datasource.connector.yuque.YuqueConnector;
import com.ragagent.datasource.mapper.DataSourceRepository;
import com.ragagent.datasource.mapper.SyncLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * datasource 模块的装配。
 *
 * <h2>为什么连接器注册表是普通 bean 而不是 {@code @Component}</h2>
 * <p>注册表是"被显式构造并逐条填充"的容器对象，
 * {@link ConnectorRegistry} 的类注释也写明了它刻意不给自己加 {@code @Component}
 * ——填充逻辑属于装配层。</p>
 *
 * <h2>9 个连接器实例</h2>
 * <p>feishu / lark（同一条 wiki 连接器，国际云只是 host 与 tenant 不同）、
 * feishu_drive / lark_drive（云盘模式）、notion、yuque、ima、rss、gitlab。
 * 注册表是 {@code LinkedHashMap}，{@code list()} 按注册顺序返回。</p>
 *
 * <h2>注册失败 = 启动失败</h2>
 * <p>所有注册错误聚合后<b>故意</b>让"连接器配错或重复注册"在容器初始化时就大声失败，
 * 而不是运行期静默少掉一个功能。体现为 {@link #connectorRegistry()} 抛异常 → 上下文刷新失败。</p>
 */
@Configuration
public class DataSourceWiring {

    private static final Logger log = LoggerFactory.getLogger(DataSourceWiring.class);

    /** 构造并填充连接器注册表。 */
    @Bean
    public ConnectorRegistry connectorRegistry() {
        ConnectorRegistry registry = new ConnectorRegistry();
        List<RuntimeException> errors = new ArrayList<>();

        register(registry, errors, "feishu", () -> new WikiConnector(FeishuRegion.FEISHU));
        // Lark 是飞书的国际云：同一个连接器，host 与 tenant 不同
        register(registry, errors, "lark", () -> new WikiConnector(FeishuRegion.LARK));
        // 飞书云盘模式：不同的 connector type，好让注册表派发到 Drive 连接器
        register(registry, errors, "feishu_drive",
                () -> new DriveConnector(FeishuRegion.FEISHU_DRIVE));
        register(registry, errors, "lark_drive",
                () -> new DriveConnector(FeishuRegion.LARK_DRIVE));
        register(registry, errors, "notion", NotionConnector::new);
        register(registry, errors, "yuque", YuqueConnector::new);
        register(registry, errors, "ima", ImaConnector::new);
        register(registry, errors, "rss", RssConnector::new);
        register(registry, errors, "gitlab", GitLabConnector::new);

        if (!errors.isEmpty()) {
            RuntimeException first = errors.get(0);
            for (RuntimeException e : errors) {
                log.error("[datasource] connector registration failed: {}", e.getMessage());
            }
            throw first;
        }
        return registry;
    }

    private static void register(ConnectorRegistry registry, List<RuntimeException> errors,
                                 String label, java.util.function.Supplier<Connector> factory) {
        try {
            registry.register(factory.get());
        } catch (RuntimeException e) {
            errors.add(new IllegalStateException("register " + label + " connector: "
                    + e.getMessage(), e));
        }
    }

    /** 构造数据源调度器。 */
    @Bean
    public Scheduler dataSourceScheduler(DataSourceRepository dsRepo,
                                         SyncLogRepository syncLogRepo,
                                         DataSourceSyncTaskQueue taskQueue) {
        return new Scheduler(dsRepo, syncLogRepo, taskQueue);
    }

    /**
     * 应用启动时加载全部 active 数据源的 cron 表达式并启动调度器。
     *
     * <p><b>尽力而为</b>：{@code scheduler.start} 失败只记 warn，
     * 容器照常起来（调度挂了不影响管理面）。关闭时摘掉 cron 任务。</p>
     */
    @Bean
    public SmartLifecycle dataSourceSchedulerLifecycle(Scheduler dataSourceScheduler) {
        return new SmartLifecycle() {

            private volatile boolean running;

            @Override
            public void start() {
                try {
                    dataSourceScheduler.start();
                } catch (RuntimeException e) {
                    log.warn("[Container] data source scheduler start failed: {}", e.getMessage());
                }
                running = true;
            }

            @Override
            public void stop() {
                dataSourceScheduler.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            /** 晚于绝大多数生命周期 bean，关闭时最先被停。 */
            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 100;
            }
        };
    }
}
