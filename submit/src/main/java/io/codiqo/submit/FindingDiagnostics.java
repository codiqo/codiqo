package io.codiqo.submit;

import java.util.Collection;

import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.LocationModel;
import lombok.experimental.UtilityClass;

/** A static-analysis finding as the submission's diagnostic, and how many of a unit's findings one tool reported. */
@UtilityClass
public class FindingDiagnostics {
    /**
     * What every finding's diagnostic has, whatever its tool. A unit's diagnostic adds what only its tool reports
     * ({@link Finding#describe}); a module's critical violation lists the finding without it.
     */
    public static DiagnosticModel diagnostic(Finding finding, String filePath) {
        DiagnosticModel toReturn = new DiagnosticModel();
        toReturn.setTool(DiagnosticModel.ToolEnum.fromValue(finding.getTool()));
        toReturn.setRuleId(finding.getRuleId());
        toReturn.setMessage(finding.getMessage());
        toReturn.setCategory(finding.getCategory());
        toReturn.setFilePath(filePath);
        toReturn.setSeverity(severity(finding.getSeverity()));
        toReturn.setLocation(location(finding.getLocation()));
        return toReturn;
    }
    public static int count(Collection<Finding> findings, DiagnosticModel.ToolEnum tool) {
        return (int) findings.stream().filter(finding -> tool.getValue().equals(finding.getTool())).count();
    }
    /** a column the tool did not report stays absent, rather than reading as column zero */
    private static LocationModel location(SourceLocation location) {
        LocationModel toReturn = new LocationModel();
        toReturn.setStartLine(location.getStartLine());
        toReturn.setEndLine(location.getEndLine());
        if (location.getStartColumn() > SourceLocation.NO_COLUMN) {
            toReturn.setStartColumn(location.getStartColumn());
        }
        if (location.getEndColumn() > SourceLocation.NO_COLUMN) {
            toReturn.setEndColumn(location.getEndColumn());
        }
        return toReturn;
    }
    private static DiagnosticModel.SeverityEnum severity(Finding.Severity severity) {
        return switch (severity) {
            case ERROR -> DiagnosticModel.SeverityEnum.ERROR;
            case WARNING -> DiagnosticModel.SeverityEnum.WARNING;
            case INFO -> DiagnosticModel.SeverityEnum.INFO;
        };
    }
}
