package com.ragagent.agentm.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.ragagent.agent.skills.Loader;
import com.ragagent.agent.skills.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指令型技能目录（技能降级选项 B 的选择器数据源）。
 *
 * <p>沙箱安装管线退役后，技能 = 宿主 skillDirs 里的 SKILL.md 目录（Loader 扫描），
 * agent 凭注入的指令用现有工具执行。目录由 {@code weknora.skills.host-dirs}
 * （逗号分隔，env {@code WEKNORA_SKILL_HOST_DIRS}）配置；未配置时
 * {@code skills_available=false}，前端选择器隐藏。</p>
 */
@RestController
public class SkillsCatalogController {

    private static final Logger log = LoggerFactory.getLogger(SkillsCatalogController.class);

    private final List<String> hostSkillDirs;

    public SkillsCatalogController(
            @Value("${weknora.skills.host-dirs:}") String hostDirs) {
        List<String> dirs = new ArrayList<>();
        if (hostDirs != null && !hostDirs.isBlank()) {
            for (String dir : hostDirs.split(",")) {
                String clean = dir == null ? "" : dir.strip();
                if (!clean.isEmpty()) {
                    dirs.add(clean);
                }
            }
        }
        this.hostSkillDirs = List.copyOf(dirs);
    }

    /** 响应条目（与裁剪前 GET /api/v1/skills 的 {name, description} 同形）。 */
    public record SkillInfoResponse(String name, String description) {
    }

    /**
     * 前端技能选择器的数据源。配置了宿主目录即 available；发现失败按空集降级
     * （目录打错字不应让设置页 500）。
     */
    @GetMapping("/api/v1/skills")
    public ResponseEntity<Map<String, Object>> listSkills() {
        Map<String, Object> body = new TreeMap<>();
        if (hostSkillDirs.isEmpty()) {
            body.put("data", List.of());
            body.put("skills_available", false);
            body.put("success", true);
            return ResponseEntity.ok(body);
        }
        List<SkillInfoResponse> response = new ArrayList<>();
        try {
            for (Skill.SkillMetadata meta : new Loader(hostSkillDirs).reload()) {
                if (meta != null) {
                    response.add(new SkillInfoResponse(meta.name(), meta.description()));
                }
            }
        } catch (Exception e) {
            log.warn("[skills] scan host skill dirs {}: {}", hostSkillDirs, e.getMessage());
        }
        body.put("data", response);
        body.put("skills_available", true);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }
}
