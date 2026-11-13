package io.codiqo.lang.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugPattern;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.SpotbugsPropertiesModel;

class SpotBugsFindingTest {
    @Test
    void readsTheBugAsAFindingOnLinesAlone() {
        SpotBugsFinding finding = new SpotBugsFinding(bug(3, Priorities.HIGH_PRIORITY, 476));

        assertEquals("spotbugs", finding.getTool());
        assertEquals("NP_NULL_ON_SOME_PATH", finding.getRuleId());
        assertEquals("Possible null pointer dereference", finding.getMessage());
        assertEquals("CORRECTNESS", finding.getCategory());
        assertEquals(3, finding.getRank());
        assertEquals(SourceLocation.builder()
                .startLine(12)
                .startColumn(SourceLocation.NO_COLUMN)
                .endLine(14)
                .endColumn(SourceLocation.NO_COLUMN)
                .build(), finding.getLocation());
    }
    @Test
    void theRankDecidesTheSeverity() {
        assertEquals(Finding.Severity.ERROR, new SpotBugsFinding(bug(1, Priorities.HIGH_PRIORITY, 0)).getSeverity());
        assertEquals(Finding.Severity.WARNING, new SpotBugsFinding(bug(5, Priorities.HIGH_PRIORITY, 0)).getSeverity());
        assertEquals(Finding.Severity.INFO, new SpotBugsFinding(bug(10, Priorities.HIGH_PRIORITY, 0)).getSeverity());
        assertEquals(Finding.Severity.INFO, new SpotBugsFinding(bug(20, Priorities.HIGH_PRIORITY, 0)).getSeverity());
    }
    @Test
    void describesTheRankConfidenceAndWeakness() {
        DiagnosticModel diagnostic = new DiagnosticModel();
        new SpotBugsFinding(bug(3, Priorities.NORMAL_PRIORITY, 476)).describe(diagnostic);

        assertEquals("CORRECTNESS", diagnostic.getSpotbugsInfo().getBugCategory());
        assertEquals(3, diagnostic.getSpotbugsInfo().getBugRank());
        assertEquals(SpotbugsPropertiesModel.ConfidenceEnum.MEDIUM, diagnostic.getSpotbugsInfo().getConfidence());
        assertNull(diagnostic.getSpotbugsInfo().getMethodSignature());
        assertEquals(List.of("CWE-476"), diagnostic.getCwe());
    }
    @Test
    void aBugWithoutAWeaknessNamesNone() {
        DiagnosticModel diagnostic = new DiagnosticModel();
        new SpotBugsFinding(bug(3, Priorities.LOW_PRIORITY, 0)).describe(diagnostic);

        assertEquals(SpotbugsPropertiesModel.ConfidenceEnum.LOW, diagnostic.getSpotbugsInfo().getConfidence());
        assertTrue(diagnostic.getCwe().isEmpty());
    }
    private static BugInstance bug(int rank, int priority, int cwe) {
        BugPattern pattern = mock(BugPattern.class);
        when(pattern.getType()).thenReturn("NP_NULL_ON_SOME_PATH");
        when(pattern.getCategory()).thenReturn("CORRECTNESS");
        when(pattern.getCWEid()).thenReturn(cwe);

        SourceLineAnnotation line = mock(SourceLineAnnotation.class);
        when(line.getStartLine()).thenReturn(12);
        when(line.getEndLine()).thenReturn(14);

        BugInstance toReturn = mock(BugInstance.class);
        when(toReturn.getBugPattern()).thenReturn(pattern);
        when(toReturn.getMessage()).thenReturn("Possible null pointer dereference");
        when(toReturn.getBugRank()).thenReturn(rank);
        when(toReturn.getPriority()).thenReturn(priority);
        when(toReturn.getPrimarySourceLineAnnotation()).thenReturn(line);
        return toReturn;
    }
}
