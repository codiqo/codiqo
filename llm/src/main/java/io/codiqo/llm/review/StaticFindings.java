package io.codiqo.llm.review;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.diff.EffectiveLineParser;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.llm.FindingKey;
import io.codiqo.llm.StaticAnalysisLists;
import lombok.experimental.UtilityClass;

/**
 * The static-analysis findings the triage is asked about: those touching a line the commit added, the same test the
 * server applies when it marks a diagnostic {@code introducedInCommit}, from the tools the file's language puts to the
 * triage ({@link ReviewLanguage#triagedTools}) and the static analysis review has lists for ({@link StaticAnalysisLists}).
 */
@UtilityClass
public class StaticFindings {
    public List<StaticFinding> introduced(AnalysisSubmissionModel submission, Collection<ReviewLanguage> languages) {
        List<StaticFinding> toReturn = Lists.newArrayList();
        Set<FindingKey> seen = Sets.newHashSet();
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            Set<String> tools = ReviewLanguages.of(file.getPath(), languages).triagedTools().stream()
                    .filter(tool -> StaticAnalysisLists.of(tool).isPresent())
                    .collect(Collectors.toSet());
            if (BooleanUtils.and(new boolean[] { Objects.nonNull(file.getDiff()), CollectionUtils.isNotEmpty(tools) })) {
                Set<Integer> added = EffectiveLineParser.parseAddedLines(file.getDiff());
                for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                    for (DiagnosticModel diagnostic : CollectionUtils.emptyIfNull(unit.getDiagnostics())) {
                        if (BooleanUtils.and(new boolean[] { Optional.ofNullable(diagnostic.getTool()).map(DiagnosticModel.ToolEnum::getValue).filter(tools::contains).isPresent(), Objects.nonNull(diagnostic.getLocation()) })) {
                            int start = diagnostic.getLocation().getStartLine();
                            int end = Math.max(start, diagnostic.getLocation().getEndLine());
                            if (touches(added, start, end) && seen.add(FindingKey.of(diagnostic.getTool().getValue(), diagnostic.getRuleId(), file.getPath(), start))) {
                                String severity = Objects.isNull(diagnostic.getSeverity()) ? null : diagnostic.getSeverity().getValue();
                                toReturn.add(new StaticFinding(diagnostic.getTool().getValue(), diagnostic.getRuleId(), severity, file.getPath(), start, diagnostic.getMessage()));
                            }
                        }
                    }
                }
            }
        }
        return toReturn;
    }
    private static boolean touches(Set<Integer> added, int start, int end) {
        for (int line = start; line <= end; line++) {
            if (added.contains(line)) {
                return true;
            }
        }
        return false;
    }
}
