package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.CharUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.RunArgs;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.diff.GitCommitAnalysis;
import io.codiqo.core.java.JavaLanguageSpec;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.util.Fetch;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;

/**
 * Runs the real CPD path the analysis takes, PMD included, and checks the clones it keeps.
 */
class CpdDuplicationDensityTest {
    private static final int TILE_SIZE = 100;
    private static final int THREE_COPIES = 3;
    private static final int PMD_MATCHES_FOR_TWO_CLONES = 3;
    private static final List<String> STATEMENTS = List.of(
            "v = v + 1;",
            "v = v - 1;",
            "v = v * 2;",
            "v = v / 2;",
            "v = v % 3;",
            "if (v > 0) { v++; }",
            "if (v < 0) { v--; }",
            "while (v > 9) { v -= 9; }",
            "for (int i = 0; i < 3; i++) { v += i; }",
            "v = v << 1;",
            "v = v >> 1;",
            "v = v & 7;",
            "v = v | 8;",
            "v = v ^ 5;",
            "v = -v;");

    /**
     * two unrelated clone pairs of exactly the same size: the same statements, one pair in reverse order. Counted
     * together they must add up to what each counts alone; with matches compared by size only, the second pair
     * collapsed into the first and its lines were never counted.
     */
    @Test
    void unrelatedClonesOfEqualSizeAreBothCounted(@TempDir Path dir) throws IOException {
        List<Path> forward = List.of(write(dir, "A1.java", source("A1", STATEMENTS)), write(dir, "A2.java", source("A2", STATEMENTS)));
        List<Path> reversed = List.of(write(dir, "B1.java", source("B1", STATEMENTS.reversed())), write(dir, "B2.java", source("B2", STATEMENTS.reversed())));

        int forwardLines = run(forward).duplicatedLines();
        int reversedLines = run(reversed).duplicatedLines();

        assertTrue(forwardLines > 0, "the fixture must contain a clone");
        assertEquals(forwardLines, reversedLines, "the two pairs must be the same size for this to test anything");
        assertEquals(forwardLines + reversedLines, run(ListUtils.union(forward, reversed)).duplicatedLines());
    }
    /**
     * A and B share a longer prefix than either shares with C, so PMD reports the common tail as [A,C] and [B,C]
     * instead of one match with three copies. Counted as two clones, one copied region would be penalized twice.
     */
    @Test
    void copiesOfOneRegionAreOneCloneClass(@TempDir Path dir) throws IOException {
        List<String> prefix = List.of("v += 1;", "v -= 2;", "v *= 3;");
        List<Path> files = List.of(
                write(dir, "A.java", source("A", ListUtils.union(prefix, STATEMENTS))),
                write(dir, "B.java", source("B", ListUtils.union(prefix, STATEMENTS))),
                write(dir, "C.java", source("C", ListUtils.union(List.of("v /= 4;", "v %= 5;", "v <<= 1;"), STATEMENTS))));

        LogFactory logFactory = new SlfLogFactory();
        RunArgs args = new RunArgs();
        args.setCpdMinimumTileSize(TILE_SIZE);
        List<Match> reported = new ArrayList<>();
        List<Collection<Mark>> classes = new ArrayList<>();
        try (Fetch fetch = new Fetch(args);
                JavaLanguageSpec java = new JavaLanguageSpec(logFactory, args, fetch);
                CpdAnalysis cpd = CpdAnalysis.create(DefaultLanguageProcessors.cpdConfiguration(java.lang(), args))) {
            files.forEach(cpd.files()::addFile);
            cpd.performAnalysis(report -> {
                reported.addAll(report.getMatches());
                classes.addAll(DefaultLanguageProcessors.cloneClasses(report.getMatches()).values());
            });
        }

        assertEquals(PMD_MATCHES_FOR_TWO_CLONES, reported.size(), "PMD must still split the tail, or this tests nothing: " + reported);
        assertEquals(2, classes.size(), "[A,B] with its prefix, and the tail all three files share");
        assertTrue(classes.stream().anyMatch(marks -> marks.size() == THREE_COPIES));
    }
    /**
     * calls tokenizeAndCollect directly rather than detectCopyPaste: a crash must fail the test, not be absorbed by
     * the batched retry
     */
    private static CopyPasteDetectionSummary run(List<Path> files) throws IOException {
        LogFactory logFactory = new SlfLogFactory();
        RunArgs args = new RunArgs();
        args.setCpdMinimumTileSize(TILE_SIZE);
        GitCommitAnalysis analysis = new GitCommitAnalysis();

        try (Fetch fetch = new Fetch(args);
                DefaultLanguageProcessors processors = new DefaultLanguageProcessors(logFactory, args, fetch);
                JavaLanguageSpec java = new JavaLanguageSpec(logFactory, args, fetch)) {
            processors.tokenizeAndCollect(java, files, IndexingSummary.builder().build(), analysis);
        }
        assertEquals(1, analysis.cpd().size());
        return analysis.cpd().iterator().next();
    }
    private static String source(String name, List<String> statements) {
        StringBuilder body = new StringBuilder();
        for (String statement : statements) {
            body.append("        ").append(statement).append(CharUtils.LF);
        }
        return "class " + name + " {\n    void run(int v) {\n" + body + "    }\n}\n";
    }
    private static Path write(Path dir, String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }
}
