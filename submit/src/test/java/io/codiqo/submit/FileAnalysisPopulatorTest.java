package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.codiqo.api.code.CompilerFinding;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.SymbolKindModel;

class FileAnalysisPopulatorTest {
    /** lines 3 and 4 of the new file are added, line 6 is context */
    private static final String DIFF = """
            --- a/core/Service.java
            +++ b/core/Service.java
            @@ -1,5 +1,7 @@
             class Service {
                 void run() {
            +        String.format("total", count, limit);
            +        Runnable later = () -> System.out.printf("done", count);
                     work();
                     int unused = 0;
                 }
            """;

    @Test
    void mapsEnumConstantCallerToEnumMember() {
        assertEquals(SymbolKindModel.ENUM_MEMBER, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.EnumMember));
    }
    @Test
    void mapsExecutableKindsToTheirSchemaCounterparts() {
        assertEquals(SymbolKindModel.METHOD, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Method));
        assertEquals(SymbolKindModel.CONSTRUCTOR, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Constructor));
        assertEquals(SymbolKindModel.FIELD, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Field));
        assertEquals(SymbolKindModel.propertyClass, FileAnalysisPopulator.resolveSymbolKind(SymbolKind.Class));
    }
    @ParameterizedTest
    @EnumSource(SymbolKind.class)
    void mapsEveryLanguageServerKind(SymbolKind kind) {
        assertNotNull(FileAnalysisPopulator.resolveSymbolKind(kind), "unmapped language server kind: " + kind);
    }
    /**
     * A compiler finding on an added line goes to the innermost added or changed unit holding it, as a warning of its
     * tool; one on a line the commit did not write, and one outside every unit, are not the commit's.
     */
    @Test
    void attachesCompilerFindingsOnAddedLinesToTheInnermostUnit() {
        CodeUnitModel run = unit(CodeUnitModel.OperationEnum.MODIFY, 2, 7);
        CodeUnitModel lambda = unit(CodeUnitModel.OperationEnum.NEW, 4, 4);
        CodeUnitModel removed = unit(CodeUnitModel.OperationEnum.DELETE, 3, 3);
        FileChangeModel file = new FileChangeModel().path("core/Service.java").diff(DIFF).codeUnits(List.of(removed, run, lambda));

        FileAnalysisPopulator.attachCompilerFindings(file, List.of(
                finding("FormatString", 3),
                finding("FormatString", 4),
                finding("UnusedVariable", 6),
                finding("MissingOverride", 9)));

        assertEquals(List.of("FormatString@3"), describe(run));
        assertEquals(List.of("FormatString@4"), describe(lambda));
        assertTrue(removed.getDiagnostics().isEmpty(), "a deleted unit has no new lines to hold a finding");

        DiagnosticModel diagnostic = run.getDiagnostics().getFirst();
        assertEquals(DiagnosticModel.ToolEnum.ERRORPRONE, diagnostic.getTool());
        assertEquals(DiagnosticModel.SeverityEnum.WARNING, diagnostic.getSeverity());
        assertEquals("core/Service.java", diagnostic.getFilePath());
        assertEquals("flagged", diagnostic.getMessage());
    }
    private static CodeUnitModel unit(CodeUnitModel.OperationEnum operation, int start, int end) {
        return new CodeUnitModel().operation(operation).location(new LocationModel().startLine(start).endLine(end));
    }
    private static CompilerFinding finding(String rule, int line) {
        return new CompilerFinding("errorprone", rule, "core/Service.java", line, "flagged");
    }
    private static List<String> describe(CodeUnitModel unit) {
        return unit.getDiagnostics().stream().map(diagnostic -> diagnostic.getRuleId() + "@" + diagnostic.getLocation().getStartLine()).toList();
    }
}
