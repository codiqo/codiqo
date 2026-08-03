package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedReader;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.PrintStreamHandler;
import org.apache.maven.shared.utils.cli.CommandLineTimeOutException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.TagOpt;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.MavenProjectSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.ApiException;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.ProjectMetricsModel;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.llm.client.DaemonExecutors;
import io.codiqo.maven.coverage.CoverageInjectorConfig;
import io.codiqo.maven.eventspy.BuildFailureConfig;
import io.codiqo.llm.ConventionGuidance;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.maven.populator.LlmScoringPopulator;
import io.codiqo.maven.populator.ProjectModelPopulator;
import io.codiqo.maven.populator.SubmissionSummaryPrinter;
import io.codiqo.maven.surefire.SurefireInjectorConfig;
import io.codiqo.maven.timemachine.TimeMachineConfig;
import io.codiqo.submit.CommitModelPopulator;
import io.codiqo.submit.DuplicationReportPopulator;
import io.codiqo.submit.EffectiveChangePopulator;
import io.codiqo.submit.ExcludedCoverageClassPopulator;
import io.codiqo.submit.FileAnalysisPopulator;
import io.codiqo.submit.IndexModelPopulator;
import io.codiqo.submit.MetricsAggregator;
import io.codiqo.submit.ModuleLevelMetricsPopulator;
import io.codiqo.submit.OutputSerializer;
import io.codiqo.submit.ScoringConfigs;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.util.Env;
import io.codiqo.util.Fetch;
import io.codiqo.util.JGit;
import io.codiqo.util.MemoryReport;
import io.codiqo.util.Split;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import lombok.RequiredArgsConstructor;

abstract class AbstractAnalyzeMojo extends AbstractMojo implements Function<Artifact, Collection<File>> {
    private static final Set<String> NON_CODE_PACKAGINGS = Set.of("pom", "bom");
    private static final String JAR_EXTENSION = "jar";

    private static final String LOMBOK_GROUP_ID = "org.projectlombok";
    private static final String LOMBOK_ARTIFACT_ID = "lombok";

    private static final String CODIQO_GROUP_ID = "io.codiqo";
    private static final String TIME_MACHINE_ARTIFACT_ID = "codiqo-maven-time-machine";
    private static final String COVERAGE_INJECTOR_ARTIFACT_ID = "codiqo-maven-coverage-injector";
    private static final String SUREFIRE_INJECTOR_ARTIFACT_ID = "codiqo-maven-surefire-injector";
    private static final String BUILD_EVENTSPY_ARTIFACT_ID = "codiqo-maven-build-eventspy";

    private static final String JACOCO_GROUP_ID = "org.jacoco";
    private static final String JACOCO_AGENT_ARTIFACT_ID = "org.jacoco.agent";
    private static final String JACOCO_AGENT_CLASSIFIER = "runtime";
    private static final String JACOCO_EXEC_FILE = "jacoco.exec";

    private static final String MAVEN_EXT_CLASS_PATH = "maven.ext.class.path";
    private static final String SOURCES_JAR_SUFFIX = "-sources.jar";
    /**
     * protocols the language server's resolver can actually transport, read off the two transporters Maven ships:
     * FileTransporter accepts "file", HttpTransporter accepts "http" and "https", both by equalsIgnoreCase on
     * RemoteRepository.getProtocol(). WagonTransporter would accept more, but only for wagons registered in that
     * process — which is precisely what m2e lacks, so it cannot be counted on.
     */
    private static final Set<String> M2E_TRANSPORTABLE_PROTOCOLS = Set.of("file", "http", "https");
    private static final String PRIVATE_REPOSITORY_PREFIX = "codiqo-m2-";

    // StringUtils.abbreviate requires a width of at least 4 ("a...")
    private static final int MIN_ABBREVIATE_WIDTH = 4;

    private Collection<File> timeMachineExtensionJars;
    private Collection<File> coverageInjectorJars;
    private Collection<File> surefireInjectorJars;
    private Collection<File> buildEventSpyJars;
    private File jacocoAgentJar;

    @Inject
    private RuntimeInformation runtimeInformation;

    @Inject
    protected RepositorySystem repositorySystem;

    @Inject
    protected MavenSession mavenSession;

    @Inject
    protected ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    protected List<RemoteRepository> remoteRepos;

    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    protected List<RemoteRepository> remotePluginRepos;

    @Parameter(property = "codiqo.javaHome")
    protected File javaHome;

    @Parameter(property = "codiqo.mavenHome")
    protected File mavenHome;

    @Parameter(property = "codiqo.preferYaml", defaultValue = "true")
    protected boolean preferYaml;

    @Parameter(property = "codiqo.buildTimeoutMinutes", defaultValue = "60")
    protected long buildTimeoutMinutes;

    @Parameter(property = "codiqo.testTimeoutMinutes", defaultValue = "30")
    protected long testTimeoutMinutes;

    @Parameter(property = "codiqo.perTestTimeoutMinutes")
    protected Long perTestTimeoutMinutes;

    @Parameter(property = "codiqo.importTimeoutMinutes", defaultValue = "15")
    protected long importTimeoutMinutes;

    @Parameter(property = "codiqo.lspQueryTimeoutSeconds", defaultValue = "30")
    protected long lspQueryTimeoutSeconds;

    @Parameter(property = "codiqo.connectTimeoutSeconds", defaultValue = "30")
    protected long connectTimeoutSeconds;

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "60")
    protected long readTimeoutSeconds;

    @Parameter(property = "codiqo.maxRequests", defaultValue = "256")
    protected int maxRequests;

    @Parameter(property = "codiqo.maxRequestsPerHost", defaultValue = "128")
    protected int maxRequestsPerHost;

    @Parameter(property = "codiqo.cpdMinimumTileSize", defaultValue = "64")
    protected int cpdMinimumTileSize;

    @Parameter(property = "codiqo.diffContextLines", defaultValue = "10")
    protected int diffContextLines;

    @Parameter(property = "codiqo.jdtlsVersion", defaultValue = "1.60.0")
    protected String jdtlsVersion;

    @Parameter(property = "codiqo.jdtlsUseSnapshot", defaultValue = "false")
    protected boolean jdtlsUseSnapshot;

    @Parameter(property = "codiqo.dumpAnalysis", defaultValue = "true")
    protected boolean dumpAnalysis;

    @Parameter(property = "codiqo.ignoreCoverage", defaultValue = "false")
    protected boolean ignoreCoverage;

    @Parameter(property = "codiqo.failOnUninstrumentedModule", defaultValue = "true")
    protected boolean failOnUninstrumentedModule;

    @Parameter(property = "codiqo.ignoreCpd", defaultValue = "false")
    protected boolean ignoreCpd;

    @Parameter(property = "codiqo.ignoreDiagnostics", defaultValue = "false")
    protected boolean ignoreDiagnostics;

    @Parameter(property = "codiqo.ignoreComplexity", defaultValue = "false")
    protected boolean ignoreComplexity;

    @Parameter(property = "codiqo.failOnJdtlsError", defaultValue = "false")
    protected boolean failOnJdtlsError;

    @Parameter(property = "codiqo.skipOnBuildFailure", defaultValue = "true")
    protected boolean skipOnBuildFailure;

    @Parameter(property = "codiqo.scoreOnBuildFailure", defaultValue = "false")
    protected boolean scoreOnBuildFailure;

    @Parameter(property = "codiqo.excludeRevertedCommits", defaultValue = "true")
    protected boolean excludeRevertedCommits;

    @Parameter(property = "codiqo.buildErrorCaptureLimit", defaultValue = "8192")
    protected int buildErrorCaptureLimit;

    @Parameter(property = "codiqo.pmdMinPriority", defaultValue = "high")
    protected String pmdMinPriority;

    @Parameter(property = "codiqo.pmdRules")
    protected String pmdRules;

    @Parameter(property = "codiqo.spotbugsPriorityThreshold", defaultValue = "1")
    protected int spotbugsPriorityThreshold;

    @Parameter(property = "codiqo.spotbugsOmitVisitors")
    protected String spotbugsOmitVisitors;

    @Parameter(property = "codiqo.llm.model", defaultValue = "deepseek-v4-pro:cloud")
    protected String llmModel;

    @Parameter(property = "codiqo.llm.apiKey")
    protected String llmApiKey;

    @Parameter(property = "codiqo.llm.baseUrl", defaultValue = "https://ollama.com/v1")
    protected String llmBaseUrl;

    @Parameter(property = "codiqo.llm.temperature", defaultValue = "0.0")
    protected double llmTemperature;

    @Parameter(property = "codiqo.llm.maxTokens", defaultValue = "32767")
    protected int llmMaxTokens;

    @Parameter(property = "codiqo.llm.numCtx")
    protected Integer llmNumCtx;

    @Parameter(property = "codiqo.llm.promptTokenBudget")
    protected int llmPromptTokenBudget;

    @Parameter(property = "codiqo.llm.maxCallersPerBlock")
    protected int llmMaxCallersPerBlock;

    @Parameter(property = "codiqo.llm.conventionFiles")
    protected String llmConventionFiles;

    @Parameter(property = "codiqo.llm.autoDiscoveryAgentInstructions", defaultValue = "true")
    protected boolean autoDiscoveryAgentInstructions;

    // -1 marks "unset" so that an explicit 0, which disables instruction loading, is forwarded rather than swallowed
    @Parameter(property = "codiqo.llm.conventionFilesMaxChars", defaultValue = "-1")
    protected int llmConventionFilesMaxChars;

    @Parameter(property = "codiqo.llm.seed", defaultValue = "42")
    protected Integer llmSeed;

    @Parameter(property = "codiqo.llm.enableWebSearchTool", defaultValue = "true")
    protected boolean llmEnableWebSearchTool;

    @Parameter(property = "codiqo.llm.validationMaxRetries", defaultValue = "1")
    protected short llmValidationMaxRetries;

    @Parameter(property = "codiqo.llm.readTimeoutSeconds", defaultValue = "600")
    protected long llmReadTimeoutSeconds;

    @Parameter(property = "codiqo.outputDirectory")
    protected File outputDirectory;

    @Parameter(property = "codiqo.includeBranches")
    protected String includeBranches;

    @Parameter(property = "codiqo.includeAuthorEmails")
    protected String includeAuthorEmails;

    @Parameter(property = "codiqo.excludeAuthorEmails")
    protected String excludeAuthorEmails;

    @Parameter(property = "codiqo.jdtUseSharedIndex", defaultValue = "true")
    protected boolean jdtUseSharedIndex;

    @Parameter(property = "codiqo.jdtIncludeDecompiledSources", defaultValue = "false")
    protected boolean jdtIncludeDecompiledSources;

    @Parameter(property = "codiqo.jdtDebugPort")
    protected Integer jdtDebugPort;

    @Parameter(property = "codiqo.jdtSourceExclusions",
            defaultValue = "org.scala-lang, org.apache.kafka, org.apache.pekko, org.apache.spark, org.apache.flink, com.typesafe.akka, com.typesafe, io.gatling, com.lightbend.lagom, com.twitter, org.json4s, org.scalactic, org.scalatest, org.jetbrains.kotlin, org.jetbrains.kotlinx, com.squareup.okhttp3, com.squareup.okio, org.junit.jupiter, org.springframework")
    protected String jdtSourceExclusions;

    @Parameter(property = "codiqo.driverScoreCapMultiplier", defaultValue = "2.5")
    protected double driverScoreCapMultiplier;

    @Parameter(property = "codiqo.driverFactorMaxDeviation", defaultValue = "0.75")
    protected double driverFactorMaxDeviation;

    @Parameter(property = "codiqo.driverScoreCapDryRun", defaultValue = "false")
    protected boolean driverScoreCapDryRun;

    @Parameter(property = "codiqo.timeMachineEnabled", defaultValue = "true")
    protected boolean timeMachineEnabled;

    @Parameter(property = "codiqo.moveDetectionEnabled", defaultValue = "true")
    protected boolean moveDetectionEnabled;

    @Parameter(property = "codiqo.moveSimilarityThreshold", defaultValue = "0.95")
    protected double moveSimilarityThreshold;

    @Parameter(property = "codiqo.movedLineCoefficient", defaultValue = "0.25")
    protected double movedLineCoefficient;

    @Override
    @SuppressWarnings("deprecation")
    public final Collection<File> apply(Artifact artifact) {
        for (;;) {
            try {
                /**
                 * codiqo extension artifacts (time-machine, coverage-injector, eventspy) are hosted on the repository
                 * the plugin itself was resolved from (a pluginRepository, e.g. central-snapshots), which the analyzed
                 * project's <repositories> typically does not list — so plugin repositories are consulted as fallback
                 */
                Map<String, RemoteRepository> repositories = new LinkedHashMap<>();
                for (RemoteRepository repo : ListUtils.union(remoteRepos, remotePluginRepos)) {
                    repositories.putIfAbsent(repo.getId(), repo);
                }

                CollectRequest collect = new CollectRequest();
                collect.setRoot(new org.eclipse.aether.graph.Dependency(artifact, null));
                collect.setRepositories(List.copyOf(repositories.values()));
                DependencyRequest req = new DependencyRequest(collect, null);
                DependencyResult result = repositorySystem.resolveDependencies(mavenSession.getRepositorySession(), req);
                return result
                        .getArtifactResults()
                        .stream()
                        .map(ArtifactResult::getArtifact)
                        .map(Artifact::getFile)
                        .collect(java.util.stream.Collectors.toUnmodifiableList());
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {
        RunArgs args = new RunArgs();
        Optional.ofNullable(javaHome).ifPresent(args::setJavaHome);
        Optional.ofNullable(mavenHome).ifPresent(args::setMavenHome);
        args.setBuildTimeout(Duration.ofMinutes(buildTimeoutMinutes));
        args.setTestTimeout(Duration.ofMinutes(testTimeoutMinutes));
        Optional.ofNullable(perTestTimeoutMinutes).ifPresent(minutes -> args.setPerTestTimeout(Duration.ofMinutes(minutes)));
        args.setImportTimeout(Duration.ofMinutes(importTimeoutMinutes));
        args.setLspQueryTimeout(Duration.ofSeconds(lspQueryTimeoutSeconds));
        args.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        args.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        args.setMaxRequests(maxRequests);
        args.setMaxRequestsPerHost(maxRequestsPerHost);
        args.setCpdMinimumTileSize(cpdMinimumTileSize);
        args.setDiffContextLines(diffContextLines);
        args.setJdtlsVersion(jdtlsVersion);
        args.setJdtlsUseSnapshot(jdtlsUseSnapshot);
        args.setDumpAnalysis(dumpAnalysis);
        args.setIgnoreCoverage(ignoreCoverage);
        args.setFailOnUninstrumentedModule(failOnUninstrumentedModule);
        args.setIgnoreCpd(ignoreCpd);
        args.setIgnoreDiagnostics(ignoreDiagnostics);
        args.setIgnoreComplexity(ignoreComplexity);
        args.setFailOnJdtlsError(failOnJdtlsError);
        args.setSkipOnBuildFailure(skipOnBuildFailure);
        args.setScoreOnBuildFailure(scoreOnBuildFailure);
        args.setExcludeRevertedCommits(excludeRevertedCommits);
        args.setBuildErrorCaptureLimit(Math.max(MIN_ABBREVIATE_WIDTH, buildErrorCaptureLimit));
        args.setPmdMinPriority(pmdMinPriority);
        if (StringUtils.isNotBlank(pmdRules)) {
            args.setPmdRules(Split.on(pmdRules, ','));
        }
        args.setSpotbugsPriorityThreshold(spotbugsPriorityThreshold);
        Optional.ofNullable(spotbugsOmitVisitors).ifPresent(args::setSpotbugsOmitVisitors);
        args.setLlmModel(llmModel);
        args.setLlmBaseUrl(llmBaseUrl);
        args.setLlmTemperature(llmTemperature);
        args.setLlmMaxTokens(llmMaxTokens);
        args.setLlmNumCtx(llmNumCtx);
        if (llmPromptTokenBudget > 0) {
            args.setLlmPromptTokenBudget(llmPromptTokenBudget);
        }
        if (llmMaxCallersPerBlock > 0) {
            args.setLlmMaxCallersPerBlock(llmMaxCallersPerBlock);
        }
        if (StringUtils.isNotBlank(llmConventionFiles)) {
            args.setLlmConventionFiles(Split.on(llmConventionFiles, ','));
        }
        args.setAutoDiscoveryAgentInstructions(autoDiscoveryAgentInstructions);
        if (llmConventionFilesMaxChars >= 0) {
            args.setLlmConventionFilesMaxChars(llmConventionFilesMaxChars);
        }
        args.setLlmSeed(llmSeed);
        args.setLlmEnableWebSearchTool(llmEnableWebSearchTool);
        args.setLlmValidationMaxRetries(llmValidationMaxRetries);
        args.setLlmReadTimeout(Duration.ofSeconds(llmReadTimeoutSeconds));
        Optional.ofNullable(outputDirectory).ifPresent(args::setOutputDirectory);
        Optional.ofNullable(includeBranches).ifPresent(args::setIncludeBranches);
        Optional.ofNullable(includeAuthorEmails).ifPresent(args::setIncludeAuthorEmails);
        Optional.ofNullable(excludeAuthorEmails).ifPresent(args::setExcludeAuthorEmails);
        args.setJdtUseSharedIndex(jdtUseSharedIndex);
        args.setJdtIncludeDecompiledSources(jdtIncludeDecompiledSources);
        args.setJdtDebugPort(jdtDebugPort);

        args.setDriverScoreCapMultiplier(driverScoreCapMultiplier);
        args.setDriverFactorMaxDeviation(driverFactorMaxDeviation);
        args.setDriverScoreCapDryRun(driverScoreCapDryRun);

        args.setTimeMachineEnabled(timeMachineEnabled);

        args.setMoveDetectionEnabled(moveDetectionEnabled);
        args.setMoveSimilarityThreshold(moveSimilarityThreshold);
        args.setMovedLineCoefficient(movedLineCoefficient);

        Env.resolveInto(llmApiKey, args::setLlmApiKey);
        args.validate();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("codiqo.versions")) {
            if (Objects.isNull(stream)) {
                throw new MojoExecutionException("resource not found on plugin classpath: codiqo.versions");
            }
            Properties versions = new Properties();
            versions.load(stream);
            for (DefaultArtifact agent : new DefaultArtifact[] {
                    new DefaultArtifact(LOMBOK_GROUP_ID, LOMBOK_ARTIFACT_ID, JAR_EXTENSION, versions.get("lombok.version").toString())
            }) {
                args.getAgents().addAll(apply(agent));
            }
            timeMachineExtensionJars = apply(new DefaultArtifact(CODIQO_GROUP_ID, TIME_MACHINE_ARTIFACT_ID, JAR_EXTENSION, versions.get("codiqo.version").toString()));

            /**
             * best-effort: the build-failure EventSpy is a purely diagnostic extension injected on every fork. if it
             * cannot be resolved (offline build, private mirror without the artifact), leave the jars null — buildProject
             * then falls back to scraping the console log instead of aborting the whole analysis.
             */
            try {
                buildEventSpyJars = apply(new DefaultArtifact(CODIQO_GROUP_ID, BUILD_EVENTSPY_ARTIFACT_ID, JAR_EXTENSION, versions.get("codiqo.version").toString()));
            } catch (Exception err) {
                getLog().debug("build-failure eventspy resolution failed", err);
            }

            if (BooleanUtils.negate(ignoreCoverage)) {
                /**
                 * agent resolution is best-effort: apply() sneaky-throws on an unresolvable artifact (offline build,
                 * private mirror without the runtime classifier), so catch it here and leave the jar null — coverage
                 * auto-injection then stays off instead of aborting the whole analysis. resolve the injector extension
                 * before the agent so a non-null agent jar always implies the injector jars are present too.
                 */
                try {
                    coverageInjectorJars = apply(new DefaultArtifact(CODIQO_GROUP_ID, COVERAGE_INJECTOR_ARTIFACT_ID, JAR_EXTENSION, versions.get("codiqo.version").toString()));
                    jacocoAgentJar = apply(new DefaultArtifact(JACOCO_GROUP_ID, JACOCO_AGENT_ARTIFACT_ID, JACOCO_AGENT_CLASSIFIER, JAR_EXTENSION, versions.get("jacoco.version").toString()))
                            .stream()
                            .filter(jar -> BooleanUtils.and(new boolean[] { jar.getName().startsWith(JACOCO_AGENT_ARTIFACT_ID + "-"), jar.getName().endsWith("-" + JACOCO_AGENT_CLASSIFIER + ".jar") }))
                            .findFirst()
                            .orElse(null);
                } catch (Exception err) {
                    getLog().warn("coverage auto-injection artifact resolution failed", err);
                }
                if (Objects.isNull(jacocoAgentJar)) {
                    getLog().warn(String.format("could not resolve %s:%s:%s; coverage auto-injection disabled for projects without jacoco-maven-plugin", JACOCO_GROUP_ID, JACOCO_AGENT_ARTIFACT_ID, JACOCO_AGENT_CLASSIFIER));
                }

                /**
                 * best-effort like the other extensions: apply() sneaky-throws on an unresolvable artifact, so catch it
                 * and leave the jars null — the per-test timeout then stays off instead of aborting the analysis. only
                 * resolved when the timeout is actually going to be applied (tests run only on this coverage path).
                 */
                if (Objects.nonNull(args.getPerTestTimeout()) && args.getPerTestTimeout().compareTo(Duration.ZERO) > 0) {
                    try {
                        surefireInjectorJars = apply(new DefaultArtifact(CODIQO_GROUP_ID, SUREFIRE_INJECTOR_ARTIFACT_ID, JAR_EXTENSION, versions.get("codiqo.version").toString()));
                    } catch (Exception err) {
                        getLog().warn("per-test timeout injector artifact resolution failed", err);
                    }
                }
            }
        } catch (IOException err) {
            throw new MojoExecutionException(err);
        }
        try (Repository orig = JGit.openRepository(project.getBasedir())) {
            args.setGit(orig);
            doPrepare(args);
            doExecute(args);
        } catch (Exception err) {
            throw new MojoFailureException(err);
        } finally {
            args.getProjects().forEach(prj -> {
                try {
                    prj.close();
                } catch (IOException e) {
                    getLog().warn("failed to close project: " + project.getName(), e);
                }
            });
            FileUtils.deleteQuietly(args.getTimeMachineMetaDir());
        }
    }
    protected ClassGraphSpec scanProjects(RunArgs args, Collection<MavenProject> projects) {
        Set<URI> jars = new LinkedHashSet<>();
        projects.stream()
                .filter(reactor -> BooleanUtils.negate(NON_CODE_PACKAGINGS.contains(reactor.getPackaging())))
                .filter(reactor -> CollectionUtils.isEmpty(reactor.getModules())).filter(reactor -> {
                    for (;;) {
                        try {
                            return BooleanUtils.or(new boolean[] {
                                    CollectionUtils.isNotEmpty(reactor.getCompileClasspathElements()),
                                    CollectionUtils.isNotEmpty(reactor.getTestClasspathElements()),
                            });
                        } catch (Exception err) {
                            ExceptionUtils.wrapAndThrow(err);
                        }
                    }
                }).forEach(prj -> {
                    MavenProjectWrapper toReturn = new MavenProjectWrapper();
                    toReturn.setId(prj.getId());
                    toReturn.setCode(prj.getGroupId() + ":" + prj.getArtifactId());
                    toReturn.setGroupId(prj.getGroupId());
                    toReturn.setArtifactId(prj.getArtifactId());
                    toReturn.setName(prj.getName());
                    toReturn.setPackaging(prj.getPackaging());
                    toReturn.setDescription(prj.getDescription());
                    toReturn.setVersion(prj.getVersion());
                    toReturn.setBaseDirectory(prj.getBasedir());
                    toReturn.setOutputDirectory(new File(prj.getBuild().getOutputDirectory()));
                    for (Entry<Object, Object> entry : prj.getProperties().entrySet()) {
                        toReturn.getProperties().put(entry.getKey().toString(), entry.getValue().toString());
                    }
                    if (Objects.nonNull(prj.getParent())) {
                        toReturn.setParent(Optional.of(prj.getParent().getId()));
                    }
                    File jacocoDestFile = new File(prj.getBuild().getDirectory(), JACOCO_EXEC_FILE);
                    if (jacocoDestFile.exists()) {
                        toReturn.setCoverage(Optional.of(jacocoDestFile));
                    }
                    try {
                        prj.getCompileSourceRoots().forEach(root -> {
                            File file = new File(root);
                            if (file.exists()) {
                                toReturn.getCompileSourceRoots().add(file);
                            }
                        });
                        prj.getCompileClasspathElements().forEach(element -> {
                            File file = new File(element);
                            if (file.exists()) {
                                toReturn.getCompileClasspathElements().add(file);
                                jars.add(file.toURI());
                            }
                        });
                    } catch (DependencyResolutionRequiredException err) {
                        ExceptionUtils.wrapAndThrow(err);
                    }
                    try {
                        prj.getTestCompileSourceRoots().forEach(root -> {
                            File file = new File(root);
                            if (file.exists()) {
                                toReturn.getTestCompileSourceRoots().add(file);
                            }
                        });
                        prj.getTestClasspathElements().forEach(element -> {
                            File file = new File(element);
                            if (file.exists()) {
                                toReturn.getTestClasspathElements().add(file);
                                jars.add(file.toURI());
                            }
                        });
                    } catch (DependencyResolutionRequiredException err) {
                        ExceptionUtils.wrapAndThrow(err);
                    }
                    prj.getArtifacts().forEach(artifact -> {
                        File file = artifact.getFile();
                        if (Objects.nonNull(file) && file.exists()) {
                            toReturn.getArtifacts().put(artifact, file);
                        }
                    });
                    args.getProjects().add(toReturn);
                });

        StopWatch stopWatch = StopWatch.createStarted();
        ClassGraph classGraph = new ClassGraph().enableAllInfo();
        jars.forEach(classGraph::overrideClasspath);
        classGraph.enableSystemJarsAndModules();
        ScanResult scan = classGraph.scan();

        ClassGraphSpec graphSpec = new ClassGraphWrapper(scan);
        args.getProjects().forEach(spec -> {
            if (spec instanceof MavenProjectWrapper) {
                ((MavenProjectWrapper) spec).setScan(graphSpec);
            }
        });

        stopWatch.stop();
        getLog().info(String.format("configured maven projects in %s, classgraph classes: %d", stopWatch, graphSpec.getAllClasses().size()));
        return graphSpec;
    }
    protected void resolveCommit(RunArgs args, String commitId) throws Exception {
        ObjectId objectId = args.getGit().resolve(commitId);
        if (Objects.isNull(objectId)) {
            try (Git git = Git.wrap(args.getGit())) {
                git.fetch()
                        .setRemote(Constants.DEFAULT_REMOTE_NAME)
                        .setTagOpt(TagOpt.NO_TAGS)
                        .setTimeout((int) args.getReadTimeout().getSeconds())
                        .setRefSpecs(Constants.R_HEADS + "*", Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + "*")
                        .setTransportConfigCallback(transport -> {
                            if (transport instanceof HttpTransport http) {
                                http.setTimeout((int) args.getReadTimeout().getSeconds());
                            }
                        }).call();
                objectId = args.getGit().resolve(commitId);
                if (Objects.isNull(objectId)) {
                    throw new MojoFailureException("failed to resolve commit ID: " + commitId);
                }
            }
        }
    }
    protected InvocationRequest invocationRequest(RunArgs args, boolean timeMachineRequested, Duration targetOffset) throws IOException {
        File rootPom = new File(args.getGit().getWorkTree(), "pom.xml");
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(rootPom);
        if (args.isIgnoreCoverage()) {
            request.addArgs(List.of(
                    "clean",
                    "verify",
                    "-DskipTests=true",
                    "-Djacoco.skip=true",
                    "-Dmaven.javadoc.skip=true",
                    "-Dmdep.analyze.skip=true"));
        } else {
            long surefireTimeout = args.getTestTimeout().getSeconds();
            long surefireExitTimeout = args.getBuildTimeout().minusMinutes(1).getSeconds();
            /**
             * jacoco.skip=true is deliberate: codiqo owns coverage in the fork. the injector extension attaches its
             * own agent to every module (uniform per-module destfile) and strips dangling agent-property tokens, so
             * the project's jacoco — whatever its shape: per-module, aggregated destFile, or misconfigured argLine —
             * never runs and never interferes.
             */
            request.addArgs(List.of(
                    "clean",
                    "verify",
                    "-DskipTests=false",
                    "-DfailIfNoTests=false",
                    "-Djacoco.skip=true",
                    "-Dmaven.test.failure.ignore=true",
                    "-Dmaven.javadoc.skip=true",
                    "-Dmdep.analyze.skip=true",
                    "-Dsurefire.timeout=" + surefireTimeout,
                    "-Dsurefire.forkedProcessExitTimeoutInSeconds=" + surefireExitTimeout));
        }
        request.setTimeoutInSeconds((int) args.getBuildTimeout().getSeconds());
        request.setBatchMode(true);
        request.setThreads(String.valueOf(mavenSession.getRequest().getDegreeOfConcurrency()));
        if (Objects.nonNull(javaHome)) {
            request.setJavaHome(javaHome);
        }
        if (Objects.nonNull(mavenHome)) {
            request.setMavenHome(mavenHome);
        }

        boolean timeMachineActive = BooleanUtils.and(new boolean[] { StringUtils.isNotBlank(args.getCommitId()), timeMachineRequested });
        boolean injectJacocoAgent = BooleanUtils.and(new boolean[] { BooleanUtils.negate(args.isIgnoreCoverage()), Objects.nonNull(jacocoAgentJar) });
        boolean perTestTimeoutActive = BooleanUtils.negate(args.isIgnoreCoverage())
                && Objects.nonNull(args.getPerTestTimeout())
                && args.getPerTestTimeout().compareTo(Duration.ZERO) > 0;

        /**
         * codiqo core extensions are loaded on the forked build's maven.ext.class.path. the build-failure EventSpy is
         * injected on every run so structured failure detail is always captured; the time-machine snapshot resolver
         * (codiqo-maven-time-machine, with the Google/Aether stack), the JaCoCo agent injector
         * (codiqo-maven-coverage-injector), and the per-test timeout injector (codiqo-maven-surefire-injector) are
         * added only when their own feature is active — so a coverage-only run never puts the time-machine resolver on
         * the extension realm, and vice versa.
         */
        List<String> extensionClasspath = new ArrayList<>();
        Properties props = Optional.ofNullable(request.getProperties()).orElseGet(Properties::new);

        if (CollectionUtils.isNotEmpty(buildEventSpyJars)) {
            extensionClasspath.addAll(extensionJarPaths(buildEventSpyJars));

            File reportFile = File.createTempFile("codiqo-buildfail-", ".log");
            reportFile.deleteOnExit();
            args.setBuildFailureReportFile(reportFile);
            props.setProperty(BuildFailureConfig.PROP_REPORT_FILE, reportFile.getAbsolutePath());
        }

        if (timeMachineActive) {
            extensionClasspath.addAll(extensionJarPaths(timeMachineExtensionJars));

            Instant ts = TimeMachineSupport.resolveCommitTimestamp(args);
            FileUtils.deleteQuietly(args.getTimeMachineMetaDir());
            File metaDir = Files.createTempDirectory("codiqo-tm-").toFile();
            args.setTimeMachineMetaDir(metaDir);

            /**
             * a private local repository holding one timestamp per snapshot. Maven maps a -SNAPSHOT to a timestamp
             * through maven-metadata-<repoId>.xml and the -SNAPSHOT alias file in the version directory, and both are
             * overwritten by whichever attempt resolved last — so a repository shared across the back-off ladder
             * accumulates the pins of every rung, and the language server's m2e, which resolves independently, can
             * pick a version this build never used. Clearing every snapshot before each attempt leaves exactly the
             * successful attempt's pins behind, which is what the JDT import then reads.
             *
             * The path is stable per project rather than a fresh temp directory: JDT's shared index is keyed by the
             * absolute path of the jar it indexed, so a new path per commit would miss on every dependency and
             * re-index the whole set each time. Releases are immutable, so they are seeded once and survive; only
             * snapshots are volatile and only they are cleared.
             */
            File localRepository = privateLocalRepository();
            if (localRepository.isDirectory()) {
                clearSnapshots(localRepository);
            } else {
                FileUtils.forceMkdir(localRepository);
                seedReleaseArtifacts(new File(mavenSession.getRepositorySession().getLocalRepositoryManager().getRepository().getBasedir().getAbsolutePath()), localRepository);
            }
            args.setLocalRepositoryDir(localRepository);

            FileUtils.deleteQuietly(args.getMavenUserSettings());
            args.setMavenUserSettings(writeLocalRepositorySettings(localRepository));
            request.setLocalRepositoryDirectory(localRepository);

            props.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, DateTimeFormatter.ISO_INSTANT.format(ts));
            props.setProperty(TimeMachineConfig.PROP_META_DIR, metaDir.getAbsolutePath());
            if (targetOffset.compareTo(Duration.ZERO) > 0) {
                props.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, targetOffset.toString());
            }
            getLog().info(String.format("time-machine enabled for commit %s (timestamp: %s, offset: %s, metaDir: %s, localRepository: %s)",
                    args.getCommitId(), ts, targetOffset, metaDir.getAbsolutePath(), localRepository.getAbsolutePath()));
        } else {
            /**
             * a non-time-machine attempt may follow a failed time-machine attempt; drop the failed attempt's
             * sidecar dir so ProjectModelPopulator never attaches its metadata to this build's submission, and
             * stop pointing at the private repository so the fallback resolves LATEST from the shared one —
             * nothing is pinned here, so the JDT import stays online against the default local repository. The
             * private repository itself is left on disk: its seeded releases are immutable and the next commit
             * reuses them, and its snapshots are cleared before they are ever read again.
             */
            FileUtils.deleteQuietly(args.getTimeMachineMetaDir());
            args.setTimeMachineMetaDir(null);

            FileUtils.deleteQuietly(args.getMavenUserSettings());
            args.setLocalRepositoryDir(null);
            args.setMavenUserSettings(null);
        }

        if (injectJacocoAgent) {
            extensionClasspath.addAll(extensionJarPaths(coverageInjectorJars));

            props.setProperty(CoverageInjectorConfig.PROP_AGENT_JAR, jacocoAgentJar.getAbsolutePath());
            getLog().info("JaCoCo agent injection enabled for modules without jacoco-maven-plugin: " + jacocoAgentJar.getAbsolutePath());
        }

        if (perTestTimeoutActive) {
            if (CollectionUtils.isNotEmpty(surefireInjectorJars)) {
                extensionClasspath.addAll(extensionJarPaths(surefireInjectorJars));

                long perTestSeconds = args.getPerTestTimeout().getSeconds();
                props.setProperty(SurefireInjectorConfig.PROP_PER_TEST_TIMEOUT_SECONDS, String.valueOf(perTestSeconds));
                getLog().info(String.format("per-test timeout injection enabled: %ds per JUnit 5 test method (SEPARATE_THREAD)", perTestSeconds));
            } else {
                getLog().warn("per-test timeout requested but codiqo-maven-surefire-injector could not be resolved — per-test timeout not applied");
            }
        }

        if (CollectionUtils.isNotEmpty(extensionClasspath)) {
            props.setProperty(MAVEN_EXT_CLASS_PATH, StringUtils.join(extensionClasspath.stream().distinct().toList(), File.pathSeparator));
            request.setProperties(props);
        }
        return request;
    }
    private static List<String> extensionJarPaths(Collection<File> jars) {
        return jars.stream().map(File::getAbsolutePath).toList();
    }
    /**
     * one repository per analyzed project, stable across commits so JDT's path-keyed shared index keeps hitting,
     * and distinct per project so two analyses on one machine cannot interleave their pins.
     */
    private File privateLocalRepository() {
        String key = DigestUtils.sha1Hex(mavenSession.getTopLevelProject().getBasedir().getAbsolutePath()).substring(0, 12);
        return new File(FileUtils.getTempDirectory(), PRIVATE_REPOSITORY_PREFIX + key);
    }
    /**
     * remove every snapshot from the private repository, leaving the seeded releases in place. Releases are
     * immutable so re-seeding them per attempt would only cost I/O; snapshots are the volatile part, and a stale
     * one is exactly what would let m2e resolve a version this build never used.
     */
    private void clearSnapshots(File localRepository) throws IOException {
        AtomicInteger cleared = new AtomicInteger();

        Files.walkFileTree(localRepository.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException err) {
                getLog().warn(String.format("could not inspect %s while clearing snapshots: %s", file, err.getMessage()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (ArtifactUtils.isSnapshot(dir.getFileName().toString())) {
                    FileUtils.deleteDirectory(dir.toFile());
                    cleared.incrementAndGet();
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        getLog().info(String.format("cleared %d snapshot versions from %s", cleared.get(), localRepository.getAbsolutePath()));
    }
    /**
     * Hard-link every release artifact from the host's local repository into this attempt's private one. A release
     * is immutable, so sharing the inode costs no disk and spares re-downloading the bulk of a reactor's
     * dependencies into an otherwise cold repository. Everything mutable is deliberately left behind: -SNAPSHOT
     * directories, so the time-machine resolves the commit's own pins onto a clean slate rather than finding a
     * newer timestamp already cached, and all resolution metadata, since it records what today's registry offered.
     * _remote.repositories goes too — without it Maven treats a cached artifact as locally installed and always
     * serves it, instead of refusing one whose recording repository id is out of scope.
     */
    private void seedReleaseArtifacts(File source, File target) throws IOException {
        Path sourceRoot = source.toPath();
        Path targetRoot = target.toPath();
        AtomicBoolean copyFallback = new AtomicBoolean();

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
            /**
             * a local repository under concurrent use holds transient download fragments, lock files and entries
             * that vanish between the directory listing and the visit. The default implementation rethrows, which
             * would turn a seeding optimisation into a hard failure for the whole commit — seeding is best-effort
             * by nature, since anything missing is simply downloaded again.
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException err) {
                getLog().warn(String.format("could not seed %s into the private local repository: %s", file, err.getMessage()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                /**
                 * ArtifactUtils rather than a "-SNAPSHOT" suffix test: it is Maven's own definition, and it also
                 * recognises an already-timestamped version, so a repository that happens to hold one is skipped
                 * too instead of being seeded as if it were a release
                 */
                if (ArtifactUtils.isSnapshot(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isSeedable(file.getFileName().toString())) {
                    Path link = targetRoot.resolve(sourceRoot.relativize(file));
                    Files.createDirectories(link.getParent());
                    /**
                     * a hard link fails across filesystems (the temp dir and the local repository need not share
                     * one), and on filesystems that do not support them at all — copy is correct either way, just
                     * slower, so it stays the fallback rather than the default
                     */
                    try {
                        Files.createLink(link, file);
                    } catch (IOException | UnsupportedOperationException err) {
                        if (copyFallback.compareAndSet(false, true)) {
                            getLog().warn(String.format(
                                    "cannot hard-link into %s (%s) — seeding falls back to copying, which duplicates the release set on disk. Point java.io.tmpdir at the same filesystem as the local repository to avoid it.",
                                    targetRoot, err.getMessage()));
                        }
                        Files.copy(file, link);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
    /**
     * Maven exposes no API that classifies an arbitrary repository path, so the resolution bookkeeping is matched
     * by name — these are the four artifacts the local repository layout writes alongside a downloaded file.
     */
    /**
     * source JARs are excluded on top of the resolution bookkeeping: the language server is configured with
     * downloadSources=false so it never needs them, and purgeNonJavaSourceJars deletes Kotlin/Scala ones from the
     * HOST repository to stop JDT hanging during call-hierarchy resolution — a hard link here would survive that
     * delete and hand the language server the very file the purge exists to remove.
     */
    /**
     * Aether parses the protocol with a regex that also understands compound schemes such as dav:http, so the
     * repository is built and asked rather than the URL being split on "://" — a naive split misreads those and
     * returns the whole string for a URL with no scheme at all.
     */
    private static boolean isUntransportableByM2e(String url) {
        String protocol = new RemoteRepository.Builder("probe", "default", url).build().getProtocol();
        return BooleanUtils.negate(M2E_TRANSPORTABLE_PROTOCOLS.contains(StringUtils.lowerCase(protocol)));
    }
    private static boolean isSeedable(String fileName) {
        return BooleanUtils.negate(BooleanUtils.or(new boolean[] {
                Strings.CS.startsWith(fileName, "maven-metadata"),
                Strings.CS.endsWith(fileName, ".lastUpdated"),
                Strings.CS.equals(fileName, "resolver-status.properties"),
                Strings.CS.equals(fileName, "_remote.repositories"),
                Strings.CS.endsWith(fileName, SOURCES_JAR_SUFFIX) }));
    }
    /**
     * m2e reads its own user settings, not the forked build's -Dmaven.repo.local, so the only way to point the
     * language server at this attempt's private repository is a settings file naming it. That file must EXTEND the
     * host settings rather than replace them: jdt.ls treats userSettings as the whole user configuration, and the
     * mirrors, servers and registry properties in ~/.m2/settings.xml exist precisely because m2e is a separate
     * process — drop them and a project whose POMs declare ${artifact.registry.url} has no way to interpolate or
     * reach that repository, which is the zero-callers failure this whole mechanism is meant to remove. The
     * session's effective settings are already the merge of global and user files, so clone them (mutating the
     * session's own instance would leak the private repository into the host build) and override one field.
     */
    private File writeLocalRepositorySettings(File localRepository) throws IOException {
        File settingsFile = File.createTempFile("codiqo-m2-settings-", ".xml");
        settingsFile.deleteOnExit();

        /**
         * m2e gets the host settings with the local repository redirected, because it must be able to fetch what
         * the fork did not happen to leave behind — plugin descriptors read for lifecycle mapping, a parent reached
         * before the POM's own <repositories>. Denying it that produces a workspace with no Java project at all,
         * which is the zero-callers failure this mechanism exists to remove. Snapshot pinning does not depend on
         * the network being closed: the private repository holds one timestamp per snapshot in freshly written
         * maven-metadata, and updateSnapshots stays off, so m2e resolves the pins the fork built against.
         *
         * Repositories m2e cannot transport are dropped rather than passed through. Aether ships http/https/file
         * only, and a URL like artifactregistry:// is served by a .mvn/extensions.xml wagon that m2e never loads;
         * left in, every resolution through it fails and the import collapses. Dropping them costs nothing it
         * could have used, and the clone keeps the mirrors and credentials for the ones it can.
         */
        Settings settings = mavenSession.getSettings().clone();
        settings.setLocalRepository(localRepository.getAbsolutePath());
        settings.getMirrors().removeIf(mirror -> isUntransportableByM2e(mirror.getUrl()));
        settings.getProfiles().forEach(profile -> {
            profile.getRepositories().removeIf(repository -> isUntransportableByM2e(repository.getUrl()));
            profile.getPluginRepositories().removeIf(repository -> isUntransportableByM2e(repository.getUrl()));
        });

        try (Writer writer = Files.newBufferedWriter(settingsFile.toPath(), StandardCharsets.UTF_8)) {
            new SettingsXpp3Writer().write(writer, settings);
        }
        /**
         * the clone carries the host's server credentials, so the file is readable only by this user — the
         * analysis leaves it behind for the language server to read while the fork runs
         */
        settingsFile.setReadable(false, false);
        settingsFile.setReadable(true, true);
        return settingsFile;
    }
    protected void warnIfHostTimeMachineMissing() {
        /**
         * the extension's core realm is not visible from the plugin realm, so a Class.forName probe always fails
         * even when the extension is active; detect it the same way the re-launch hint loads it — via the host
         * Maven's maven.ext.class.path
         */
        if (Strings.CS.contains(System.getProperty(MAVEN_EXT_CLASS_PATH), TIME_MACHINE_ARTIFACT_ID)) {
            return;
        }
        getLog().warn(String.format(
                "codiqo-maven-time-machine is not loaded in the host Maven — host-side POM model building resolves LATEST snapshots and may fail on historical commits whose POMs no longer interpolate against them. relaunch with: -Dmaven.ext.class.path=%s",
                StringUtils.join(extensionJarPaths(timeMachineExtensionJars), File.pathSeparator)));
    }
    protected BuildOutcome buildProject(
            RunArgs args,
            InvocationRequest request,
            ProjectBuildingRequest buildingRequest) throws Exception {
        File rootPom = new File(args.getGit().getWorkTree(), "pom.xml");

        CapturingOutputHandler sysout = new CapturingOutputHandler(new PrintStreamHandler(System.out, false), args.getBuildErrorCaptureLimit());
        CapturingOutputHandler syserr = new CapturingOutputHandler(new PrintStreamHandler(System.err, false), args.getBuildErrorCaptureLimit());
        request.setOutputHandler(sysout);
        request.setErrorHandler(syserr);

        Invoker invoker = new DefaultInvoker();
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            if (result.getExecutionException() instanceof CommandLineTimeOutException) {
                if (args.isSkipOnBuildFailure()) {
                    String reason = "fork build timed out after " + args.getBuildTimeout();
                    getLog().warn(reason + ", skipping with category " + AnalysisExcludeCategory.BUILD_FAILURE);
                    return new BuildOutcome.Skipped(reason, AnalysisExcludeCategory.BUILD_FAILURE, buildFailureDetail(args, sysout, syserr));
                }
                getLog().warn("maven build timed out after " + args.getBuildTimeout() + " — test coverage may be incomplete");
            } else if (args.isSkipOnBuildFailure()) {
                String reason = Optional.ofNullable(sysout.firstErrorLine())
                        .or(() -> Optional.ofNullable(syserr.firstErrorLine()))
                        .orElse("fork build failed (exit code " + result.getExitCode() + ")");
                List<String> helpLines = new ArrayList<>();
                helpLines.addAll(sysout.helpUrlLines());
                helpLines.addAll(syserr.helpUrlLines());
                AnalysisExcludeCategory category = Maven.classifyForkFailure(helpLines);
                getLog().warn(String.format("fork build failed (exit code %d), skipping with category %s: %s", result.getExitCode(), category, reason));
                return new BuildOutcome.Skipped(reason, category, buildFailureDetail(args, sysout, syserr));
            } else {
                throw new MojoExecutionException("maven build failed in fork", result.getExecutionException());
            }
        }
        return new BuildOutcome.Proceeded(TimeMachineSupport.withHostPinning(args, getLog(), () -> projectBuilder.build(rootPom, buildingRequest)));
    }
    private String buildFailureDetail(RunArgs args, CapturingOutputHandler sysout, CapturingOutputHandler syserr) {
        return readBuildFailureReport(args)
                .or(sysout::capturedDetail)
                .or(syserr::capturedDetail)
                .orElse(null);
    }
    private Optional<String> readBuildFailureReport(RunArgs args) {
        File reportFile = args.getBuildFailureReportFile();
        if (Objects.nonNull(reportFile) && reportFile.isFile() && reportFile.length() > 0L) {
            try (BoundedReader reader = new BoundedReader(Files.newBufferedReader(reportFile.toPath()), args.getBuildErrorCaptureLimit())) {
                String content = IOUtils.toString(reader);
                return StringUtils.isNotEmpty(content) ? Optional.of(content) : Optional.empty();
            } catch (IOException err) {
                getLog().debug("failed to read build-failure report " + reportFile, err);
            }
        }
        return Optional.empty();
    }
    protected BuildOutcome resolveDependenciesOffline(RunArgs args) throws Exception {
        File rootPom = new File(args.getGit().getWorkTree(), "pom.xml");

        ProjectBuildingRequest request = Maven.buildingRequest(mavenSession);
        Properties systemProperties = new Properties();
        if (Objects.nonNull(request.getSystemProperties())) {
            systemProperties.putAll(request.getSystemProperties());
        }
        systemProperties.putAll(Maven.detectOsProperties());
        request.setSystemProperties(systemProperties);
        Maven.pinMultiModuleProjectDirectory(request, args.getGit().getWorkTree());

        if (StringUtils.isNotBlank(args.getCommitId())) {
            request.setResolveDependencies(false);
        }
        if (Objects.nonNull(args.getTimeMachineTargetOffset())) {
            Maven.isolateRepositorySession(request);
        }
        if (Objects.nonNull(args.getLocalRepositoryDir())) {
            Maven.pinLocalRepository(repositorySystem, request, args.getLocalRepositoryDir());
        }

        try {
            return new BuildOutcome.Proceeded(TimeMachineSupport.withHostPinning(args, getLog(), () -> projectBuilder.build(rootPom, request)));
        } catch (ProjectBuildingException pbe) {
            return classifyProjectBuildingException(pbe);
        }
    }
    protected Optional<BuildOutcome.Skipped> buildAndCollectModules(
            MavenProject parent,
            File baseDir,
            ProjectBuildingRequest buildingRequest,
            RunArgs args,
            Collection<MavenProject> collected) throws Exception {
        if (CollectionUtils.isEmpty(parent.getModules())) {
            collected.add(parent);
            return Optional.empty();
        }
        for (String moduleName : parent.getModules()) {
            File modulePom = new File(new File(baseDir, moduleName), "pom.xml");
            if (modulePom.exists()) {
                /**
                 * fresh isolated session per module build: resolveDependencies=true on an earlier module reads
                 * REMOTE sibling POMs (latest snapshot deploys) whose lineage shares GAVs with the local
                 * checkout — those raw models enter the shared session model cache and a
                 * later module's parent/import chain then assembles against the anachronistic remote lineage,
                 * dropping managed versions ('dependencies.dependency.version is missing' broken-POM failures)
                 */
                Maven.isolateRepositorySession(buildingRequest);
                if (Objects.nonNull(args.getLocalRepositoryDir())) {
                    Maven.pinLocalRepository(repositorySystem, buildingRequest, args.getLocalRepositoryDir());
                }

                ProjectBuildingResult moduleResult;
                try {
                    moduleResult = TimeMachineSupport.withHostPinning(args, getLog(), () -> projectBuilder.build(modulePom, buildingRequest));
                } catch (ProjectBuildingException pbe) {
                    return Optional.of(classifyProjectBuildingException(pbe));
                }

                MavenProject moduleProject = moduleResult.getProject();
                if (CollectionUtils.isEmpty(moduleProject.getModules())) {
                    collected.add(moduleProject);
                } else {
                    Optional<BuildOutcome.Skipped> nested = buildAndCollectModules(
                            moduleProject,
                            new File(baseDir, moduleName),
                            buildingRequest,
                            args,
                            collected);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
            }
        }
        return Optional.empty();
    }
    protected void doPrepare(RunArgs args) throws Exception {
        if (StringUtils.isEmpty(args.getDefaultBranch())) {
            JGit.detectDefaultBranch(args.getGit()).ifPresent(args::setDefaultBranch);
        }
        if (args.getRemoteUrls().isEmpty()) {
            StoredConfig config = args.getGit().getConfig();
            if (Objects.nonNull(config)) {
                Set<String> remotes = config.getSubsections("remote");
                if (CollectionUtils.isNotEmpty(remotes)) {
                    args.setRemoteUrls(remotes.stream().map(remote -> config.getString("remote", remote, "url")).collect(Collectors.toSet()));
                }
            }
        }
    }
    protected void doExecute(RunArgs args) throws Exception {
        Optional<SubmissionContext> opt = doAnalyze(args);
        if (opt.isPresent()) {
            SubmissionContext ctx = opt.get();
            if (ctx.getAnalysis().isRevertCommit()) {
                doExcludeRevertCommit(args, ctx);
            } else {
                doLlmScoring(ctx);
            }
        }
    }
    protected Optional<SubmissionContext> doAnalyze(RunArgs args) throws Exception {
        purgeNonJavaSourceJars(args);

        LogFactory logFactory = new MavenLogFactory(getLog());
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                getLog().info(MemoryReport.snapshot("before language server load"));
                registry.load();
                getLog().info(MemoryReport.snapshot("after language server load"));
                MutableBoolean toApply = new MutableBoolean();
                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, args);
                CommitAnalysis analysis = analyzer.analyze();
                List<String> changedFiles = new ArrayList<>();
                analysis.forEach(diff -> {
                    String name = diff.getFile().getName();
                    changedFiles.add(name);
                    if (BooleanUtils.or(new boolean[] {
                            FilenameUtils.isExtension(name, registry.extensions()),
                            ConfigFiles.isConfigFile(name)
                    })) {
                        toApply.setTrue();
                    }
                });
                String skipReason = null;
                AnalysisExcludeCategory skipCategory = null;
                if (toApply.isFalse()) {
                    skipReason = String.format("no diff files match registered languages %s or supported config files (pom.xml, .proto) — changed files: %s", registry.extensions(), changedFiles);
                    skipCategory = AnalysisExcludeCategory.NO_ANALYZABLE_DIFF;
                    getLog().warn(String.format("commit %s skipped: %s", args.getCommitId(), skipReason));
                }

                boolean branchMatches = args.matchesByBranch(analysis.getBranches());
                boolean authorMatches = args.matchesByAuthor(analysis.getAuthorEmail());
                if (BooleanUtils.or(new boolean[] {
                        BooleanUtils.negate(branchMatches),
                        BooleanUtils.negate(authorMatches)
                })) {
                    skipReason = String.format("filtered by include-rules — branch match: %s (branches=%s), author match: %s (author=%s)",
                            branchMatches,
                            analysis.getBranches(),
                            authorMatches,
                            analysis.getAuthorEmail());
                    skipCategory = AnalysisExcludeCategory.FILTERED_BY_RULES;
                    getLog().warn(String.format("commit %s skipped: %s", args.getCommitId(), skipReason));
                    toApply.setFalse();
                }

                ClientInfoModel clientInfo = new ClientInfoModel();
                clientInfo.setBuildTool(ClientInfoModel.BuildToolEnum.MAVEN);
                clientInfo.setVersion(runtimeInformation.getMavenVersion());
                clientInfo.setName("codiqo-maven-plugin");

                if (Objects.nonNull(skipReason)) {
                    /**
                     * excluded commits still carry the raw git diff so the backend persists per-file
                     * changes (mirrors the build-failure exclusion path) — indexing has not run, so
                     * the populator emits diff-only file models without code units or coverage
                     */
                    SubmissionContext excludeCtx = SubmissionContext.create(
                            args,
                            null,
                            analysis,
                            workTree,
                            logFactory,
                            project.getGroupId() + ":" + project.getArtifactId(),
                            project.getName(),
                            clientInfo);
                    new FileAnalysisPopulator().accept(excludeCtx);
                    doExcludeAnalysis(args.getCommitId(), skipReason, skipCategory, null, excludeCtx.getSubmissionModel().getFiles());
                }
                if (toApply.isTrue()) {
                    IndexingSummary index = registry.index(analysis);
                    getLog().info(MemoryReport.snapshot("after index"));
                    registry.identifyAffectedSymbols(index, analysis);
                    getLog().info(MemoryReport.snapshot("after identify symbols"));
                    try {
                        registry.collectAndCapture(index, analysis);
                    } catch (IOException err) {
                        /**
                         * only the coverage-required capture failure surfaces as IOException; treat it as a build
                         * failure. codiqo-internal defects (RuntimeExceptions from symbol capture, JDT, etc.) are not
                         * caught here so they propagate and fail loudly instead of being mislabeled BUILD_FAILURE.
                         */
                        if (BooleanUtils.and(new boolean[] { StringUtils.isNotBlank(args.getCommitId()), args.isSkipOnBuildFailure() })) {
                            String reason = StringUtils.defaultIfBlank(err.getMessage(), err.getClass().getSimpleName());
                            getLog().warn(String.format("commit %s: analysis failed after build: %s", args.getCommitId(), reason), err);
                            doDegradedAnalysis(args, reason, AnalysisExcludeCategory.BUILD_FAILURE,
                                    StringUtils.abbreviate(ExceptionUtils.getStackTrace(err), args.getBuildErrorCaptureLimit()));
                            return Optional.empty();
                        }
                        throw err;
                    }
                    getLog().info(MemoryReport.snapshot("after collect capture"));

                    SubmissionContext ctx = SubmissionContext.create(
                            args,
                            index,
                            analysis,
                            workTree,
                            logFactory,
                            project.getGroupId() + ":" + project.getArtifactId(),
                            project.getName(),
                            clientInfo);
                    new ProjectModelPopulator(getLog()).accept(ctx);
                    new CommitModelPopulator().accept(ctx);
                    new ModuleLevelMetricsPopulator().accept(ctx);
                    new FileAnalysisPopulator().accept(ctx);
                    new EffectiveChangePopulator().accept(ctx);
                    new IndexModelPopulator().accept(ctx);
                    DuplicationReportPopulator duplicationPopulator = new DuplicationReportPopulator();
                    duplicationPopulator.accept(ctx);
                    new MetricsAggregator(duplicationPopulator.getTotalDuplicatedLines()).accept(ctx);
                    new ExcludedCoverageClassPopulator().accept(ctx);
                    new SubmissionSummaryPrinter(getLog()).accept(ctx);
                    // set before the dump so codiqo-submission-<sha> replays carry the effective config
                    ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
                    applyAgentInstructions(ctx, args);
                    new OutputSerializer(preferYaml, logFactory.getLogger(OutputSerializer.class)).accept(ctx);
                    return Optional.of(ctx);
                }
            }
        }
        return Optional.empty();
    }
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        ExecutorService executor = DaemonExecutors.newCachedDaemonPool("codiqo-openai");
        try {
            new LlmScoringPopulator(getLog(), executor).accept(ctx);
        } finally {
            executor.shutdown();
        }
    }
    /**
     * shared revert gate for the successful-build and degraded paths: the revert itself is excluded
     * unconditionally, and with codiqo.excludeRevertedCommits enabled the original (reverted) commit
     * is retroactively excluded too — the backend flips its already-scored analysis to excluded, so
     * reverted work stops counting toward effort. 404 means the original predates the indexing window
     */
    protected void doExcludeRevertCommit(RunArgs args, SubmissionContext ctx) throws Exception {
        getLog().warn(String.format("commit %s skipped: revert commit (no LLM scoring or submission)", args.getCommitId()));
        doExcludeAnalysis(args.getCommitId(), "revert commit (no LLM scoring performed)", AnalysisExcludeCategory.REVERT_COMMIT);

        if (args.isExcludeRevertedCommits()) {
            String revertedSha = ctx.getAnalysis().getRevertedCommitId();
            try {
                doExcludeAnalysis(revertedSha, String.format("reverted by commit %s", JGit.shortSha(args.getCommitId())), AnalysisExcludeCategory.REVERTED);
            } catch (ApiException err) {
                if (err.getCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                    getLog().warn(String.format("reverted commit %s not known to backend (outside indexing window?) — skipping its exclusion", revertedSha));
                } else {
                    throw err;
                }
            }
        }
    }
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category) throws Exception {
        doExcludeAnalysis(commitSha, reason, category, null);
    }
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail) throws Exception {
        doExcludeAnalysis(commitSha, reason, category, detail, List.of());
    }
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files) throws Exception {
        doExcludeAnalysis(commitSha, reason, category, detail, files, null);
    }
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category, String detail, List<FileChangeModel> files, ProjectMetricsModel projectMetrics) throws Exception {
        getLog().debug(String.format("no exclusion handler, commit %s would be excluded with reason: %s (category: %s)", commitSha, reason, category));
    }
    /**
     * with codiqo.scoreOnBuildFailure enabled, a build-pipeline failure no longer excludes the commit
     * outright: the change still represents real developer work, so score it in degraded mode instead.
     * falls back to exclusion when the flag is off (default), when the commit has no analyzable diff files,
     * and mirrors the revert gate that the successful-build path applies in doExecute. only reachable from
     * historical commit analysis — analyze-uncommitted-changes never forks a build (it analyzes the working
     * tree against the host session's already-resolved reactor), so it has no failure signal to degrade on.
     */
    protected void doDegradedAnalysis(RunArgs args, String reason, AnalysisExcludeCategory category, String detail) throws Exception {
        SubmissionContext ctx = buildDegradedSubmission(args, reason, category, detail);
        List<FileChangeModel> files = ctx.getSubmissionModel().getFiles();

        if (args.isScoreOnBuildFailure()) {
            if (CollectionUtils.isEmpty(files)) {
                getLog().warn(String.format("commit %s skipped: build failed and no analyzable diff — %s", args.getCommitId(), reason));
                doExcludeAnalysis(args.getCommitId(), reason, category, detail, files, ctx.getSubmissionModel().getProjectMetrics());
                return;
            }
            if (ctx.getAnalysis().isRevertCommit()) {
                doExcludeRevertCommit(args, ctx);
                return;
            }
            getLog().warn(String.format("commit %s: build failed (%s: %s) — running degraded analysis", args.getCommitId(), category, reason));
            new OutputSerializer(preferYaml, ctx.getLogFactory().getLogger(OutputSerializer.class)).accept(ctx);
            doLlmScoring(ctx);
        } else {
            getLog().warn(String.format("commit %s skipped: %s", args.getCommitId(), reason));
            doExcludeAnalysis(args.getCommitId(), reason, category, detail, files, ctx.getSubmissionModel().getProjectMetrics());
        }
    }
    /**
     * degraded submission for a build-failed commit. a failed build leaves no resolved reactor, so a
     * synthetic whole-worktree owner drives a source-only PMD index (no JDT, no coverage, no CPD) that
     * still yields the driver-score statistics and the changed-code blocks the volume scorer needs, so
     * genuine code volume is measured rather than scored from config lines alone. any failure of that
     * best-effort pass falls back to the strictly diff-only submission (git diff + commit metadata).
     */
    protected SubmissionContext buildDegradedSubmission(RunArgs args, String reason, AnalysisExcludeCategory category, String detail) throws Exception {
        boolean hadProjectModel = CollectionUtils.isNotEmpty(args.getProjects());
        try {
            return buildSourceOnlyDegradedSubmission(args, reason, category, detail);
        } catch (IOException err) {
            /**
             * only an I/O failure of the best-effort source index degrades to diff-only; codiqo-internal
             * defects (RuntimeExceptions from the populators) are not caught here so they propagate and
             * fail loudly rather than silently masking a bug as a config-only score
             */
            getLog().warn(String.format("commit %s: source-only degraded index failed (%s) — falling back to diff-only scoring",
                    args.getCommitId(), ExceptionUtils.getRootCauseMessage(err)), err);
            if (BooleanUtils.negate(hadProjectModel)) {
                args.getProjects().clear();
            }
            return buildDiffOnlyDegradedSubmission(args, reason, category, detail);
        }
    }
    protected SubmissionContext buildSourceOnlyDegradedSubmission(RunArgs args, String reason, AnalysisExcludeCategory category, String detail) throws Exception {
        LogFactory logFactory = new MavenLogFactory(getLog());
        Path workTree = args.getGit().getWorkTree().toPath().normalize();

        if (CollectionUtils.isEmpty(args.getProjects())) {
            args.getProjects().add(SourceOnlyProjectSpec.forWorkTree(args.getGit().getWorkTree(), project));
        }

        CommitAnalysis analysis = new JGitDeltaAnalyzer(logFactory, args).analyze();
        IndexingSummary index = buildSourceOnlyIndex(args, analysis, logFactory);

        ClientInfoModel clientInfo = new ClientInfoModel();
        clientInfo.setBuildTool(ClientInfoModel.BuildToolEnum.MAVEN);
        clientInfo.setVersion(runtimeInformation.getMavenVersion());
        clientInfo.setName("codiqo-maven-plugin");

        SubmissionContext ctx = SubmissionContext.create(
                args,
                index,
                analysis,
                workTree,
                logFactory,
                project.getGroupId() + ":" + project.getArtifactId(),
                project.getName(),
                clientInfo);
        new ProjectModelPopulator(getLog()).accept(ctx);
        new CommitModelPopulator().accept(ctx);
        new ModuleLevelMetricsPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);
        new EffectiveChangePopulator().accept(ctx);

        /**
         * only the driver-score statistics (project totals + scalers) are populated — no coverage, PMD,
         * or CPD is captured for a failed build, so the quality and coverage aggregates are left absent
         * (rendered n/a) rather than fabricated as zeros
         */
        MetricsAggregator.populateDriverMetrics(ctx);

        applyBuildFailure(ctx, args, reason, category, detail);
        return ctx;
    }
    /**
     * strictly diff-only submission: git diff + commit metadata, no code units, no coverage, no PMD/CPD,
     * no project-level metrics. the degraded score is derived from the diff alone. carries the
     * buildFailure block that drives degraded LLM scoring.
     */
    protected SubmissionContext buildDiffOnlyDegradedSubmission(RunArgs args, String reason, AnalysisExcludeCategory category, String detail) throws Exception {
        LogFactory logFactory = new MavenLogFactory(getLog());
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        CommitAnalysis analysis = new JGitDeltaAnalyzer(logFactory, args).analyze();

        ClientInfoModel clientInfo = new ClientInfoModel();
        clientInfo.setBuildTool(ClientInfoModel.BuildToolEnum.MAVEN);
        clientInfo.setVersion(runtimeInformation.getMavenVersion());
        clientInfo.setName("codiqo-maven-plugin");

        SubmissionContext ctx = SubmissionContext.create(
                args,
                null,
                analysis,
                workTree,
                logFactory,
                project.getGroupId() + ":" + project.getArtifactId(),
                project.getName(),
                clientInfo);
        new ProjectModelPopulator(getLog()).accept(ctx);
        new CommitModelPopulator().accept(ctx);
        new FileAnalysisPopulator().accept(ctx);

        applyBuildFailure(ctx, args, reason, category, detail);
        return ctx;
    }
    @SuppressWarnings("deprecation")
    private void purgeNonJavaSourceJars(RunArgs args) throws IOException {
        List<String> exclusions = Split.on(jdtSourceExclusions, ',');
        if (CollectionUtils.isNotEmpty(exclusions)) {
            LocalRepositoryManager localRepoManager = mavenSession.getRepositorySession().getLocalRepositoryManager();
            AtomicInteger purged = new AtomicInteger();

            for (ProjectSpec prj : args.getProjects()) {
                if (prj instanceof MavenProjectSpec mvn) {
                    mvn.getArtifacts().keySet().forEach(artifact -> {
                        boolean matches = exclusions.stream().anyMatch(prefix -> artifact.getGroupId().startsWith(prefix));
                        if (matches) {
                            try {
                                DefaultArtifact sources = new DefaultArtifact(
                                        artifact.getGroupId(),
                                        artifact.getArtifactId(),
                                        "sources",
                                        "jar",
                                        artifact.getVersion());
                                File sourceJar = new File(
                                        localRepoManager.getRepository().getBasedir(),
                                        localRepoManager.getPathForLocalArtifact(sources));
                                if (sourceJar.exists()) {
                                    getLog().info("purging source JAR: " + sourceJar.getAbsolutePath());
                                    if (sourceJar.delete()) {
                                        purged.incrementAndGet();
                                    }
                                }
                            } catch (Exception err) {
                                ExceptionUtils.wrapAndThrow(err);
                            }
                        }
                    });
                }
            }

            if (purged.get() > 0) {
                getLog().info("purged " + purged.get() + " non-Java source JARs from local repository");

                File sharedIndex = RunArgs.JDT_SHARED_INDEX.resolve(args.effectiveJdtlsVersion()).toFile();
                if (sharedIndex.exists()) {
                    FileUtils.deleteDirectory(sharedIndex);
                    getLog().info("invalidated JDT shared index cache: " + sharedIndex.getAbsolutePath());
                }
            }
        }
    }
    private static BuildOutcome.Skipped classifyProjectBuildingException(ProjectBuildingException pbe) throws MojoExecutionException {
        List<String> unresolved = Maven.unresolvedDependencyCoords(pbe);
        if (CollectionUtils.isNotEmpty(unresolved)) {
            return new BuildOutcome.Skipped("host model building: unresolved dependencies: " + StringUtils.join(unresolved, ", "), AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE, null);
        }

        Optional<String> structural = Maven.severeProblem(pbe.getResults().stream().flatMap(r -> r.getProblems().stream()));
        if (structural.isPresent()) {
            return new BuildOutcome.Skipped("host model building: " + structural.get(), AnalysisExcludeCategory.BUILD_FAILURE, null);
        }

        throw new MojoExecutionException("project build failed: " + Objects.toString(pbe.getMessage(), pbe.getClass().getSimpleName()), pbe);
    }
    private static IndexingSummary buildSourceOnlyIndex(RunArgs args, CommitAnalysis analysis, LogFactory logFactory) throws IOException {
        try (Fetch fetch = new Fetch(args);
                LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
            /**
             * no registry.load(): index() and identifyAffectedSymbols() are pure source (PMD) passes, so
             * the JDT language server is never downloaded or started for a build-failed commit
             */
            IndexingSummary index = registry.index(analysis);
            registry.identifyAffectedSymbols(index, analysis);
            return index;
        }
    }
    private void applyBuildFailure(SubmissionContext ctx, RunArgs args, String reason, AnalysisExcludeCategory category, String detail) throws IOException {
        AnalysisBuildFailureModel buildFailure = new AnalysisBuildFailureModel();
        buildFailure.setReason(reason);
        buildFailure.setCategory(category);
        buildFailure.setDetail(detail);

        ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
        ctx.getSubmissionModel().setBuildFailure(buildFailure);
        applyAgentInstructions(ctx, args);
    }
    /**
     * Scoring runs server-side against the submitted payload, so the instruction text is collected here —
     * this is the last point that still has a work tree — and travels with the submission. Setting it before
     * the dump also makes a score-from-file replay reproduce the prompt the server saw.
     */
    private void applyAgentInstructions(SubmissionContext ctx, RunArgs args) throws IOException {
        ctx.getSubmissionModel().setAgentInstructions(
                StringUtils.trimToNull(ConventionGuidance.read(args, new MavenMessageReporter(getLog()))));
    }

    protected sealed interface BuildOutcome {
        record Skipped(String reason, AnalysisExcludeCategory category, String detail) implements BuildOutcome {}
        record Proceeded(ProjectBuildingResult result) implements BuildOutcome {}
    }

    @RequiredArgsConstructor
    private static final class CapturingOutputHandler implements InvocationOutputHandler {
        private static final Pattern HELP_LINE = Pattern.compile("\\[ERROR\\]\\s+\\[Help\\s+\\d+\\]\\s+");
        private static final int MAX_CAPTURED_ERROR_LINES = 500;

        private final InvocationOutputHandler delegate;
        private final int captureLimit;
        private final List<String> helpUrlLines = new ArrayList<>();
        private final Queue<String> capturedLines = new CircularFifoQueue<>(MAX_CAPTURED_ERROR_LINES);
        private String firstErrorLine;
        private boolean capturing;

        @Override
        public void consumeLine(String line) throws IOException {
            delegate.consumeLine(line);
            if (Strings.CS.startsWithAny(line, "[FATAL]", "[ERROR]")) {
                if (Objects.isNull(firstErrorLine)) {
                    firstErrorLine = line;
                }
                capturing = true;
            }
            if (capturing) {
                capturedLines.add(line);
            }
            if (HELP_LINE.matcher(line).find()) {
                helpUrlLines.add(line);
            }
        }
        public String firstErrorLine() {
            return firstErrorLine;
        }
        public List<String> helpUrlLines() {
            return helpUrlLines;
        }
        public Optional<String> capturedDetail() {
            return capturedLines.isEmpty() ? Optional.empty() : Optional.of(StringUtils.abbreviate(StringUtils.join(capturedLines, CharUtils.LF), captureLimit));
        }
    }
}
