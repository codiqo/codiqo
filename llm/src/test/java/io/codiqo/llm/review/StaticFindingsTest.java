package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.api.review.UnitName;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocationModel;

class StaticFindingsTest {
    /** a made-up language of .java files whose PMD and SpotBugs findings are triaged */
    private static final ReviewLanguage TRIAGING = new ReviewLanguage() {
        @Override
        public Collection<String> extensions() {
            return List.of("java");
        }
        @Override
        public String namingRule() {
            return StringUtils.EMPTY;
        }
        @Override
        public UnitName labelled(String signature, String path) {
            return new UnitName(List.of(), signature);
        }
        @Override
        public UnitName unit(String name, String signature, String path) {
            return new UnitName(List.of(), name);
        }
        @Override
        public Set<String> triagedTools() {
            return Set.of("pmd", "spotbugs");
        }
    };

    private static final String DIFF = """
            --- a/a/Service.java
            +++ b/a/Service.java
            @@ -1,3 +1,4 @@
             class Service {
            +    Object cache = null;
                 void run() {
                 }
            """;

    /** the findings on added lines, of the tools the file's language triages, each once though it is attached to every unit it spans */
    @Test
    void onlyTheToolFindingsOnAddedLinesAreAsked() {
        DiagnosticModel added = diagnostic(DiagnosticModel.ToolEnum.PMD, "NullAssignment", 2, 2);
        DiagnosticModel spanning = diagnostic(DiagnosticModel.ToolEnum.SPOTBUGS, "URF_UNREAD_FIELD", 1, 4);
        DiagnosticModel untouched = diagnostic(DiagnosticModel.ToolEnum.PMD, "UncommentedEmptyMethodBody", 3, 4);
        DiagnosticModel otherTool = diagnostic(DiagnosticModel.ToolEnum.ERRORPRONE, "UnusedVariable", 2, 2);
        FileChangeModel file = new FileChangeModel().path("a/Service.java").diff(DIFF).codeUnits(List.of(
                new CodeUnitModel().diagnostics(List.of(added, spanning, untouched, otherTool)),
                new CodeUnitModel().diagnostics(List.of(spanning))));

        List<StaticFinding> findings = StaticFindings.introduced(new AnalysisSubmissionModel().files(List.of(file)), List.of(TRIAGING));

        assertEquals(List.of(
                new StaticFinding("pmd", "NullAssignment", "warning", "a/Service.java", 2, "flagged"),
                new StaticFinding("spotbugs", "URF_UNREAD_FIELD", "warning", "a/Service.java", 1, "flagged")), findings);
    }
    /** a file of no registered language has no tool its findings would be triaged for */
    @Test
    void aFileOfNoRegisteredLanguageIsNotTriaged() {
        FileChangeModel file = new FileChangeModel().path("a/Service.java").diff(DIFF).codeUnits(List.of(
                new CodeUnitModel().diagnostics(List.of(diagnostic(DiagnosticModel.ToolEnum.PMD, "NullAssignment", 2, 2)))));

        assertEquals(List.of(), StaticFindings.introduced(new AnalysisSubmissionModel().files(List.of(file)), List.of()));
    }
    private static DiagnosticModel diagnostic(DiagnosticModel.ToolEnum tool, String rule, int start, int end) {
        return new DiagnosticModel().tool(tool).ruleId(rule).severity(DiagnosticModel.SeverityEnum.WARNING).message("flagged")
                .location(new LocationModel().startLine(start).endLine(end));
    }
}
