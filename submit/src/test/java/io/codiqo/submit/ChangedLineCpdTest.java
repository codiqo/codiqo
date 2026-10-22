package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;

class ChangedLineCpdTest {
    @Test
    void theSharesTravelWithTheLinesTheyAreTakenOver() {
        FileChangeModel file = new FileChangeModel();
        file.setPath("A.java");
        AnalysisSubmissionModel submission = new AnalysisSubmissionModel();
        submission.setFiles(List.of(file));
        DuplicationReportModel report = new DuplicationReportModel();

        DuplicationReportPopulator.populateChangedLineCpd(
                SubmissionContext.builder().submissionModel(submission).build(),
                report,
                Map.of("A.java", Set.of(1, 2, 7)),
                Map.of("A.java", new ChangedLines(Set.of(1, 2, 3, 4), Set.of(7, 8))));

        assertEquals(50.0, report.getAddedLineCpdPercent(), 0.001);
        assertEquals(50.0, report.getModifiedLineCpdPercent(), 0.001);
        assertEquals(50.0, report.getChangedLineCpdPercent(), 0.001, "3 of the 6 changed lines sit in a clone");
        assertEquals(4, report.getAddedLines());
        assertEquals(2, report.getAddedDuplicatedLines());
        assertEquals(2, report.getModifiedLines());
        assertEquals(1, report.getModifiedDuplicatedLines());
    }
}
