package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.PreviousRevision;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.core.java.JavaLanguageSpec;
import io.codiqo.lang.spec.JavaCodeBlockInfo;
import io.codiqo.util.Fetch;

class RemovedCodeUnitTest {
    @TempDir
    Path workTree;

    @Test
    void aRemovedUnitIsNamedAndTypedLikeAnyOther() throws Exception {
        String before = """
                package com.example;

                import java.io.IOException;

                public class Shrinking {
                    @Deprecated
                    public void foo(int x) throws IOException {
                        System.out.println(x);
                    }
                    public void foo(String s) {
                        System.out.println(s);
                    }
                    public void foo(long l) {
                        System.out.println(l);
                    }
                    public Shrinking(int seed) {
                        System.out.println(seed);
                    }
                }
                """;
        String after = """
                package com.example;

                public class Shrinking {
                    public void foo(long l) {
                        System.out.println(l + 1);
                    }
                }
                """;
        Path source = workTree.resolve("Shrinking.java");
        Files.writeString(source, after);

        Map<String, CodeUnitModel> byName;
        RunArgs args = new RunArgs();
        try (Fetch fetch = new Fetch(args); JavaLanguageSpec spec = new JavaLanguageSpec(StubAnalysis.LOGS, args, fetch)) {
            SubmissionContext ctx = SubmissionContext.builder().build();
            byName = spec.parseRemoved(mock(ProjectSpec.class), List.of(new PreviousRevision(source.toFile(), "Shrinking.java", before))).values().stream()
                    .map(block -> FileAnalysisPopulator.removedCodeUnitModel(ctx, spec.lang(), (JavaCodeBlockInfo) block, workTree))
                    .collect(Collectors.toMap(CodeUnitModel::getName, Function.identity()));
        }

        assertEquals(Set.of("foo(int)", "foo(String)", "Shrinking(int)"), byName.keySet());
        assertEquals(CodeUnitModel.OperationEnum.DELETE, byName.get("foo(int)").getOperation());
        assertEquals(Boolean.TRUE, byName.get("foo(int)").getJavaInfo().getIsDeprecated());
        assertEquals(List.of("IOException"), byName.get("foo(int)").getJavaInfo().getThrowsTypes());
        assertEquals(SymbolKindModel.METHOD, byName.get("foo(String)").getKind());
        assertEquals(SymbolKindModel.CONSTRUCTOR, byName.get("Shrinking(int)").getKind());
    }
    @Test
    void aRemovedUnitTakesNoDuplicationFromTheNewTree() {
        CodeUnitModel removed = unit(CodeUnitModel.OperationEnum.DELETE);
        CodeUnitModel modified = unit(CodeUnitModel.OperationEnum.MODIFY);

        FileChangeModel file = new FileChangeModel();
        file.setPath("A.java");
        file.setCodeUnits(List.of(removed, modified));
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel();
        submission.setFiles(List.of(file));

        DuplicationReportPopulator.applyDuplicationToCodeUnits(
                SubmissionContext.builder().submissionModel(submission).build(),
                Map.of("p/A.f()V", Set.of("p/B.g()V")),
                Map.of("A.java", Set.of(1, 2)),
                Map.of("A.java", new ChangedLines(Set.of(1), Set.of())));

        assertNull(removed.getDuplication());
        assertNotNull(modified.getDuplication());
    }
    private static CodeUnitModel unit(CodeUnitModel.OperationEnum operation) {
        LocationModel location = new LocationModel();
        location.setStartLine(1);
        location.setEndLine(2);

        CodeUnitModel toReturn = new CodeUnitModel();
        toReturn.setSignature("p/A.f()V");
        toReturn.setOperation(operation);
        toReturn.setLocation(location);
        return toReturn;
    }
}
