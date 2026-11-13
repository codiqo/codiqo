package io.codiqo.lang.spec;

import java.util.List;
import java.util.Objects;

import com.google.common.collect.Lists;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugRankCategory;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.SpotbugsPropertiesModel;
import lombok.Value;

/**
 * A SpotBugs bug as a finding. SpotBugs compares its bugs by what they report, so two of these are equal when their
 * bugs are, as the hotspot report has always counted them.
 */
@Value
public class SpotBugsFinding implements Finding {
    BugInstance bug;

    @Override
    public String getTool() {
        return DiagnosticModel.ToolEnum.SPOTBUGS.getValue();
    }
    @Override
    public String getRuleId() {
        return bug.getBugPattern().getType();
    }
    @Override
    public String getMessage() {
        return bug.getMessage();
    }
    @Override
    public String getCategory() {
        return bug.getBugPattern().getCategory();
    }
    @Override
    public Severity getSeverity() {
        return switch (BugRankCategory.getRank(getRank())) {
            case SCARIEST -> Severity.ERROR;
            case SCARY -> Severity.WARNING;
            case TROUBLING, OF_CONCERN -> Severity.INFO;
        };
    }
    /**
     * The bug's primary source lines: SpotBugs reads bytecode, which keeps no columns. SpotBugs always has them: it
     * falls back from the bug's own lines to its method's, field's and class's lines, and throws when the bug has none
     * of those, so a line it could not read arrives as its unknown line, -1.
     */
    @Override
    public SourceLocation getLocation() {
        SourceLineAnnotation line = bug.getPrimarySourceLineAnnotation();
        return SourceLocation.builder()
                .startLine(line.getStartLine())
                .startColumn(SourceLocation.NO_COLUMN)
                .endLine(line.getEndLine())
                .endColumn(SourceLocation.NO_COLUMN)
                .build();
    }
    /** the bug's rank, 1 (scariest) to 20 */
    @Override
    public int getRank() {
        return bug.getBugRank();
    }
    @Override
    public void describe(DiagnosticModel diagnostic) {
        SpotbugsPropertiesModel spotbugsInfo = new SpotbugsPropertiesModel();
        spotbugsInfo.setBugCategory(bug.getBugPattern().getCategory());
        spotbugsInfo.setBugRank(getRank());
        spotbugsInfo.setConfidence(confidence(bug.getPriority()));
        MethodAnnotation primaryMethod = bug.getPrimaryMethod();
        if (Objects.nonNull(primaryMethod)) {
            spotbugsInfo.setMethodSignature(primaryMethod.getFullMethod(bug.getPrimaryClass()));
        }
        diagnostic.setSpotbugsInfo(spotbugsInfo);

        int cweid = bug.getBugPattern().getCWEid();
        if (cweid > 0) {
            diagnostic.setCwe(Lists.newArrayList(List.of("CWE-" + cweid)));
        }
    }
    private static SpotbugsPropertiesModel.ConfidenceEnum confidence(int priority) {
        if (priority == Priorities.HIGH_PRIORITY) {
            return SpotbugsPropertiesModel.ConfidenceEnum.HIGH;
        } else if (priority == Priorities.NORMAL_PRIORITY) {
            return SpotbugsPropertiesModel.ConfidenceEnum.MEDIUM;
        } else {
            return SpotbugsPropertiesModel.ConfidenceEnum.LOW;
        }
    }
}
