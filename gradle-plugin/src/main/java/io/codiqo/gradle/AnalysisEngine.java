package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

import com.google.common.annotations.VisibleForTesting;

import io.codiqo.api.BuildTool;
import io.codiqo.api.RunArgs;
import io.codiqo.api.Settings;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ClientInfoModel.BuildToolEnum;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.submit.CodiqoServer;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.CommitExclusions.Exclusion;
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
        RunArgs args = runArgs(request);

        Log log = logFactory.getLogger(AnalysisEngine.class);
        try (Repository git = JGit.openRepository(new File(request.getRootDir()))) {
            applyRepository(request, args, git);

            /** a hidden-source run without a usable key fails here, before anything is analysed, sent or paid for */
            CommitRun commit = commitRun(request, server(request, args, log), args, logFactory);
            CommitOutcome outcome = commit.admit().orElseGet(commit::run);
            /** the worker exits with the failure, which fails the javaexec and the task; a halt is already recorded */
            if (outcome instanceof CommitOutcome.Failed failed) {
                throw failed.getCause();
            }
        }
    }
    /** the run's settings with the lombok agent the daemon resolved for the language server */
    @VisibleForTesting
    public static RunArgs runArgs(AnalysisRequest request) {
        RunArgs toReturn = runArgs(request.getSettings());
        Optional.ofNullable(request.getLombokAgentJar()).map(File::new).ifPresent(toReturn.getAgents()::add);
        return toReturn;
    }
    /**
     * The one binding of a Gradle run's settings: the daemon runs it before the build ({@code GradleSettings}) and the
     * worker again on the map it was handed, so what the daemon accepts is what the worker runs. Over the settings
     * given, it sets the build tool, and the review's trace goes beside the analysis's output unless a directory was
     * given. Declared here, on the worker's side, because the worker cannot load a class that imports Gradle types.
     */
    public static RunArgs runArgs(Map<String, String> settings) {
        RunArgs toReturn = new RunArgs();
        toReturn.setBuildTool(BuildTool.GRADLE);
        Settings.bind(toReturn, settings);
        toReturn.setReviewTraceDirectory(Optional.ofNullable(toReturn.getReviewTraceDirectory()).orElse(toReturn.getOutputDirectory()));
        toReturn.validate();
        return toReturn;
    }
    /** the commit's run, with Gradle's build of it and, unless {@code codiqo.dumpAnalysis} is off, the analysis written as YAML */
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
        args.setHotspotsCommitId(JGit.resolveCommit(git, Optional.ofNullable(args.getHotspotsCommitId()).orElse(Constants.HEAD)));
        args.setRemoteUrls(JGit.detectRemoteUrls(git));
    }
    /**
     * The exclusion of a commit that changes nothing analysable, decided from git alone by the rule Maven applies right
     * after its checkout ({@link CommitExclusions#beforeBuild}). The daemon asks it before any task runs and skips the
     * build and the tests of such a commit, which ran in full only for the worker to exclude the commit as
     * NO_ANALYZABLE_DIFF. The worker still decides on its own, through {@link CommitRun}, by the same rule and the same
     * registered extensions, so the exclusion it records is unchanged; a daemon that skipped a build the worker then
     * needed would score a commit with no classes, which one rule for both cannot produce.
     */
    public static Optional<Exclusion> nothingToAnalyse(AnalysisRequest request) throws Exception {
        try (Repository git = JGit.openRepository(new File(request.getRootDir()))) {
            return CommitExclusions.beforeBuild(git, resolveCommitId(request, git), DefaultLanguageProcessors.supportedExtensions());
        }
    }
    private static String resolveCommitId(AnalysisRequest request, Repository git) throws Exception {
        return JGit.resolveCommit(git, Optional.ofNullable(request.getCommitId()).orElse(Constants.HEAD));
    }
    /** Codiqo for the run's project, as the configured key or the browser login stored on this machine ({@link #credential}) */
    @VisibleForTesting
    public static CodiqoServer server(AnalysisRequest request, RunArgs args, Log log) {
        return new HttpCodiqoServer(request.getApiUrl(), request.getResourceUrl(), () -> credential(request, log), args.getConnectTimeout().toSeconds(),
                args.getReadTimeout().toSeconds(), request.getRootCode(), hiding(request), log);
    }
    private static SourceHiding hiding(AnalysisRequest request) {
        return SourceHiding.of(request.isSourceHidden(), Optional.ofNullable(request.getSourceHashKey()));
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
