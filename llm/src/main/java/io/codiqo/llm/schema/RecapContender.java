package io.codiqo.llm.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * one ranked contributor as the award writer sees them. every figure here was computed by the caller and is
 * already on screen in the reel, so the model is asked to write around these numbers rather than produce any
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecapContender {
    private int rank;
    private String name;
    private String seniority;
    private String headlineLabel;
    private String headlineValue;
    private Integer commits;
    private Integer seniorGradeCommits;
    private Integer filesChanged;
    private Integer linesChanged;
    private Double avgComplexity;
    private Double avgCoverage;
    private String distinction;
}
