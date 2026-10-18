package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.llm.client.LlmJson;
import io.codiqo.llm.schema.LlmScoringResponse;

class FindingTriageTest {
    private static final List<StaticFinding> ASKED = List.of(
            new StaticFinding("pmd", "GodClass", "warning", "a/Service.java", 3, "possible God Class"),
            new StaticFinding("spotbugs", "OS_OPEN_STREAM", "high", "a/Service.java", 19, "stream not closed"));

    /** one verdict per asked finding, a defect with its severity and type, is an answer the submission takes as it is */
    @Test
    void oneVerdictPerFindingHasNoProblems() {
        assertEquals(List.of(), triage(
                verdict("PMD", "GodClass", 3, FindingVerdict.Verdict.HARMLESS),
                defect(verdict("spotbugs", "OS_OPEN_STREAM", 19, FindingVerdict.Verdict.DEFECT))).problems(ASKED), "the tool is matched in any case");
    }
    /** a coordinator once answered the triage under the list's own name, "findings": that is reported as such */
    @Test
    void anAnswerUnderAnotherFieldNameHasNoVerdictsList() {
        FindingTriage answer = LlmJson.readAnswer("{\"findings\":[{\"tool\":\"Error Prone\",\"rule\":\"StringSplitter\",\"file\":\"a/Service.java\",\"line\":3,\"verdict\":\"false_positive\"}]}", FindingTriage.class);

        assertEquals(List.of("no \"verdicts\" list"), answer.problems(ASKED));
    }
    /** anything but one verdict with a known value per asked finding is named, so the review fails on it */
    @Test
    void anAnswerThatIsNotOneVerdictPerFindingIsNamed() {
        FindingTriage nullList = new FindingTriage();
        nullList.setVerdicts(null);

        assertEquals(List.of("no \"verdicts\" list"), nullList.problems(ASKED));
        assertEquals(List.of("a null verdict", "pmd GodClass at a/Service.java:3 has no known verdict", "not judged: [FindingKey(tool=spotbugs, rule=OS_OPEN_STREAM, file=a/Service.java, line=19)]"),
                triage(null, verdict("pmd", "GodClass", 3, null)).problems(ASKED));
        assertEquals(List.of("spotbugs OS_OPEN_STREAM at a/Service.java:19 is a defect without a known severity and bug type",
                "pmd GodClass at a/Service.java:3 is not a listed finding, or is judged twice",
                "pmd GodClass at a/Service.java:4 is not a listed finding, or is judged twice"),
                triage(
                        verdict("pmd", "GodClass", 3, FindingVerdict.Verdict.HARMLESS),
                        verdict("spotbugs", "OS_OPEN_STREAM", 19, FindingVerdict.Verdict.DEFECT),
                        verdict("pmd", "GodClass", 3, FindingVerdict.Verdict.HARMLESS),
                        verdict("pmd", "GodClass", 4, FindingVerdict.Verdict.HARMLESS)).problems(ASKED));
    }
    private static FindingTriage triage(FindingVerdict... verdicts) {
        FindingTriage toReturn = new FindingTriage();
        toReturn.setVerdicts(Lists.newArrayList(verdicts));
        return toReturn;
    }
    private static FindingVerdict verdict(String tool, String rule, int line, FindingVerdict.Verdict value) {
        FindingVerdict toReturn = new FindingVerdict();
        toReturn.setTool(tool);
        toReturn.setRule(rule);
        toReturn.setFile("a/Service.java");
        toReturn.setLine(line);
        toReturn.setVerdict(value);
        return toReturn;
    }
    private static FindingVerdict defect(FindingVerdict verdict) {
        verdict.setSeverity(FindingVerdict.Severity.MAJOR);
        verdict.setType(LlmScoringResponse.BugType.values()[0]);
        return verdict;
    }
}
