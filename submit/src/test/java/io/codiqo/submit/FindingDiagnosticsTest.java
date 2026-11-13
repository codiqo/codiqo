package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.client.model.DiagnosticModel;

class FindingDiagnosticsTest {
    @Test
    void aFindingBecomesADiagnosticWithItsColumns() {
        SourceLocation location = SourceLocation.builder().startLine(3).startColumn(5).endLine(4).endColumn(9).build();

        DiagnosticModel diagnostic = FindingDiagnostics.diagnostic(
                new StubFinding("pmd", "UnusedLocalVariable", Finding.Severity.ERROR, location, 1), "core/Service.java");

        assertEquals(DiagnosticModel.ToolEnum.PMD, diagnostic.getTool());
        assertEquals("UnusedLocalVariable", diagnostic.getRuleId());
        assertEquals("UnusedLocalVariable found", diagnostic.getMessage());
        assertEquals("category of UnusedLocalVariable", diagnostic.getCategory());
        assertEquals(DiagnosticModel.SeverityEnum.ERROR, diagnostic.getSeverity());
        assertEquals("core/Service.java", diagnostic.getFilePath());
        assertEquals(List.of(3, 5, 4, 9), List.of(
                diagnostic.getLocation().getStartLine(),
                diagnostic.getLocation().getStartColumn(),
                diagnostic.getLocation().getEndLine(),
                diagnostic.getLocation().getEndColumn()));
        assertNull(diagnostic.getPmdInfo(), "what only its tool reports is the finding's to add");
    }
    @Test
    void aColumnTheToolDidNotReportStaysAbsent() {
        DiagnosticModel diagnostic = FindingDiagnostics.diagnostic(
                new StubFinding("spotbugs", "NP_NULL_ON_SOME_PATH", Finding.Severity.WARNING, StubFinding.lines(7, 8), 5), "core/Service.java");

        assertEquals(DiagnosticModel.SeverityEnum.WARNING, diagnostic.getSeverity());
        assertEquals(7, diagnostic.getLocation().getStartLine());
        assertEquals(8, diagnostic.getLocation().getEndLine());
        assertNull(diagnostic.getLocation().getStartColumn());
        assertNull(diagnostic.getLocation().getEndColumn());
    }
    @Test
    void countsOneToolsFindings() {
        List<Finding> findings = List.of(
                new StubFinding("pmd", "A", Finding.Severity.INFO, StubFinding.lines(1, 1), 3),
                new StubFinding("spotbugs", "B", Finding.Severity.INFO, StubFinding.lines(1, 1), 15),
                new StubFinding("pmd", "C", Finding.Severity.INFO, StubFinding.lines(1, 1), 3));

        assertEquals(2, FindingDiagnostics.count(findings, DiagnosticModel.ToolEnum.PMD));
        assertEquals(1, FindingDiagnostics.count(findings, DiagnosticModel.ToolEnum.SPOTBUGS));
        assertEquals(0, FindingDiagnostics.count(findings, DiagnosticModel.ToolEnum.ERRORPRONE));
    }
}
