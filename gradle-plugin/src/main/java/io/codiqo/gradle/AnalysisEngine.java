package io.codiqo.gradle;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;


import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ClientInfoModel.BuildToolEnum;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.ModuleData;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.submit.CommitModelPopulator;
import io.codiqo.submit.DuplicationReportPopulator;
import io.codiqo.submit.EffectiveChangePopulator;
import io.codiqo.submit.FileAnalysisPopulator;
import io.codiqo.submit.IndexModelPopulator;
import io.codiqo.submit.MetricsAggregator;
import io.codiqo.submit.ModuleLevelMetricsPopulator;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.ScoringConfigs;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.util.Fetch;
import io.codiqo.util.JGit;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import lombok.experimental.UtilityClass;

/**
 * Runs the shared analysis engine against a collected {@link AnalysisRequest}. Operates only on
 * plain data (no Gradle types), so it runs inside the isolated analysis worker.
 */
@UtilityClass
public class AnalysisEngine {
    public void run(AnalysisRequest request, LogFactory logFactory) throws Exception {
        RunArgs args = new RunArgs();
        args.setDumpAnalysis(true);
        args.setIgnoreCoverage(request.isIgnoreCoverage());
        args.setIgnoreCpd(request.isIgnoreCpd());
        args.setIgnoreDiagnostics(request.isIgnoreDiagnostics());
        args.setIgnoreComplexity(request.isIgnoreComplexity());
        args.setFailOnJdtlsError(request.isFailOnJdtlsError());
        args.setFailOnUninstrumentedModule(request.isFailOnUninstrumentedModule());
        args.setJavaHome(new File(request.getJavaHome()));
        args.setOutputDirectory(new File(request.getOutputDirectory()));
        args.validate();

        try (ClassGraphSpec scan = buildProjects(request, args)) {
            try (Repository git = JGit.openRepository(new File(request.getRootDir()))) {
                args.setGit(git);
                args.setDefaultBranch(JGit.currentBranchOrDefault(git));
                args.setCommitId(resolveCommitId(request, git));

                runEngine(request, args, logFactory);
            } finally {
                Log log = logFactory.getLogger(AnalysisEngine.class);
                args.getProjects().forEach(spec -> {
                    try {
                        spec.close();
                    } catch (Exception err) {
                        log.warn("failed to close project spec %s: %s", spec, err.getMessage());
                    }
                });
            }
        }
    }
    private static ClassGraphSpec buildProjects(AnalysisRequest request, RunArgs args) {
        Set<URI> jars = new LinkedHashSet<>();
        for (ModuleData module : request.getModules()) {
            GradleProjectWrapper wrapper = new GradleProjectWrapper();
            wrapper.setId(module.getId());
            wrapper.setGroupId(module.getGroupId());
            wrapper.setArtifactId(module.getArtifactId());
            wrapper.setName(module.getArtifactId());
            wrapper.setVersion(module.getVersion());
            wrapper.setPackaging(module.getPackaging());
            wrapper.setDescription(module.getDescription());
            wrapper.setBaseDirectory(new File(module.getBaseDirectory()));

            File classesDir = new File(module.getOutputDirectory());
            wrapper.setOutputDirectory(classesDir);
            if (classesDir.exists()) {
                jars.add(classesDir.toURI());
            }

            File coverage = new File(module.getCoveragePath());
            if (coverage.exists()) {
                wrapper.setCoverage(Optional.of(coverage));
            }

            for (String path : module.getCompileSourceRoots()) {
                File dir = new File(path);
                if (dir.exists()) {
                    wrapper.getCompileSourceRoots().add(dir);
                }
            }
            for (String path : module.getTestCompileSourceRoots()) {
                File dir = new File(path);
                if (dir.exists()) {
                    wrapper.getTestCompileSourceRoots().add(dir);
                }
            }
            for (String path : module.getCompileClasspathElements()) {
                File file = new File(path);
                if (file.exists()) {
                    wrapper.getCompileClasspathElements().add(file);
                    jars.add(file.toURI());
                }
            }
            for (String path : module.getTestClasspathElements()) {
                File file = new File(path);
                if (file.exists()) {
                    wrapper.getTestClasspathElements().add(file);
                    jars.add(file.toURI());
                }
            }
            wrapper.setDependencies(module.getDependencies());

            args.getProjects().add(wrapper);
        }

        ClassGraph classGraph = new ClassGraph().enableAllInfo();
        jars.forEach(classGraph::overrideClasspath);
        classGraph.enableSystemJarsAndModules();
        ScanResult scanResult = classGraph.scan();

        ClassGraphSpec graphSpec = new ClassGraphWrapper(scanResult);
        args.getProjects().forEach(spec -> {
            if (spec instanceof GradleProjectWrapper wrapper) {
                wrapper.setScan(graphSpec);
            }
        });
        return graphSpec;
    }
    private static String resolveCommitId(AnalysisRequest request, Repository git) throws Exception {
        if (request.getCommitId() != null) {
            return request.getCommitId();
        }
        ObjectId head = git.resolve("HEAD");
        return head.name();
    }
    private static void runEngine(AnalysisRequest request, RunArgs args, LogFactory logFactory) throws Exception {
        Log log = logFactory.getLogger(AnalysisEngine.class);
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load();

                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, args);
                CommitAnalysis analysis = analyzer.analyze();

                MutableBoolean toApply = new MutableBoolean();
                analysis.forEach(diff -> {
                    String name = diff.getFile().getName();
                    if (BooleanUtils.or(new boolean[] {
                            FilenameUtils.isExtension(name, registry.extensions()),
                            ConfigFiles.isConfigFile(name)
                    })) {
                        toApply.setTrue();
                    }
                });
                if (toApply.isFalse()) {
                    log.log(org.slf4j.event.Level.WARN, "commit %s: no diff files match registered languages %s — nothing to dump",
                            args.getCommitId(), registry.extensions());
                    return;
                }

                IndexingSummary index = registry.index(analysis);
                registry.identifyAffectedSymbols(index, analysis);
                registry.collectAndCapture(index, analysis);

                ClientInfoModel clientInfo = new ClientInfoModel();
                clientInfo.setBuildTool(BuildToolEnum.GRADLE);
                clientInfo.setVersion(request.getGradleVersion());
                clientInfo.setName("codiqo-gradle-plugin");

                SubmissionContext ctx = SubmissionContext.create(
                        args,
                        index,
                        analysis,
                        workTree,
                        logFactory,
                        request.getRootCode(),
                        request.getRootName(),
                        clientInfo);

                new GradleProjectModelPopulator(logFactory.getLogger(GradleProjectModelPopulator.class)).accept(ctx);
                new CommitModelPopulator().accept(ctx);
                new ModuleLevelMetricsPopulator().accept(ctx);
                new FileAnalysisPopulator().accept(ctx);
                new EffectiveChangePopulator().accept(ctx);
                new IndexModelPopulator().accept(ctx);

                DuplicationReportPopulator duplicationPopulator = new DuplicationReportPopulator();
                duplicationPopulator.accept(ctx);
                new MetricsAggregator(duplicationPopulator.getTotalDuplicatedLines()).accept(ctx);

                ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
                new OutputSerializer(true, logFactory.getLogger(OutputSerializer.class)).accept(ctx);
            }
        }
    }
}
