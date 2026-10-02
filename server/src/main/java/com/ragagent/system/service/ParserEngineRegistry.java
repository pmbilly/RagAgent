package com.ragagent.system.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.system.dto.SystemDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 解析引擎注册表 + 合并逻辑（对照 Go internal/infrastructure/docparser/
 * engine_registry.go ListAllEngines + engines.go 的 8 个本地引擎）。
 *
 * <p>合并规则（逐条照抄）：</p>
 * <ul>
 *   <li>本地引擎恒在列表里（注册序 = 展示序），可用性走 Go 侧 CheckAvailable；</li>
 *   <li>远端（docreader ListEngines RPC）同名引擎的 FileTypes（非空）与 Description
 *       （非空）**覆盖**本地值——远端对自己能力是权威；</li>
 *   <li>远端独有的引擎**原样追加**（自动发现新增 Python 引擎，如 markitdown/opendataloader）。</li>
 * </ul>
 *
 * <p><b>已知差异（照抄 Go 的不可达分支）</b>：anydoc 是 Go 编译期绑定
 * （{@code -tags anydoc}），未打该 tag 的构建恒 false——Java 无对应物，
 * 恒走 "not built into this binary" 分支。mineru/mineru_cloud/paddleocr_vl(_cloud)
 * 的 Ping 分支只在对应 override 配置了才触达（配置了就会发真实网络请求），
 * 未配置的 "not configured" 文案分支确定。</p>
 */
@Service
public class ParserEngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(ParserEngineRegistry.class);

    private final DocReaderClient docReader;

    public ParserEngineRegistry(DocReaderClient docReader) {
        this.docReader = docReader;
    }

    /** 引擎名常量（Go engines.go 的常量组）。 */
    public static final String BUILTIN = "builtin";
    public static final String SIMPLE = "simple";
    public static final String ANYDOC = "anydoc";
    public static final String WEKNORACLOUD = "weknoracloud";
    public static final String MINERU = "mineru";
    public static final String MINERU_CLOUD = "mineru_cloud";
    public static final String PADDLEOCR_VL = "paddleocr_vl";
    public static final String PADDLEOCR_VL_CLOUD = "paddleocr_vl_cloud";

    private static void engine(String name, String description, List<String> fileTypes,
                               boolean available, String reason,
                               List<SystemDtos.ParserEngineInfo> out) {
        out.add(new SystemDtos.ParserEngineInfo(name, description, fileTypes, available, reason));
    }

    /**
     * 对照 ListAllEngines：本地静态引擎 + 远端引擎合并。
     *
     * @param docreaderConnected 对照 DocumentReader.IsConnected()
     * @param overrides          对照 ParserEngineConfig.ToOverridesMap() + weknoracloud_app_id
     * @param remoteEngines      远端 ListEngines 的结果（未连接/失败时为 null）
     */
    public List<SystemDtos.ParserEngineInfo> listAllEngines(
            boolean docreaderConnected, Map<String, String> overrides,
            List<SystemDtos.ParserEngineInfo> remoteEngines) {
        Map<String, SystemDtos.ParserEngineInfo> remoteMap = new LinkedHashMap<>();
        if (remoteEngines != null) {
            for (SystemDtos.ParserEngineInfo re : remoteEngines) {
                remoteMap.put(re.name(), re);
            }
        }

        List<SystemDtos.ParserEngineInfo> result = new ArrayList<>();

        // builtin — DocReader（Python）解析套件；connected 才可用。
        // 远端同名引擎的 description/file_types 覆盖本地（golden 实测远端生效：
        // description="内置解析引擎"、21 个排序后的 file types）。
        engine(BUILTIN, "DocReader built-in parser engine",
                List.of("docx", "doc", "pdf", "md", "markdown", "xlsx", "xls",
                        "pptx", "ppt", "epub", "html", "htm", "mhtml", "xmind",
                        "jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp",
                        "mp3", "wav", "m4a", "flac", "ogg"),
                docreaderConnected,
                docreaderConnected ? "" : "DocReader service not connected",
                result);

        // simple — Go 原生文本/图片，恒可用。
        engine(SIMPLE, "Simple format & image parsing (no external service required)",
                List.of("md", "markdown", "txt", "csv", "json",
                        "jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp",
                        "mp3", "wav", "m4a", "flac", "ogg"),
                true, "", result);

        // anydoc — Go 编译期绑定；未打 tag 构建 → 恒 false（Java 无对应物，恒走此分支）。
        // FileTypes = Go anydoc.SupportedFileTypes()（golden 实录的展示序）。
        engine(ANYDOC, "anydoc in-process office document converter (no external service required)",
                List.of("csv", "doc", "docm", "docx", "epub", "odp", "ods", "odt", "pdf",
                        "ppt", "pptm", "pptx", "rtf", "xls", "xlsm", "xlsx"),
                false,
                "anydoc engine not built into this binary (rebuild with `make build-anydoc` / `-tags anydoc`; Docker images need `--build-arg WITH_ANYDOC=1`)",
                result);

        // weknoracloud — 租户凭据（weknoracloud_app_id override）。
        boolean cloud = overrides != null && !overrides.getOrDefault("weknoracloud_app_id", "").isEmpty();
        engine(WEKNORACLOUD, "WeKnoraCloud document reader",
                List.of("docx", "doc", "pdf", "md", "markdown", "xlsx", "xls", "pptx", "ppt"),
                cloud,
                cloud ? "" : "WeKnora Cloud credentials not configured. Go to Settings → WeKnora Cloud to set up.",
                result);

        // mineru / mineru_cloud / paddleocr_vl / paddleocr_vl_cloud — override 未配置即不可用
        // （Ping 分支需真实网络，配置后才触达——与 Go 相同的短路顺序）。
        String mineruEndpoint = overrides == null ? "" : overrides.getOrDefault("mineru_endpoint", "").trim();
        engine(MINERU, "MinerU self-hosted service",
                List.of("pdf", "jpg", "jpeg", "png", "bmp", "tiff", "doc", "docx", "ppt", "pptx"),
                !mineruEndpoint.isEmpty(),
                mineruEndpoint.isEmpty() ? "MinerU service not configured" : "",
                result);

        String mineruKey = overrides == null ? "" : overrides.getOrDefault("mineru_api_key", "").trim();
        engine(MINERU_CLOUD, "MinerU Cloud API",
                List.of("pdf", "jpg", "jpeg", "png", "bmp", "tiff", "doc", "docx", "ppt", "pptx"),
                !mineruKey.isEmpty(),
                mineruKey.isEmpty() ? "MinerU API Key not configured" : "",
                result);

        String paddleEndpoint = overrides == null ? "" : overrides.getOrDefault("paddleocr_vl_endpoint", "").trim();
        engine(PADDLEOCR_VL, "PaddleOCR-VL self-hosted service",
                List.of("pdf", "jpg", "jpeg", "png", "bmp", "tiff"),
                !paddleEndpoint.isEmpty(),
                paddleEndpoint.isEmpty() ? "PaddleOCR-VL service not configured" : "",
                result);

        String paddleToken = overrides == null ? "" : overrides.getOrDefault("paddleocr_vl_cloud_token", "").trim();
        engine(PADDLEOCR_VL_CLOUD, "PaddleOCR-VL Cloud API",
                List.of("pdf", "jpg", "jpeg", "png", "bmp", "tiff"),
                !paddleToken.isEmpty(),
                paddleToken.isEmpty() ? "PaddleOCR-VL Cloud Token not configured" : "",
                result);

        // 远端覆盖：同名 → FileTypes（非空）/Description（非空）覆盖本地值。
        for (int i = 0; i < result.size(); i++) {
            SystemDtos.ParserEngineInfo local = result.get(i);
            SystemDtos.ParserEngineInfo re = remoteMap.get(local.name());
            if (re == null) {
                continue;
            }
            List<String> fileTypes = !re.fileTypes().isEmpty() ? re.fileTypes() : local.fileTypes();
            String description = !re.description().isEmpty() ? re.description() : local.description();
            result.set(i, new SystemDtos.ParserEngineInfo(local.name(), description, fileTypes,
                    local.available(), local.unavailableReason()));
        }
        // 远端独有引擎追加（自动发现）。
        for (SystemDtos.ParserEngineInfo re : remoteMap.values()) {
            boolean seen = result.stream().anyMatch(e -> e.name().equals(re.name()));
            if (!seen) {
                result.add(re);
            }
        }
        return result;
    }

    /**
     * 对照 fetchRemoteEngines：未连接 → null（回落本地静态注册表）；
     * RPC 失败 → 记 WARN + null（Go 逐分支一致）。
     */
    public List<SystemDtos.ParserEngineInfo> fetchRemoteEngines(
            boolean connected, Map<String, String> overrides) {
        if (!connected) {
            return null;
        }
        try {
            List<DocReaderClient.RemoteEngine> engines = docReader.listEngines(
                    overrides == null ? Map.of() : overrides);
            if (engines == null) {
                return null;
            }
            List<SystemDtos.ParserEngineInfo> mapped = new ArrayList<>(engines.size());
            for (DocReaderClient.RemoteEngine e : engines) {
                mapped.add(new SystemDtos.ParserEngineInfo(e.name(), e.description(),
                        e.fileTypes() == null ? List.of() : e.fileTypes(),
                        e.available(), e.unavailableReason() == null ? "" : e.unavailableReason()));
            }
            return mapped;
        } catch (RuntimeException e) {
            log.warn("Failed to fetch remote engines from docreader: {}", e.toString());
            return null;
        }
    }

}
