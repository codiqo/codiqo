package io.codiqo.llm;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.metrics.CodeLineCounter;
import lombok.Getter;
import lombok.Value;

/**
 * Single-pass walker over a per-file unified diff. Tracks old- and new-file line counters from
 * {@code @@} hunk headers, applies the same effective-line filter the server uses for the
 * per-file {@code linesAdded}/{@code linesDeleted} targets (blank / import / comment-only lines
 * are excluded — see {@code SubmissionToRequestMapper.DiffStats}), and groups the surviving
 * candidate lines into {@link ChangeBlock}s: maximal runs of {@code ±} lines uninterrupted by
 * context. Exposes:
 * <ul>
 * <li>{@link #getAddedLines()} / {@link #getDeletedLines()} — every raw {@code ±} line,</li>
 * <li>{@link #getCandidateAddedLines()} / {@link #getCandidateDeletedLines()} — effective lines
 * only (the classifiable subset),</li>
 * <li>{@link #getBlocks()} — change blocks with server-assigned ids ({@code B1}, {@code B2}, …)
 * in diff order; a run with no effective line gets no block,</li>
 * <li>{@link #getAnnotated()} — the diff with each candidate line prefixed
 * {@code -<old>|B<n>|content} / {@code +<new>|B<n>|content} (filtered {@code ±} lines keep the
 * Phase-1 {@code -<old>|content} form without a block tag), so the LLM copies coordinates and
 * block ids instead of deriving them.</li>
 * </ul>
 */
@Getter
public final class UnifiedDiffLines {
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    private static final String IMPORT_PREFIX = "import ";

    private final Set<Integer> addedLines = Sets.newTreeSet();
    private final Set<Integer> deletedLines = Sets.newTreeSet();
    private final Set<Integer> candidateAddedLines = Sets.newTreeSet();
    private final Set<Integer> candidateDeletedLines = Sets.newTreeSet();
    private final List<ChangeBlock> blocks = Lists.newArrayList();
    private final String annotated;

    private UnifiedDiffLines(String diff, boolean requiresLineFiltering) {
        StringBuilder out = new StringBuilder(diff.length() + 256);
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;
        List<Integer> runDeleted = Lists.newArrayList();
        List<Integer> runAdded = Lists.newArrayList();
        // -1 limit keeps trailing empty strings so the annotated text round-trips exactly
        String[] lines = diff.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (i > 0) {
                out.append('\n');
            }

            Matcher hunk = HUNK_HEADER.matcher(raw);
            if (hunk.find()) {
                closeRun(runDeleted, runAdded);
                inHunk = true;
                oldLine = Integer.parseInt(hunk.group(1));
                newLine = Integer.parseInt(hunk.group(2));
                out.append(raw);
                continue;
            }
            // file headers before the first @@ and "\ No newline at end of file" markers are
            // metadata: they advance neither counter. A "\" marker can sit between the deleted
            // and added halves of one logical block, so it must not close the run either.
            if (!inHunk || raw.startsWith("\\")) {
                out.append(raw);
                continue;
            }
            if (raw.startsWith("-")) {
                deletedLines.add(oldLine);
                if (isCandidate(raw.substring(1), requiresLineFiltering)) {
                    candidateDeletedLines.add(oldLine);
                    runDeleted.add(oldLine);
                    out.append('-').append(oldLine).append("|B").append(currentBlockId()).append('|').append(raw, 1, raw.length());
                } else {
                    out.append('-').append(oldLine).append('|').append(raw, 1, raw.length());
                }
                oldLine++;
            } else if (raw.startsWith("+")) {
                addedLines.add(newLine);
                if (isCandidate(raw.substring(1), requiresLineFiltering)) {
                    candidateAddedLines.add(newLine);
                    runAdded.add(newLine);
                    out.append('+').append(newLine).append("|B").append(currentBlockId()).append('|').append(raw, 1, raw.length());
                } else {
                    out.append('+').append(newLine).append('|').append(raw, 1, raw.length());
                }
                newLine++;
            } else {
                closeRun(runDeleted, runAdded);
                out.append(raw);
                oldLine++;
                newLine++;
            }
        }
        closeRun(runDeleted, runAdded);
        annotated = out.toString();
    }
    // the open run is always the next block to be closed; blocks only grow at run boundaries
    private int currentBlockId() {
        return blocks.size() + 1;
    }
    private void closeRun(List<Integer> runDeleted, List<Integer> runAdded) {
        if (runDeleted.isEmpty() && runAdded.isEmpty()) {
            return;
        }
        blocks.add(new ChangeBlock("B" + (blocks.size() + 1), List.copyOf(runDeleted), List.copyOf(runAdded)));
        runDeleted.clear();
        runAdded.clear();
    }
    /**
     * Mirrors {@code SubmissionToRequestMapper.DiffStats.categorize} exactly — the per-file
     * {@code linesAdded}/{@code linesDeleted} targets subtract blank, comment (JVM languages
     * only), and import lines, so candidate enumeration must apply the identical rules or block
     * sums drift from the targets.
     */
    private static boolean isCandidate(String content, boolean requiresLineFiltering) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (requiresLineFiltering && CodeLineCounter.isCommentLine(trimmed)) {
            return false;
        }
        return !trimmed.startsWith(IMPORT_PREFIX);
    }

    public static UnifiedDiffLines parse(String diff, boolean requiresLineFiltering) {
        return new UnifiedDiffLines(diff, requiresLineFiltering);
    }

    /** One maximal run of {@code ±} lines between context lines, holding effective lines only. */
    @Value
    public static class ChangeBlock {
        String id;
        List<Integer> deletedLines;
        List<Integer> addedLines;
    }
}
