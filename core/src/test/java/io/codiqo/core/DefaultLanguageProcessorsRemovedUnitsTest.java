package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.HashMultimap;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.core.diff.GitDiffHunk;
import io.codiqo.core.diff.GitFileAnalysis;
import io.codiqo.core.diff.GitStructuredDiff;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.util.Fetch;

class DefaultLanguageProcessorsRemovedUnitsTest {
    private static final String BEFORE = """
            package com.example;

            public class Sample {
                public void retained() {
                    System.out.println(1);
                }
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void aFileTheIndexDidNotParseReportsNoRemovals() throws Exception {
        GitFileAnalysis file = editedFile();

        identify(file, Set.of());

        assertEquals(Set.of(), file.getRemovedCodeBlocks(), "an empty block list for an unparsed file is not proof of removal");
    }
    @Test
    void aParsedFileThatNowHasNoCodeUnitsReportsItsRemovals() throws Exception {
        GitFileAnalysis file = editedFile();
        Files.writeString(file.getFile().toPath(), "package com.example;\n\npublic class Sample {\n}\n");

        identify(file, Set.of(file.getFile()));

        assertEquals(
                Set.of("com/example/Sample.retained()V"),
                file.getRemovedCodeBlocks().stream().map(CodeBlockInfo::getSignature).collect(Collectors.toSet()));
    }
    private GitFileAnalysis editedFile() {
        GitDiffHunk hunk = new GitDiffHunk();
        hunk.setType(Edit.Type.REPLACE);
        hunk.setOldStartLine(4);
        hunk.setOldEndLine(5);
        hunk.setNewStartLine(4);
        hunk.setNewEndLine(5);

        GitStructuredDiff diff = new GitStructuredDiff();
        diff.setChangeType(DiffEntry.ChangeType.MODIFY);
        diff.getHunks().add(hunk);

        GitFileAnalysis toReturn = new GitFileAnalysis();
        toReturn.setFile(tempDir.resolve("Sample.java").toFile());
        toReturn.setOldPath("Sample.java");
        toReturn.setChangeType(DiffEntry.ChangeType.MODIFY);
        toReturn.setContentBefore(BEFORE);
        toReturn.setStructuredDiff(diff);
        toReturn.accept(mock(ProjectSpec.class));
        return toReturn;
    }
    private static void identify(GitFileAnalysis file, Set<File> parsedFiles) throws Exception {
        CommitAnalysis analysis = mock(CommitAnalysis.class);
        when(analysis.iterator()).thenAnswer(invocation -> List.<FileAnalysis> of(file).iterator());

        IndexingSummary summary = IndexingSummary.builder()
                .blocks(HashMultimap.create())
                .parsedFiles(parsedFiles)
                .build();

        RunArgs args = new RunArgs();
        try (Fetch fetch = new Fetch(args); DefaultLanguageProcessors processors = new DefaultLanguageProcessors(new SlfLogFactory(), args, fetch)) {
            processors.identifyAffectedSymbols(summary, analysis);
        }
    }
}
