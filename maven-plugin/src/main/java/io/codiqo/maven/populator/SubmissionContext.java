package io.codiqo.maven.populator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;

import io.codiqo.api.IndexingSummary;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.DependencyRegistryModel;
import io.codiqo.client.model.ModuleFullCoverageModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Builder
public class SubmissionContext {
    private final RunArgs args;
    private final IndexingSummary index;
    private final CommitAnalysis analysis;
    private final Path workTree;
    private final LogFactory logFactory;
    private final MavenProject project;
    private final RuntimeInformation runtimeInformation;

    private final AnalysisSubmissionModel submissionModel;
    private final DependencyRegistryModel dependencyRegistryModel;
    private final ProjectModel projectModel;

    @Builder.Default
    private final LoadingCache<String, ModuleQualityTracker> qualityTrackers = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public ModuleQualityTracker load(String key) {
            return new ModuleQualityTracker();
        }
    });
    @Builder.Default
    private final Map<String, ModuleFullCoverageModel> moduleFullCoverages = Maps.newHashMap();

    @Setter
    private int methodCapQuantileProd;
    @Setter
    private int methodCapQuantileTest;
    @Setter
    private int constructorCapQuantileProd;
    @Setter
    private int constructorCapQuantileTest;

    @Setter
    @Builder.Default
    private DriverScaler methodScalerProd = DriverScaler.EMPTY;
    @Setter
    @Builder.Default
    private DriverScaler methodScalerTest = DriverScaler.EMPTY;
    @Setter
    @Builder.Default
    private DriverScaler constructorScalerProd = DriverScaler.EMPTY;
    @Setter
    @Builder.Default
    private DriverScaler constructorScalerTest = DriverScaler.EMPTY;

    @Setter
    @Builder.Default
    private SampleMaxTracker methodMaxProd = new SampleMaxTracker();
    @Setter
    @Builder.Default
    private SampleMaxTracker methodMaxTest = new SampleMaxTracker();
    @Setter
    @Builder.Default
    private SampleMaxTracker constructorMaxProd = new SampleMaxTracker();
    @Setter
    @Builder.Default
    private SampleMaxTracker constructorMaxTest = new SampleMaxTracker();

    @Setter
    private LlmScoringResponse llmScoringResponse;
    @Setter
    private ScoringResult llmScoringResult;
    @Setter
    private Duration llmAnalysisDuration;
    @Setter
    private String llmModel;

    public static SubmissionContext create(
            RunArgs args,
            IndexingSummary index,
            CommitAnalysis analysis,
            Path workTree,
            LogFactory logFactory,
            MavenProject project,
            RuntimeInformation runtimeInformation) {

        AnalysisSubmissionModel submissionModel = new AnalysisSubmissionModel();
        DependencyRegistryModel dependencyRegistryModel = new DependencyRegistryModel();
        submissionModel.setDependencies(dependencyRegistryModel);

        ProjectModel projectModel = new ProjectModel();
        projectModel.setCode(project.getGroupId() + ":" + project.getArtifactId());
        projectModel.setName(project.getName());

        return SubmissionContext.builder()
                .args(args)
                .index(index)
                .analysis(analysis)
                .workTree(workTree)
                .logFactory(logFactory)
                .project(project)
                .runtimeInformation(runtimeInformation)
                .submissionModel(submissionModel)
                .dependencyRegistryModel(dependencyRegistryModel)
                .projectModel(projectModel)
                .build();
    }
}
