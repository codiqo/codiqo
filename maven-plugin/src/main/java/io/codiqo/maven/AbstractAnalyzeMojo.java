package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
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
import org.codehaus.plexus.util.xml.Xpp3Dom;
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
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.TagOpt;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
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
import io.codiqo.client.model.ScoringConfigModel;
import io.codiqo.core.ClassGraphWrapper;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.populator.CommitModelPopulator;
import io.codiqo.maven.populator.DuplicationReportPopulator;
import io.codiqo.maven.populator.EffectiveChangePopulator;
import io.codiqo.maven.populator.FileAnalysisPopulator;
import io.codiqo.maven.populator.IndexModelPopulator;
import io.codiqo.maven.populator.LlmScoringPopulator;
import io.codiqo.maven.populator.MetricsAggregator;
import io.codiqo.maven.populator.SubmissionSummaryPrinter;
import io.codiqo.maven.populator.ModuleLevelMetricsPopulator;
import io.codiqo.maven.populator.OutputSerializer;
import io.codiqo.maven.populator.ProjectModelPopulator;
import io.codiqo.maven.populator.SubmissionContext;
import io.codiqo.util.Fetch;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

abstract class AbstractAnalyzeMojo extends AbstractMojo implements Function<Artifact, Collection<File>> {
    private static final String ENV_PREFIX = "env:";
    private static final Set<String> NON_CODE_PACKAGINGS = Set.of("pom", "bom");
    private static final String JAR_EXTENSION = "jar";
    private static final String LOMBOK_GROUP_ID = "org.projectlombok";
    private static final String LOMBOK_ARTIFACT_ID = "lombok";
    private static final String JACOCO_GROUP_ID = "org.jacoco";
    private static final String JACOCO_MAVEN_PLUGIN_ARTIFACT_ID = "jacoco-maven-plugin";
    private static final String JACOCO_MAVEN_PLUGIN_DEST_FILE = "destFile";
    private static final String JACOCO_MAVEN_PLUGIN_DEST_FILE_DEFAULT_VALUE = "jacoco.exec";

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
    protected boolean preferYaml = true;

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

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "300")
    protected long readTimeoutSeconds;

    @Parameter(property = "codiqo.maxRequests", defaultValue = "256")
    protected int maxRequests;

    @Parameter(property = "codiqo.maxRequestsPerHost", defaultValue = "128")
    protected int maxRequestsPerHost;

    @Parameter(property = "codiqo.cpdMinimumTileSize", defaultValue = "64")
    protected int cpdMinimumTileSize;

    @Parameter(property = "codiqo.jdtlsVersion", defaultValue = "1.58.0")
    protected String jdtlsVersion;

    @Parameter(property = "codiqo.dumpAnalysis", defaultValue = "true")
    protected boolean dumpAnalysis = true;

    @Parameter(property = "codiqo.ignoreCoverage", defaultValue = "false")
    protected boolean ignoreCoverage = false;

    @Parameter(property = "codiqo.ignoreCpd", defaultValue = "false")
    protected boolean ignoreCpd = false;

    @Parameter(property = "codiqo.ignoreDiagnostics", defaultValue = "false")
    protected boolean ignoreDiagnostics = false;

    @Parameter(property = "codiqo.ignoreComplexity", defaultValue = "false")
    protected boolean ignoreComplexity = false;

    @Parameter(property = "codiqo.failOnJdtlsError", defaultValue = "false")
    protected boolean failOnJdtlsError = false;

    @Parameter(property = "codiqo.pmdMinPriority", defaultValue = "medium_high")
    protected String pmdMinPriority;

    @Parameter(property = "codiqo.spotbugsPriorityThreshold", defaultValue = "2")
    protected int spotbugsPriorityThreshold;

    @Parameter(property = "codiqo.spotbugsOmitVisitors")
    protected String spotbugsOmitVisitors;

    @Parameter(property = "codiqo.llm.model", defaultValue = "nemotron-3-super:cloud")
    protected String llmModel;

    @Parameter(property = "codiqo.llm.apiKey")
    protected String llmApiKey;

    @Parameter(property = "codiqo.llm.baseUrl", defaultValue = "https://ollama.com/v1")
    protected String llmBaseUrl;

    @Parameter(property = "codiqo.llm.temperature", defaultValue = "0.3")
    protected double llmTemperature;

    @Parameter(property = "codiqo.llm.maxTokens", defaultValue = "32767")
    protected int llmMaxTokens;

    @Parameter(property = "codiqo.llm.enableWebSearchTool", defaultValue = "false")
    protected boolean llmEnableWebSearchTool;

    @Parameter(property = "codiqo.outputDirectory")
    protected File outputDirectory;

    @Parameter(property = "codiqo.includeBranches")
    protected String includeBranches;

    @Parameter(property = "codiqo.includeAuthorEmails")
    protected String includeAuthorEmails;

    @Parameter(property = "codiqo.hideSourceCode", defaultValue = "false")
    protected boolean hideSourceCode = false;

    @Parameter(property = "codiqo.jdtUseSharedIndex", defaultValue = "true")
    protected boolean jdtUseSharedIndex = true;

    @Parameter(property = "codiqo.jdtIncludeDecompiledSources", defaultValue = "false")
    protected boolean jdtIncludeDecompiledSources = false;

    @Parameter(property = "codiqo.jdtDebugPort")
    protected Integer jdtDebugPort;

    @Parameter(property = "codiqo.jdtSourceExclusions",
            defaultValue = "org.scala-lang, org.apache.kafka, org.apache.pekko, org.apache.spark, org.apache.flink, com.typesafe.akka, com.typesafe, io.gatling, com.lightbend.lagom, com.twitter, org.json4s, org.scalactic, org.scalatest")
    protected String jdtSourceExclusions;

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
        args.setJdtlsVersion(jdtlsVersion);
        args.setDumpAnalysis(dumpAnalysis);
        args.setIgnoreCoverage(ignoreCoverage);
        args.setIgnoreCpd(ignoreCpd);
        args.setIgnoreDiagnostics(ignoreDiagnostics);
        args.setIgnoreComplexity(ignoreComplexity);
        args.setFailOnJdtlsError(failOnJdtlsError);
        args.setPmdMinPriority(pmdMinPriority);
        args.setSpotbugsPriorityThreshold(spotbugsPriorityThreshold);
        Optional.ofNullable(spotbugsOmitVisitors).ifPresent(args::setSpotbugsOmitVisitors);
        args.setLlmModel(llmModel);
        args.setLlmBaseUrl(llmBaseUrl);
        args.setLlmTemperature(llmTemperature);
        args.setLlmMaxTokens(llmMaxTokens);
        args.setLlmEnableWebSearchTool(llmEnableWebSearchTool);
        Optional.ofNullable(outputDirectory).ifPresent(args::setOutputDirectory);
        Optional.ofNullable(includeBranches).ifPresent(args::setIncludeBranches);
        Optional.ofNullable(includeAuthorEmails).ifPresent(args::setIncludeAuthorEmails);
        args.setHideSourceCode(hideSourceCode);
        args.setJdtUseSharedIndex(jdtUseSharedIndex);
        args.setJdtIncludeDecompiledSources(jdtIncludeDecompiledSources);
        args.setJdtDebugPort(jdtDebugPort);
        if (StringUtils.isNotEmpty(llmApiKey)) {
            if (llmApiKey.startsWith(ENV_PREFIX)) {
                String envVar = llmApiKey.substring(ENV_PREFIX.length());
                args.setLlmApiKey(System.getenv(envVar));
            } else {
                args.setLlmApiKey(llmApiKey);
            }
        }
        args.validate();
        try (InputStream stream = Resources.getResource("codiqo.versions").openStream()) {
            Properties versions = new Properties();
            versions.load(stream);
            for (DefaultArtifact agent : new DefaultArtifact[] {
                    new DefaultArtifact(LOMBOK_GROUP_ID, LOMBOK_ARTIFACT_ID, JAR_EXTENSION, versions.get("lombok.version").toString())
            }) {
                args.getAgents().addAll(apply(agent));
            }
        } catch (IOException err) {
            throw new MojoExecutionException(err);
        }
        try (Repository orig = new FileRepositoryBuilder().setGitDir(new File(project.getBasedir(), ".git")).readEnvironment().findGitDir().build()) {
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
                    File jacocoDestFile = autoDetectJacocoDestFile(prj);
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
    @SuppressWarnings("deprecation")
    private void purgeNonJavaSourceJars(RunArgs args) throws IOException {
        List<String> exclusions = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(jdtSourceExclusions);
        if (CollectionUtils.isNotEmpty(exclusions)) {
            LocalRepositoryManager localRepoManager = mavenSession.getRepositorySession().getLocalRepositoryManager();
            AtomicInteger purged = new AtomicInteger();

            for (ProjectSpec prj : args.getProjects()) {
                if (prj instanceof MavenProjectSpec) {
                    MavenProjectSpec mvn = (MavenProjectSpec) prj;
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

                File sharedIndex = RunArgs.JDT_SHARED_INDEX.resolve(args.getJdtlsVersion()).toFile();
                if (sharedIndex.exists()) {
                    FileUtils.deleteDirectory(sharedIndex);
                    getLog().info("invalidated JDT shared index cache: " + sharedIndex.getAbsolutePath());
                }
            }
        }
    }
    protected File autoDetectJacocoDestFile(MavenProject reactor) {
        Predicate<Plugin> filter = plugin -> BooleanUtils.and(new boolean[] {
                JACOCO_GROUP_ID.equals(plugin.getGroupId()),
                JACOCO_MAVEN_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())
        });
        Optional<Plugin> opt = reactor.getBuild().getPlugins().stream().filter(filter).findAny();
        if (opt.isEmpty()) {
            PluginManagement pluginManagement = reactor.getBuild().getPluginManagement();
            if (Objects.nonNull(pluginManagement)) {
                opt = pluginManagement.getPlugins().stream().filter(filter).findAny();
            }
        }
        if (opt.isPresent()) {
            Xpp3Dom config = (Xpp3Dom) opt.get().getConfiguration();
            if (Objects.nonNull(config)) {
                Xpp3Dom destFileNode = config.getChild(JACOCO_MAVEN_PLUGIN_DEST_FILE);
                if (Objects.nonNull(destFileNode)) {
                    return Paths.get(destFileNode.getValue()).toFile();
                }
            }
        }
        return Paths.get(reactor.getBuild().getDirectory(), JACOCO_MAVEN_PLUGIN_DEST_FILE_DEFAULT_VALUE).toFile();
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
                            if (transport instanceof HttpTransport) {
                                HttpTransport http = (HttpTransport) transport;
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
    protected InvocationRequest invocationRequest(RunArgs args) {
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
            long surefireExitTimeout = args.getBuildTimeout().minusMinutes(2).getSeconds();
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

            request.setTimeoutInSeconds((int) args.getBuildTimeout().getSeconds());
        }
        request.setBatchMode(true);
        request.setThreads(String.valueOf(mavenSession.getRequest().getDegreeOfConcurrency()));
        request.setOutputHandler(new InvocationOutputHandler() {
            PrintStreamHandler sysout = new PrintStreamHandler(System.out, false);

            @Override
            public void consumeLine(String line) throws IOException {
                sysout.consumeLine(line);
            }
        });
        request.setErrorHandler(new InvocationOutputHandler() {
            PrintStreamHandler syserr = new PrintStreamHandler(System.err, false);

            @Override
            public void consumeLine(String line) throws IOException {
                syserr.consumeLine(line);
            }
        });
        if (Objects.nonNull(javaHome)) {
            request.setJavaHome(javaHome);
        }
        if (Objects.nonNull(mavenHome)) {
            request.setMavenHome(mavenHome);
        }
        return request;
    }
    protected ProjectBuildingRequest buildingRequest() {
        ProjectBuildingRequest req = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
        req.setResolveDependencies(true);
        req.setProcessPlugins(true);
        return req;
    }
    protected ProjectBuildingResult buildProject(
            RunArgs args,
            InvocationRequest request,
            ProjectBuildingRequest buildingRequest) throws Exception {
        File rootPom = new File(args.getGit().getWorkTree(), "pom.xml");
        Invoker invoker = new DefaultInvoker();
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            if (result.getExecutionException() instanceof CommandLineTimeOutException) {
                getLog().warn("maven build timed out after " + args.getBuildTimeout() + " — test coverage may be incomplete");
            } else {
                throw new MojoExecutionException("maven build failed in fork", result.getExecutionException());
            }
        }
        return projectBuilder.build(rootPom, buildingRequest);
    }
    protected void buildAndCollectModules(
            MavenProject parent,
            File baseDir,
            ProjectBuildingRequest buildingRequest,
            Collection<MavenProject> collected) throws Exception {
        if (CollectionUtils.isEmpty(parent.getModules())) {
            collected.add(parent);
            return;
        }
        for (String moduleName : parent.getModules()) {
            File modulePom = new File(new File(baseDir, moduleName), "pom.xml");
            if (modulePom.exists()) {
                ProjectBuildingResult moduleResult = projectBuilder.build(modulePom, buildingRequest);
                MavenProject moduleProject = moduleResult.getProject();
                if (CollectionUtils.isEmpty(moduleProject.getModules())) {
                    collected.add(moduleProject);
                } else {
                    buildAndCollectModules(moduleProject, new File(baseDir, moduleName), buildingRequest, collected);
                }
            }
        }
    }
    protected void doPrepare(RunArgs args) throws Exception {
        if (StringUtils.isEmpty(args.getDefaultBranch())) {
            args.setDefaultBranch(args.getDefaultBranch());
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
        SubmissionContext ctx = doAnalyze(args);
        if (Objects.nonNull(ctx) && BooleanUtils.negate(ctx.getAnalysis().isRevertCommit())) {
            ctx.getSubmissionModel().setScoringConfig(mapScoringConfig(args));
            doLlmScoring(ctx);
        }
    }
    private static ScoringConfigModel mapScoringConfig(RunArgs args) {
        ScoringConfigModel toReturn = new ScoringConfigModel();

        toReturn.setCommitId(args.getCommitId());
        toReturn.setJdtlsVersion(args.getJdtlsVersion());
        toReturn.setPmdMinPriority(args.getPmdMinPriority());
        toReturn.setSpotbugsPriorityThreshold(args.getSpotbugsPriorityThreshold());
        toReturn.setSpotbugsOmitVisitors(args.getSpotbugsOmitVisitors());

        toReturn.setIncludeUntracked(args.isIncludeUntracked());
        toReturn.setAutoBuild(args.isAutoBuild());
        toReturn.setDumpAnalysis(args.isDumpAnalysis());
        toReturn.setIgnoreCoverage(args.isIgnoreCoverage());
        toReturn.setIgnoreComplexity(args.isIgnoreComplexity());
        toReturn.setIgnoreCpd(args.isIgnoreCpd());
        toReturn.setIgnoreDiagnostics(args.isIgnoreDiagnostics());
        toReturn.setFailOnJdtlsError(args.isFailOnJdtlsError());
        toReturn.setHideSourceCode(args.isHideSourceCode());
        toReturn.setJdtUseSharedIndex(args.isJdtUseSharedIndex());

        toReturn.setBuildTimeout(Optional.ofNullable(args.getBuildTimeout()).map(Duration::toString).orElse(null));
        toReturn.setTestTimeout(Optional.ofNullable(args.getTestTimeout()).map(Duration::toString).orElse(null));
        toReturn.setImportTimeout(Optional.ofNullable(args.getImportTimeout()).map(Duration::toString).orElse(null));
        toReturn.setLspQueryTimeout(Optional.ofNullable(args.getLspQueryTimeout()).map(Duration::toString).orElse(null));
        toReturn.setConnectTimeout(Optional.ofNullable(args.getConnectTimeout()).map(Duration::toString).orElse(null));
        toReturn.setReadTimeout(Optional.ofNullable(args.getReadTimeout()).map(Duration::toString).orElse(null));

        toReturn.setMaxRequests(args.getMaxRequests());
        toReturn.setMaxRequestsPerHost(args.getMaxRequestsPerHost());
        toReturn.setCpdMinimumTileSize(args.getCpdMinimumTileSize());
        toReturn.setCpdIntroducedThreshold(args.getCpdIntroducedThreshold());

        toReturn.setLlmModel(args.getLlmModel());
        toReturn.setLlmBaseUrl(args.getLlmBaseUrl());
        toReturn.setLlmTemperature(args.getLlmTemperature());
        toReturn.setLlmTopP(args.getLlmTopP());
        toReturn.setLlmMaxTokens(args.getLlmMaxTokens());
        toReturn.setLlmMaxRetries(Optional.ofNullable(args.getLlmMaxRetries()).map(Short::intValue).orElse(null));
        toReturn.setLlmEnableWebSearchTool(args.isLlmEnableWebSearchTool());

        toReturn.setIncludeBranches(args.getIncludeBranches());
        toReturn.setIncludeAuthorEmails(args.getIncludeAuthorEmails());

        toReturn.setSizeFactorDivisor(args.getSizeFactorDivisor());
        toReturn.setModifyMultiplierScale(args.getModifyMultiplierScale());
        toReturn.setModifyMultiplierCap(args.getModifyMultiplierCap());
        toReturn.setAddMultiplierScale(args.getAddMultiplierScale());
        toReturn.setQualityMultiplierMin(args.getQualityMultiplierMin());
        toReturn.setQualityMultiplierMax(args.getQualityMultiplierMax());

        toReturn.setStaticAnalysisPenaltyCap(args.getStaticAnalysisPenaltyCap());
        toReturn.setStaticAnalysisIntroducedPenalty(args.getStaticAnalysisIntroducedPenalty());
        toReturn.setStaticAnalysisPreExistingPenalty(args.getStaticAnalysisPreExistingPenalty());
        toReturn.setStaticAnalysisCleanBonus(args.getStaticAnalysisCleanBonus());
        toReturn.setArchitecturePenaltyCap(args.getArchitecturePenaltyCap());
        toReturn.setQualityGatePenaltyCap(args.getQualityGatePenaltyCap());

        toReturn.setVolumeExponent(args.getVolumeExponent());
        toReturn.setFilesScopeLogCoefficient(args.getFilesScopeLogCoefficient());
        toReturn.setFilesScopeMaxBonus(args.getFilesScopeMaxBonus());
        toReturn.setStatementsDensityCapMultiplier(args.getStatementsDensityCapMultiplier());
        toReturn.setStatsQuantile(args.getStatsQuantile());

        toReturn.setCoverageLowThreshold(args.getCoverageLowThreshold());
        toReturn.setCoverageCriticalThreshold(args.getCoverageCriticalThreshold());
        toReturn.setCoverageHighThreshold(args.getCoverageHighThreshold());
        toReturn.setHighComplexityThreshold(args.getHighComplexityThreshold());

        toReturn.setCpdCleanBonus(args.getCpdCleanBonus());
        toReturn.setCpdModeratePenalty(args.getCpdModeratePenalty());
        toReturn.setCpdHighPenalty(args.getCpdHighPenalty());
        toReturn.setCpdSeverePenalty(args.getCpdSeverePenalty());
        toReturn.setCpdCleanThreshold(args.getCpdCleanThreshold());
        toReturn.setCpdAcceptableThreshold(args.getCpdAcceptableThreshold());
        toReturn.setCpdModerateThreshold(args.getCpdModerateThreshold());
        toReturn.setCpdHighThreshold(args.getCpdHighThreshold());
        toReturn.setTestCodeScoreMultiplier(args.getTestCodeScoreMultiplier());
        toReturn.setTestCodePenaltyWeight(args.getTestCodePenaltyWeight());

        toReturn.setScoreThresholdHuge(args.getScoreThresholdHuge());
        toReturn.setScoreThresholdLarge(args.getScoreThresholdLarge());
        toReturn.setScoreThresholdMedium(args.getScoreThresholdMedium());
        toReturn.setScoreThresholdSmall(args.getScoreThresholdSmall());

        toReturn.setDimensionScoreCritical(args.getDimensionScoreCritical());
        toReturn.setDimensionScoreMajor(args.getDimensionScoreMajor());
        toReturn.setDimensionScoreModerate(args.getDimensionScoreModerate());

        toReturn.setCallerThresholdHigh(args.getCallerThresholdHigh());
        toReturn.setCallerThresholdModerate(args.getCallerThresholdModerate());

        toReturn.setMaxClonesToShow(args.getMaxClonesToShow());
        toReturn.setMaxSourceLines(args.getMaxSourceLines());
        toReturn.setTruncateSourceLines(args.getTruncateSourceLines());

        toReturn.setArchitectureBonusFactor(args.getArchitectureBonusFactor());

        toReturn.setPmdPriority1Penalty(args.getPmdPriority1Penalty());
        toReturn.setPmdPriority2Penalty(args.getPmdPriority2Penalty());
        toReturn.setPmdPriority3Penalty(args.getPmdPriority3Penalty());
        toReturn.setSpotbugsScariestPenalty(args.getSpotbugsScariestPenalty());
        toReturn.setSpotbugsScaryPenalty(args.getSpotbugsScaryPenalty());
        toReturn.setSpotbugsTroublingPenalty(args.getSpotbugsTroublingPenalty());

        toReturn.setCoverageExcellentBonus(args.getCoverageExcellentBonus());
        toReturn.setCoverageGoodBonus(args.getCoverageGoodBonus());
        toReturn.setCoverageLowPenalty(args.getCoverageLowPenalty());
        toReturn.setCoveragePoorPenalty(args.getCoveragePoorPenalty());
        toReturn.setCoverageTerriblePenalty(args.getCoverageTerriblePenalty());

        toReturn.setArchitectureMinorPenalty(args.getArchitectureMinorPenalty());
        toReturn.setArchitectureSolidPenalty(args.getArchitectureSolidPenalty());
        toReturn.setArchitectureMajorPenalty(args.getArchitectureMajorPenalty());
        toReturn.setQualityGateFailurePenalty(args.getQualityGateFailurePenalty());

        toReturn.setArchitectureImpactScoreThreshold(args.getArchitectureImpactScoreThreshold());
        toReturn.setArchitectureImpactCoverageRequired(args.getArchitectureImpactCoverageRequired());
        toReturn.setConcurrencyRiskThreshold(args.getConcurrencyRiskThreshold());
        toReturn.setIntegrationSurfaceThreshold(args.getIntegrationSurfaceThreshold());
        toReturn.setDataIntegrityThreshold(args.getDataIntegrityThreshold());
        toReturn.setSecuritySensitivityThreshold(args.getSecuritySensitivityThreshold());
        toReturn.setScalabilityImpactThreshold(args.getScalabilityImpactThreshold());
        toReturn.setObservabilityThreshold(args.getObservabilityThreshold());
        toReturn.setResilienceThreshold(args.getResilienceThreshold());
        toReturn.setPerformanceThreshold(args.getPerformanceThreshold());
        toReturn.setSeniorReviewThreshold(args.getSeniorReviewThreshold());
        toReturn.setSeniorReviewCriticalThreshold(args.getSeniorReviewCriticalThreshold());

        toReturn.setComplexityHighDisplayThreshold(args.getComplexityHighDisplayThreshold());
        toReturn.setComplexityModerateDisplayThreshold(args.getComplexityModerateDisplayThreshold());

        toReturn.setSimilarityCriticalThreshold(args.getSimilarityCriticalThreshold());
        toReturn.setSimilarityMajorThreshold(args.getSimilarityMajorThreshold());

        toReturn.setRiskHighDimensionThreshold(args.getRiskHighDimensionThreshold());
        toReturn.setRiskBaseMultiplier(args.getRiskBaseMultiplier());
        toReturn.setRiskHighDimensionPenalty(args.getRiskHighDimensionPenalty());
        toReturn.setRiskCoreLibraryPenalty(args.getRiskCoreLibraryPenalty());
        toReturn.setRiskBreakingChangesPenalty(args.getRiskBreakingChangesPenalty());
        toReturn.setRiskScoreMax(args.getRiskScoreMax());
        toReturn.setRiskLevelLowMax(args.getRiskLevelLowMax());
        toReturn.setRiskLevelModerateMax(args.getRiskLevelModerateMax());
        toReturn.setRiskLevelHighMax(args.getRiskLevelHighMax());
        toReturn.setRiskLevelVeryHighMax(args.getRiskLevelVeryHighMax());

        toReturn.setCoverageImpactExcellentMin(args.getCoverageImpactExcellentMin());
        toReturn.setCoverageImpactGoodMin(args.getCoverageImpactGoodMin());
        toReturn.setCoverageImpactAcceptableMin(args.getCoverageImpactAcceptableMin());
        toReturn.setCoverageImpactLowMin(args.getCoverageImpactLowMin());
        toReturn.setCoverageImpactPoorMin(args.getCoverageImpactPoorMin());

        toReturn.setFanOutHighThreshold(args.getFanOutHighThreshold());
        toReturn.setNpathComplexThreshold(args.getNpathComplexThreshold());

        return toReturn;
    }
    protected SubmissionContext doAnalyze(RunArgs args) throws Exception {
        purgeNonJavaSourceJars(args);

        LogFactory logFactory = new MavenLogFactory(getLog());
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load().block();
                MutableBoolean toApply = new MutableBoolean();
                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, args);
                CommitAnalysis analysis = analyzer.analyze();
                analysis.forEach(diff -> {
                    if (FilenameUtils.isExtension(diff.getFile().getName(), registry.extensions())) {
                        toApply.setTrue();
                    }
                });
                if (BooleanUtils.or(new boolean[] {
                        BooleanUtils.negate(args.matchesByBranch(analysis.getBranches())),
                        BooleanUtils.negate(args.matchesByAuthor(analysis.getAuthorEmail()))
                })) {
                    getLog().info("commit filtered out — branch: " + analysis.getBranches() + ", author: " + analysis.getAuthorEmail());
                    toApply.setFalse();
                }
                if (toApply.isTrue()) {
                    IndexingSummary index = registry.index(analysis);
                    registry.identifyAffectedSymbols(index, analysis);
                    registry.collectAndCapture(index, analysis);
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
                    return ctx;
                }
            }
        }
        return null;
    }
    protected void doLlmScoring(SubmissionContext ctx) throws Exception {
        new LlmScoringPopulator(getLog()).accept(ctx);
    }
}
