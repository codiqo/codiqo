package io.codiqo.lang.spec;

import java.net.URI;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import io.codiqo.api.code.Finding;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.PmdPropertiesModel;
import lombok.Value;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.RuleViolation;

/**
 * A PMD violation as a finding. PMD's violations are equal only to themselves, so two of these are equal exactly when
 * they adapt the same violation, however many units it is attached to.
 */
@Value
public class PmdFinding implements Finding {
    RuleViolation violation;

    @Override
    public String getTool() {
        return DiagnosticModel.ToolEnum.PMD.getValue();
    }
    @Override
    public String getRuleId() {
        return violation.getRule().getName();
    }
    @Override
    public String getMessage() {
        return violation.getDescription();
    }
    @Override
    public String getCategory() {
        return violation.getRule().getRuleSetName();
    }
    @Override
    public Severity getSeverity() {
        int priority = getRank();
        if (priority == RulePriority.HIGH.getPriority()) {
            return Severity.ERROR;
        } else if (priority == RulePriority.MEDIUM.getPriority()) {
            return Severity.WARNING;
        } else {
            return Severity.INFO;
        }
    }
    @Override
    public SourceLocation getLocation() {
        return SourceLocation.builder()
                .startLine(violation.getBeginLine())
                .startColumn(violation.getBeginColumn())
                .endLine(violation.getEndLine())
                .endColumn(violation.getEndColumn())
                .build();
    }
    /** the rule's priority, 1 (high) to 5 (low) */
    @Override
    public int getRank() {
        return violation.getRule().getPriority().getPriority();
    }
    @Override
    public void describe(DiagnosticModel diagnostic) {
        PmdPropertiesModel pmdInfo = new PmdPropertiesModel();
        pmdInfo.setRuleSet(violation.getRule().getRuleSetName());
        pmdInfo.setPriority(getRank());
        Optional.ofNullable(violation.getRule().getExternalInfoUrl()).filter(StringUtils::isNotEmpty).map(URI::create).ifPresent(pmdInfo::setExternalInfoUrl);
        diagnostic.setPmdInfo(pmdInfo);
    }
}
