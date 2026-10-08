package io.codiqo.llm.review;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.diff.EffectiveLineParser;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.llm.FindingKey;
import lombok.experimental.UtilityClass;

/**
 * The PMD and SpotBugs findings the triage is asked about: those touching a line the commit added, the same test the
 * server applies when it marks a diagnostic {@code introducedInCommit}. Only these two tools have lists in the static
 * analysis review, so the others are not asked about.
 */
@UtilityClass
public class StaticFindings {
    private static final EnumSet<DiagnosticModel.ToolEnum> TRIAGED_TOOLS = EnumSet.of(DiagnosticModel.ToolEnum.PMD, DiagnosticModel.ToolEnum.SPOTBUGS);

    public List<StaticFinding> introduced(AnalysisSubmissionModel submission) {
        List<StaticFinding> toReturn = Lists.newArrayList();
        Set<FindingKey> seen = Sets.newHashSet();
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            if (Objects.nonNull(file.getDiff())) {
                Set<Integer> added = EffectiveLineParser.parseAddedLines(file.getDiff());
                for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                    for (DiagnosticModel diagnostic : CollectionUtils.emptyIfNull(unit.getDiagnostics())) {
                        if (BooleanUtils.and(new boolean[] { TRIAGED_TOOLS.contains(diagnostic.getTool()), Objects.nonNull(diagnostic.getLocation()) })) {
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
