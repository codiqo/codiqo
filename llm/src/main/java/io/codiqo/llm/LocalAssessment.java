package io.codiqo.llm;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.Lists;

import io.codiqo.llm.client.LlmJson;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import lombok.experimental.UtilityClass;

/**
 * Puts the local review's assessment over a scoring response: the judgment the agents made from reading the commit
 * replaces the single prompt's for the same pieces, before the score is computed from them.
 *
 * <p>What the agents cannot see stays with the response: testing coverage needs the build's coverage, and the diff
 * classification needs the numbered diff. A gate verdict the response already has is kept per dimension even when the
 * score is replaced, the review's own verdict fills a dimension the response did not judge, and a dimension the review
 * did not score keeps the response's.
 */
@UtilityClass
public class LocalAssessment {
    private static final List<Dimension> DIMENSIONS = List.of(
            new Dimension(QualityDimensions::getArchitectureImpact, QualityDimensions::setArchitectureImpact),
            new Dimension(QualityDimensions::getConcurrencyRisk, QualityDimensions::setConcurrencyRisk),
            new Dimension(QualityDimensions::getIntegrationSurface, QualityDimensions::setIntegrationSurface),
            new Dimension(QualityDimensions::getDataIntegrity, QualityDimensions::setDataIntegrity),
            new Dimension(QualityDimensions::getSecuritySensitivity, QualityDimensions::setSecuritySensitivity),
            new Dimension(QualityDimensions::getScalabilityImpact, QualityDimensions::setScalabilityImpact),
            new Dimension(QualityDimensions::getObservability, QualityDimensions::setObservability),
            new Dimension(QualityDimensions::getResilience, QualityDimensions::setResilience),
            new Dimension(QualityDimensions::getPerformance, QualityDimensions::setPerformance));

    public void overlay(LlmScoringResponse target, LlmScoringResponse local) {
        if (StringUtils.isNotBlank(local.getSummary())) {
            target.setSummary(local.getSummary());
        }
        if (Objects.nonNull(local.getTags())) {
            target.setTags(local.getTags());
        }
        List<LlmScoringResponse.TaskType> taskTypes = CollectionUtils.emptyIfNull(local.getTaskTypes()).stream().filter(Objects::nonNull).toList();
        if (CollectionUtils.isNotEmpty(taskTypes)) {
            target.setTaskTypes(taskTypes);
        }
        if (Objects.nonNull(local.getTaskComplexity())) {
            target.setTaskComplexity(local.getTaskComplexity());
            target.setTaskComplexityRationale(local.getTaskComplexityRationale());
            target.setTaskComplexityNew(local.getTaskComplexityNew());
            target.setTaskComplexityModified(local.getTaskComplexityModified());
        }
        if (Objects.nonNull(local.getQualityDimensions())) {
            overlayDimensions(target, local.getQualityDimensions());
        }
        /** The review's labels are the whole judgment: a unit it left out is MECHANICAL, as one the prompt left out was. */
        target.setBlockCategories(CollectionUtils.emptyIfNull(local.getBlockCategories()).stream().toList());
        if (Objects.nonNull(local.getQualityMultiplier()) && Objects.nonNull(local.getQualityMultiplier().getArchitectureAnalysis())) {
            overlayArchitecture(target, local.getQualityMultiplier().getArchitectureAnalysis());
        }
        if (Objects.nonNull(local.getBlastRadiusAnalysis())) {
            overlayBlastRadius(target, local.getBlastRadiusAnalysis());
        }
        /** the triage's verdicts on the tool findings; the rules add every finding it did not judge */
        if (Objects.nonNull(local.getStaticAnalysisReview())) {
            target.setStaticAnalysisReview(local.getStaticAnalysisReview());
        }
        /** The score is a primitive, so a review that left it out reads 0: only a judged one replaces the response's. */
        if (BooleanUtils.or(new boolean[] { local.getRequiresSeniorReview() > 0, CollectionUtils.isNotEmpty(local.getSeniorReviewReasons()) })) {
            target.setRequiresSeniorReview(local.getRequiresSeniorReview());
            target.setSeniorReviewReasons(Lists.newArrayList(CollectionUtils.emptyIfNull(local.getSeniorReviewReasons())));
        }
    }
    /** A copy to score separately, for comparing the two scores without either computation touching the other. */
    public LlmScoringResponse copy(LlmScoringResponse response) {
        return LlmJson.responseMapper().convertValue(response, LlmScoringResponse.class);
    }
    /** The review's findings; what they cost is computed from them, never copied. */
    private static void overlayArchitecture(LlmScoringResponse target, LlmScoringResponse.ArchitectureAnalysis local) {
        if (Objects.isNull(target.getQualityMultiplier())) {
            target.setQualityMultiplier(new LlmScoringResponse.QualityMultiplier());
        }
        target.getQualityMultiplier().setArchitectureAnalysis(LlmScoringResponse.ArchitectureAnalysis.builder()
                .solidViolations(Lists.newArrayList(CollectionUtils.emptyIfNull(local.getSolidViolations())))
                .architectureIssues(Lists.newArrayList(CollectionUtils.emptyIfNull(local.getArchitectureIssues())))
                .build());
    }
    /** The review's judgment of the module and its signatures; the caller counts stay the response's, grounded by the server. */
    private static void overlayBlastRadius(LlmScoringResponse target, LlmScoringResponse.BlastRadiusAnalysis local) {
        if (Objects.isNull(target.getBlastRadiusAnalysis())) {
            target.setBlastRadiusAnalysis(new LlmScoringResponse.BlastRadiusAnalysis());
        }
        LlmScoringResponse.BlastRadiusAnalysis blast = target.getBlastRadiusAnalysis();
        if (Objects.nonNull(local.getModuleType())) {
            blast.setModuleType(local.getModuleType());
        }
        if (Objects.nonNull(local.getSignatureChanges())) {
            blast.setSignatureChanges(local.getSignatureChanges());
        }
        if (StringUtils.isNotBlank(local.getExplanation())) {
            blast.setExplanation(local.getExplanation());
        }
    }
    private static void overlayDimensions(LlmScoringResponse target, QualityDimensions local) {
        if (Objects.isNull(target.getQualityDimensions())) {
            target.setQualityDimensions(new QualityDimensions());
        }
        for (Dimension dimension : DIMENSIONS) {
            DimensionScore localScore = dimension.get(local);
            if (Objects.nonNull(localScore) && Objects.nonNull(localScore.getScore())) {
                DimensionScore current = dimension.get(target.getQualityDimensions());
                /** The prompt's verdict when there is one; otherwise the review's, which is met unless it found otherwise. */
                boolean gateMet = localScore.isQualityGateMet();
                if (Objects.nonNull(current)) {
                    gateMet = current.isQualityGateMet();
                }
                dimension.set(target.getQualityDimensions(), new DimensionScore(localScore.getScore(), localScore.getRationale(), gateMet));
            }
        }
    }

    private static final class Dimension extends ImmutablePair<Function<QualityDimensions, DimensionScore>, BiConsumer<QualityDimensions, DimensionScore>> {
        private Dimension(Function<QualityDimensions, DimensionScore> getter, BiConsumer<QualityDimensions, DimensionScore> setter) {
            super(getter, setter);
        }
        private DimensionScore get(QualityDimensions dimensions) {
            return getLeft().apply(dimensions);
        }
        private void set(QualityDimensions dimensions, DimensionScore score) {
            getRight().accept(dimensions, score);
        }
    }
}
