package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OutputBudgets.splitBudgetFairly 的 Go 实录（10 组，探针原样调用 Go
 * max-min 公平分配）+ OutputBudget 的 ctx 缺省语义（3 条，Java 侧对应
 * {@link ToolRequest#outputBudget()}）。
 */
class OutputBudgetsRecordingTest {

    private static final String[] CASES = {
            "R_BUDGET_FITS",
            "R_BUDGET_DONATE",
            "R_BUDGET_MIXED",
            "R_BUDGET_NIL_SIZES",
            "R_BUDGET_ZERO_TOTAL",
            "R_BUDGET_NEG_TOTAL",
            "R_BUDGET_EXACT",
            "R_BUDGET_OVER_TOTAL",
            "R_BUDGET_SINGLE",
            "R_BUDGET_ZEROS_MIXED",
    };

    @Test
    void splitFairlyMatchesGoRecording() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            int total = r.get("total").asInt();
            List<Integer> sizes = new ArrayList<>();
            r.get("sizes").forEach(n -> sizes.add(n.asInt()));
            int[] sizesArr = sizes.stream().mapToInt(Integer::intValue).toArray();

            int[] got = OutputBudgets.splitBudgetFairly(total, sizesArr);

            List<Integer> want = new ArrayList<>();
            r.get("out").forEach(n -> want.add(n.asInt()));
            assertThat(toList(got))
                    .as("splitBudgetFairly %s", r.get("id").asText())
                    .containsExactlyElementsOf(want);
        }
    }

    @Test
    void outputBudgetDefaultsMatchGoCtxSemantics() {
        JsonNode def = RecordingSupport.rec(field("R_BUDGET_CTX_DEFAULT"));
        assertThat(ToolRequest.of(RecordingSupport.readTree("{}")).outputBudget())
                .isEqualTo(def.get("out").asInt());

        JsonNode unset = RecordingSupport.rec(field("R_BUDGET_CTX_UNSET_ZERO"));
        assertThat(new ToolRequest(RecordingSupport.readTree("{}"), null,
                ToolCancellation.LIVE, 0).outputBudget())
                .isEqualTo(unset.get("out").asInt());

        JsonNode set = RecordingSupport.rec(field("R_BUDGET_CTX_SET"));
        assertThat(new ToolRequest(RecordingSupport.readTree("{}"), null,
                ToolCancellation.LIVE, 1234).outputBudget())
                .isEqualTo(set.get("out").asInt());
    }

    private static List<Integer> toList(int[] arr) {
        List<Integer> out = new ArrayList<>(arr.length);
        for (int v : arr) {
            out.add(v);
        }
        return out;
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45A.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
