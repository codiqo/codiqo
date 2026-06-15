package io.codiqo.maven.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitModel.OperationEnum;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.SymbolKindModel;

class EffectiveChangePopulatorTest {
    private static final String JAVA_DIFF = String.join("\n",
            "diff --git a/A.java b/A.java",
            "--- a/A.java",
            "+++ b/A.java",
            "@@ -8,6 +8,9 @@",
            " class A {",
            "     void m() {",
            "-        old.call();",
            "+        first.do();",
            "+        // comment — does not count",
            "+",
            "+        second.do();",
            "     }",
            " }",
            "") + "\n";

    @Test
    void countsEffectiveAddedLinesWithinMethodRange() {
        CodeUnitModel unit = modifyMethod(10, 16, List.of());
        SubmissionContext ctx = contextWith(javaFile(JAVA_DIFF, unit));

        new EffectiveChangePopulator().accept(ctx);

        assertEquals(2, unit.getEffectiveLinesChanged());
    }
    @Test
    void invocationsChangedSumsAddedFromMetricsAndDeletedFromInvocationCounter() {
        CodeUnitModel unit = modifyMethod(10, 16, List.of(10, 11, 13));
        SubmissionContext ctx = contextWith(javaFile(JAVA_DIFF, unit));

        new EffectiveChangePopulator().accept(ctx);

        assertEquals(3, unit.getEffectiveInvocationsChanged());
    }
    @Test
    void countsDeletedInvocationsOnAnchoredLinesViaJavaInvocationCounter() {
        String diff = String.join("\n",
                "diff --git a/A.java b/A.java",
                "--- a/A.java",
                "+++ b/A.java",
                "@@ -10,4 +10,2 @@",
                " void m() {",
                "-    old.call(); other.do();",
                "-    third.fn();",
                " }",
                "") + "\n";
        CodeUnitModel unit = modifyMethod(10, 13, List.of());
        SubmissionContext ctx = contextWith(javaFile(diff, unit));

        new EffectiveChangePopulator().accept(ctx);

        assertEquals(3, unit.getEffectiveInvocationsChanged());
    }
    @Test
    void nonJavaFilesGetZeroDeletedInvocations() {
        String diff = String.join("\n",
                "diff --git a/a.py b/a.py",
                "--- a/a.py",
                "+++ b/a.py",
                "@@ -10,3 +10,1 @@",
                " def m():",
                "-    old.call()",
                "-    other.do()",
                "") + "\n";
        CodeUnitModel unit = modifyMethod(10, 12, List.of());
        FileChangeModel file = new FileChangeModel();
        file.setPath("a.py");
        file.setDiff(diff);
        file.setLanguage(LanguageEnum.PYTHON);
        file.setCodeUnits(List.of(unit));
        SubmissionContext ctx = contextWith(file);

        new EffectiveChangePopulator().accept(ctx);

        assertEquals(0, unit.getEffectiveInvocationsChanged());
    }
    @Test
    void linesInsideNestedBlockAttributeOnlyToInnermostUnit() {
        String diff = String.join("\n",
                "diff --git a/A.java b/A.java",
                "--- a/A.java",
                "+++ b/A.java",
                "@@ -38,2 +38,2 @@",
                " protected Object createInstance() {",
                "-    container.start();",
                "+    container.restart();",
                "") + "\n";
        CodeUnitModel outer = modifyMethod(33, 53, List.of());
        outer.setKind(SymbolKindModel.METHOD);
        CodeUnitModel inner = modifyMethod(38, 43, List.of(39));
        inner.setKind(SymbolKindModel.METHOD);
        SubmissionContext ctx = contextWith(javaFile(diff, outer, inner));

        new EffectiveChangePopulator().accept(ctx);

        assertEquals(0, outer.getEffectiveLinesChanged());
        assertEquals(0, outer.getEffectiveInvocationsChanged());
        assertEquals(1, inner.getEffectiveLinesChanged());
        assertEquals(2, inner.getEffectiveInvocationsChanged());
    }
    @Test
    void skipsCodeUnitsThatAreNotModifiedOperations() {
        CodeUnitModel unit = modifyMethod(10, 16, List.of(11));
        unit.setOperation(OperationEnum.NEW);
        SubmissionContext ctx = contextWith(javaFile(JAVA_DIFF, unit));

        new EffectiveChangePopulator().accept(ctx);

        assertEquals(null, unit.getEffectiveLinesChanged());
        assertEquals(null, unit.getEffectiveInvocationsChanged());
    }
    private static FileChangeModel javaFile(String diff, CodeUnitModel... units) {
        FileChangeModel file = new FileChangeModel();
        file.setPath("A.java");
        file.setDiff(diff);
        file.setLanguage(LanguageEnum.JAVA);
        file.setCodeUnits(List.of(units));
        return file;
    }
    private static CodeUnitModel modifyMethod(int startLine, int endLine, List<Integer> directInvocationLines) {
        LocationModel location = new LocationModel();
        location.setStartLine(startLine);
        location.setEndLine(endLine);

        MetricsModel metrics = new MetricsModel();
        metrics.setDirectInvocationLines(directInvocationLines);

        CodeUnitModel unit = new CodeUnitModel();
        unit.setOperation(OperationEnum.MODIFY);
        unit.setLocation(location);
        unit.setMetrics(metrics);
        return unit;
    }
    private static SubmissionContext contextWith(FileChangeModel... files) {
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel();
        submission.setFiles(List.of(files));
        return SubmissionContext.builder().submissionModel(submission).build();
    }
}
