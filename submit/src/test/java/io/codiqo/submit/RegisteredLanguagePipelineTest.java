package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.util.Fetch;

/**
 * A language the registry is given runs through the same pipeline as Java, from the commit's git delta to the full
 * submission: the index, the affected and removed units and the capture. Nothing on the way may expect a Java unit, or
 * the language's units would be dropped without a word. The checks a run makes before the registry exists (which
 * commits are built, which files the review knows) read the registered languages, not the ones this test hands in.
 */
class RegisteredLanguagePipelineTest {
    private static final String FILE = "Calc" + FilenameUtils.EXTENSION_SEPARATOR_STR + FakeLanguage.EXTENSION;
    private static final String BEFORE = """
            fn add
              return a + b
            end
            fn sub
              return a - b
            end
            """;
    private static final String AFTER = """
            fn add
              return b + a
            end
            fn mul
              TODO overflow
              return a * b
            end
            """;

    @TempDir
    Path workTree;

    @Test
    void aRegisteredLanguagesUnitsReachTheSubmission() throws Exception {
        FileChangeModel file;
        try (Git git = Git.init().setDirectory(workTree.toFile()).call()) {
            commit(git, BEFORE);

            RunArgs args = new RunArgs();
            args.setGit(git.getRepository());
            args.setCommitId(commit(git, AFTER));
            args.getProjects().add(module());

            try (Fetch fetch = new Fetch(args); DefaultLanguageProcessors registry = new DefaultLanguageProcessors(StubAnalysis.LOGS, args, fetch, List.of(new FakeLanguage()))) {
                CommitAnalysis analysis = new JGitDeltaAnalyzer(StubAnalysis.LOGS, args).analyze();
                IndexingSummary index = registry.index(analysis);
                registry.identifyAffectedSymbols(index, analysis);
                registry.collectAndCapture(index, analysis);

                SubmissionContext ctx = SubmissionContext.create(args, index, analysis, workTree, StubAnalysis.LOGS, "group:artifact", "test", new ClientInfoModel());
                SubmissionAssembly.full(ctx);
                file = ctx.getSubmissionModel().getFiles().iterator().next();
            }
        }

        assertEquals(FILE, file.getPath());
        assertEquals(FileChangeModel.LanguageEnum.GO, file.getLanguage());

        Map<String, CodeUnitModel> units = file.getCodeUnits().stream().collect(Collectors.toMap(CodeUnitModel::getName, Function.identity()));
        assertEquals(Set.of("add", "mul", "sub"), units.keySet());
        assertEquals(CodeUnitModel.OperationEnum.MODIFY, units.get("add").getOperation());
        assertEquals(CodeUnitModel.OperationEnum.DELETE, units.get("sub").getOperation(), "a function the commit removed");
        assertEquals("  return a - b", units.get("sub").getBodyBefore().lines().toList().get(1));

        CodeUnitModel mul = units.get("mul");
        assertEquals(SymbolKindModel.FUNCTION, mul.getKind());
        assertEquals(List.of(4, 7), List.of(mul.getLocation().getStartLine(), mul.getLocation().getEndLine()));

        DiagnosticModel todo = mul.getDiagnostics().iterator().next();
        assertEquals(List.of(DiagnosticModel.ToolEnum.PMD, FakeLanguage.TODO_RULE, 5), List.of(todo.getTool(), todo.getRuleId(), todo.getLocation().getStartLine()));
    }
    private String commit(Git git, String content) throws Exception {
        Files.writeString(workTree.resolve(FILE), content, StandardCharsets.UTF_8);
        git.add().addFilepattern(FILE).call();
        return git.commit().setMessage("change " + FILE).call().getName();
    }
    private ProjectSpec module() {
        ProjectSpec toReturn = mock(ProjectSpec.class);
        when(toReturn.getId()).thenReturn("app");
        when(toReturn.getName()).thenReturn("app");
        when(toReturn.getBaseDirectory()).thenReturn(workTree.toFile());
        when(toReturn.contains(any())).thenReturn(true);
        return toReturn;
    }
}
