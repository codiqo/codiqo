package io.codiqo.maven.populator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.collections4.CollectionUtils;

import io.codiqo.api.diff.EffectiveLineParser;
import io.codiqo.api.diff.JavaInvocationCounter;
import io.codiqo.api.diff.JavaLineFilters;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitModel.OperationEnum;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FileChangeModel.LanguageEnum;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;

public class EffectiveChangePopulator implements SubmissionPopulator {
    @Override
    public void accept(SubmissionContext ctx) {
        for (FileChangeModel file : ctx.getSubmissionModel().getFiles()) {
            String diff = file.getDiff();
            if (Objects.isNull(diff) || diff.isEmpty()) {
                continue;
            }
            boolean isJava = file.getLanguage() == LanguageEnum.JAVA;
            Predicate<String> addedIneffective = isJava ? JavaLineFilters.COMMENT : JavaLineFilters.NONE;
            Predicate<String> deletedIneffective = isJava ? JavaLineFilters.COMMENT_OR_IMPORT : JavaLineFilters.NONE;
            Set<Integer> effectiveAddedLines = EffectiveLineParser.parseEffectiveAddedLines(diff, addedIneffective);
            Map<Integer, List<String>> effectiveDeletedContents = isJava ? EffectiveLineParser.parseEffectiveDeletedLineContents(diff, deletedIneffective) : Collections.emptyMap();

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

                codeUnit.setEffectiveLinesChanged(countInRange(effectiveAddedLines, startLine, endLine));

                List<Integer> invocationLines = Collections.emptyList();
                MetricsModel metrics = codeUnit.getMetrics();
                if (Objects.nonNull(metrics) && CollectionUtils.isNotEmpty(metrics.getDirectInvocationLines())) {
                    invocationLines = metrics.getDirectInvocationLines();
                }
                int addedInvocations = countInvocationsInChangedRange(invocationLines, effectiveAddedLines, startLine, endLine);
                int deletedInvocations = countDeletedInvocationsInRange(effectiveDeletedContents, startLine, endLine);
                codeUnit.setEffectiveInvocationsChanged(addedInvocations + deletedInvocations);
            }
        }
    }
    private static int countInRange(Set<Integer> lines, int startLine, int endLine) {
        int count = 0;
        for (int line = startLine; line <= endLine; line++) {
            if (lines.contains(line)) {
                count++;
            }
        }
        return count;
    }
    private static int countInvocationsInChangedRange(List<Integer> invocationLines, Set<Integer> changedLines, int startLine, int endLine) {
        int count = 0;
        for (Integer line : invocationLines) {
            if (Objects.isNull(line) || line < startLine || line > endLine) {
                continue;
            }
            if (changedLines.contains(line)) {
                count++;
            }
        }
        return count;
    }
    private static int countDeletedInvocationsInRange(Map<Integer, List<String>> deletedContents, int startLine, int endLine) {
        int count = 0;
        for (Entry<Integer, List<String>> entry : deletedContents.entrySet()) {
            int anchor = entry.getKey();
            if (anchor < startLine || anchor > endLine + 1) {
                continue;
            }
            for (String content : entry.getValue()) {
                count += JavaInvocationCounter.countInLine(content);
            }
        }
        return count;
    }
}
