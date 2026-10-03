package com.ragagent.datasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器注册表。
 *
 * <h2>装配期一次性填充</h2>
 * <p>只在装配期填充一次；{@link #register} 的
 * 校验顺序（null → type 为空 → 覆盖写入）是装配错误聚合语义
 * （{@code errors.Join}）依赖的约定：它<b>不去重、不静默跳过</b>，
 * 重复注册 = 覆盖，不报错。</p>
 *
 * <h2>为什么是普通类而不是 Spring Bean</h2>
 * <p>它由装配代码显式构造并注入 service，登记为 bean 的时机由装配方决定——
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
     * <p>返回顺序 = 注册顺序（{@link LinkedHashMap}）。
     * 下游若有依赖本列表顺序的逻辑，注意该顺序是确定的；
     * 顺序本身不应作为对外契约。</p>
     */
    public List<String> list() {
        return new ArrayList<>(connectors.keySet());
    }
}
