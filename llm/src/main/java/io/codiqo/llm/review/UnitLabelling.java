package io.codiqo.llm.review;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.experimental.UtilityClass;

/**
 * Labels by id instead of by name: after the build, the review is handed the submission's own units, each under an id
 * ({@code U1}, {@code U2}, ...), and answers with the ids, so a label is matched exactly and nothing parses a name a
 * model wrote. The single scoring prompt learned this first: units it was given and asked to copy matched, names it
 * derived itself did not.
 */
@UtilityClass
public class UnitLabelling {
    private static final String ID_PREFIX = "U";

    /** the submission's added and changed units, numbered in the order the submission lists them */
    public Checklist checklist(AnalysisSubmissionModel submission) {
        List<ReviewUnit> units = Lists.newArrayList();
        Map<String, CodeUnitModel> byId = Maps.newLinkedHashMap();
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (LocalReviewModels.LABELLED_OPERATIONS.contains(unit.getOperation())) {
                    String id = ID_PREFIX + (units.size() + 1);
                    units.add(new ReviewUnit(id, file.getPath(), unit.getName(), unit.getOperation().name(), lines(unit.getLocation())));
                    byId.put(id, unit);
                }
            }
        }
        return new Checklist(units, byId);
    }
    /**
     * Gives each listed unit its label, unless the unit was labelled before the review. A reason is kept only above
     * MECHANICAL, where one is asked for. An id the checklist does not hold is reported, never guessed at; a label
     * repeated for one id keeps its last category, as the scoring does.
     */
    public Applied apply(Checklist checklist, UnitLabels labels) {
        Map<String, UnitLabels.UnitLabel> byId = Maps.newLinkedHashMap();
        List<String> unknown = Lists.newArrayList();
        for (UnitLabels.UnitLabel label : CollectionUtils.emptyIfNull(labels.getLabels())) {
            if (Objects.nonNull(label.getCategory())) {
                String id = StringUtils.strip(label.getId());
                if (checklist.getUnits().containsKey(id)) {
                    byId.put(id, label);
                } else {
                    unknown.add(String.valueOf(label.getId()));
                }
            }
        }

        int labelled = 0;
        for (Map.Entry<String, UnitLabels.UnitLabel> entry : byId.entrySet()) {
            CodeUnitModel unit = checklist.getUnits().get(entry.getKey());
            if (Objects.isNull(unit.getCategory())) {
                LlmScoringResponse.CodeBlockCategory category = entry.getValue().getCategory();
                unit.setCategory(CodeUnitModel.CategoryEnum.fromValue(category.name()));
                if (category != LlmScoringResponse.CodeBlockCategory.MECHANICAL) {
                    unit.setCategoryReason(StringUtils.trimToNull(entry.getValue().getReason()));
                }
                labelled++;
            }
        }
        return new Applied(checklist.getUnits().size(), labelled, unknown);
    }
    private static String lines(LocationModel location) {
        if (Objects.isNull(location) || Objects.isNull(location.getStartLine())) {
            return "?";
        }
        return location.getStartLine() + "-" + Objects.requireNonNullElse(location.getEndLine(), location.getStartLine());
    }

    /** the units the turn is shown, and the submission's unit behind each id */
    public static final class Checklist extends ImmutablePair<List<ReviewUnit>, Map<String, CodeUnitModel>> {
        private Checklist(List<ReviewUnit> listed, Map<String, CodeUnitModel> units) {
            super(listed, units);
        }
        public List<ReviewUnit> getListed() {
            return getLeft();
        }
        public Map<String, CodeUnitModel> getUnits() {
            return getRight();
        }
    }

    /** the checklist's size, the units the answer labelled, and the ids it named that the checklist does not hold */
    public static final class Applied extends ImmutableTriple<Integer, Integer, List<String>> {
        private Applied(int units, int labelled, List<String> unknownIds) {
            super(units, labelled, unknownIds);
        }
        public int getUnits() {
            return getLeft();
        }
        public int getLabelled() {
            return getMiddle();
        }
        public List<String> getUnknownIds() {
            return getRight();
        }
    }
}
