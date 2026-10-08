package io.codiqo.llm.client;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.llm.GoldenText;
import io.codiqo.llm.schema.RecapContender;

class RecapAwardsClientTest {
    /** a prompt change moves what the model writes, so the contenders' facts are pinned as they are sent */
    @Test
    void theContendersAreDescribedExactly() throws Exception {
        RecapContender full = RecapContender.builder()
                .rank(1)
                .name("  Ada Lovelace ")
                .seniority("Senior")
                .headlineLabel("effort score")
                .headlineValue("412")
                .commits(31)
                .seniorGradeCommits(12)
                .filesChanged(140)
                .linesChanged(9_870)
                .avgComplexity(6.5)
                .avgCoverage(81.25)
                .distinction("most senior-grade commits")
                .projects(Lists.newArrayList("ledger", null, "payments"))
                .bestWork("moves refunds onto the double-entry ledger")
                .build();
        RecapContender sparse = RecapContender.builder()
                .rank(2)
                .name("Grace")
                .seniority(" ")
                .headlineLabel("effort score")
                .headlineValue("300")
                .commits(9)
                .projects(Lists.newArrayList())
                .build();

        GoldenText.assertMatches("recap-awards-prompt", RecapAwardsClient.prompt("Example Org", "September 2026", "effort", List.of(full, sparse)));
    }
}
