package io.codiqo.llm.schema;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    /** busiest first */
    @Builder.Default
    private List<String> projects = new ArrayList<>();
    /** the analysis summary of their hardest commit in the window */
    private String bestWork;
}
