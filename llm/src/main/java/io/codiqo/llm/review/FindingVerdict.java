package io.codiqo.llm.review;

import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FindingVerdict {
    private String tool;
    private String rule;
    private String file;
    private int line;
    private Verdict verdict;
    private Severity severity;
    private LlmScoringResponse.BugType type;
    private String reason;
    private String suggestedFix;

    public enum Verdict {
        DEFECT,
        HARMLESS,
        FALSE_POSITIVE
    }

    public enum Severity {
        BLOCKING,
        MAJOR,
        MINOR
    }
}
