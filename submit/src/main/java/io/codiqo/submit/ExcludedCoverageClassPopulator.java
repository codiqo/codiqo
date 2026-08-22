package io.codiqo.submit;

import java.util.Collection;

import org.apache.commons.collections4.CollectionUtils;

import io.codiqo.api.coverage.ExcludedCoverageClass;
import io.codiqo.client.model.CoverageExclusionReason;
import io.codiqo.client.model.ExcludedCoverageClassModel;

public class ExcludedCoverageClassPopulator implements SubmissionPopulator {
    @Override
    public void accept(SubmissionContext ctx) {
        Collection<ExcludedCoverageClass> excluded = ctx.getAnalysis().excludedCoverageClasses();
        if (CollectionUtils.isNotEmpty(excluded)) {
            ctx.getSubmissionModel().setExcludedCoverageClasses(excluded.stream()
                    .map(ExcludedCoverageClassPopulator::map)
                    .toList());
        }
    }
    private static ExcludedCoverageClassModel map(ExcludedCoverageClass excluded) {
        ExcludedCoverageClassModel toReturn = new ExcludedCoverageClassModel();
        toReturn.setClassName(excluded.getClassName());
        toReturn.setReason(mapReason(excluded.getReason()));
        return toReturn;
    }
    private static CoverageExclusionReason mapReason(io.codiqo.api.coverage.CoverageExclusionReason reason) {
        switch (reason) {
            case DUPLICATE_FULLY_QUALIFIED_NAME:
                return CoverageExclusionReason.DUPLICATE_FULLY_QUALIFIED_NAME;
            default:
                throw new IllegalArgumentException("Unknown coverage exclusion reason: " + reason);
        }
    }
}
