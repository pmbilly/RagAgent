package com.ragagent.sandbox.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * docker context 元数据解析（对照 Go {@code docker_host.go} 的 {@code dockerContextHost}
 * 与 {@code docker_host_test.go} 的夹具形状）。
 *
 * <p>回归锚点：真实 docker CLI 写出的 {@code meta.json} 是
 * {@code {"Name":"orbstack","Endpoints":{"docker":{"Host":"unix:///…"}}}}——{@code Name}
 * 在顶层、{@code Endpoints} 是对象。2026-09-25 的 E2E 抓到旧实现按
 * {@code Metadata.Name} + {@code Endpoints} 数组解析，恒空 → 回落 macOS 上失效的
 * {@code /var/run/docker.sock}。</p>
 */
class DockerHostSupportTest {

    @Test
    @DisplayName("真实形状：顶层 Name + Endpoints 对象 → 取 docker.Host 并 trim")
    void parsesRealContextMeta() {
        String meta = "{\"Name\":\"orbstack\",\"Metadata\":{},"
                + "\"Endpoints\":{\"docker\":{\"Host\":\" unix:///Users/b/.orbstack/run/docker.sock \","
                + "\"SkipTLSVerify\":false}},\"TLSMaterial\":{},\"Storage\":{\"MetadataPath\":\"x\"}}";
        assertThat(DockerHostSupport.contextHostFromMeta(meta, "orbstack"))
                .isEqualTo("unix:///Users/b/.orbstack/run/docker.sock");
    }

    @Test
    @DisplayName("名字不符 / 缺 Endpoints / 缺 docker 端点 / 非对象 → 空串（回落默认）")
    void rejectsMismatchedShapes() {
        String meta = "{\"Name\":\"colima\",\"Endpoints\":{\"docker\":{\"Host\":\"unix:///x\"}}}";
        assertThat(DockerHostSupport.contextHostFromMeta(meta, "orbstack")).isEmpty();
        assertThat(DockerHostSupport.contextHostFromMeta("{\"Name\":\"orbstack\"}", "orbstack"))
                .isEmpty();
        assertThat(DockerHostSupport.contextHostFromMeta(
                "{\"Name\":\"orbstack\",\"Endpoints\":{}}", "orbstack")).isEmpty();
        // 旧实现（被修复）误认的数组形状必须继续判空——别把回归项又加回来
        assertThat(DockerHostSupport.contextHostFromMeta(
                "{\"Metadata\":{\"Name\":\"orbstack\"},\"Endpoints\":[{\"Host\":\"unix:///x\"}]}",
                "orbstack")).isEmpty();
        assertThat(DockerHostSupport.contextHostFromMeta("", "orbstack")).isEmpty();
        assertThat(DockerHostSupport.contextHostFromMeta("not-json", "orbstack")).isEmpty();
    }
}
