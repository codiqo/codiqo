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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
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
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;

import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.maven.populator.CommitModelPopulator;
import io.codiqo.maven.populator.DuplicationReportPopulator;
import io.codiqo.maven.populator.FileAnalysisPopulator;
import io.codiqo.maven.populator.IndexModelPopulator;
import io.codiqo.maven.populator.LlmScoringPopulator;
import io.codiqo.maven.populator.MetricsAggregator;
import io.codiqo.maven.populator.ModuleLevelMetricsPopulator;
import io.codiqo.maven.populator.OutputSerializer;
import io.codiqo.maven.populator.ProjectModelPopulator;
import io.codiqo.maven.populator.SubmissionContext;
import io.codiqo.util.Fetch;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import lombok.SneakyThrows;

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

    @Parameter(property = "codiqo.importTimeoutMinutes", defaultValue = "15")
    protected long importTimeoutMinutes;

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

    @Parameter(property = "codiqo.jdtlsVersion", defaultValue = "1.55.0")
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

    @Parameter(property = "codiqo.pmdMinPriority", defaultValue = "medium_high")
    protected String pmdMinPriority;

    @Parameter(property = "codiqo.spotbugsPriorityThreshold", defaultValue = "2")
    protected int spotbugsPriorityThreshold;

    @Parameter(property = "codiqo.spotbugsOmitVisitors")
    protected String spotbugsOmitVisitors;

    @Parameter(property = "codiqo.llm.model", defaultValue = "gpt-oss:120b-cloud")
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

    @Override
    @SneakyThrows
    @SuppressWarnings("deprecation")
    public final Collection<File> apply(Artifact artifact) {
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
    }
    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {
        RunArgs args = new RunArgs();
        Optional.ofNullable(javaHome).ifPresent(args::setJavaHome);
        Optional.ofNullable(mavenHome).ifPresent(args::setMavenHome);
        args.setImportTimeout(Duration.ofMinutes(importTimeoutMinutes));
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
        if (StringUtils.isNotEmpty(llmApiKey)) {
            if (llmApiKey.startsWith(ENV_PREFIX)) {
                String envVar = llmApiKey.substring(ENV_PREFIX.length());
                args.setLlmApiKey(System.getenv(envVar));
            } else {
                args.setLlmApiKey(llmApiKey);
            }
        }
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
    protected ScanResult scanProjects(RunArgs args, Collection<MavenProject> projects) {
        Set<URI> jars = Sets.newLinkedHashSet();
        projects.stream()
                .filter(reactor -> BooleanUtils.negate(NON_CODE_PACKAGINGS.contains(reactor.getPackaging())))
                .filter(reactor -> CollectionUtils.isEmpty(reactor.getModules())).filter(new Predicate<>() {
                    @Override
                    @SneakyThrows
                    public boolean test(MavenProject reactor) {
                        return BooleanUtils.or(new boolean[] {
                                CollectionUtils.isNotEmpty(reactor.getCompileClasspathElements()),
                                CollectionUtils.isNotEmpty(reactor.getTestClasspathElements()),
                        });
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
        ClassGraph classGraph = new ClassGraph().enableAllInfo();
        jars.forEach(classGraph::overrideClasspath);
        StopWatch stopWatch = StopWatch.createStarted();
        ScanResult scan = classGraph.scan();
        stopWatch.stop();
        args.getProjects().forEach(spec -> {
            if (spec instanceof MavenProjectWrapper) {
                ((MavenProjectWrapper) spec).setScan(scan);
            }
        });
        getLog().info(String.format("configured maven projects in %s, classgraph classes: %d", stopWatch, scan.getAllClasses().size()));
        return scan;
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
                    "-Dmaven.javadoc.skip=true"));
        } else {
            request.addArgs(ImmutableList.of(
                    "clean",
                    "verify",
                    "-DskipTests=false",
                    "-DfailIfNoTests=false",
                    "-Djacoco.skip=false",
                    "-Dmaven.test.failure.ignore=true",
                    "-Dmaven.javadoc.skip=true"));
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
            throw new MojoExecutionException("maven build failed in fork", result.getExecutionException());
        }
        return projectBuilder.build(rootPom, buildingRequest);
    }
    protected void buildAndCollectModules(
            MavenProject parent,
            File baseDir,
            ProjectBuildingRequest buildingRequest,
            Collection<MavenProject> collected) throws Exception {
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
            doLlmScoring(ctx);
        }
    }
    protected SubmissionContext doAnalyze(RunArgs args) throws Exception {
        LogFactory logFactory = new MavenLogFactory(getLog());
        Path workTree = args.getGit().getWorkTree().toPath().normalize();
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load().block();
                MutableBoolean toApply = new MutableBoolean();
                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, registry, args);
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
                    new IndexModelPopulator().accept(ctx);
                    DuplicationReportPopulator duplicationPopulator = new DuplicationReportPopulator();
                    duplicationPopulator.accept(ctx);
                    new MetricsAggregator(duplicationPopulator.getTotalDuplicatedLines()).accept(ctx);
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
