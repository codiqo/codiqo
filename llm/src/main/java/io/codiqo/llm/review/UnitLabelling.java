package io.codiqo.llm.review;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

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
     * What keeps an answer from being one label per listed unit, as the prompt asks: no list, a null entry, a category
     * that is not one of the four (read as null, since unknown enum values are read leniently), an id that was never
     * listed or is labelled twice, and a listed id left without a label. Empty for an answer {@link #apply} can take as
     * it is.
     */
    public List<String> problems(List<ReviewUnit> listed, UnitLabels labels) {
        if (Objects.isNull(labels.getLabels())) {
            return List.of("no \"labels\" list");
        }

        List<String> toReturn = Lists.newArrayList();
        Set<String> unlabelled = listed.stream().map(ReviewUnit::getId).collect(Collectors.toCollection(Sets::newLinkedHashSet));
        Set<String> seen = Sets.newHashSet();
        for (UnitLabels.UnitLabel label : labels.getLabels()) {
            if (Objects.isNull(label)) {
                toReturn.add("a null label");
            } else if (seen.add(label.getId())) {
                if (unlabelled.remove(label.getId())) {
                    if (Objects.isNull(label.getCategory())) {
                        toReturn.add(label.getId() + " has no known category");
                    }
                } else {
                    toReturn.add(label.getId() + " is not a listed id");
                }
            } else {
                toReturn.add(label.getId() + " is labelled twice");
            }
        }
        if (CollectionUtils.isNotEmpty(unlabelled)) {
            toReturn.add("not labelled: " + unlabelled);
        }
        return toReturn;
    }
    /**
     * Gives each listed unit its label, unless the unit was labelled before the review. A reason is kept only above
     * MECHANICAL, where one is asked for. The labels are an answer {@link #problems} found nothing wrong with.
     */
    public Applied apply(Checklist checklist, UnitLabels labels) {
        int labelled = 0;
        for (UnitLabels.UnitLabel label : labels.getLabels()) {
            CodeUnitModel unit = checklist.getUnits().get(label.getId());
            if (Objects.isNull(unit.getCategory())) {
                unit.setCategory(CodeUnitModel.CategoryEnum.fromValue(label.getCategory().name()));
                if (label.getCategory() != LlmScoringResponse.CodeBlockCategory.MECHANICAL) {
                    unit.setCategoryReason(StringUtils.trimToNull(label.getReason()));
                }
                labelled++;
            }
        }
        return new Applied(checklist.getUnits().size(), labelled);
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

    /** the checklist's size and the units the answer labelled */
    public static final class Applied extends ImmutablePair<Integer, Integer> {
        private Applied(int units, int labelled) {
            super(units, labelled);
        }
        public int getUnits() {
            return getLeft();
        }
        public int getLabelled() {
            return getRight();
        }
    }
}
