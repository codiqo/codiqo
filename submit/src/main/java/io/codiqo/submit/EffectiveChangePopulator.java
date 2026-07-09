package io.codiqo.submit;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;


import io.codiqo.api.diff.EffectiveLineParser;
import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.api.diff.JavaInvocationCounter;
import io.codiqo.api.diff.NestedBlockRanges;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitModel.OperationEnum;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.llm.lang.LanguageCapabilities;

public class EffectiveChangePopulator implements SubmissionPopulator {
    private static final EnumSet<SymbolKindModel> METHOD_OR_CONSTRUCTOR = EnumSet.of(SymbolKindModel.METHOD, SymbolKindModel.CONSTRUCTOR);

    @Override
    public void accept(SubmissionContext ctx) {
        for (FileChangeModel file : ctx.getSubmissionModel().getFiles()) {
            String diff = file.getDiff();
            if (StringUtils.isEmpty(diff)) {
                continue;
            }
            IneffectiveLineFilter filter = LanguageCapabilities.filterFor(file);
            Predicate<String> addedIneffective = filter.commentFilter();
            Predicate<String> deletedIneffective = filter.commentOrImportFilter();
            boolean trackDeleted = BooleanUtils.negate(filter.isNone());

            Set<Integer> allAddedLines = new HashSet<>();
            Set<Integer> effectiveAddedLines = new HashSet<>();
            Map<Integer, List<String>> effectiveDeletedContents = new HashMap<>();
            /**
             * one pass over the diff populates all three line sets — raw added lines (new-method detection),
             * effective added lines (changed-line counting), and effective deleted line contents (deleted-
             * invocation counting) — instead of parsing the patch two or three times.
             */
            EffectiveLineParser.walk(diff, (kind, newLine, content) -> {
                if (kind == EffectiveLineParser.LineKind.ADDED) {
                    allAddedLines.add(newLine);
                    String trimmed = content.trim();
                    if (EffectiveLineParser.isEffective(trimmed, addedIneffective)) {
                        effectiveAddedLines.add(newLine);
                    }
                } else if (kind == EffectiveLineParser.LineKind.DELETED) {
                    if (trackDeleted) {
                        String trimmed = content.trim();
                        if (EffectiveLineParser.isEffective(trimmed, deletedIneffective)) {
                            effectiveDeletedContents.computeIfAbsent(newLine, k -> new ArrayList<>()).add(trimmed);
                        }
                    }
                }
            });

            List<int[]> blockRanges = collectBlockRanges(file.getCodeUnits());

            for (CodeUnitModel codeUnit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (codeUnit.getOperation() == OperationEnum.MODIFY) {
                    LocationModel location = codeUnit.getLocation();
                    if (Objects.isNull(location) || Objects.isNull(location.getStartLine()) || Objects.isNull(location.getEndLine())) {
                        continue;
                    }
                    int startLine = location.getStartLine();
                    int endLine = location.getEndLine();
                    if (BooleanUtils.or(new boolean[] { startLine <= 0, endLine < startLine })) {
                        continue;
                    }

                    /**
                     * a method whose entire line span is added AND has no deletions anchored in it did not
                     * exist in the parent — it is new code inside a modified file, so it scores as NEW (full
                     * body via DriverScore.forNew), not MODIFY. Requiring no in-range deletions excludes a
                     * fully-rewritten existing method (whose old body shows up as deletions), keeping it MODIFY.
                     */
                    if (isWhollyAdded(allAddedLines, startLine, endLine)) {
                        if (isPureInsertion(effectiveDeletedContents, startLine, endLine)) {
                            codeUnit.setOperation(OperationEnum.NEW);
                            continue;
                        }
                    }

                    List<int[]> nestedRanges = NestedBlockRanges.nestedWithin(startLine, endLine, blockRanges);
                    codeUnit.setEffectiveLinesChanged(countInRange(effectiveAddedLines, startLine, endLine, nestedRanges));

                    List<Integer> invocationLines = Collections.emptyList();
                    MetricsModel metrics = codeUnit.getMetrics();
                    if (Objects.nonNull(metrics) && CollectionUtils.isNotEmpty(metrics.getDirectInvocationLines())) {
                        invocationLines = metrics.getDirectInvocationLines();
                    }

                    /**
                     * added invocations come from the AST list where same-type fluent chains collapse to one,
                     * while deleted invocations are regex-counted per call — so a chain rewrite counts the two
                     * sides at different granularities. Bounded to delete+add rewrites; the regex path cannot
                     * resolve types to match the collapse (see JavaInvocationCounter).
                     */
                    int addedInvocations = countInvocationsInChangedRange(invocationLines, effectiveAddedLines, startLine, endLine, nestedRanges);
                    int deletedInvocations = countDeletedInvocationsInRange(effectiveDeletedContents, startLine, endLine, nestedRanges);
                    codeUnit.setEffectiveInvocationsChanged(addedInvocations + deletedInvocations);
                }
            }
        }
    }
    private static List<int[]> collectBlockRanges(List<CodeUnitModel> codeUnits) {
        List<int[]> toReturn = new ArrayList<>();
        for (CodeUnitModel codeUnit : CollectionUtils.emptyIfNull(codeUnits)) {
            if (Boolean.TRUE.equals(codeUnit.getIsTrivial())) {
                continue;
            }
            if (METHOD_OR_CONSTRUCTOR.contains(codeUnit.getKind())) {
                LocationModel location = codeUnit.getLocation();
                if (Objects.nonNull(location) && Objects.nonNull(location.getStartLine()) && Objects.nonNull(location.getEndLine())) {
                    toReturn.add(new int[] { location.getStartLine(), location.getEndLine() });
                }
            }
        }
        return toReturn;
    }
    private static boolean isWhollyAdded(Set<Integer> addedLines, int startLine, int endLine) {
        for (int line = startLine; line <= endLine; line++) {
            if (BooleanUtils.negate(addedLines.contains(line))) {
                return false;
            }
        }
        return true;
    }
    private static boolean isPureInsertion(Map<Integer, List<String>> deletedContents, int startLine, int endLine) {
        for (int anchor : deletedContents.keySet()) {
            if (BooleanUtils.and(new boolean[] { anchor >= startLine, anchor <= endLine + 1 })) {
                return false;
            }
        }
        return true;
    }
    private static int countInRange(Set<Integer> lines, int startLine, int endLine, List<int[]> nestedRanges) {
        int count = 0;
        for (int line = startLine; line <= endLine; line++) {
            if (BooleanUtils.and(new boolean[] { lines.contains(line), BooleanUtils.negate(NestedBlockRanges.coversLine(nestedRanges, line)) })) {
                count++;
            }
        }
        return count;
    }
    private static int countInvocationsInChangedRange(List<Integer> invocationLines, Set<Integer> changedLines, int startLine, int endLine, List<int[]> nestedRanges) {
        int count = 0;
        for (Integer line : invocationLines) {
            if (Objects.isNull(line) || line < startLine || line > endLine) {
                continue;
            }
            if (BooleanUtils.and(new boolean[] { changedLines.contains(line), BooleanUtils.negate(NestedBlockRanges.coversLine(nestedRanges, line)) })) {
                count++;
            }
        }
        return count;
    }
    private static int countDeletedInvocationsInRange(Map<Integer, List<String>> deletedContents, int startLine, int endLine, List<int[]> nestedRanges) {
        int count = 0;
        for (Entry<Integer, List<String>> entry : deletedContents.entrySet()) {
            int anchor = entry.getKey();
            if (BooleanUtils.or(new boolean[] { anchor < startLine, anchor > endLine + 1, NestedBlockRanges.coversAnchor(nestedRanges, anchor) })) {
                continue;
            }
            for (String content : entry.getValue()) {
                count += JavaInvocationCounter.countInLine(content);
            }
        }
        return count;
    }
}
