package io.codiqo.llm;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.diff.IneffectiveLineFilter;
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
    private static final String LF = "\n";
    private static final String ADDED_PREFIX = "+";
    private static final String DELETED_PREFIX = "-";
    private static final String NO_NEWLINE_MARKER = "\\";
    private static final String BLOCK_ID_PREFIX = "B";
    private static final char FIELD_SEPARATOR = '|';

    private final Set<Integer> addedLines = Sets.newTreeSet();
    private final Set<Integer> deletedLines = Sets.newTreeSet();
    private final Set<Integer> candidateAddedLines = Sets.newTreeSet();
    private final Set<Integer> candidateDeletedLines = Sets.newTreeSet();
    private final List<ChangeBlock> blocks = Lists.newArrayList();
    private final String annotated;

    private UnifiedDiffLines(String diff, IneffectiveLineFilter filter) {
        Predicate<String> ineffective = filter.commentOrImportFilter();

        StringBuilder out = new StringBuilder(diff.length() + 256);
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;
        List<Integer> runDeleted = Lists.newArrayList();
        List<Integer> runAdded = Lists.newArrayList();
        // -1 limit keeps trailing empty strings so the annotated text round-trips exactly
        String[] lines = diff.split(LF, -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (i > 0) {
                out.append(LF);
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

            /**
             * file headers before the first @@ and "\ No newline at end of file" markers are
             * metadata: they advance neither counter. A "\" marker can sit between the deleted
             * and added halves of one logical block, so it must not close the run either.
             */
            if (BooleanUtils.or(new boolean[] { BooleanUtils.negate(inHunk), raw.startsWith(NO_NEWLINE_MARKER) })) {
                out.append(raw);
                continue;
            }
            if (raw.startsWith(DELETED_PREFIX)) {
                deletedLines.add(oldLine);
                if (isCandidate(raw.substring(1), ineffective)) {
                    candidateDeletedLines.add(oldLine);
                    runDeleted.add(oldLine);
                    out.append(DELETED_PREFIX).append(oldLine).append(FIELD_SEPARATOR).append(BLOCK_ID_PREFIX).append(currentBlockId()).append(FIELD_SEPARATOR).append(raw, 1, raw.length());
                } else {
                    out.append(DELETED_PREFIX).append(oldLine).append(FIELD_SEPARATOR).append(raw, 1, raw.length());
                }
                oldLine++;
            } else if (raw.startsWith(ADDED_PREFIX)) {
                addedLines.add(newLine);
                if (isCandidate(raw.substring(1), ineffective)) {
                    candidateAddedLines.add(newLine);
                    runAdded.add(newLine);
                    out.append(ADDED_PREFIX).append(newLine).append(FIELD_SEPARATOR).append(BLOCK_ID_PREFIX).append(currentBlockId()).append(FIELD_SEPARATOR).append(raw, 1, raw.length());
                } else {
                    out.append(ADDED_PREFIX).append(newLine).append(FIELD_SEPARATOR).append(raw, 1, raw.length());
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
    private int currentBlockId() {
        return blocks.size() + 1;
    }
    private void closeRun(List<Integer> runDeleted, List<Integer> runAdded) {
        if (BooleanUtils.or(new boolean[] { CollectionUtils.isNotEmpty(runDeleted), CollectionUtils.isNotEmpty(runAdded) })) {
            blocks.add(new ChangeBlock(BLOCK_ID_PREFIX + (blocks.size() + 1), List.copyOf(runDeleted), List.copyOf(runAdded)));
            runDeleted.clear();
            runAdded.clear();
        }
    }
    public static UnifiedDiffLines parse(String diff, IneffectiveLineFilter filter) {
        return new UnifiedDiffLines(diff, filter);
    }
    /**
     * Mirrors {@code SubmissionToRequestMapper.DiffStats} exactly — the per-file
     * {@code linesAdded}/{@code linesDeleted} targets subtract blank, comment, and import lines per
     * the file's {@link IneffectiveLineFilter}, so candidate enumeration must apply the identical
     * rules or block sums drift from the targets.
     */
    private static boolean isCandidate(String content, Predicate<String> ineffective) {
        String trimmed = content.trim();
        boolean skip = BooleanUtils.or(new boolean[] { trimmed.isEmpty(), ineffective.test(trimmed) });
        return BooleanUtils.negate(skip);
    }

    @Value
    public static class ChangeBlock {
        String id;
        List<Integer> deletedLines;
        List<Integer> addedLines;
    }
}
