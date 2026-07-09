package io.codiqo.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections4.Bag;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.bag.HashBag;
import org.apache.commons.lang3.StringUtils;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import lombok.Value;

/**
 * Deterministic commit-wide detector of relocated lines: deleted lines whose content reappears
 * near-identically as an added line elsewhere in the commit (same file or a different one) — the
 * signature of code moved into a new method, class, or file. Candidates are offered to the LLM as
 * hints ({@code user-message.txt}); only confirmed ids ({@code confirmedMoveIds}) are discounted.
 * <p>
 * Similarity is multiset containment {@code |tokens(A)∩tokens(B)| / min(|A|,|B|)} — deliberately
 * NOT Dice/Jaccard. Relocation typically only adds tokens (re-qualified receivers:
 * {@code props.X} → {@code channel.props.X}), so a genuine move scores 1.0 under containment
 * while symmetric metrics fall below the threshold on short lines. Over-matching is bounded by a
 * size-ratio guard and a minimum-token filter; the LLM confirmation is the semantic backstop.
 * <p>
 * Matching is greedy 1:1 in (file, line) order over {@link UnifiedDiffLines} candidate lines, so
 * results are deterministic for a given request and threshold and can be recomputed at every
 * consumption point (prompt build, validation, derivation) instead of being threaded as state.
 */
public class MovedLineDetector {
    private static final Pattern WORD_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|\"[^\"]*\"|'[^']*'");
    private static final int MIN_INFORMATIVE_TOKENS = 3;
    private static final double SIZE_RATIO_MIN = 0.5;
    private static final String CANDIDATE_ID_PREFIX = "M";

    private final boolean enabled;
    private final double similarityThreshold;

    public MovedLineDetector(RunArgs args) {
        this.enabled = args.isMoveDetectionEnabled();
        this.similarityThreshold = args.getMoveSimilarityThreshold();
    }
    public List<MoveCandidate> detect(LlmScoringRequest request) {
        List<MoveCandidate> toReturn = new ArrayList<>();
        if (enabled && Objects.nonNull(request) && CollectionUtils.isNotEmpty(request.getFileChanges())) {
            List<LineEntry> deleted = new ArrayList<>();
            List<LineEntry> added = new ArrayList<>();
            for (FileChange fc : request.getFileChanges()) {
                if (fc.isLinesJustificationRequired() && StringUtils.isNotBlank(fc.getDiff())) {
                    UnifiedDiffLines diffLines = UnifiedDiffLines.parse(fc.getDiff(), fc.getLineFilter());
                    collectEntries(fc.getPath(), diffLines.getCandidateDeletedContent(), deleted);
                    collectEntries(fc.getPath(), diffLines.getCandidateAddedContent(), added);
                }
            }

            boolean[] consumed = new boolean[added.size()];
            for (LineEntry del : deleted) {
                int best = findBestMatch(del, added, consumed);
                if (best >= 0) {
                    consumed[best] = true;
                    LineEntry add = added.get(best);
                    toReturn.add(new MoveCandidate(
                            CANDIDATE_ID_PREFIX + (toReturn.size() + 1),
                            del.getFile(),
                            del.getLine(),
                            add.getFile(),
                            add.getLine(),
                            containment(del.getTokens(), add.getTokens()),
                            del.getContent()));
                }
            }
        }
        return toReturn;
    }
    private int findBestMatch(LineEntry deleted, List<LineEntry> added, boolean[] consumed) {
        int toReturn = -1;
        double bestSimilarity = 0.0;
        boolean bestSameFile = false;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < added.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            LineEntry candidate = added.get(i);
            if (isSizeCompatible(deleted.getTokens(), candidate.getTokens())) {
                double similarity = containment(deleted.getTokens(), candidate.getTokens());
                if (similarity >= similarityThreshold) {
                    boolean sameFile = deleted.getFile().equals(candidate.getFile());
                    int distance = sameFile ? Math.abs(candidate.getLine() - deleted.getLine()) : Integer.MAX_VALUE;
                    if (isBetterMatch(similarity, sameFile, distance, bestSimilarity, bestSameFile, bestDistance, toReturn)) {
                        toReturn = i;
                        bestSimilarity = similarity;
                        bestSameFile = sameFile;
                        bestDistance = distance;
                    }
                }
            }
        }
        return toReturn;
    }
    private static void collectEntries(String file, Map<Integer, String> contentByLine, List<LineEntry> target) {
        for (Map.Entry<Integer, String> entry : contentByLine.entrySet()) {
            Bag<String> tokens = tokenize(entry.getValue());
            if (tokens.size() >= MIN_INFORMATIVE_TOKENS) {
                target.add(new LineEntry(file, entry.getKey(), entry.getValue().trim(), tokens));
            }
        }
    }
    private static boolean isBetterMatch(
            double similarity,
            boolean sameFile,
            int distance,
            double bestSimilarity,
            boolean bestSameFile,
            int bestDistance,
            int currentBest) {
        if (currentBest < 0) {
            return true;
        }
        if (similarity != bestSimilarity) {
            return similarity > bestSimilarity;
        }
        if (sameFile != bestSameFile) {
            return sameFile;
        }
        return distance < bestDistance;
    }
    private static boolean isSizeCompatible(Bag<String> a, Bag<String> b) {
        return (double) Math.min(a.size(), b.size()) / Math.max(a.size(), b.size()) >= SIZE_RATIO_MIN;
    }
    private static double containment(Bag<String> a, Bag<String> b) {
        return (double) CollectionUtils.intersection(a, b).size() / Math.min(a.size(), b.size());
    }
    private static Bag<String> tokenize(String content) {
        Bag<String> toReturn = new HashBag<>();

        Matcher matcher = WORD_TOKEN.matcher(content);
        while (matcher.find()) {
            toReturn.add(matcher.group());
        }
        return toReturn;
    }

    @Value
    public static class MoveCandidate {
        String id;
        String fromFile;
        int fromLine;
        String toFile;
        int toLine;
        double similarity;
        String content;
    }

    @Value
    private static class LineEntry {
        String file;
        int line;
        String content;
        Bag<String> tokens;
    }
}
