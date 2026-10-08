package io.codiqo.llm.review;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.Lists;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FindingTriage {
    private List<FindingVerdict> verdicts = Lists.newArrayList();
    private SessionUsage usage;
    private String answer;
    /** what the triage was asked about, kept beside the verdicts for the tool's severity; never part of the answer */
    @JsonIgnore
    private List<StaticFinding> findings = Lists.newArrayList();
}
