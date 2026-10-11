package io.codiqo.llm.review;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.Lists;

import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The labelling turn's answer: a category per unit id. */
@Data
@NoArgsConstructor
public class UnitLabels {
    private List<UnitLabel> labels = Lists.newArrayList();

    @JsonIgnore
    private SessionUsage usage;

    @JsonIgnore
    private String answer;

    @Data
    @NoArgsConstructor
    public static class UnitLabel {
        private String id;
        private LlmScoringResponse.CodeBlockCategory category;
        private String reason;
    }
}
