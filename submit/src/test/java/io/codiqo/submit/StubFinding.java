package io.codiqo.submit;

import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.client.model.DiagnosticModel;
import lombok.Value;

/** a finding of any tool, which adds nothing of its own to its diagnostic */
@Value
public class StubFinding implements Finding {
    String tool;
    String ruleId;
    Severity severity;
    SourceLocation location;
    int rank;

    public static SourceLocation lines(int startLine, int endLine) {
        return SourceLocation.builder()
                .startLine(startLine)
                .startColumn(SourceLocation.NO_COLUMN)
                .endLine(endLine)
                .endColumn(SourceLocation.NO_COLUMN)
                .build();
    }
    @Override
    public String getMessage() {
        return ruleId + " found";
    }
    @Override
    public String getCategory() {
        return "category of " + ruleId;
    }
    @Override
    public void describe(DiagnosticModel diagnostic) {}
}
