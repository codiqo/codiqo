package io.codiqo.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.llm.lang.LanguageCapabilities;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * How far the deterministic move detector agrees with the moves the scoring LLM confirmed on real commits, measured
 * over an exported dataset ({@code -Dcodiqo.moves.dataset=<file>}: each commit's diffs and the moved lines the LLM's
 * classification kept). Not a regression test: it reports the numbers that decide whether the LLM's confirmation can
 * be dropped, and skips without the dataset.
 *
 * <p>Three counts, in changed lines: lines the detector proposes and the LLM kept (agreement), lines it proposes and
 * the LLM dropped (the semantic backstop at work), and lines the LLM marked moved that the detector never proposed
 * (the companion lines and extra pairs the LLM adds).
 */
class MoveDetectionAgreementTest {
    private static final int REJECTED_SAMPLE = 40;
    private static final int EXAMPLES_PER_KIND = 6;
    private static final List<ImmutablePair<String, Pattern>> KINDS = List.of(
            ImmutablePair.of("import or package", Pattern.compile("^(import|package)\\b.*")),
            ImmutablePair.of("annotation", Pattern.compile("^@\\w+.*")),
            ImmutablePair.of("brackets only", Pattern.compile("^[\\s{}()\\[\\];,]*$")),
            ImmutablePair.of("logging", Pattern.compile(".*\\b(log|logger|LOG)\\.\\w+\\(.*")),
            ImmutablePair.of("assertion", Pattern.compile(".*\\b(assert\\w*|verify|expect\\w*)\\(.*")),
            ImmutablePair.of("return or jump", Pattern.compile("^(return|break|continue|throw)\\b.*")));

    @Test
    void reportAgreementWithTheLlmsConfirmedMoves() throws Exception {
        String dataset = System.getProperty("codiqo.moves.dataset");
        Assumptions.assumeTrue(StringUtils.isNotBlank(dataset), "needs -Dcodiqo.moves.dataset");

        List<Commit> commits = JsonMapper.builder().build().readValue(new File(dataset), new TypeReference<List<Commit>>() {});
        MovedLineDetector detector = new MovedLineDetector(new RunArgs());

        long agreed = 0;
        long detectorOnly = 0;
        long llmOnly = 0;
        long stored = 0;
        long changed = 0;
        int rejectedShown = 0;
        long ruleAgreedTotal = 0;
        long ruleOnlyTotal = 0;
        long ruleMissedTotal = 0;
        Map<String, List<String>> rejectedByKind = Maps.newTreeMap();
        Map<String, Integer> rejectedSameFile = Maps.newTreeMap();
        Map<String, Integer> rejectedByShape = Maps.newTreeMap();
        Map<String, Integer> confirmedByShape = Maps.newTreeMap();
        for (Commit commit : commits) {
            List<FileChange> files = Lists.newArrayList();
            Set<String> storedMoved = Sets.newHashSet();
            for (StoredFile file : commit.getFiles()) {
                FileChangeModel model = new FileChangeModel().path(file.getPath()).diff(file.getDiff());
                // the analysis resolves the language; only Java gets the line filter, so that is the one that matters
                if ("java".equals(FilenameUtils.getExtension(file.getPath()))) {
                    model.setLanguage(FileChangeModel.LanguageEnum.JAVA);
                }
                files.add(FileChange.builder()
                        .path(file.getPath())
                        .diff(file.getDiff())
                        .lineFilter(LanguageCapabilities.filterFor(model))
                        .linesJustificationRequired(LanguageCapabilities.requiresDiffClassification(model))
                        .build());
                file.getMovedAdded().forEach(line -> storedMoved.add(file.getPath() + "+" + line));
                file.getMovedDeleted().forEach(line -> storedMoved.add(file.getPath() + "-" + line));
                changed += StringUtils.countMatches(file.getDiff(), "\n+") + StringUtils.countMatches(file.getDiff(), "\n-");
            }

            Set<String> proposed = Sets.newHashSet();
            List<MovedLineDetector.MoveCandidate> candidates = detector.detect(LlmScoringRequest.builder().fileChanges(files).build());
            for (MovedLineDetector.MoveCandidate candidate : candidates) {
                String from = candidate.getFromFile() + "-" + candidate.getFromLine();
                String to = candidate.getToFile() + "+" + candidate.getToLine();
                proposed.add(from);
                proposed.add(to);
                if (BooleanUtils.and(new boolean[] { !storedMoved.contains(from), !storedMoved.contains(to) })) {
                    String kind = kindOf(candidate.getContent());
                    rejectedByKind.computeIfAbsent(kind, k -> Lists.newArrayList()).add(String.format(Locale.ROOT, "%s%s", candidate.getFromFile().equals(candidate.getToFile()) ? "same file   " : "cross-file  ",
                            StringUtils.abbreviate(StringUtils.strip(candidate.getContent()), 100)));
                    if (candidate.getFromFile().equals(candidate.getToFile())) {
                        rejectedSameFile.merge(kind, 1, Integer::sum);
                    }
                    String shape = (candidate.getFromFile().equals(candidate.getToFile()) ? "same file" : "cross-file") + (isolated(candidate, candidates) ? ", isolated line" : ", part of a moved run");
                    rejectedByShape.merge(shape, 1, Integer::sum);
                }
                if (BooleanUtils.and(new boolean[] { storedMoved.contains(from), storedMoved.contains(to) })) {
                    confirmedByShape.merge((candidate.getFromFile().equals(candidate.getToFile()) ? "same file" : "cross-file")
                            + (isolated(candidate, candidates) ? ", isolated line" : ", part of a moved run"), 1, Integer::sum);
                }
                if (BooleanUtils.and(new boolean[] { !storedMoved.contains(from), !storedMoved.contains(to), rejectedShown < REJECTED_SAMPLE })) {
                    rejectedShown++;
                    System.out.printf(Locale.ROOT, "  dropped by the LLM: %s:%d -> %s:%d  %s%n", FilenameUtils.getName(candidate.getFromFile()), candidate.getFromLine(),
                            FilenameUtils.getName(candidate.getToFile()), candidate.getToLine(), StringUtils.abbreviate(StringUtils.strip(candidate.getContent()), 110));
                }
            }

            Set<String> confirmedByRule = Sets.newHashSet();
            for (MovedLineDetector.MoveCandidate candidate : candidates) {
                if (confirmedByRule(candidate, candidates)) {
                    confirmedByRule.add(candidate.getFromFile() + "-" + candidate.getFromLine());
                    confirmedByRule.add(candidate.getToFile() + "+" + candidate.getToLine());
                }
            }
            long ruleAgreed = confirmedByRule.stream().filter(storedMoved::contains).count();
            ruleAgreedTotal += ruleAgreed;
            ruleOnlyTotal += confirmedByRule.size() - ruleAgreed;
            ruleMissedTotal += storedMoved.size() - ruleAgreed;

            long commitAgreed = proposed.stream().filter(storedMoved::contains).count();
            agreed += commitAgreed;
            detectorOnly += proposed.size() - commitAgreed;
            llmOnly += storedMoved.size() - commitAgreed;
            stored += storedMoved.size();
            System.out.printf(Locale.ROOT, "analysis %s: llm kept %d moved lines, detector proposes %d, agreed %d%n",
                    commit.getAnalysisId(), storedMoved.size(), proposed.size(), commitAgreed);
        }

        System.out.printf(Locale.ROOT, "%n%d commits, %d changed lines, %d lines the LLM kept as moved%n", commits.size(), changed, stored);
        System.out.printf(Locale.ROOT, "agreed: %d (%.0f%% of the LLM's)%n", agreed, 100.0 * agreed / Math.max(1, stored));
        System.out.printf(Locale.ROOT, "detector only (LLM dropped): %d (%.2f%% of changed lines)%n", detectorOnly, 100.0 * detectorOnly / Math.max(1, changed));
        System.out.printf(Locale.ROOT, "LLM only (detector never proposed): %d (%.2f%% of changed lines)%n", llmOnly, 100.0 * llmOnly / Math.max(1, changed));
        System.out.printf(Locale.ROOT, "%nrule (all but repeated cross-file text%.0s): agreed %d (%.0f%%), rule only %d (%.2f%%), missed %d (%.2f%%)%n",
                StringUtils.EMPTY, ruleAgreedTotal, 100.0 * ruleAgreedTotal / Math.max(1, stored), ruleOnlyTotal, 100.0 * ruleOnlyTotal / Math.max(1, changed),
                ruleMissedTotal, 100.0 * ruleMissedTotal / Math.max(1, changed));
        long rejectedTotal = rejectedByKind.values().stream().mapToLong(List::size).sum();
        System.out.printf(Locale.ROOT, "%ncandidates the LLM rejected, by what the line holds (%d pairs):%n", rejectedTotal);
        rejectedByKind.forEach((kind, lines) -> {
            System.out.printf(Locale.ROOT, "  %-18s %4d (%.0f%%), %d within one file%n", kind, lines.size(), 100.0 * lines.size() / Math.max(1, rejectedTotal),
                    rejectedSameFile.getOrDefault(kind, 0));
            lines.stream().limit(EXAMPLES_PER_KIND).forEach(line -> System.out.println("      " + line));
        });
        System.out.printf(Locale.ROOT, "%nthe same rejected pairs by shape:%n");
        rejectedByShape.forEach((shape, count) -> System.out.printf(Locale.ROOT, "  rejected  %-36s %4d%n", shape, count));
        confirmedByShape.forEach((shape, count) -> System.out.printf(Locale.ROOT, "  confirmed %-36s %4d%n", shape, count));
        assertTrue(stored > 0, "the dataset carries moved lines");
    }

    /** no other candidate moves a neighbouring line along with it: one line matching, not a body relocating */
    private static boolean isolated(MovedLineDetector.MoveCandidate candidate, List<MovedLineDetector.MoveCandidate> all) {
        return all.stream().noneMatch(other -> BooleanUtils.and(new boolean[] { other != candidate, other.getFromFile().equals(candidate.getFromFile()),
                other.getToFile().equals(candidate.getToFile()), Math.abs(other.getFromLine() - candidate.getFromLine()) <= 2, Math.abs(other.getToLine() - candidate.getToLine()) <= 2 }));
    }
    private static String kindOf(String content) {
        String line = StringUtils.strip(content);
        return KINDS.stream().filter(kind -> kind.getRight().matcher(line).matches()).map(ImmutablePair::getLeft).findFirst().orElse("other code");
    }
    /** every candidate, except a cross-file match of text the commit's candidates carry more than once: boilerplate */
    private static boolean confirmedByRule(MovedLineDetector.MoveCandidate candidate, List<MovedLineDetector.MoveCandidate> all) {
        if (candidate.getFromFile().equals(candidate.getToFile())) {
            return true;
        }
        String text = StringUtils.normalizeSpace(candidate.getContent());
        return all.stream().filter(other -> text.equals(StringUtils.normalizeSpace(other.getContent()))).count() < 2;
    }
    @Data
    @NoArgsConstructor
    static class Commit {
        private String analysisId;
        private List<StoredFile> files = Lists.newArrayList();
    }

    @Data
    @NoArgsConstructor
    static class StoredFile {
        private String path;
        private String diff;
        private List<Integer> movedAdded = Lists.newArrayList();
        private List<Integer> movedDeleted = Lists.newArrayList();
        private List<Integer> cosmeticAdded = Lists.newArrayList();
        private List<Integer> cosmeticDeleted = Lists.newArrayList();
        private List<Integer> collapsedAdded = Lists.newArrayList();
    }
}
