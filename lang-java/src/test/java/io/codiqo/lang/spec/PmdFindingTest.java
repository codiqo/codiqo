package io.codiqo.lang.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.client.model.DiagnosticModel;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.RuleViolation;

class PmdFindingTest {
    @Test
    void readsTheViolationAsAFinding() {
        PmdFinding finding = new PmdFinding(violation(RulePriority.HIGH, "https://pmd.github.io/rule"));

        assertEquals("pmd", finding.getTool());
        assertEquals("UnusedLocalVariable", finding.getRuleId());
        assertEquals("Avoid unused local variables", finding.getMessage());
        assertEquals("Best Practices", finding.getCategory());
        assertEquals(1, finding.getRank());
        assertEquals(SourceLocation.builder().startLine(3).startColumn(9).endLine(3).endColumn(20).build(), finding.getLocation());
    }
    @Test
    void thePriorityDecidesTheSeverity() {
        assertEquals(Finding.Severity.ERROR, new PmdFinding(violation(RulePriority.HIGH, null)).getSeverity());
        assertEquals(Finding.Severity.WARNING, new PmdFinding(violation(RulePriority.MEDIUM, null)).getSeverity());
        assertEquals(Finding.Severity.INFO, new PmdFinding(violation(RulePriority.LOW, null)).getSeverity());
    }
    @Test
    void describesTheRuleSetPriorityAndDocumentation() {
        DiagnosticModel diagnostic = new DiagnosticModel();
        new PmdFinding(violation(RulePriority.MEDIUM, "https://pmd.github.io/rule")).describe(diagnostic);

        assertEquals("Best Practices", diagnostic.getPmdInfo().getRuleSet());
        assertEquals(3, diagnostic.getPmdInfo().getPriority());
        assertEquals(URI.create("https://pmd.github.io/rule"), diagnostic.getPmdInfo().getExternalInfoUrl());
    }
    @Test
    void aRuleWithoutDocumentationLinksNone() {
        DiagnosticModel diagnostic = new DiagnosticModel();
        new PmdFinding(violation(RulePriority.MEDIUM, "")).describe(diagnostic);

        assertNull(diagnostic.getPmdInfo().getExternalInfoUrl());
    }
    @Test
    void equalOnlyForTheSameViolation() {
        RuleViolation violation = violation(RulePriority.MEDIUM, null);

        assertEquals(new PmdFinding(violation), new PmdFinding(violation));
        assertNotEquals(new PmdFinding(violation), new PmdFinding(violation(RulePriority.MEDIUM, null)));
        assertEquals(List.of(new PmdFinding(violation)), List.of(new PmdFinding(violation)));
    }
    private static RuleViolation violation(RulePriority priority, String externalInfoUrl) {
        Rule rule = mock(Rule.class);
        when(rule.getName()).thenReturn("UnusedLocalVariable");
        when(rule.getRuleSetName()).thenReturn("Best Practices");
        when(rule.getPriority()).thenReturn(priority);
        when(rule.getExternalInfoUrl()).thenReturn(externalInfoUrl);

        RuleViolation toReturn = mock(RuleViolation.class);
        when(toReturn.getRule()).thenReturn(rule);
        when(toReturn.getDescription()).thenReturn("Avoid unused local variables");
        when(toReturn.getBeginLine()).thenReturn(3);
        when(toReturn.getBeginColumn()).thenReturn(9);
        when(toReturn.getEndLine()).thenReturn(3);
        when(toReturn.getEndColumn()).thenReturn(20);
        return toReturn;
    }
}
