package com.ragagent.sandbox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ragagent.common.error.BizException;
import com.ragagent.sandbox.service.SkillBundleParser;
import com.ragagent.sandbox.service.TenantSkillService;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go {@code respondSkillServiceError}（sandbox_skill.go L174-193）的
 * sentinel → 400 分类表：按<b>类型</b>匹配而非消息——改写措辞的校验错误不能悄悄
 * 开始对坏输入返回 500（Go 注释原文）。其余错误原样上抛。
 */
class SandboxSkillErrorClassificationTest {

    @Test
    void bundleInvalidSentinelPromotesTo400() {
        SkillBundleParser.BundleInvalidException err =
                new SkillBundleParser.BundleInvalidException(
                        "not a readable zip archive: zip: not a valid zip file");
        BizException out = assertThrows(BizException.class,
                () -> SandboxSkillController.respondSkillServiceError(err));
        assertEquals(1000, out.appError().code());
        assertEquals("skill bundle is invalid: not a readable zip archive: "
                + "zip: not a valid zip file", out.appError().message());
    }

    @Test
    void sourceInvalidSentinelPromotesTo400() {
        TenantSkillService.SkillSourceInvalidException err =
                new TenantSkillService.SkillSourceInvalidException("skill source is invalid: x");
        BizException out = assertThrows(BizException.class,
                () -> SandboxSkillController.respondSkillServiceError(err));
        assertEquals(1000, out.appError().code());
    }

    @Test
    void appErrorsPassThroughUnchanged() {
        // 404/409 等已成型 AppError 原样上抛（stop 的 400、resolveSkill 的 404 都走这里）
        BizException notFound = BizException.notFound("skill not found");
        BizException out = assertThrows(BizException.class,
                () -> SandboxSkillController.respondSkillServiceError(notFound));
        assertSame(notFound, out);
        assertEquals(1003, out.appError().code());

        BizException bad = BizException.badRequest("skill is not installing");
        assertSame(bad, assertThrows(BizException.class,
                () -> SandboxSkillController.respondSkillServiceError(bad)));
    }

    @Test
    void pipelineErrorsPassThroughForPlain500() {
        // 管线/仓储错误原样上抛 → controller-local plain-500 分支（无 details 键）
        IllegalStateException pipeline = new IllegalStateException("store bundle: no space");
        IllegalStateException out = assertThrows(IllegalStateException.class,
                () -> SandboxSkillController.respondSkillServiceError(pipeline));
        assertSame(pipeline, out);
    }
}
