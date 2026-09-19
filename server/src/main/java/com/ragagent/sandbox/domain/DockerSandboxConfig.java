package com.ragagent.sandbox.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code types.DockerSandboxConfig}（internal/types/tenant.go L734-782）。
 *
 * <p>寻址一个 Docker daemon。image 必填（对 MicroVM 后端相当于 template ID）。
 * daemon endpoint 是唯一的连接字段，TLS 材料按路径引用而非存储（不进备份/导出/响应）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DockerSandboxConfig {

    @JsonProperty("image")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String image = "";

    /** DOCKER_HOST 形式的 daemon endpoint；空 = 本地 unix socket */
    @JsonProperty("host")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String host = "";

    /** WeKnora 宿主机上含 ca.pem/cert.pem/key.pem 的目录；Host 为 TCP 时必填 */
    @JsonProperty("tls_cert_path")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String tlsCertPath = "";

    /** 单沙箱可用 CPU 核数；0 = 内建默认 */
    @JsonProperty("cpu_limit")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private double cpuLimit;

    @JsonProperty("memory_limit_mb")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int memoryLimitMb;

    @JsonProperty("pids_limit")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int pidsLimit;

    /** 只接受 "bridge" / "none"（见 runtime.ValidateDockerNetworkMode） */
    @JsonProperty("network_mode")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String networkMode = "";

    /** 备选 OCI runtime（如 "runsc"）；空 = daemon 默认 */
    @JsonProperty("runtime")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String runtime = "";

    @JsonProperty("idle_ttl_seconds")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int idleTtlSeconds;

    @JsonProperty("http_timeout_sec")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int httpTimeoutSec;

    public String getImage() { return image; }
    public void setImage(String v) { image = v == null ? "" : v; }
    public String getHost() { return host; }
    public void setHost(String v) { host = v == null ? "" : v; }
    public String getTlsCertPath() { return tlsCertPath; }
    public void setTlsCertPath(String v) { tlsCertPath = v == null ? "" : v; }
    public double getCpuLimit() { return cpuLimit; }
    public void setCpuLimit(double v) { cpuLimit = v; }
    public int getMemoryLimitMb() { return memoryLimitMb; }
    public void setMemoryLimitMb(int v) { memoryLimitMb = v; }
    public int getPidsLimit() { return pidsLimit; }
    public void setPidsLimit(int v) { pidsLimit = v; }
    public String getNetworkMode() { return networkMode; }
    public void setNetworkMode(String v) { networkMode = v == null ? "" : v; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String v) { runtime = v == null ? "" : v; }
    public int getIdleTtlSeconds() { return idleTtlSeconds; }
    public void setIdleTtlSeconds(int v) { idleTtlSeconds = v; }
    public int getHttpTimeoutSec() { return httpTimeoutSec; }
    public void setHttpTimeoutSec(int v) { httpTimeoutSec = v; }

    public DockerSandboxConfig copy() {
        DockerSandboxConfig c = new DockerSandboxConfig();
        c.image = image;
        c.host = host;
        c.tlsCertPath = tlsCertPath;
        c.cpuLimit = cpuLimit;
        c.memoryLimitMb = memoryLimitMb;
        c.pidsLimit = pidsLimit;
        c.networkMode = networkMode;
        c.runtime = runtime;
        c.idleTtlSeconds = idleTtlSeconds;
        c.httpTimeoutSec = httpTimeoutSec;
        return c;
    }
}
