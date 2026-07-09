package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;


import io.codiqo.api.RunArgs;
import io.codiqo.llm.MovedLineDetector.MoveCandidate;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;

class MovedLineDetectorTest {
    /**
     * modeled on the ResteasyChannel lifecycle split (turbospaces-boot 92d9804): a method body is
     * deleted and re-added lower in the same file with re-qualified receivers (props.X →
     * channel.props.X). deleted lines are old-file 11-14, added lines are new-file 25-28
     */
    private static final String RELOCATION_DIFF = String.join("\n",
            "--- a/Channel.java",
            "+++ b/Channel.java",
            "@@ -10,6 +10,2 @@",
            " context",
            "-server.setBacklog(props.TCP_SOCKET_BACKLOG.get());",
            "-server.setIoWorkerCount(props.NETTY_ACCEPTOR_POOL_SIZE.get());",
            "-deployment.setRegistry(registry);",
            "-deployment.start();",
            " context2",
            "@@ -30,2 +24,6 @@",
            " context3",
            "+server.setBacklog(channel.props.TCP_SOCKET_BACKLOG.get());",
            "+server.setIoWorkerCount(channel.props.NETTY_ACCEPTOR_POOL_SIZE.get());",
            "+deployment.setRegistry(channel.registry);",
            "+deployment.start();",
            " context4");

    @Test
    void requalifiedRelocationMatchesAtFullContainment() {
        List<MoveCandidate> candidates = detector().detect(requestWith(fileChange("Channel.java", RELOCATION_DIFF)));

        assertEquals(3, candidates.size(), "the three informative lines match; deployment.start() is too trivial");
        for (MoveCandidate candidate : candidates) {
            assertEquals(1.0, candidate.getSimilarity(), 0.0001,
                    "re-qualification only adds tokens — containment of the deleted side is exactly 1.0");
            assertEquals("Channel.java", candidate.getFromFile());
            assertEquals("Channel.java", candidate.getToFile());
        }
        assertEquals(List.of("M1", "M2", "M3"), candidates.stream().map(MoveCandidate::getId).toList());

        MoveCandidate first = candidates.get(0);
        assertEquals(11, first.getFromLine());
        assertEquals(25, first.getToLine());
    }
    @Test
    void crossFileMoveDetected() {
        String deletedSide = String.join("\n",
                "--- a/Source.java",
                "+++ b/Source.java",
                "@@ -5,3 +5,1 @@",
                " context",
                "-keystore.store(out, SelfSignedCertificateGenerator.PASSWORD.toCharArray());",
                " context2");
        String addedSide = String.join("\n",
                "--- a/Target.java",
                "+++ b/Target.java",
                "@@ -40,1 +40,2 @@",
                " context",
                "+keystore.store(out, SelfSignedCertificateGenerator.PASSWORD.toCharArray());",
                " context2");

        List<MoveCandidate> candidates = detector().detect(requestWith(
                fileChange("Source.java", deletedSide),
                fileChange("Target.java", addedSide)));

        assertEquals(1, candidates.size());
        assertEquals("Source.java", candidates.get(0).getFromFile());
        assertEquals(6, candidates.get(0).getFromLine());
        assertEquals("Target.java", candidates.get(0).getToFile());
        assertEquals(41, candidates.get(0).getToLine());
    }
    @Test
    void trivialLinesAreNeverCandidates() {
        String diff = String.join("\n",
                "--- a/Foo.java",
                "+++ b/Foo.java",
                "@@ -5,4 +5,1 @@",
                " context",
                "-}",
                "-return;",
                "-server.stop();",
                " context2",
                "@@ -30,1 +26,4 @@",
                " context3",
                "+}",
                "+return;",
                "+server.stop();",
                " context4");

        assertTrue(detector().detect(requestWith(fileChange("Foo.java", diff))).isEmpty(),
                "lines with fewer than 3 word tokens carry no relocation signal");
    }
    @Test
    void sizeRatioGuardRejectsShortLineAbsorbedByLongLine() {
        String diff = String.join("\n",
                "--- a/Foo.java",
                "+++ b/Foo.java",
                "@@ -5,2 +5,2 @@",
                " context",
                "-validate(name, value);",
                "+validate(name, value, context, options, flags, extra, more);",
                " context2");

        assertTrue(detector().detect(requestWith(fileChange("Foo.java", diff))).isEmpty(),
                "containment is 1.0 but the added line is more than twice the size — not a move");
    }
    @Test
    void duplicateAddedLinesConsumeOneToOne() {
        String diff = String.join("\n",
                "--- a/Foo.java",
                "+++ b/Foo.java",
                "@@ -5,2 +5,3 @@",
                " context",
                "-registry.register(handler, priority);",
                " context2",
                "@@ -30,1 +27,3 @@",
                " context3",
                "+registry.register(handler, priority);",
                "+registry.register(handler, priority);",
                " context4");

        List<MoveCandidate> candidates = detector().detect(requestWith(fileChange("Foo.java", diff)));

        assertEquals(1, candidates.size(), "one deleted line consumes exactly one of the identical added lines");
    }
    @Test
    void detectionIsDeterministic() {
        LlmScoringRequest request = requestWith(fileChange("Channel.java", RELOCATION_DIFF));

        assertEquals(detector().detect(request), detector().detect(request));
    }
    @Test
    void disabledDetectionReturnsEmpty() {
        RunArgs args = new RunArgs();
        args.setMoveDetectionEnabled(false);

        assertTrue(new MovedLineDetector(args).detect(requestWith(fileChange("Channel.java", RELOCATION_DIFF))).isEmpty());
    }
    @Test
    void ineligibleFilesAreIgnored() {
        FileChange fc = fileChange("Channel.java", RELOCATION_DIFF);
        fc.setLinesJustificationRequired(false);

        assertTrue(detector().detect(requestWith(fc)).isEmpty());
    }

    private static MovedLineDetector detector() {
        return new MovedLineDetector(new RunArgs());
    }
    private static LlmScoringRequest requestWith(FileChange... fileChanges) {
        return LlmScoringRequest.builder()
                .fileChanges(new ArrayList<>(List.of(fileChanges)))
                .build();
    }
    private static FileChange fileChange(String file, String diff) {
        return FileChange.builder()
                .path(file)
                .diff(diff)
                .linesJustificationRequired(true)
                .build();
    }
}
