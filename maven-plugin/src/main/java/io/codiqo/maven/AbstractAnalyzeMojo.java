package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
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

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.MavenProjectSpec;
import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.lang.config.ConfigFiles;
import io.codiqo.llm.client.DaemonExecutors;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.populator.CommitModelPopulator;
import io.codiqo.maven.populator.DuplicationReportPopulator;
import io.codiqo.maven.populator.EffectiveChangePopulator;
import io.codiqo.maven.populator.FileAnalysisPopulator;
import io.codiqo.maven.populator.IndexModelPopulator;
import io.codiqo.maven.populator.LlmScoringPopulator;
import io.codiqo.maven.populator.MetricsAggregator;
import io.codiqo.maven.populator.ModuleLevelMetricsPopulator;
import io.codiqo.maven.populator.OutputSerializer;
import io.codiqo.maven.populator.ProjectModelPopulator;
import io.codiqo.maven.populator.SubmissionContext;
import io.codiqo.maven.populator.SubmissionSummaryPrinter;
import io.codiqo.maven.timemachine.TimeMachineConfig;
import io.codiqo.util.Env;
import io.codiqo.util.Fetch;
import io.codiqo.util.JGit;
import io.codiqo.util.MemoryReport;
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

    private static final Set<String> TIME_MACHINE_EXTENSION_JAR_BLACKLIST = Set.of("slf4j-api-");

    private Collection<File> timeMachineExtensionJars;

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

    @Parameter(property = "codiqo.javaHome")
    protected File javaHome;

    @Parameter(property = "codiqo.mavenHome")
    protected File mavenHome;

    @Parameter(property = "codiqo.preferYaml", defaultValue = "true")
    protected boolean preferYaml;

    @Parameter(property = "codiqo.buildTimeoutMinutes", defaultValue = "30")
    protected long buildTimeoutMinutes;

    @Parameter(property = "codiqo.testTimeoutMinutes", defaultValue = "10")
    protected long testTimeoutMinutes;

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

    @Parameter(property = "codiqo.jdtlsVersion", defaultValue = "1.58.0")
    protected String jdtlsVersion;

    @Parameter(property = "codiqo.jdtlsUseSnapshot", defaultValue = "true")
    protected boolean jdtlsUseSnapshot;

    @Parameter(property = "codiqo.dumpAnalysis", defaultValue = "true")
    protected boolean dumpAnalysis;

    @Parameter(property = "codiqo.ignoreCoverage", defaultValue = "false")
    protected boolean ignoreCoverage;

    @Parameter(property = "codiqo.ignoreCpd", defaultValue = "false")
    protected boolean ignoreCpd;

    @Parameter(property = "codiqo.ignoreDiagnostics", defaultValue = "false")
    protected boolean ignoreDiagnostics;

    @Parameter(property = "codiqo.ignoreComplexity", defaultValue = "false")
    protected boolean ignoreComplexity;

    @Parameter(property = "codiqo.failOnJdtlsError", defaultValue = "false")
    protected boolean failOnJdtlsError;

    @Parameter(property = "codiqo.skipOnUnresolvedDependencies", defaultValue = "false")
    protected boolean skipOnUnresolvedDependencies;

    @Parameter(property = "codiqo.skipOnBuildFailure", defaultValue = "true")
    protected boolean skipOnBuildFailure;

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

    @Parameter(property = "codiqo.llm.seed", defaultValue = "42")
    protected Integer llmSeed;

    @Parameter(property = "codiqo.llm.enableWebSearchTool", defaultValue = "false")
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

    @Parameter(property = "codiqo.jdtUseSharedIndex", defaultValue = "true")
    protected boolean jdtUseSharedIndex;

    @Parameter(property = "codiqo.jdtIncludeDecompiledSources", defaultValue = "false")
    protected boolean jdtIncludeDecompiledSources;

    @Parameter(property = "codiqo.jdtDebugPort")
    protected Integer jdtDebugPort;

    @Parameter(property = "codiqo.jdtSourceExclusions",
            defaultValue = "org.scala-lang, org.apache.kafka, org.apache.pekko, org.apache.spark, org.apache.flink, com.typesafe.akka, com.typesafe, io.gatling, com.lightbend.lagom, com.twitter, org.json4s, org.scalactic, org.scalatest")
    protected String jdtSourceExclusions;

    @Parameter(property = "codiqo.driverScoreCapMultiplier", defaultValue = "2.5")
    protected double driverScoreCapMultiplier;

    @Parameter(property = "codiqo.driverFactorMaxDeviation", defaultValue = "0.75")
    protected double driverFactorMaxDeviation;

    @Parameter(property = "codiqo.driverScoreCapDryRun", defaultValue = "false")
    protected boolean driverScoreCapDryRun;

    @Override
    @SuppressWarnings("deprecation")
    public final Collection<File> apply(Artifact artifact) {
        for (;;) {
            try {
                CollectRequest collect = new CollectRequest();
                collect.setRoot(new org.eclipse.aether.graph.Dependency(artifact, null));
                collect.setRepositories(remoteRepos);
                DependencyRequest req = new DependencyRequest(collect, null);
                DependencyResult result = repositorySystem.resolveDependencies(mavenSession.getRepositorySession(), req);
                return result
                        .getArtifactResults()
                        .stream()
                        .map(ArtifactResult::getArtifact)
                        .map(Artifact::getFile)
                        .collect(ImmutableList.toImmutableList());
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
        args.setIgnoreCpd(ignoreCpd);
        args.setIgnoreDiagnostics(ignoreDiagnostics);
        args.setIgnoreComplexity(ignoreComplexity);
        args.setFailOnJdtlsError(failOnJdtlsError);
        args.setSkipOnUnresolvedDependencies(skipOnUnresolvedDependencies);
        args.setSkipOnBuildFailure(skipOnBuildFailure);
        args.setPmdMinPriority(pmdMinPriority);
        if (StringUtils.isNotBlank(pmdRules)) {
            args.setPmdRules(Splitter.on(',').trimResults().omitEmptyStrings().splitToList(pmdRules));
        }
        args.setSpotbugsPriorityThreshold(spotbugsPriorityThreshold);
        Optional.ofNullable(spotbugsOmitVisitors).ifPresent(args::setSpotbugsOmitVisitors);
        args.setLlmModel(llmModel);
        args.setLlmBaseUrl(llmBaseUrl);
        args.setLlmTemperature(llmTemperature);
        args.setLlmMaxTokens(llmMaxTokens);
        args.setLlmNumCtx(llmNumCtx);
        args.setLlmSeed(llmSeed);
        args.setLlmEnableWebSearchTool(llmEnableWebSearchTool);
        args.setLlmValidationMaxRetries(llmValidationMaxRetries);
        args.setLlmReadTimeout(Duration.ofSeconds(llmReadTimeoutSeconds));
        Optional.ofNullable(outputDirectory).ifPresent(args::setOutputDirectory);
        Optional.ofNullable(includeBranches).ifPresent(args::setIncludeBranches);
        Optional.ofNullable(includeAuthorEmails).ifPresent(args::setIncludeAuthorEmails);
        args.setJdtUseSharedIndex(jdtUseSharedIndex);
        args.setJdtIncludeDecompiledSources(jdtIncludeDecompiledSources);
        args.setJdtDebugPort(jdtDebugPort);

        args.setDriverScoreCapMultiplier(driverScoreCapMultiplier);
        args.setDriverFactorMaxDeviation(driverFactorMaxDeviation);
        args.setDriverScoreCapDryRun(driverScoreCapDryRun);

        Env.resolveInto(llmApiKey, args::setLlmApiKey);
        args.validate();
        try (InputStream stream = Resources.getResource("codiqo.versions").openStream()) {
            Properties versions = new Properties();
            versions.load(stream);
            for (DefaultArtifact agent : new DefaultArtifact[] {
                    new DefaultArtifact(LOMBOK_GROUP_ID, LOMBOK_ARTIFACT_ID, JAR_EXTENSION, versions.get("lombok.version").toString())
            }) {
                args.getAgents().addAll(apply(agent));
            }
            timeMachineExtensionJars = apply(new DefaultArtifact(CODIQO_GROUP_ID, TIME_MACHINE_ARTIFACT_ID, JAR_EXTENSION, versions.get("codiqo.version").toString()));
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
        Set<URI> jars = Sets.newLinkedHashSet();
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
                    File jacocoDestFile = Maven.autoDetectJacocoDestFile(prj);
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
    protected InvocationRequest invocationRequest(RunArgs args) throws IOException {
        File rootPom = new File(args.getGit().getWorkTree(), "pom.xml");
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(rootPom);
        if (args.isIgnoreCoverage()) {
            request.addArgs(ImmutableList.of(
                    "clean",
                    "verify",
                    "-DskipTests=true",
                    "-Djacoco.skip=true",
                    "-Dmaven.javadoc.skip=true",
                    "-Dmdep.analyze.skip=true"));
        } else {
            long surefireTimeout = args.getTestTimeout().getSeconds();
            long surefireExitTimeout = args.getBuildTimeout().minusMinutes(1).getSeconds();
            request.addArgs(ImmutableList.of(
                    "clean",
                    "verify",
                    "-DskipTests=false",
                    "-DfailIfNoTests=false",
                    "-Djacoco.skip=false",
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

        if (StringUtils.isNotBlank(args.getCommitId())) {
            Instant ts = TimeMachineSupport.resolveCommitTimestamp(args);

            List<String> extensionClasspath = timeMachineExtensionJars.stream()
                    .filter(jar -> TIME_MACHINE_EXTENSION_JAR_BLACKLIST.stream().noneMatch(prefix -> jar.getName().startsWith(prefix)))
                    .map(File::getAbsolutePath)
                    .toList();

            File metaDir = Files.createTempDirectory("codiqo-tm-").toFile();
            args.setTimeMachineMetaDir(metaDir);

            Properties props = Optional.ofNullable(request.getProperties()).orElseGet(Properties::new);
            props.setProperty(TimeMachineConfig.MAVEN_EXT_CLASS_PATH, Joiner.on(File.pathSeparator).join(extensionClasspath));
            props.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, DateTimeFormatter.ISO_INSTANT.format(ts));
            props.setProperty(TimeMachineConfig.PROP_META_DIR, metaDir.getAbsolutePath());
            request.setProperties(props);

            getLog().info(String.format("time-machine enabled for commit %s (timestamp: %s, metaDir: %s)", args.getCommitId(), ts, metaDir.getAbsolutePath()));
        }
        return request;
    }
    protected BuildOutcome buildProject(
            RunArgs args,
            InvocationRequest request,
            ProjectBuildingRequest buildingRequest) throws Exception {
        File rootPom = new File(args.getGit().getWorkTree(), "pom.xml");

        CapturingOutputHandler sysout = new CapturingOutputHandler(new PrintStreamHandler(System.out, false));
        CapturingOutputHandler syserr = new CapturingOutputHandler(new PrintStreamHandler(System.err, false));
        request.setOutputHandler(sysout);
        request.setErrorHandler(syserr);

        Invoker invoker = new DefaultInvoker();
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            if (result.getExecutionException() instanceof CommandLineTimeOutException) {
                if (args.isSkipOnBuildFailure()) {
                    String reason = "fork build timed out after " + args.getBuildTimeout();
                    getLog().warn(reason + ", skipping with category " + AnalysisExcludeCategory.BUILD_FAILURE);
                    return new BuildOutcome.Skipped(reason, AnalysisExcludeCategory.BUILD_FAILURE);
                }
                getLog().warn("maven build timed out after " + args.getBuildTimeout() + " — test coverage may be incomplete");
            } else if (args.isSkipOnBuildFailure()) {
                String reason = Optional.ofNullable(sysout.firstErrorLine())
                        .or(() -> Optional.ofNullable(syserr.firstErrorLine()))
                        .orElse("fork build failed (exit code " + result.getExitCode() + ")");
                List<String> helpLines = Lists.newArrayList();
                helpLines.addAll(sysout.helpUrlLines());
                helpLines.addAll(syserr.helpUrlLines());
                AnalysisExcludeCategory category = Maven.classifyForkFailure(helpLines);
                getLog().warn(String.format("fork build failed (exit code %d), skipping with category %s: %s", result.getExitCode(), category, reason));
                return new BuildOutcome.Skipped(reason, category);
            } else {
                throw new MojoExecutionException("maven build failed in fork", result.getExecutionException());
            }
        }
        return new BuildOutcome.Proceeded(projectBuilder.build(rootPom, buildingRequest));
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

        if (StringUtils.isNotBlank(args.getCommitId())) {
            request.setResolveDependencies(false);
        }

        try {
            return new BuildOutcome.Proceeded(projectBuilder.build(rootPom, request));
        } catch (ProjectBuildingException pbe) {
            return classifyProjectBuildingException(pbe, args);
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
                ProjectBuildingResult moduleResult;
                try {
                    moduleResult = projectBuilder.build(modulePom, buildingRequest);
                } catch (ProjectBuildingException pbe) {
                    return Optional.of(classifyProjectBuildingException(pbe, args));
                }

                MavenProject moduleProject = moduleResult.getProject();
                if (CollectionUtils.isEmpty(moduleProject.getModules())) {
                    collected.add(moduleProject);
                } else {
                    Optional<BuildOutcome.Skipped> nested = buildAndCollectModules(
                            moduleProject, new File(baseDir, moduleName), buildingRequest, args, collected);
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
                getLog().warn(String.format("commit %s skipped: revert commit (no LLM scoring or submission)", args.getCommitId()));
                doExcludeAnalysis(args.getCommitId(), "revert commit (no LLM scoring performed)", null);
            } else {
                ctx.getSubmissionModel().setScoringConfig(ScoringConfigs.map(args));
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
                registry.load().block();
                getLog().info(MemoryReport.snapshot("after language server load"));
                MutableBoolean toApply = new MutableBoolean();
                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, args);
                CommitAnalysis analysis = analyzer.analyze();
                List<String> changedFiles = Lists.newArrayList();
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
                if (toApply.isFalse()) {
                    skipReason = String.format("no diff files match registered languages %s or supported config files (pom.xml, .proto) — changed files: %s", registry.extensions(), changedFiles);
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
                    getLog().warn(String.format("commit %s skipped: %s", args.getCommitId(), skipReason));
                    toApply.setFalse();
                }

                if (Objects.nonNull(skipReason)) {
                    doExcludeAnalysis(args.getCommitId(), skipReason, null);
                }
                if (toApply.isTrue()) {
                    IndexingSummary index = registry.index(analysis);
                    getLog().info(MemoryReport.snapshot("after index"));
                    registry.identifyAffectedSymbols(index, analysis);
                    getLog().info(MemoryReport.snapshot("after identify symbols"));
                    registry.collectAndCapture(index, analysis);
                    getLog().info(MemoryReport.snapshot("after collect capture"));
                    SubmissionContext ctx = SubmissionContext.create(
                            args,
                            index,
                            analysis,
                            workTree,
                            logFactory,
                            project,
                            runtimeInformation);
                    new ProjectModelPopulator(getLog()).accept(ctx);
                    new CommitModelPopulator().accept(ctx);
                    new ModuleLevelMetricsPopulator().accept(ctx);
                    new FileAnalysisPopulator().accept(ctx);
                    new EffectiveChangePopulator().accept(ctx);
                    new IndexModelPopulator().accept(ctx);
                    DuplicationReportPopulator duplicationPopulator = new DuplicationReportPopulator();
                    duplicationPopulator.accept(ctx);
                    new MetricsAggregator(duplicationPopulator.getTotalDuplicatedLines()).accept(ctx);
                    new SubmissionSummaryPrinter(getLog()).accept(ctx);
                    new OutputSerializer(preferYaml, getLog()).accept(ctx);
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
    protected void doExcludeAnalysis(String commitSha, String reason, AnalysisExcludeCategory category) throws Exception {
        getLog().debug(String.format("no exclusion handler, commit %s would be excluded with reason: %s (category: %s)", commitSha, reason, category));
    }
    @SuppressWarnings("deprecation")
    private void purgeNonJavaSourceJars(RunArgs args) throws IOException {
        List<String> exclusions = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(jdtSourceExclusions);
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
    private static BuildOutcome.Skipped classifyProjectBuildingException(ProjectBuildingException pbe, RunArgs args) throws MojoExecutionException {
        List<String> unresolved = Maven.unresolvedDependencyCoords(pbe);
        if (CollectionUtils.isNotEmpty(unresolved)) {
            String reason = "unresolved dependencies: " + Joiner.on(", ").join(unresolved);
            if (args.isSkipOnUnresolvedDependencies()) {
                return new BuildOutcome.Skipped(reason, AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE);
            }
            throw new MojoExecutionException(reason, pbe);
        }

        Optional<String> structural = Maven.severeProblem(pbe.getResults().stream().flatMap(r -> r.getProblems().stream()));
        if (structural.isPresent()) {
            return new BuildOutcome.Skipped(structural.get(), AnalysisExcludeCategory.BUILD_FAILURE);
        }

        throw new MojoExecutionException("project build failed: " + Objects.toString(pbe.getMessage(), pbe.getClass().getSimpleName()), pbe);
    }

    protected sealed interface BuildOutcome {
        record Skipped(String reason, AnalysisExcludeCategory category) implements BuildOutcome {}
        record Proceeded(ProjectBuildingResult result) implements BuildOutcome {}
    }

    @RequiredArgsConstructor
    private static final class CapturingOutputHandler implements InvocationOutputHandler {
        private static final Pattern HELP_LINE = Pattern.compile("\\[ERROR\\]\\s+\\[Help\\s+\\d+\\]\\s+");

        private final InvocationOutputHandler delegate;
        private final List<String> helpUrlLines = Lists.newArrayList();
        private String firstErrorLine;

        @Override
        public void consumeLine(String line) throws IOException {
            delegate.consumeLine(line);
            if (Objects.isNull(firstErrorLine) && Strings.CS.startsWithAny(line, "[FATAL]", "[ERROR]")) {
                firstErrorLine = line;
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
    }
}
