package com.ragagent.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ragagent.auth.domain.APIPrincipalConfig;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.memory.domain.MemoryExtractionState;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.vectorstore.domain.ConnectionConfig;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.wiki.domain.WikiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「我方载体面」的键名守卫（B16「静默失效」扫描产出）。
 *
 * <p><b>为什么需要它</b>：本仓已出过两次同源缺陷——知识库配置 jsonb 三方键名分裂（B3b）、存储行配置
 * 两套词汇（B14/B15）。共同形态都是「写的人按一套键名、读的人按另一套，而反序列化又忽略未知键 ⇒
 * 数据被<b>静默丢弃</b>、不报错」。这条契约（§2 第 4/11 条：JSON 字段名 = Java 字段名）此前只由
 * 人工评审与"恰好有契约测试的那几个面"守着，覆盖不全。</p>
 *
 * <p><b>本测试做什么</b>：对下列<b>登记面</b>做静态（反射）检查——</p>
 * <ul>
 *   <li>{@code @JsonProperty} 的键名必须等于字段名（出现不同名＝有人又开了第二套词汇）；</li>
 *   <li>不许 {@code @JsonAlias}（§2 第 11 条禁止兼容别名——防旧键回流）；</li>
 *   <li>不许类级 {@code @JsonNaming} 下划线策略（§2 第 4 条明令禁止）。</li>
 * </ul>
 *
 * <p><b>登记范围</b>：已换锚到 camel 的<b>我方</b>载体面（DB jsonb / 内部载荷）。冻结面（第三方线格式、
 * 事件载荷、LLM 载荷、{@code auth/domain/tenantconfig} 租户配置 jsonb 等，见 HANDOFF §15.3）
 * 不在内——它们<b>有意</b>保留各自的键名。</p>
 *
 * <p><b>已知未登记（属换锚欠账，不在此钉）</b>：{@code agent/AgentConfig}（14 处 snake，逐字段注解）、
 * {@code websearch/controller} 的请求 DTO（{@code is_default} 等）、{@code wiki} page 面 ingest 载荷、
 * {@code datasource/domain} 的 {@code SyncCursor}/{@code SyncResult} 等。它们换锚完成后应<b>加进本表</b>。</p>
 */
class JsonFaceVocabularyTest {

    private static final List<Class<?>> OUR_FACES = List.of(
            WebSearchProviderParams.class,
            ConnectionConfig.class,
            IndexConfig.class,
            McpAuthConfig.class,
            APIPrincipalConfig.class,
            ModelParameters.class,
            WikiConfig.class,
            MemoryExtractionState.class,
            DataSourceConfig.class);

    @Test
    @DisplayName("登记面：@JsonProperty 键名必须等于 Java 字段名（禁第二套词汇）")
    void jsonKeysMustEqualJavaFieldNames() {
        for (Class<?> type : OUR_FACES) {
            forEachMemberField(type, (owner, name, annotation) -> {
                if (annotation != null && !annotation.value().isEmpty()) {
                    assertThat(annotation.value())
                            .as("%s.%s 的 JSON 键名与字段名不一致——违反 §2 第 4/11 条，且会造成静默丢数据",
                                    owner.getSimpleName(), name)
                            .isEqualTo(name);
                }
            });
        }
    }

    @Test
    @DisplayName("登记面：禁止 @JsonAlias（防旧键回流）")
    void aliasesAreForbidden() {
        for (Class<?> type : OUR_FACES) {
            forEachMemberField(type, (owner, name, ignored) -> assertThat(findAlias(owner, name))
                    .as("%s.%s：§2 第 11 条禁止兼容别名（旧键回流＝同一份数据又有第二条路径）",
                            owner.getSimpleName(), name)
                    .isNull());
        }
    }

    @Test
    @DisplayName("登记面：禁止类级下划线命名策略")
    void snakeCaseNamingStrategyIsForbidden() {
        for (Class<?> type : OUR_FACES) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                assertThat(c.getAnnotation(JsonNaming.class))
                        .as("%s：§2 第 4 条禁止 @JsonNaming 下划线转换", c.getSimpleName())
                        .isNull();
            }
        }
    }

    // ── 反射工具（含 record 组件：record 的注解落在组件上，字段扫描会漏） ──────────

    private interface FieldVisitor {
        void visit(Class<?> owner, String name, JsonProperty annotation);
    }

    private static void forEachMemberField(Class<?> type, FieldVisitor visitor) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                visitor.visit(c, field.getName(), field.getAnnotation(JsonProperty.class));
            }
            if (c.isRecord()) {
                for (RecordComponent component : c.getRecordComponents()) {
                    visitor.visit(c, component.getName(), component.getAnnotation(JsonProperty.class));
                }
            }
        }
    }

    private static JsonAlias findAlias(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            return field.getAnnotation(JsonAlias.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
