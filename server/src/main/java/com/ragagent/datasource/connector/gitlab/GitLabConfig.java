package com.ragagent.datasource.connector.gitlab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * GitLab 数据源的 {@code settings} 形状与解析（对照 Go 的
 * {@code internal/datasource/connector/gitlab/types.go} 全文）。
 *
 * <h2>它住在 {@code DataSourceConfig.settings} 这个 jsonb 列里</h2>
 * <p>形状是 {@code {"projects":[{"project_id":"…","ref":"…","paths":["…"]}]}}。
 * 因为它会落库、也会经 {@code GET /datasources/:id} 回给前端，
 * {@link ProjectSelection} 的键名必须逐字对齐 Go 的 json tag
 * （{@code project_id} / {@code ref} / {@code paths}）。</p>
 *
 * <h2>三条容易翻错的语义</h2>
 * <ol>
 *   <li><b>错误消息里带哨兵前缀</b>：Go 全部用
 *       {@code fmt.Errorf("%w: <细节>", ErrInvalidConfig)}，所以文案是
 *       {@code "invalid configuration: <细节>"}。Java 侧用
 *       {@link ConnectorException.InvalidConfig#InvalidConfig(String)} 产出同样的字符串。</li>
 *   <li><b>{@code paths} 的"缺键"与"空数组"等价</b>：两条路径都走到
 *       {@code collapsePaths(nil)} → {@code nil} → "整个项目"。</li>
 *   <li><b>{@code normalizePath} 会拒绝 {@code ".."} 之外的一切相对回退</b>，
 *       但<b>放行裸 {@code ".."}</b>——{@code path.Clean("..") == ".."} 让
 *       {@code Clean(v) != v} 这个判据不成立，而 {@code ".."} 既不匹配
 *       {@code Prefix("../")} 也不含 {@code "/../"}。这是 Go 的既有行为，照抄。</li>
 * </ol>
 *
 * <p><b>本类是配置形状，不是响应体</b>：它不直接序列化出去
 * （{@code settings} 由 {@link DataSourceConfig} 的 {@code DataSourceMapSerializer} 处理）。</p>
 */
public final class GitLabConfig {

    private final List<ProjectSelection> projects;

    private GitLabConfig(List<ProjectSelection> projects) {
        this.projects = projects;
    }

    public List<ProjectSelection> projects() {
        return projects;
    }

    /**
     * 一个项目的同步选择（对照 Go 的 {@code projectSelection}）。
     *
     * <p>{@code paths} 允许为 {@code null}（Go 的 nil slice = "整个项目"）；
     * {@code collapsePaths} 的返回值就是这个形态，别再塞空数组——
     * 空数组会让 {@code walkFiles} 的 {@code len(roots) == 0} 判据失守。</p>
     */
    public record ProjectSelection(
            @JsonProperty("project_id") String projectId,
            @JsonProperty("ref") String ref,
            @JsonProperty("paths") List<String> paths) {
    }

    /**
     * 对照 Go {@code parseConfig}：从 {@code settings} 里解析并校验项目选择。
     *
     * @throws ConnectorException.InvalidConfig 各条校验失败（消息 = Go 原文）
     */
    public static GitLabConfig parse(DataSourceConfig ds) {
        if (ds == null) {
            // 对照 Go: return nil, datasource.ErrInvalidConfig（裸哨兵，无细节）
            throw new ConnectorException.InvalidConfig();
        }
        Map<String, Object> settings = ds.getSettings();
        if (settings == null || !settings.containsKey("projects")) {
            throw new ConnectorException.InvalidConfig("settings.projects is required");
        }
        Object raw = settings.get("projects");
        if (!(raw instanceof List<?> items)) {
            throw new ConnectorException.InvalidConfig("settings.projects must be an array");
        }
        List<ProjectSelection> out = new ArrayList<>(items.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object rawProject : items) {
            if (!(rawProject instanceof Map<?, ?> m)) {
                throw new ConnectorException.InvalidConfig("invalid project selection");
            }
            String id = GoStrings.trimSpace(asString(m.get("project_id")));
            if (id.isEmpty() || !seen.add(id)) {
                throw new ConnectorException.InvalidConfig("project_id must be unique and non-empty");
            }
            List<String> paths = new ArrayList<>();
            if (m.containsKey("paths")) {
                Object rawPaths = m.get("paths");
                if (!(rawPaths instanceof List<?> values)) {
                    throw new ConnectorException.InvalidConfig("paths must be an array");
                }
                for (Object value : values) {
                    if (!(value instanceof String s)) {
                        throw new ConnectorException.InvalidConfig("path must be a string");
                    }
                    paths.add(normalizePath(s));
                }
            }
            out.add(new ProjectSelection(id, GoStrings.trimSpace(asString(m.get("ref"))),
                    collapsePaths(paths)));
        }
        if (out.isEmpty()) {
            throw new ConnectorException.InvalidConfig("at least one project is required");
        }
        return new GitLabConfig(out);
    }

    /**
     * 对照 Go {@code normalizePath}：把用户填的仓库路径归一成"干净的相对目录"。
     *
     * <p>第一步是 {@code Trim(TrimSpace(value), "/")}——<b>根路径 {@code "/"} 归一成空串</b>，
     * 空串随后被 {@link #collapsePaths} 视作"含空元素 → 整个 paths 置 nil"，
     * 于是 {@code paths: ["/"]} 的语义变成"同步整个项目"。这条链路是 Go 的
     * {@code TestParseConfigRootMeansWholeProject} 钉住的，别拆开看。</p>
     */
    public static String normalizePath(String value) {
        String v = GoStrings.trim(GoStrings.trimSpace(value), '/');
        if (v.isEmpty()) {
            return "";
        }
        if (v.contains("\\")) {
            throw new ConnectorException.InvalidConfig("path must use forward slashes");
        }
        if (!GoPath.clean(v).equals(v) || ".".equals(v)
                || v.startsWith("../") || v.contains("/../")) {
            throw new ConnectorException.InvalidConfig("invalid repository path");
        }
        return v;
    }

    /**
     * 对照 Go {@code collapsePaths}：排序后去掉"被父目录覆盖"的项。
     *
     * <p>两个必须照抄的细节：</p>
     * <ul>
     *   <li><b>含空元素时整个返回 nil</b>（不是"跳过空元素"）——这就是
     *       {@code paths: ["/"]} → 整个项目的那一步；</li>
     *   <li>判据是"等于上一项，或以 {@code 上一项+"/"} 开头"，
     *       所以 {@code ["docs/guide","docs"]} → {@code ["docs"]}，
     *       而 {@code ["ab","a"]} → {@code ["a","ab"]}（{@code ab} 不是 {@code a} 的子目录）。</li>
     * </ul>
     *
     * <p><b>与 Go 的一处刻意差异</b>：Go 的 {@code sort.Strings(paths)} 会<b>就地</b>
     * 改动调用方传进来的切片。Java 侧先拷一份再排——唯一可观察的差别是调用方列表的顺序，
     * 而 Go 里唯一的调用方（{@code parseConfig}）传的是刚构造、随后即被覆盖的切片，
     * 所以两边的返回值完全一致，且避免了未来调用方传不可变列表时炸掉。</p>
     *
     * @return 归一后的路径列表；输入为 null / 空 / 含空串时返回 {@code null}（对照 Go 的 nil slice）
     */
    public static List<String> collapsePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(paths);
        sorted.sort(String::compareTo);
        for (String p : sorted) {
            if (p.isEmpty()) {
                return null;
            }
        }
        List<String> out = new ArrayList<>(sorted.size());
        for (String p : sorted) {
            if (!out.isEmpty()) {
                String last = out.get(out.size() - 1);
                if (p.equals(last) || p.startsWith(last + "/")) {
                    continue;
                }
            }
            out.add(p);
        }
        return out;
    }

    /** 对照 Go 的 {@code v, _ := m[k].(string)}：非字符串一律当零值 {@code ""}。 */
    private static String asString(Object value) {
        return value instanceof String s ? s : "";
    }
}
