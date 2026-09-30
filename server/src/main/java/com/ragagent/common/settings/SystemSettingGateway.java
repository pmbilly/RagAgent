package com.ragagent.common.settings;

/**
 * 系统设置的**只读端口**（跨域读取系统设置的最小接口）。
 *
 * <p>为什么定义在最底层：{@code auth} 的注册/邀请流程要读系统设置（注册模式、自助建租户、
 * 自动接受邀请等），而 {@code system} 本身依赖 {@code auth}——若 auth 直接注入 system 的
 * {@code SystemSettingService}，两域互相依赖成环。端口放在 {@code common}、由 system 侧实现承载后，
 * 依赖方向变为"域 → 端口 ← 实现"，单向。</p>
 *
 * <p>接口只列 auth 实际用到的三个读方法（{@code getString}/{@code getBool}/{@code getInt}）；
 * 写侧与列表等能力仍留在 system 域，不对外暴露。</p>
 */
public interface SystemSettingGateway {

    /** 读字符串设置：DB → env → 默认值。 */
    String getString(String key, String envName, String def);

    /** 读布尔设置：DB → env → 默认值。 */
    boolean getBool(String key, String envName, boolean def);

    /** 读整数设置：DB → env → 默认值。 */
    long getInt(String key, String envName, long def);
}
