package com.ragagent.knowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Knowledge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「搬移中」防线的行为契约（{@link ChunkAccessGuard#rejectMovingKnowledge}）。
 *
 * <p><b>背景</b>：这条防线曾有两份实现——{@code ChunkAccessGuard} 的严格版与
 * {@code KnowledgeFolderService} 的宽松副本；两者规则相同但**严格度不同**：同一份形态异常的
 * metadata，走文件夹路由被**静默放行**、走编辑路由报 500。2026-09-30 统一到严格版并删除副本，
 * 本测试把行为钉死，防止再出现"异常态放行"的分支。
 *
 * <p><b>为什么值得专门测</b>：防线失效不会抛错、也不会有日志——搬移进行中的文档会被直接清空，
 * 而用户侧只看到一个"正常成功"的响应。
 */
class ChunkAccessGuardMoveGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Knowledge knowledgeWith(String metadataJson) {
        Knowledge k = new Knowledge();
        k.setMetadata(metadataJson == null ? null : read(metadataJson));
        return k;
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试用 JSON 不合法: " + json, e);
        }
    }

    @Test
    @DisplayName("文档为 null → 404（严格版行为，宽松副本此处会 NPE）")
    void nullKnowledgeIsNotFound() {
        assertThatThrownBy(() -> ChunkAccessGuard.rejectMovingKnowledge(null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(404);
                    assertThat(e.appError().message()).isEqualTo("knowledge not found");
                });
    }

    @Test
    @DisplayName("无 metadata / 无 transfer 键 / transfer 为 null / metadata 非对象 → 放行")
    void passesWhenNoTransferState() {
        for (String json : new String[] {null, "{}", "{\"_knowledge_transfer\":null}", "[]", "\"text\""}) {
            assertThatCode(() -> ChunkAccessGuard.rejectMovingKnowledge(knowledgeWith(json)))
                    .as("metadata=%s 应放行", json)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("transfer 形态异常（非对象）→ 500 —— 宽松副本曾静默放行")
    void malformedTransferIsRejected() {
        assertThatThrownBy(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":\"garbage\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("malformed knowledge transfer state");
        assertThatThrownBy(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":[1,2]}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("malformed knowledge transfer state");
    }

    @Test
    @DisplayName("operation / phase 非文本 → 500 —— 宽松副本曾静默放行")
    void nonTextualFieldsAreRejected() {
        assertThatThrownBy(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":{\"operation\":123,\"phase\":\"moving\"}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("malformed knowledge transfer state");
        assertThatThrownBy(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":123}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("malformed knowledge transfer state");
    }

    @Test
    @DisplayName("operation=move 且 phase=moving → 409（搬移未完成，先重试搬移）")
    void movingIsConflict() {
        assertThatThrownBy(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"moving\"}}")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(409);
                    assertThat(e.appError().message())
                            .isEqualTo("knowledge has an unfinished move; retry the move first");
                });
    }

    @Test
    @DisplayName("operation=move 但 phase 已完成 / 其它 operation → 放行")
    void passesWhenMoveFinished() {
        assertThatCode(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"done\"}}")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":{\"operation\":\"copy\",\"phase\":\"moving\"}}")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChunkAccessGuard.rejectMovingKnowledge(
                knowledgeWith("{\"_knowledge_transfer\":{}}")))
                .doesNotThrowAnyException();
    }
}
