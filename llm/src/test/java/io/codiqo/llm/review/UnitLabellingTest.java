package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.llm.schema.LlmScoringResponse.CodeBlockCategory;

class UnitLabellingTest {
    /** only added and changed units are listed, numbered in submission order, with the analysis's own name and lines */
    @Test
    void theChecklistListsTheAddedAndChangedUnits() {
        CodeUnitModel handle = unit("handle(Request)", CodeUnitModel.OperationEnum.MODIFY, 12, 30);
        CodeUnitModel removed = unit("old()", CodeUnitModel.OperationEnum.DELETE, 5, 9);
        CodeUnitModel build = unit("build()", CodeUnitModel.OperationEnum.NEW, 41, 44);

        UnitLabelling.Checklist checklist = UnitLabelling.checklist(submission(new FileChangeModel().path("a/Service.java").codeUnits(List.of(handle, removed, build))));

        assertEquals(List.of(new ReviewUnit("U1", "a/Service.java", "handle(Request)", "MODIFY", "12-30"), new ReviewUnit("U2", "a/Service.java", "build()", "NEW", "41-44")),
                checklist.getListed());
        assertEquals(build, checklist.getUnits().get("U2"));
    }
    /** a label reaches the unit behind its id, a unit labelled before the review keeps its label, and a reason is kept only above MECHANICAL */
    @Test
    void labelsReachTheirUnitsById() {
        CodeUnitModel handle = unit("handle(Request)", CodeUnitModel.OperationEnum.MODIFY, 12, 30);
        CodeUnitModel build = unit("build()", CodeUnitModel.OperationEnum.NEW, 41, 44);
        CodeUnitModel kept = unit("kept()", CodeUnitModel.OperationEnum.NEW, 50, 52).category(CodeUnitModel.CategoryEnum.INTRICATE);
        UnitLabelling.Checklist checklist = UnitLabelling.checklist(submission(new FileChangeModel().path("a/Service.java").codeUnits(List.of(handle, build, kept))));
        UnitLabels labels = labels(
                label("U1", CodeBlockCategory.SUBSTANTIVE, "a new retry path"),
                label("U2", CodeBlockCategory.MECHANICAL, "a builder"),
                label("U3", CodeBlockCategory.MECHANICAL, null));

        assertEquals(List.of(), UnitLabelling.problems(checklist.getListed(), labels));
        UnitLabelling.Applied applied = UnitLabelling.apply(checklist, labels);

        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, handle.getCategory());
        assertEquals("a new retry path", handle.getCategoryReason());
        assertEquals(CodeUnitModel.CategoryEnum.MECHANICAL, build.getCategory());
        assertNull(build.getCategoryReason());
        assertEquals(CodeUnitModel.CategoryEnum.INTRICATE, kept.getCategory());
        assertEquals(3, applied.getUnits());
        assertEquals(2, applied.getLabelled());
    }
    /** anything but one label with a known category per listed id is named, so the review fails on it */
    @Test
    void anAnswerThatIsNotOneLabelPerUnitIsNamed() {
        List<ReviewUnit> listed = List.of(
                new ReviewUnit("U1", "a/Service.java", "handle(Request)", "MODIFY", "12-30"),
                new ReviewUnit("U2", "a/Service.java", "build()", "NEW", "41-44"),
                new ReviewUnit("U3", "a/Service.java", "kept()", "NEW", "50-52"));
        UnitLabels nullList = new UnitLabels();
        nullList.setLabels(null);
        List<UnitLabels.UnitLabel> withNull = Lists.newArrayList(label("U1", CodeBlockCategory.ROUTINE, "glue"), null);
        UnitLabels nullEntry = new UnitLabels();
        nullEntry.setLabels(withNull);

        assertEquals(List.of("no \"labels\" list"), UnitLabelling.problems(listed, nullList));
        assertEquals(List.of("a null label", "not labelled: [U2, U3]"), UnitLabelling.problems(listed, nullEntry));
        assertEquals(List.of("U1 is labelled twice", "U2 has no known category", " U3 is not a listed id", "U9 is not a listed id", "not labelled: [U3]"),
                UnitLabelling.problems(listed, labels(
                        label("U1", CodeBlockCategory.ROUTINE, "glue"),
                        label("U1", CodeBlockCategory.INTRICATE, "a lock"),
                        label("U2", null, "unknown category"),
                        label(" U3", CodeBlockCategory.MECHANICAL, null),
                        label("U9", CodeBlockCategory.ROUTINE, "not listed"))));
    }
    private static AnalysisSubmissionModel submission(FileChangeModel file) {
        return new AnalysisSubmissionModel().addFilesItem(file);
    }
    private static CodeUnitModel unit(String name, CodeUnitModel.OperationEnum operation, int start, int end) {
        return new CodeUnitModel().name(name).operation(operation).location(new LocationModel().startLine(start).endLine(end));
    }
    private static UnitLabels labels(UnitLabels.UnitLabel... labels) {
        UnitLabels toReturn = new UnitLabels();
        toReturn.setLabels(List.of(labels));
        return toReturn;
    }
    private static UnitLabels.UnitLabel label(String id, CodeBlockCategory category, String reason) {
        UnitLabels.UnitLabel toReturn = new UnitLabels.UnitLabel();
        toReturn.setId(id);
        toReturn.setCategory(category);
        toReturn.setReason(reason);
        return toReturn;
    }
}
