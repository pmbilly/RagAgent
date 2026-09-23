package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.ragagent.agent.tools.RemoteDirEntry;
import com.ragagent.sandbox.runtime.SandboxManager;
import com.ragagent.sandbox.runtime.SandboxSessionClient;
import com.ragagent.sandbox.runtime.SessionBoundManager;

/**
 * ArtifactCollector 的沙箱文件源生产装配（对照 Go artifact_collector.go 的
 * SandboxArtifactSource 接口 + container.go L316-321 的 Provide
 * {@code NewArtifactCollectorFromSandboxManager}）。
 *
 * <h2>装配点（Go Provide 等价物）</h2>
 * <p>Go 侧容器在启动时调 {@code NewArtifactCollectorFromSandboxManager(sandboxMgr,
 * sandboxResolver, pinner, fileService, repo, catalog)}：把 {@code *sandbox.SessionBoundManager}
 * 断言提升为 {@code SandboxArtifactSource}（其他后端 source 为 nil），collector 随每回合
 * 经 {@code sessionSource} 按 pin 重新解析。Java 侧对应装配（主会话完成）：</p>
 * <ol>
 *   <li>对本回合解析出的 {@link SandboxManager} 调
 *       {@link #fromSandboxManager(SandboxManager, long)}——非 SessionBoundManager
 *       返回 null，等价 Go 的类型断言失败分支（"sandbox backend does not advertise
 *       session filesystem"）；</li>
 *   <li>把返回值作为 {@link ArtifactCollector} 构造器的 source 注入；source 为 null
 *       时 Collect 走"无会话文件系统 → 没有可附加产物"降级链；</li>
 *   <li>Go 的 per-turn {@code sessionSource}（按会话 pin 的 config 重新解析，宁可
 *       不读也不换后端）由主会话在回合装配点用
 *       {@code SessionSandboxExecutionService.resolveForExecution} 接线——collector
 *       本体（排水/去重/上传）已翻译，不在此重复。</li>
 * </ol>
 *
 * <p>适配面：{@code listSessionFiles(输出根)} + {@code readSessionFile}——把
 * {@link SessionBoundManager} 的 {@code SandboxSessionClient.DirEntry}（枚举类型 +
 * OffsetDateTime）投影成 collector 的 {@link RemoteDirEntry}（字符串类型 + Instant），
 * 与 {@code SessionSandboxExecutionService.BoundFileStore} 同形。</p>
 */
public final class SessionBoundArtifactSource implements ArtifactCollector.SandboxArtifactSource {

    private final SessionBoundManager bound;
    private final long tenantId;

    private SessionBoundArtifactSource(SessionBoundManager bound, long tenantId) {
        this.bound = bound;
        this.tenantId = tenantId;
    }

    /**
     * 对照 Go {@code source, _ := sandboxMgr.(SandboxArtifactSource)}：管理器支持
     * 会话文件面 → 文件源；否则 null（调用方按"没有可附加产物"处理）。
     */
    public static SessionBoundArtifactSource fromSandboxManager(SandboxManager mgr, long tenantId) {
        if (mgr instanceof SessionBoundManager bound) {
            return new SessionBoundArtifactSource(bound, tenantId);
        }
        return null;
    }

    @Override
    public List<RemoteDirEntry> listSessionFiles(String sessionId, String path) {
        List<RemoteDirEntry> out = new ArrayList<>();
        for (SandboxSessionClient.DirEntry e : bound.listSessionFiles(tenantId, sessionId, path)) {
            out.add(new RemoteDirEntry(e.name(), e.path(),
                    e.type() == null ? RemoteDirEntry.TYPE_OTHER
                            : e.type().name().toLowerCase(Locale.ROOT),
                    e.size(), e.modTime() == null ? null : e.modTime().toInstant()));
        }
        return out;
    }

    @Override
    public byte[] readSessionFile(String sessionId, String path) {
        return bound.readSessionFile(tenantId, sessionId, path);
    }
}
