package com.ragagent.system.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.system.dto.SystemDtos;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * GET /system/capabilities 的启动快照（对照 Go 的
 * {@code router.NewRouter → params.SystemHandler.BindDeploymentCapabilities(
 * deploymentCapabilitiesFromRouter(params))} 装配点）。
 *
 * <p>Go 的可用性判定是"对应 handler 是否被注入"（nil = 路由未注册）。Java 侧等价物是
 * "对应模块的控制器/服务 bean 是否存在"——用 {@link ObjectProvider} 在启动期探测，
 * 结果绑定进本 holder（快照 = 启动期装配，运行期不重算；docker 的活值覆盖在
 * controller 里做，对照 overlayLiveDockerSandboxCapability）。</p>
 *
 * <p>当前 Java 部署的注册状态（= 快照值，随模块翻译推进而变化——这正是该端点的语义）：
 * organizations/agents/integrations.im/integrations.embed/settings.sandbox 的路由未注册 →
 * supported=false + "route_not_registered"；其余 true。与 Go dev 的差异属于<b>部署状态漂移</b>
 * （同 §9 "vector_store_engine_type 键的有无"的先例），A/B 时按部署各自断言。</p>
 */
@Component
public class DeploymentCapabilitiesHolder {

    private volatile SystemDtos.DeploymentCapabilitiesData snapshot =
            new SystemDtos.DeploymentCapabilitiesData("standard", new LinkedHashMap<>());

    public SystemDtos.DeploymentCapabilitiesData snapshot() {
        return snapshot;
    }

    /**
     * 组合根（WebConfig）在装配完成后调用一次——对照 Go 在 NewRouter 尾部的
     * BindDeploymentCapabilities。
     */
    public void bind(boolean organizations, boolean agents, boolean im, boolean embed,
                     boolean api, boolean mcp, boolean webSearch, boolean vectorStore,
                     boolean storage, boolean sandbox) {
        boolean isLite = "lite".equalsIgnoreCase(edition());
        SystemDtos.DeploymentCapability organizationsCap =
                SystemDtos.DeploymentCapability.notRegistered();
        if (organizations && !isLite) {
            organizationsCap = SystemDtos.DeploymentCapability.yes();
        } else if (isLite) {
            organizationsCap = new SystemDtos.DeploymentCapability(false, "not_supported_in_lite");
        }

        Map<String, SystemDtos.DeploymentCapability> caps = new LinkedHashMap<>();
        // 构造顺序无语义——encoding/json 对 map 恒按字母序输出（Jackson 用 record 声明序
        // 序列化字段、LinkedHashMap 保插入序，因此这里要按 Go 输出的字母序插入）。
        caps.put("agents", capability(agents));
        caps.put("integrations.api", capability(api));
        caps.put("integrations.embed", capability(embed));
        caps.put("integrations.im", capability(im));
        caps.put("organizations", organizationsCap);
        caps.put("settings.mcp", capability(mcp));
        caps.put("settings.sandbox", capability(sandbox));
        // 快照里的 docker 与 Go 同构：Sandbox && DockerBackendEnabled()；Java 无 docker 后端
        caps.put("settings.sandbox.docker", sandbox
                ? new SystemDtos.DeploymentCapability(false, "docker_backend_disabled")
                : SystemDtos.DeploymentCapability.notRegistered());
        caps.put("settings.storage", capability(storage));
        caps.put("settings.vectorstore", capability(vectorStore));
        caps.put("settings.websearch", capability(webSearch));
        snapshot = new SystemDtos.DeploymentCapabilitiesData(edition(), caps);
    }

    private static String edition() {
        String raw = System.getenv("WEKNORA_EDITION");
        return raw == null || raw.isBlank() ? "standard" : raw.trim();
    }

    private static SystemDtos.DeploymentCapability capability(boolean present) {
        return present ? SystemDtos.DeploymentCapability.yes()
                : SystemDtos.DeploymentCapability.notRegistered();
    }
}
