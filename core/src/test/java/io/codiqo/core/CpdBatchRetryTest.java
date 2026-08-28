package io.codiqo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.java.JavaLanguageSpec;
import io.codiqo.core.logging.SlfLogFactory;
import io.codiqo.util.Fetch;

/**
 * Guards the CPD containment policy. The crash it contains — PMD's shared {@code CpdLexer} accumulating
 * {@code ConstructorDetector} state across file boundaries — only appears on thousands of real files, which cannot be
 * committed as a fixture, so what is pinned here is the policy: the whole set is tried first, and the batched retry
 * covers every file exactly once. The first invariant is the load-bearing one, because CPD finds clones only within a
 * single analysis and batching unconditionally would silently drop every cross-batch duplicate.
 */
class CpdBatchRetryTest {
    private static final int FILE_COUNT = 4500;
    private static final int BATCH_SIZE = 1000;
    private static final int EXPECTED_BATCHES = 5;

    @Test
    void triesTheWholeSetFirstAndDoesNotBatchWhenItSucceeds() throws Exception {
        List<List<Path>> attempts = run(0);

        assertEquals(1, attempts.size(), "a healthy set must be analyzed in one pass, or cross-file clones are lost");
        assertEquals(paths(), attempts.iterator().next());
    }
    @Test
    void retriesInBatchesCoveringEveryFileExactlyOnce() throws Exception {
        List<List<Path>> attempts = run(1);

        List<List<Path>> retries = attempts.subList(1, attempts.size());
        assertEquals(EXPECTED_BATCHES, retries.size(), FILE_COUNT + " files at a batch size of " + BATCH_SIZE);
        assertTrue(retries.stream().allMatch(batch -> batch.size() <= BATCH_SIZE));
        assertEquals(paths(), retries.stream().flatMap(List::stream).toList(), "every file is retried once, in order");
    }
    @Test
    void aPoisonedBatchLosesOnlyItsOwnData() throws Exception {
        List<List<Path>> attempts = run(Integer.MAX_VALUE);

        assertEquals(EXPECTED_BATCHES + 1, attempts.size(),
                "one whole-set attempt plus every batch: the retry must not abort on the first failing batch");
    }
    private static List<List<Path>> run(int failuresToThrow) throws IOException {
        LogFactory logFactory = new SlfLogFactory();
        RunArgs args = new RunArgs();

        try (Fetch fetch = new Fetch(args);
                RecordingProcessors processors = new RecordingProcessors(logFactory, args, fetch, failuresToThrow)) {
            processors.detectCopyPaste(new JavaLanguageSpec(logFactory, args, fetch), paths(), null, null);
            return processors.attempts;
        }
    }
    private static List<Path> paths() {
        return IntStream.range(0, FILE_COUNT).mapToObj(i -> Paths.get("/src/Foo" + i + ".java")).toList();
    }
    private static final class RecordingProcessors extends DefaultLanguageProcessors {
        private final List<List<Path>> attempts = new ArrayList<>();
        private final int failuresToThrow;
        private int calls;

        private RecordingProcessors(LogFactory logFactory, RunArgs args, Fetch fetch, int failuresToThrow) {
            super(logFactory, args, fetch);
            this.failuresToThrow = failuresToThrow;
        }
        @Override
        void tokenizeAndCollect(LanguageSpec processor, List<Path> files, IndexingSummary summary, CommitAnalysis analysis) {
            attempts.add(files);
            calls++;
            if (calls <= failuresToThrow) {
                throw new IllegalStateException("simulated PMD lexer state leak");
            }
        }
    }
}
