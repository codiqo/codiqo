package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

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
    /**
     * A label reaches the unit behind its id; an id that was never given is reported, a unit labelled before the review
     * keeps its label, and a reason is kept only above MECHANICAL.
     */
    @Test
    void labelsReachTheirUnitsByIdOnly() {
        CodeUnitModel handle = unit("handle(Request)", CodeUnitModel.OperationEnum.MODIFY, 12, 30);
        CodeUnitModel build = unit("build()", CodeUnitModel.OperationEnum.NEW, 41, 44);
        CodeUnitModel kept = unit("kept()", CodeUnitModel.OperationEnum.NEW, 50, 52).category(CodeUnitModel.CategoryEnum.INTRICATE);
        UnitLabelling.Checklist checklist = UnitLabelling.checklist(submission(new FileChangeModel().path("a/Service.java").codeUnits(List.of(handle, build, kept))));

        UnitLabelling.Applied applied = UnitLabelling.apply(checklist, labels(
                label(" U1 ", CodeBlockCategory.SUBSTANTIVE, "a new retry path"),
                label("U2", CodeBlockCategory.MECHANICAL, "a builder"),
                label("U3", CodeBlockCategory.MECHANICAL, null),
                label("U9", CodeBlockCategory.ROUTINE, "not listed")));

        assertEquals(CodeUnitModel.CategoryEnum.SUBSTANTIVE, handle.getCategory());
        assertEquals("a new retry path", handle.getCategoryReason());
        assertEquals(CodeUnitModel.CategoryEnum.MECHANICAL, build.getCategory());
        assertNull(build.getCategoryReason());
        assertEquals(CodeUnitModel.CategoryEnum.INTRICATE, kept.getCategory());
        assertEquals(3, applied.getUnits());
        assertEquals(2, applied.getLabelled());
        assertEquals(List.of("U9"), applied.getUnknownIds());
    }
    /** a label repeated for one id keeps its last category, as the scoring does */
    @Test
    void aRepeatedIdKeepsItsLastLabel() {
        CodeUnitModel handle = unit("handle(Request)", CodeUnitModel.OperationEnum.MODIFY, 12, 30);
        UnitLabelling.Checklist checklist = UnitLabelling.checklist(submission(new FileChangeModel().path("a/Service.java").codeUnits(List.of(handle))));

        UnitLabelling.apply(checklist, labels(label("U1", CodeBlockCategory.ROUTINE, "glue"), label("U1", CodeBlockCategory.INTRICATE, "a lock")));

        assertEquals(CodeUnitModel.CategoryEnum.INTRICATE, handle.getCategory());
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
