package io.codiqo.maven.populator;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.collections4.CollectionUtils;

import com.google.common.collect.Lists;

import io.codiqo.api.diff.EffectiveLineParser;
import io.codiqo.api.diff.JavaInvocationCounter;
import io.codiqo.api.diff.JavaLineFilters;
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
            if (Objects.isNull(diff) || diff.isEmpty()) {
                continue;
            }
            boolean requiresLineFiltering = LanguageCapabilities.requiresLineFiltering(file);
            Predicate<String> addedIneffective = requiresLineFiltering ? JavaLineFilters.COMMENT : JavaLineFilters.NONE;
            Predicate<String> deletedIneffective = requiresLineFiltering ? JavaLineFilters.COMMENT_OR_IMPORT : JavaLineFilters.NONE;
            Set<Integer> effectiveAddedLines = EffectiveLineParser.parseEffectiveAddedLines(diff, addedIneffective);
            Map<Integer, List<String>> effectiveDeletedContents = requiresLineFiltering ? EffectiveLineParser.parseEffectiveDeletedLineContents(diff, deletedIneffective) : Collections.emptyMap();

            List<int[]> blockRanges = collectBlockRanges(file.getCodeUnits());

            for (CodeUnitModel codeUnit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (codeUnit.getOperation() != OperationEnum.MODIFY) {
                    continue;
                }
                LocationModel location = codeUnit.getLocation();
                if (Objects.isNull(location) || Objects.isNull(location.getStartLine()) || Objects.isNull(location.getEndLine())) {
                    continue;
                }
                int startLine = location.getStartLine();
                int endLine = location.getEndLine();
                if (startLine <= 0 || endLine < startLine) {
                    continue;
                }

                List<int[]> nestedRanges = NestedBlockRanges.nestedWithin(startLine, endLine, blockRanges);
                codeUnit.setEffectiveLinesChanged(countInRange(effectiveAddedLines, startLine, endLine, nestedRanges));

                List<Integer> invocationLines = Collections.emptyList();
                MetricsModel metrics = codeUnit.getMetrics();
                if (Objects.nonNull(metrics) && CollectionUtils.isNotEmpty(metrics.getDirectInvocationLines())) {
                    invocationLines = metrics.getDirectInvocationLines();
                }
                int addedInvocations = countInvocationsInChangedRange(invocationLines, effectiveAddedLines, startLine, endLine, nestedRanges);
                int deletedInvocations = countDeletedInvocationsInRange(effectiveDeletedContents, startLine, endLine, nestedRanges);
                codeUnit.setEffectiveInvocationsChanged(addedInvocations + deletedInvocations);
            }
        }
    }
    private static List<int[]> collectBlockRanges(List<CodeUnitModel> codeUnits) {
        List<int[]> toReturn = Lists.newArrayList();
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
    private static int countInRange(Set<Integer> lines, int startLine, int endLine, List<int[]> nestedRanges) {
        int count = 0;
        for (int line = startLine; line <= endLine; line++) {
            if (lines.contains(line) && !NestedBlockRanges.coversLine(nestedRanges, line)) {
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
            if (changedLines.contains(line) && !NestedBlockRanges.coversLine(nestedRanges, line)) {
                count++;
            }
        }
        return count;
    }
    private static int countDeletedInvocationsInRange(Map<Integer, List<String>> deletedContents, int startLine, int endLine, List<int[]> nestedRanges) {
        int count = 0;
        for (Entry<Integer, List<String>> entry : deletedContents.entrySet()) {
            int anchor = entry.getKey();
            if (anchor < startLine || anchor > endLine + 1 || NestedBlockRanges.coversAnchor(nestedRanges, anchor)) {
                continue;
            }
            for (String content : entry.getValue()) {
                count += JavaInvocationCounter.countInLine(content);
            }
        }
        return count;
    }
}
