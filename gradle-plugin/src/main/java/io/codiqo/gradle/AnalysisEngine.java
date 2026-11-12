package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

import com.google.common.annotations.VisibleForTesting;

import io.codiqo.api.BuildTool;
import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ClientInfoModel.BuildToolEnum;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.HttpCodiqoServer;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.submit.auth.CodiqoCredentials;
import io.codiqo.util.JGit;
import io.codiqo.submit.SourceHiding;
import io.codiqo.submit.outcome.ReviewStarter;
import io.codiqo.submit.outcome.CommitRun;
import io.codiqo.submit.outcome.CommitOutcome;
import io.codiqo.submit.outcome.AnalysedProject;
import lombok.experimental.UtilityClass;

/**
 * Runs the shared analysis engine against a collected {@link AnalysisRequest}. Operates only on plain data (no Gradle
 * types), so it runs inside the isolated analysis worker.
 */
@UtilityClass
public class AnalysisEngine {
    private static final String ERR_NO_CREDENTIAL = "no codiqo.apiKey configured and no browser login stored; "
            + "run 'gradle codiqoLogin' once on this machine, or set codiqo.apiKey";
    /**
     * Declared on the worker side, not on the Gradle-typed helper that also uses it. The worker runs on a bare
     * classpath with no gradle-api, so it must not reference a class that imports Gradle types. Reading the constant
     * from {@code GradleBuildSupport} only appeared to work because javac folds a compile-time String constant; moving
     * it there, or making it non-constant, would load a Gradle-typed class in the worker and fail it.
     */
    public static final String EXEC_PART_PREFIX = "codiqo-";

    public void run(AnalysisRequest request, LogFactory logFactory) throws Exception {
        RunArgs args = new RunArgs();
        args.setDumpAnalysis(true);
        args.setIgnoreCoverage(request.isIgnoreCoverage());
        args.setIgnoreCpd(request.isIgnoreCpd());
        args.setExcludeProjects(request.getExcludeProjects());
        args.setExcludePaths(request.getExcludePaths());
        args.setBuildTool(BuildTool.GRADLE);
        args.setSkipOnBuildFailure(request.isSkipOnBuildFailure());
        args.setFirstParentOnly(request.isFirstParentOnly());
        args.setExcludeRevertedCommits(request.isExcludeRevertedCommits());
        Optional.ofNullable(request.getIncludeBranches()).ifPresent(args::setIncludeBranches);
        Optional.ofNullable(request.getIncludeAuthorEmails()).ifPresent(args::setIncludeAuthorEmails);
        Optional.ofNullable(request.getExcludeAuthorEmails()).ifPresent(args::setExcludeAuthorEmails);
        args.setIgnoreDiagnostics(request.isIgnoreDiagnostics());
        args.setIgnoreComplexity(request.isIgnoreComplexity());
        args.setHotspotsEnabled(request.isHotspots());
        args.setFailOnJdtlsError(request.isFailOnJdtlsError());
        args.setFailOnUninstrumentedModule(request.isFailOnUninstrumentedModule());
        Optional.ofNullable(request.getTestFailureHoldShare()).filter(StringUtils::isNotBlank).map(String::trim).map(Double::parseDouble).ifPresent(args::setTestFailureHoldShare);
        Optional.ofNullable(request.getTestFailureHoldMinTests()).filter(StringUtils::isNotBlank).map(String::trim).map(Integer::parseInt).ifPresent(args::setTestFailureHoldMinTests);
        args.setJavaHome(new File(request.getJavaHome()));
        Optional.ofNullable(request.getJdtlsJavaHome()).filter(StringUtils::isNotBlank).map(File::new).ifPresent(args::setJdtlsJavaHome);
        args.setOutputDirectory(new File(request.getOutputDirectory()));
        Optional.ofNullable(request.getBuildProgressFile()).map(File::new).ifPresent(args::setBuildProgressFile);

        args.setJdtlsVersion(request.getJdtlsVersion());
        args.setJdtlsUseSnapshot(request.isJdtlsUseSnapshot());
        args.setJdtUseSharedIndex(request.isJdtUseSharedIndex());
        args.setJdtIncludeDecompiledSources(request.isJdtIncludeDecompiledSources());
        args.setJdtLombokAgent(request.isJdtLombokAgent());
        Optional.ofNullable(request.getLombokAgentJar()).map(File::new).ifPresent(args.getAgents()::add);
        args.setImportTimeout(Duration.ofMinutes(request.getImportTimeoutMinutes()));
        args.setLspQueryTimeout(Duration.ofSeconds(request.getLspQueryTimeoutSeconds()));
        applyReviewSettings(request, args);

        args.validate();

        Log log = logFactory.getLogger(AnalysisEngine.class);
        try (Repository git = JGit.openRepository(new File(request.getRootDir()))) {
            applyRepository(request, args, git);

            /** a hidden-source run without a usable key fails here, before anything is analysed, sent or paid for */
            CommitRun commit = commitRun(request, server(request, log), args, logFactory);
            CommitOutcome outcome = commit.admit().orElseGet(commit::run);
            /** the worker exits with the failure, which fails the javaexec and the task; a halt is already recorded */
            if (outcome instanceof CommitOutcome.Failed failed) {
                throw failed.getCause();
            }
        }
    }
    /** the commit's run, with Gradle's build of it and the analysis written as YAML beside the build */
    @VisibleForTesting
    public static CommitRun commitRun(AnalysisRequest request, CodiqoServer server, RunArgs args, LogFactory logFactory) {
        return new CommitRun(
                args,
                server,
                new GradleCommitBuild(request, args, logFactory),
                new AnalysedProject(request.getRootCode(), request.getRootName(), clientInfo(request)),
                new OutputSerializer(true, logFactory.getLogger(OutputSerializer.class)),
                ReviewStarter.LOCAL,
                logFactory);
    }
    private static ClientInfoModel clientInfo(AnalysisRequest request) {
        ClientInfoModel toReturn = new ClientInfoModel();
        toReturn.setBuildTool(BuildToolEnum.GRADLE);
        toReturn.setVersion(request.getGradleVersion());
        toReturn.setName("codiqo-gradle-plugin");
        return toReturn;
    }
    /**
     * What the analysed repository says about the commit: its branch, the commit and the repository's URLs, which the
     * project model sends so the server can tie the submission to its repository. Without them every Gradle submission
     * went without repository URLs, which a Maven run of the same commit carries.
     */
    @VisibleForTesting
    public static void applyRepository(AnalysisRequest request, RunArgs args, Repository git) throws Exception {
        args.setGit(git);
        JGit.currentBranchOrDefault(git).ifPresent(args::setDefaultBranch);
        args.setCommitId(resolveCommitId(request, git));
        args.setHotspotsCommitId(JGit.resolveCommit(git, Optional.ofNullable(request.getHotspotsCommitId()).orElse(Constants.HEAD)));
        args.setRemoteUrls(JGit.detectRemoteUrls(git));
    }
    private static String resolveCommitId(AnalysisRequest request, Repository git) throws Exception {
        return JGit.resolveCommit(git, Optional.ofNullable(request.getCommitId()).orElse(Constants.HEAD));
    }
    /** Codiqo for the run's project, as the configured key or the browser login stored on this machine ({@link #credential}) */
    @VisibleForTesting
    public static CodiqoServer server(AnalysisRequest request, Log log) {
        return new HttpCodiqoServer(request.getApiUrl(), request.getResourceUrl(), () -> credential(request, log), request.getConnectTimeoutSeconds(),
                request.getReadTimeoutSeconds(), request.getRootCode(), hiding(request), log);
    }
    private static SourceHiding hiding(AnalysisRequest request) {
        return SourceHiding.of(request.isSourceHidden(), Optional.ofNullable(request.getSourceHashKey()));
    }
    /** each review setting left unset keeps its {@link RunArgs} default; the trace goes beside the analysis's output */
    @VisibleForTesting
    public static void applyReviewSettings(AnalysisRequest request, RunArgs args) {
        args.setReviewEnabled(request.isReview());
        Optional.ofNullable(request.getReviewExecutable()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewExecutable);
        Optional.ofNullable(request.getReviewBaseUrl()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewBaseUrl);
        Optional.ofNullable(request.getReviewCoordinatorModel()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewCoordinatorModel);
        Optional.ofNullable(request.getReviewReviewerModel()).filter(StringUtils::isNotBlank).ifPresent(args::setReviewReviewerModel);
        Optional.ofNullable(request.getReviewTimeoutMinutes()).filter(StringUtils::isNotBlank).map(Long::parseLong)
                .ifPresent(minutes -> args.setReviewTimeout(Duration.ofMinutes(minutes)));
        Optional.ofNullable(request.getReviewTemperature()).filter(StringUtils::isNotBlank).map(Double::parseDouble).ifPresent(args::setReviewTemperature);
        Optional.ofNullable(request.getReviewTopP()).filter(StringUtils::isNotBlank).map(Double::parseDouble).ifPresent(args::setReviewTopP);
        Optional.ofNullable(request.getReviewRelayRetries()).filter(StringUtils::isNotBlank).map(Integer::parseInt).ifPresent(args::setReviewRelayRetries);
        Optional.ofNullable(request.getReviewRelayTimeoutSeconds()).filter(StringUtils::isNotBlank).map(Long::parseLong)
                .ifPresent(seconds -> args.setReviewRelayRequestTimeout(Duration.ofSeconds(seconds)));
        args.setReviewTraceDirectory(new File(Optional.ofNullable(request.getReviewTraceDirectory()).filter(StringUtils::isNotBlank).orElse(request.getOutputDirectory())));
    }
    /**
     * A configured key, or the browser login stored on this machine. Never the browser itself: the worker has nobody
     * at its terminal, so a missing login fails here with the task that creates one.
     */
    private static CodiqoCredential credential(AnalysisRequest request, Log log) throws IOException {
        return CodiqoCredentials.resolve(request.getApiKey(), request.getAuthUrl(), request.getResourceUrl(), false, log)
                .orElseThrow(() -> new IOException(ERR_NO_CREDENTIAL));
    }
}
