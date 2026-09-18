package com.ragagent.datasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器注册表（对照 Go {@code datasource.ConnectorRegistry}，
 * internal/datasource/connector.go L96-136）。
 *
 * <h2>Go 的 map + 互斥锁 → Java 的装配期一次性填充</h2>
 * <p>Go 的 {@code Register} 会在运行期写 map（因此没有锁，但只在容器启动时调用）。
 * Java 侧同样只在装配期填充一次；{@link #register} 仍然保持与 Go 逐条一致的
 * 校验顺序（nil → type 为空 → 覆盖写入），因为
 * {@code container.initConnectorRegistry} 的错误聚合语义（{@code errors.Join}）
 * 依赖它<b>不去重、不静默跳过</b>：重复注册在 Go 里是覆盖（不报错），
 * Java 也照抄覆盖。</p>
 *
 * <h2>为什么是普通类而不是 Spring Bean</h2>
 * <p>Go 侧它是被容器显式构造并注入 service 的普通结构体。Java 侧同样由
 * 装配代码构造（下一步的 service 层），登记为 bean 的时机由主会话决定——
 * 本类不给自己加 {@code @Component}，避免在 service 层落地前就产生一个
 * 没人消费的 bean。</p>
 */
public class ConnectorRegistry {

    private final Map<String, Connector> connectors = new LinkedHashMap<>();

    /**
     * 注册一个连接器。
     *
     * @throws ConnectorException.NilConnector      连接器为 null
     * @throws ConnectorException.EmptyConnectorType {@code type()} 是空串
     */
    public void register(Connector connector) {
        if (connector == null) {
            throw new ConnectorException.NilConnector();
        }
        String type = connector.type();
        if (type == null || type.isEmpty()) {
            throw new ConnectorException.EmptyConnectorType();
        }
        connectors.put(type, connector);
    }

    /**
     * 按类型取连接器。
     *
     * @throws ConnectorException.NotFound 未注册过该类型
     */
    public Connector get(String connectorType) {
        Connector connector = connectors.get(connectorType);
        if (connector == null) {
            throw new ConnectorException.NotFound();
        }
        return connector;
    }

    /**
     * 返回全部已注册的类型。
     *
     * <p><b>与 Go 的已知差异</b>：Go 的 {@code List()} 遍历一个
     * {@code map[string]Connector}，<b>顺序随机</b>。Java 侧用
     * {@link LinkedHashMap} 按注册顺序返回——信息等价（同一集合），
     * 且可复现。下游若有依赖本列表顺序的逻辑，Java 侧反而更稳定；
     * 若下游需要"与 Go 一致的随机顺序"，那本身就是不可复现的，
     * 不应作为契约。</p>
     */
    public List<String> list() {
        return new ArrayList<>(connectors.keySet());
    }
}
