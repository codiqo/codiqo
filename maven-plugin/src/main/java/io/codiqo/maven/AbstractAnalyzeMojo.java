package io.codiqo.maven;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import org.apache.commons.lang3.Strings;
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
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolTag;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.Priorities;
import io.codiq.lang.spec.JBinaryMethodSig;
import io.codiq.lang.spec.JavaBinarySignatureFormatter.BinarySignatureData;
import io.codiq.lang.spec.JavaCodeBlockInfo;
import io.codiq.lang.spec.JavaConstructorBlockInfo;
import io.codiq.lang.spec.JavaMethodBlockInfo;
import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.DuplicateMark;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.cpd.DuplicationMatch;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CallerModel;
import io.codiqo.client.model.ClientInfoModel;
import io.codiqo.client.model.ClientInfoModel.BuildToolEnum;
import io.codiqo.client.model.CloneFromExistingModel;
import io.codiqo.client.model.CloneLocationModel;
import io.codiqo.client.model.CloneModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitRefModel;
import io.codiqo.client.model.CodebaseIndexModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.CoverageCounterModel;
import io.codiqo.client.model.CoverageModel;
import io.codiqo.client.model.DependencyModel;
import io.codiqo.client.model.DependencyRegistryModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.JavaInfoModel;
import io.codiqo.client.model.LineCoverageModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MavenDependencyModel;
import io.codiqo.client.model.MavenModuleModel;
import io.codiqo.client.model.MethodCallModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.NewCloneGroupModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.jdtls.Lsp4jAffectedSymbolInfo;
import io.codiqo.maven.logging.MavenLogFactory;
import io.codiqo.util.Fetch;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import lombok.SneakyThrows;
import net.sourceforge.pmd.lang.java.ast.ASTAnonymousClassDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTThrowsList;
import net.sourceforge.pmd.lang.java.ast.ASTTypeParameters;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.RuleViolation;

abstract class AbstractAnalyzeMojo extends AbstractMojo implements Function<Artifact, Collection<File>> {
    public static final Set<String> NON_CODE_PACKAGINGS = Set.of("pom", "bom");
    public static final String JAR_EXTENSION = "jar";

    public static final String LOMBOK_GROUP_ID = "org.projectlombok";
    public static final String LOMBOK_ARTIFACT_ID = "lombok";

    public static final String JACOCO_GROUP_ID = "org.jacoco";
    public static final String JACOCO_MAVEN_PLUGIN_ARTIFACT_ID = "jacoco-maven-plugin";
    public static final String JACOCO_MAVEN_PLUGIN_DEST_FILE = "destFile";
    public static final String JACOCO_MAVEN_PLUGIN_DEST_FILE_DEFAULT_VALUE = "jacoco.exec";

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

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "60")
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

    protected final Function<Integer, DiagnosticModel.SeverityEnum> pmdPriorityMapper = priority -> {
        RulePriority valueOf = RulePriority.valueOf(priority);
        switch (valueOf) {
            case HIGH:
                return DiagnosticModel.SeverityEnum.ERROR;
            case MEDIUM_HIGH:
            case MEDIUM:
            case MEDIUM_LOW:
                return DiagnosticModel.SeverityEnum.WARNING;
            case LOW:
            default:
                return DiagnosticModel.SeverityEnum.INFO;
        }
    };
    protected final Function<Integer, DiagnosticModel.SeverityEnum> spotbugsPriorityMapper = priority -> {
        switch (priority) {
            case Priorities.HIGH_PRIORITY:
                return DiagnosticModel.SeverityEnum.ERROR;
            case Priorities.NORMAL_PRIORITY:
                return DiagnosticModel.SeverityEnum.WARNING;
            case Priorities.LOW_PRIORITY:
            case Priorities.IGNORE_PRIORITY:
            case Priorities.EXP_PRIORITY:
            default:
                return DiagnosticModel.SeverityEnum.INFO;
        }
    };

    @Override
    @SuppressWarnings("deprecation")
    public final Collection<File> apply(Artifact artifact) {
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
        } catch (DependencyResolutionException err) {
            throw new UndeclaredThrowableException(err);
        }
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
        if (!opt.isPresent()) {
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

                if (toApply.isTrue()) {
                    IndexingSummary index = registry.index(analysis);
                    registry.collectAndCapture(index, analysis);

                    AnalysisSubmissionModel submissionModel = new AnalysisSubmissionModel();
                    DependencyRegistryModel dependencyRegistryModel = new DependencyRegistryModel();
                    submissionModel.setDependencies(dependencyRegistryModel);

                    /**
                     * project info
                     */
                    ProjectModel projectModel = new ProjectModel();
                    projectModel.setId(project.getId());
                    projectModel.setName(project.getName());

                    args.getRemoteUrls().forEach(url -> {
                        try {
                            URIish urIish = new URIish(url);
                            if (StringUtils.isNotEmpty(urIish.getScheme())) {
                                URI repositoryUri = URI.create(urIish.toString());
                                projectModel.getRepositoryUrls().add(repositoryUri);
                            } else {
                                String path = Strings.CS.removeStart(urIish.getPath(), "/");
                                URI repositoryUri = URI.create(String.format("https://%s/%s", urIish.getHost(), path));
                                projectModel.getRepositoryUrls().add(repositoryUri);
                            }
                        } catch (URISyntaxException err) {
                            getLog().error("failed to parse repository URL: " + url, err);
                        }
                    });

                    args.getProjects().stream()
                            .filter(spec -> spec instanceof MavenProjectWrapper)
                            .map(spec -> (MavenProjectWrapper) spec)
                            .forEach(mavenSpec -> {
                                try {
                                    for (org.apache.maven.artifact.Artifact artifact : mavenSpec.getArtifacts().keySet()) {
                                        MavenDependencyModel mavenDependencyModel = new MavenDependencyModel();
                                        mavenDependencyModel.setGroupId(artifact.getGroupId());
                                        mavenDependencyModel.setArtifactId(artifact.getArtifactId());
                                        mavenDependencyModel.setClassifier(artifact.getClassifier());
                                        mavenDependencyModel.setType(artifact.getType());

                                        DependencyModel dependencyModel = new DependencyModel();
                                        dependencyModel.setMaven(mavenDependencyModel);
                                        dependencyModel.setName(artifact.getId());
                                        dependencyModel.setVersion(artifact.getVersion());
                                        dependencyModel.setOptional(artifact.isOptional());
                                        dependencyModel.setSnapshot(artifact.isSnapshot());
                                        dependencyModel.setRelease(artifact.isRelease());
                                        if (StringUtils.isNotEmpty(artifact.getDownloadUrl())) {
                                            dependencyModel.setHomepage(new URI(artifact.getDownloadUrl()));
                                        }
                                        if (Objects.nonNull(artifact.getRepository())) {
                                            if (StringUtils.isNotEmpty(artifact.getRepository().getUrl())) {
                                                dependencyModel.setRepositoryUrl(new URI(artifact.getRepository().getUrl()));
                                            }
                                        }
                                        dependencyRegistryModel.getArtifacts().put(artifact.getId(), dependencyModel);
                                    }

                                } catch (URISyntaxException err) {
                                    getLog().error("failed to parse dependency URL in project: " + mavenSpec.getName(), err);
                                }
                            });

                    /**
                     * modules
                     */
                    args.getProjects().forEach(spec -> {
                        if (spec instanceof MavenProjectWrapper) {
                            MavenProjectWrapper mavenSpec = (MavenProjectWrapper) spec;

                            Path projectDir = workTree.relativize(mavenSpec.getBaseDirectory().toPath()).normalize();

                            MavenModuleModel mavenModuleModel = new MavenModuleModel();
                            mavenModuleModel.setGroupId(mavenSpec.getGroupId());
                            mavenModuleModel.setArtifactId(mavenSpec.getArtifactId());
                            mavenModuleModel.setVersion(mavenSpec.getVersion());
                            mavenModuleModel.setPackaging(mavenSpec.getPackaging());
                            mavenSpec.parent().ifPresent(parentId -> mavenModuleModel.setParent(parentId));

                            ModuleModel moduleModel = new ModuleModel();
                            moduleModel.setMaven(mavenModuleModel);
                            moduleModel.setId(spec.getId());
                            moduleModel.setName(mavenSpec.getName());
                            moduleModel.setBaseDirectory(projectDir.toString());

                            for (File file : mavenSpec.getCompileSourceRoots()) {
                                moduleModel.getSourceRoots().add(workTree.resolve(projectDir).relativize(file.toPath()).normalize().toString());
                            }
                            for (File file : mavenSpec.getTestCompileSourceRoots()) {
                                moduleModel.getTestSourceRoots().add(workTree.resolve(projectDir).relativize(file.toPath()).normalize().toString());
                            }
                            for (org.apache.maven.artifact.Artifact artifact : mavenSpec.getArtifacts().keySet()) {
                                moduleModel.getDependencies().add(artifact.getId());
                            }

                            projectModel.getModules().add(moduleModel);
                        }
                    });

                    submissionModel.setProject(projectModel);

                    /**
                     * commit info
                     */
                    CommitModel commitModel = new CommitModel();
                    commitModel.setSha(analysis.getCommitId());
                    commitModel.setMessage(analysis.getMessage());
                    commitModel.setAuthor(analysis.getAuthor());
                    commitModel.setAuthorEmail(analysis.getAuthorEmail());
                    commitModel.setTimestamp(analysis.getAuthorTimestamp().toInstant().atOffset(ZoneOffset.UTC));
                    commitModel.setParents(analysis.getParentIds());
                    commitModel.setBranches(analysis.getBranches());
                    commitModel.setIsMerge(analysis.isMergeCommit());
                    submissionModel.setCommit(commitModel);

                    /**
                     * client info
                     */
                    ClientInfoModel clientModel = new ClientInfoModel();
                    clientModel.setBuildTool(BuildToolEnum.MAVEN);
                    clientModel.setVersion(runtimeInformation.getMavenVersion());
                    clientModel.setName("codiqo-maven-plugin");
                    submissionModel.setClient(clientModel);

                    /**
                     * file changes
                     */
                    for (FileAnalysis fileAnalysis : analysis) {
                        FileChangeModel fileChangeModel = new FileChangeModel();
                        fileChangeModel.setDiff(fileAnalysis.getDiffText());
                        fileChangeModel.setContentBefore(fileAnalysis.getContentBefore());
                        fileChangeModel.setContentAfter(fileAnalysis.getContentAfter());
                        fileChangeModel.setChangeType(FileChangeModel.ChangeTypeEnum.fromValue(fileAnalysis.getChangeType().name().toLowerCase()));
                        fileChangeModel.setPreviousPath(fileAnalysis.getOldPath());
                        fileChangeModel.setPath(fileAnalysis.getNewPath());
                        fileChangeModel.setIsTest(fileAnalysis.isTestFile());
                        if (Objects.nonNull(fileAnalysis.getLanguage())) {
                            fileChangeModel.setLanguage(FileChangeModel.LanguageEnum.fromValue(fileAnalysis.getLanguage().getId()));
                        }
                        fileAnalysis.project().ifPresent(spec -> fileChangeModel.setModule(spec.getId()));

                        for (AffectedSymbolInfo affectedSymbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                            LocationModel locationModel = new LocationModel();
                            locationModel.setStartLine(affectedSymbol.getLocation().getStartLine());
                            locationModel.setStartColumn(affectedSymbol.getLocation().getStartColumn());
                            locationModel.setEndLine(affectedSymbol.getLocation().getEndLine());
                            locationModel.setEndColumn(affectedSymbol.getLocation().getEndColumn());

                            affectedSymbol.block().ifPresent(block -> {
                                if (block instanceof JavaCodeBlockInfo) {
                                    JavaCodeBlockInfo javaBlock = (JavaCodeBlockInfo) block;

                                    JavaInfoModel infoModel = new JavaInfoModel();
                                    infoModel.setIsAnonymous(javaBlock.getType() instanceof ASTAnonymousClassDeclaration);
                                    infoModel.setIsAbstract(javaBlock.isAbstract());
                                    infoModel.setIsFinal(javaBlock.isFinal());
                                    infoModel.setIsStatic(javaBlock.isStatic());
                                    infoModel.setIsSynchronized(javaBlock.isSynchronized());
                                    infoModel.setEnclosingClass(javaBlock.getEnclosingType().getSimpleName());
                                    if (Objects.nonNull(javaBlock.getType())) {
                                        infoModel.setPackageName(javaBlock.getType().getPackageName());
                                        infoModel.setClassName(javaBlock.getType().getSimpleName());
                                    }

                                    CodeUnitModel codeUnitModel = new CodeUnitModel();
                                    codeUnitModel.setName(affectedSymbol.getName());
                                    codeUnitModel.setBody(javaBlock.getBody());
                                    codeUnitModel.setLocation(locationModel);
                                    codeUnitModel.setModifiers(javaBlock.getModifiers());
                                    codeUnitModel.setSignature(javaBlock.getSignature());
                                    codeUnitModel.setIsTrivial(javaBlock.isTrivial());
                                    codeUnitModel.setJava(infoModel);

                                    /**
                                     * symbol Kind and Incoming Calls from LSP4J
                                     */
                                    if (affectedSymbol instanceof Lsp4jAffectedSymbolInfo) {
                                        Lsp4jAffectedSymbolInfo lsp4jSymbol = (Lsp4jAffectedSymbolInfo) affectedSymbol;
                                        codeUnitModel.setKind(SymbolKindModel.fromValue(lsp4jSymbol.getKind().name().toLowerCase()));
                                        List<SymbolTag> symbolTags = lsp4jSymbol.getTags();
                                        if (CollectionUtils.isNotEmpty(symbolTags)) {
                                            for (SymbolTag tag : symbolTags) {
                                                if (tag == SymbolTag.Deprecated) {
                                                    infoModel.setIsDeprecated(true);
                                                }
                                            }
                                        }

                                        /**
                                         * incoming Calls (CALLERS) - Critical for LLM blast radius analysis.
                                         * this shows who calls this method, essential for risk scoring.
                                         */
                                        for (CallHierarchyIncomingCall incomingCall : lsp4jSymbol.getIncomingCalls()) {
                                            CallHierarchyItem from = incomingCall.getFrom();
                                            if (Objects.isNull(from)) {
                                                continue;
                                            }

                                            CallerModel callerModel = new CallerModel();
                                            callerModel.setName(from.getName());
                                            callerModel.setSymbol(from.getDetail());
                                            if (StringUtils.isNotEmpty(from.getUri())) {
                                                try {
                                                    URI uri = new URI(from.getUri());
                                                    File file = new File(uri.toURL().getFile());
                                                    if (file.exists()) {
                                                        args.owner(file).ifPresent(spec -> {
                                                            callerModel.setIsTest(spec.isTestResource(file));
                                                        });
                                                    }
                                                    callerModel.setPath(workTree.toRealPath().relativize(Paths.get(uri).toRealPath()).toString());
                                                } catch (URISyntaxException | IOException err) {
                                                    ExceptionUtils.wrapAndThrow(err);
                                                }
                                            }

                                            if (Objects.nonNull(from.getKind())) {
                                                callerModel.setKind(SymbolKindModel.fromValue(from.getKind().name().toLowerCase()));
                                            }

                                            List<SymbolTag> callerTags = from.getTags();
                                            if (CollectionUtils.isNotEmpty(callerTags)) {
                                                for (SymbolTag tag : callerTags) {
                                                    if (tag == SymbolTag.Deprecated) {
                                                        callerModel.setIsDeprecated(true);
                                                    }
                                                }
                                            }

                                            if (Objects.nonNull(from.getRange())) {
                                                LocationModel callerLocation = new LocationModel();
                                                callerLocation.setStartLine(from.getRange().getStart().getLine() + BigDecimal.ONE.intValue());
                                                callerLocation.setStartColumn(from.getRange().getStart().getCharacter() + BigDecimal.ONE.intValue());
                                                callerLocation.setEndLine(from.getRange().getEnd().getLine() + BigDecimal.ONE.intValue());
                                                callerLocation.setEndColumn(from.getRange().getEnd().getCharacter() + BigDecimal.ONE.intValue());
                                                callerModel.setLocation(callerLocation);
                                            }

                                            List<Range> fromRanges = incomingCall.getFromRanges();
                                            if (CollectionUtils.isNotEmpty(fromRanges)) {
                                                for (Range range : fromRanges) {
                                                    LocationModel callSiteLocation = new LocationModel();
                                                    callSiteLocation.setStartLine(range.getStart().getLine() + BigDecimal.ONE.intValue());
                                                    callSiteLocation.setStartColumn(range.getStart().getCharacter() + BigDecimal.ONE.intValue());
                                                    callSiteLocation.setEndLine(range.getEnd().getLine() + BigDecimal.ONE.intValue());
                                                    callSiteLocation.setEndColumn(range.getEnd().getCharacter() + BigDecimal.ONE.intValue());
                                                    callerModel.getCallSites().add(callSiteLocation);
                                                }
                                            }

                                            codeUnitModel.getCallers().add(callerModel);
                                        }
                                    }

                                    /**
                                     * coverage (JaCoCo)
                                     */
                                    javaBlock.coverage().subscribe(cov -> {
                                        if (cov.hasCoverageData()) {
                                            CoverageModel coverageModel = new CoverageModel();
                                            coverageModel.setCoveredLines(cov.getCovered() + cov.getPartial());
                                            coverageModel.setMissedLines(cov.getMissed());
                                            coverageModel.setLinePercent(cov.lineCoveragePercent());

                                            if (cov.totalBranches() > 0) {
                                                coverageModel.setCoveredBranches(cov.getCoveredBranches());
                                                coverageModel.setMissedBranches(cov.getMissedBranches());
                                                coverageModel.setBranchPercent(cov.branchCoveragePercent());
                                            }

                                            for (Map.Entry<Integer, ILine> entry : javaBlock.getLineCoverage().entrySet()) {
                                                Integer lineNumber = entry.getKey();
                                                ILine lineInfo = entry.getValue();

                                                LineCoverageModel lineModel = new LineCoverageModel();
                                                lineModel.setLine(lineNumber);
                                                lineModel.setHits(lineInfo.getInstructionCounter().getCoveredCount());

                                                lineModel.setStatus(LineCoverageModel.StatusEnum.EMPTY);
                                                if (lineInfo.getStatus() == ICounter.EMPTY) {
                                                    lineModel.setStatus(LineCoverageModel.StatusEnum.EMPTY);
                                                } else if (lineInfo.getStatus() == ICounter.NOT_COVERED) {
                                                    lineModel.setStatus(LineCoverageModel.StatusEnum.MISSED);
                                                } else if (lineInfo.getStatus() == ICounter.PARTLY_COVERED) {
                                                    lineModel.setStatus(LineCoverageModel.StatusEnum.PARTIAL);
                                                } else if (lineInfo.getStatus() == ICounter.FULLY_COVERED) {
                                                    lineModel.setStatus(LineCoverageModel.StatusEnum.COVERED);
                                                }

                                                ICounter branchCtr = lineInfo.getBranchCounter();
                                                if (branchCtr.getTotalCount() > 0) {
                                                    CoverageCounterModel branchModel = new CoverageCounterModel();
                                                    branchModel.setCovered(branchCtr.getCoveredCount());
                                                    branchModel.setMissed(branchCtr.getMissedCount());
                                                    lineModel.setBranches(branchModel);
                                                }

                                                coverageModel.getLines().add(lineModel);
                                            }

                                            codeUnitModel.setCoverage(coverageModel);
                                        }
                                    });

                                    /**
                                     * method references (out bound calls)
                                     */
                                    for (JBinaryMethodSig methodCall : javaBlock.getMethodCalls()) {
                                        BinarySignatureData signatureData = methodCall.toBinarySignature();

                                        MethodCallModel methodCallModel = new MethodCallModel();
                                        methodCallModel.setTargetSignature(signatureData.getDescriptor());
                                        methodCallModel.setTargetClass(signatureData.getOwnerClass());
                                        methodCallModel.setTargetMethod(signatureData.getMethodName());
                                        methodCallModel.setIsStatic(methodCall.isStatic());
                                        methodCallModel.setIsConstructor(methodCall.isConstructor());

                                        LocationModel callLocation = new LocationModel();
                                        callLocation.setStartLine(methodCall.getBeginLine());
                                        callLocation.setStartColumn(methodCall.getBeginColumn());
                                        callLocation.setEndLine(methodCall.getEndLine());
                                        callLocation.setEndColumn(methodCall.getEndColumn());
                                        methodCallModel.setLocation(callLocation);

                                        methodCall.artifact().ifPresent(artifact -> methodCallModel.setArtifact(artifact.getId()));

                                        infoModel.getMethodCalls().add(methodCallModel);
                                    }

                                    /**
                                     * spotBugs diagnostics
                                     */
                                    for (BugInstance bug : javaBlock.getSpotbugs()) {
                                        DiagnosticModel diagnosticModel = new DiagnosticModel();
                                        diagnosticModel.setTool(DiagnosticModel.ToolEnum.SPOTBUGS);
                                        diagnosticModel.setRuleId(bug.getBugPattern().getType());
                                        diagnosticModel.setMessage(bug.getMessage());
                                        diagnosticModel.setCategory(bug.getBugPattern().getCategory());

                                        diagnosticModel.setSeverity(spotbugsPriorityMapper.apply(bug.getPriority()));

                                        Optional.ofNullable(bug.getPrimarySourceLineAnnotation()).ifPresent(srcLine -> {
                                            LocationModel diagLocation = new LocationModel();
                                            diagLocation.setStartLine(srcLine.getStartLine());
                                            diagLocation.setEndLine(srcLine.getEndLine());
                                            diagnosticModel.setLocation(diagLocation);
                                        });

                                        int cweid = bug.getBugPattern().getCWEid();
                                        if (cweid > 0) {
                                            diagnosticModel.setCwe(Lists.newArrayList("CWE-" + cweid));
                                        }

                                        codeUnitModel.getDiagnostics().add(diagnosticModel);
                                    }

                                    /**
                                     * PMD diagnostics
                                     */
                                    for (RuleViolation violation : javaBlock.getPmdViolations()) {
                                        DiagnosticModel diagnosticModel = new DiagnosticModel();
                                        diagnosticModel.setTool(DiagnosticModel.ToolEnum.PMD);
                                        diagnosticModel.setRuleId(violation.getRule().getName());
                                        diagnosticModel.setMessage(violation.getDescription());
                                        diagnosticModel.setCategory(violation.getRule().getRuleSetName());
                                        diagnosticModel.setSeverity(pmdPriorityMapper.apply(violation.getRule().getPriority().getPriority()));

                                        LocationModel diagLocation = new LocationModel();
                                        diagLocation.setStartLine(violation.getBeginLine());
                                        diagLocation.setStartColumn(violation.getBeginColumn());
                                        diagLocation.setEndLine(violation.getEndLine());
                                        diagLocation.setEndColumn(violation.getEndColumn());
                                        diagnosticModel.setLocation(diagLocation);

                                        Optional.ofNullable(violation.getRule().getExternalInfoUrl()).ifPresent(url -> {
                                            if (StringUtils.isNotEmpty(url)) {
                                                diagnosticModel.setDocumentation(URI.create(url));
                                            }
                                        });

                                        codeUnitModel.getDiagnostics().add(diagnosticModel);
                                    }

                                    /**
                                     * constructor/method(s) specific handling
                                     */
                                    if (javaBlock instanceof JavaConstructorBlockInfo) {
                                        ASTConstructorDeclaration constructor = ((JavaConstructorBlockInfo) javaBlock).getConstructor();
                                        ASTThrowsList throwsList = constructor.getThrowsList();
                                        ASTTypeParameters typeParameters = constructor.getTypeParameters();

                                        codeUnitModel.setKind(SymbolKindModel.CONSTRUCTOR);
                                        Optional.ofNullable(typeParameters).ifPresent(t -> t.forEach(tp -> infoModel.getTypeParameters().add(tp.getName())));
                                        Optional.ofNullable(throwsList).ifPresent(l -> l.forEach(tt -> infoModel.getThrowsTypes().add(tt.getSimpleName())));

                                    } else if (javaBlock instanceof JavaMethodBlockInfo) {
                                        ASTMethodDeclaration method = ((JavaMethodBlockInfo) javaBlock).getMethod();
                                        ASTTypeParameters typeParameters = method.getTypeParameters();
                                        ASTThrowsList throwsList = method.getThrowsList();

                                        codeUnitModel.setKind(SymbolKindModel.METHOD);
                                        Optional.ofNullable(typeParameters).ifPresent(t -> t.forEach(tp -> infoModel.getTypeParameters().add(tp.getName())));
                                        Optional.ofNullable(throwsList).ifPresent(l -> l.forEach(tt -> infoModel.getThrowsTypes().add(tt.getSimpleName())));
                                    }

                                    /**
                                     * metrics
                                     */
                                    javaBlock.metrics().subscribe(metrics -> {
                                        MetricsModel metricsModel = new MetricsModel();
                                        metricsModel.setCyclomaticComplexity(metrics.cyclo());
                                        metricsModel.setCognitiveComplexity(metrics.cognitive());
                                        metricsModel.setLinesOfCode(metrics.lineCount());
                                        metricsModel.setLogicalLinesOfCode(metrics.ncss());
                                        codeUnitModel.setMetrics(metricsModel);
                                    });

                                    fileChangeModel.getCodeUnits().add(codeUnitModel);
                                }
                            });
                        }

                        submissionModel.getFiles().add(fileChangeModel);
                    }

                    /**
                     * code base index
                     */
                    CodebaseIndexModel indexModel = new CodebaseIndexModel();
                    indexModel.setTotalFiles(index.getTotalFiles().size());
                    indexModel.setTotalCodeUnits(index.getBlocks().size());
                    indexModel.setIgnoredFiles(index.getIgnoredFiles().size());
                    indexModel.setSkippedFiles(index.getSkippedFiles().size());
                    indexModel.setTotalNonTrivial(index.getTotalNonTrivial());
                    indexModel.setSkippedTrivial(index.getSkippedTrivial());
                    for (FileAnalysis fileAnalysis : analysis) {
                        for (AffectedSymbolInfo affectedSymbol : fileAnalysis.getPotentiallyAffectedSymbols()) {
                            CodeUnitRefModel refModel = new CodeUnitRefModel();

                            LocationModel locationModel = new LocationModel();
                            locationModel.setStartLine(affectedSymbol.getLocation().getStartLine());
                            locationModel.setStartColumn(affectedSymbol.getLocation().getStartColumn());
                            locationModel.setEndLine(affectedSymbol.getLocation().getEndLine());
                            locationModel.setEndColumn(affectedSymbol.getLocation().getEndColumn());
                            refModel.setLocation(locationModel);
                            refModel.setPath(fileAnalysis.getNewPath());
                            affectedSymbol.block();
                            args.owner(fileAnalysis.getFile()).ifPresent(spec -> refModel.setModule(spec.getId()));
                            affectedSymbol.block().ifPresent(block -> {
                                refModel.setSignature(block.getSignature());
                            });
                            indexModel.getCodeUnits().add(refModel);
                        }
                    }
                    submissionModel.setIndex(indexModel);

                    /**
                     * duplication report
                     */
                    DuplicationReportModel duplicationReportModel = new DuplicationReportModel();
                    duplicationReportModel.setTool(DuplicationReportModel.ToolEnum.PMD_CPD);
                    duplicationReportModel.setMinimumTokens(args.getCpdMinimumTileSize());

                    for (CopyPasteDetectionSummary cpd : analysis.cpd()) {
                        for (DuplicationMatch match : cpd.affected()) {
                            CloneModel cloneModel = new CloneModel();
                            cloneModel.setTokenCount(match.getTokenCount());
                            cloneModel.setLineCount(match.getLineCount());
                            cloneModel.setIsCrossFile(match.isCrossFile());
                            duplicationReportModel.getClones().add(cloneModel);

                            for (DuplicateMark mark : match) {
                                CloneLocationModel locationModel = new CloneLocationModel();
                                locationModel.setPath(workTree.relativize(mark.getFile().toPath()).toString());

                                LocationModel loc = new LocationModel();
                                loc.setStartLine(mark.getLocation().getStartLine());
                                loc.setStartColumn(mark.getLocation().getStartColumn());
                                loc.setEndLine(mark.getLocation().getEndLine());
                                loc.setEndColumn(mark.getLocation().getEndColumn());
                                locationModel.setLocation(loc);
                                locationModel.setSourceSlice(mark.getSourceCodeSlice().toString());
                                mark.block().map(CodeBlockInfo::getSignature).ifPresent(locationModel::setCodeUnitSignature);
                                cloneModel.getLocations().add(locationModel);
                            }
                        }

                        cpd.copyPasteFrom().forEach((targetBlock, sourceBlocks) -> {
                            CloneFromExistingModel fromModel = new CloneFromExistingModel();
                            fromModel.setAffectedSignature(targetBlock.getSignature());
                            fromModel.setSourceSignatures(sourceBlocks.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toList()));
                            duplicationReportModel.getClonesFromExisting().add(fromModel);
                        });

                        cpd.copyPasteNew().forEach(newCloneSet -> {
                            NewCloneGroupModel newModel = new NewCloneGroupModel();
                            newModel.setMemberSignatures(newCloneSet.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toList()));
                            duplicationReportModel.getNewClones().add(newModel);
                        });
                    }

                    submissionModel.setDuplication(duplicationReportModel);

                    ObjectMapper mapper = preferYaml ? new YAMLMapper() : new ObjectMapper();
                    mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
                    mapper.registerModule(new JavaTimeModule());
                    mapper.setDefaultPropertyInclusion(Include.NON_NULL);
                    mapper.setDateFormat(new StdDateFormat());
                    mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
                    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

                    if (args.isDumpAnalysis()) {
                        mapper.enable(SerializationFeature.INDENT_OUTPUT);
                    }

                    if (args.isDumpAnalysis()) {
                        String extension = preferYaml ? "yaml" : "json";
                        File file = Files.createTempFile("codiqo-submission-", "." + extension).toFile();
                        String output = mapper.writeValueAsString(submissionModel);
                        try (FileOutputStream stream = new FileOutputStream(file)) {
                            try (BufferedOutputStream bufferedStream = new BufferedOutputStream(stream)) {
                                bufferedStream.write(output.getBytes(StandardCharsets.UTF_8));
                                bufferedStream.flush();
                            }
                        }
                        getLog().info("analysis submission written to " + file.getAbsolutePath());
                    }
                }
            }
        }
    }
}
