package io.codiqo.llm;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisFinding;
import io.codiqo.llm.schema.LlmScoringResponse.StaticAnalysisReview;
import lombok.Value;
import lombok.experimental.UtilityClass;

/**
 * The one table of which static-analysis tools the static analysis review keeps lists for, keyed by the diagnostic's
 * tool value. The triage asks only about these tools' findings, places its verdicts in these lists, and the quality
 * rules judge a finding once it sits in any list of its tool. A tool a language puts to the triage without a row here
 * is not asked about: asking first and finding no list to place the verdict in afterwards would waste the triage.
 */
@UtilityClass
public class StaticAnalysisLists {
    private static final Map<String, ToolLists> BY_TOOL = ImmutableMap.of(
            DiagnosticModel.ToolEnum.PMD.getValue(),
            new ToolLists("PMD", StaticAnalysisReview::getPmdInChangedLines, StaticAnalysisReview::getPmdPreExisting, StaticAnalysisReview::getPmdFalsePositives),
            DiagnosticModel.ToolEnum.SPOTBUGS.getValue(),
            new ToolLists("SpotBugs", StaticAnalysisReview::getSpotbugsInChangedLines, StaticAnalysisReview::getSpotbugsPreExisting, StaticAnalysisReview::getSpotbugsFalsePositives));

    public Optional<ToolLists> of(String tool) {
        return Optional.ofNullable(BY_TOOL.get(tool));
    }
    public Collection<ToolLists> all() {
        return BY_TOOL.values();
    }

    @Value
    public static class ToolLists {
        String displayName;
        Function<StaticAnalysisReview, List<StaticAnalysisFinding>> inChangedLines;
        Function<StaticAnalysisReview, List<StaticAnalysisFinding>> preExisting;
        Function<StaticAnalysisReview, List<StaticAnalysisFinding>> falsePositives;

        public List<Function<StaticAnalysisReview, List<StaticAnalysisFinding>>> lists() {
            return List.of(inChangedLines, preExisting, falsePositives);
        }
    }
}
