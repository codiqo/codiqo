package io.codiqo.llm.review;

import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;

import io.codiqo.llm.FindingKey;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FindingTriage {
    /** null when the answer has no such field, which {@link #problems} reports instead of judging no finding */
    private List<FindingVerdict> verdicts;
    private SessionUsage usage;
    private String answer;
    /** what the triage was asked about, kept beside the verdicts for the tool's severity; never part of the answer */
    @JsonIgnore
    private List<StaticFinding> findings = Lists.newArrayList();

    /**
     * What keeps the answer from being one verdict per finding it was asked about, as the prompt asks: no list, a null
     * entry, a verdict that is not one of the three (read as null, since unknown enum values are read leniently), a
     * defect without a known severity or bug type, a verdict on a finding that was not asked about or one judged twice,
     * and a finding left without a verdict. Empty for an answer the submission can take as it is.
     */
    public List<String> problems(List<StaticFinding> asked) {
        if (Objects.isNull(verdicts)) {
            return List.of("no \"verdicts\" list");
        }

        List<String> toReturn = Lists.newArrayList();
        Multiset<FindingKey> unjudged = HashMultiset.create(asked.stream().map(finding -> FindingKey.of(finding.getTool(), finding.getRule(), finding.getFile(), finding.getLine())).toList());
        for (FindingVerdict verdict : verdicts) {
            if (Objects.isNull(verdict)) {
                toReturn.add("a null verdict");
            } else {
                String named = verdict.getTool() + " " + verdict.getRule() + " at " + verdict.getFile() + ":" + verdict.getLine();
                if (unjudged.remove(FindingKey.of(verdict.getTool(), verdict.getRule(), verdict.getFile(), verdict.getLine()))) {
                    if (Objects.isNull(verdict.getVerdict())) {
                        toReturn.add(named + " has no known verdict");
                    } else if (verdict.getVerdict() == FindingVerdict.Verdict.DEFECT) {
                        if (BooleanUtils.or(new boolean[] { Objects.isNull(verdict.getSeverity()), Objects.isNull(verdict.getType()) })) {
                            toReturn.add(named + " is a defect without a known severity and bug type");
                        }
                    }
                } else {
                    toReturn.add(named + " is not a listed finding, or is judged twice");
                }
            }
        }
        if (CollectionUtils.isNotEmpty(unjudged)) {
            toReturn.add("not judged: " + unjudged);
        }
        return toReturn;
    }
}
