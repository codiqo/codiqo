package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
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
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.TagOpt;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;

import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.Project;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.Fetch;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.core.MavenProjectWrapper;
import io.codiqo.maven.logging.MavenLogFactory;
import lombok.SneakyThrows;

abstract class AbstractAnalyzeMojo extends AbstractMojo implements Function<Artifact, Collection<File>> {
    public static final String JAR_EXTENSION = "jar";

    public static final String LOMBOK_GROUP_ID = "org.projectlombok";
    public static final String LOMBOK_ARTIFACT_ID = "lombok";

    public static final String JACOCO_GROUP_ID = "org.jacoco";
    public static final String JACOCO_MAVEN_PLUGIN_ARTIFACT_ID = "jacoco-maven-plugin";
    public static final String JACOCO_MAVEN_PLUGIN_DEST_FILE = "destFile";
    public static final String JACOCO_MAVEN_PLUGIN_DEST_FILE_DEFAULT_VALUE = "jacoco.exec";

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

    @Override
    @SneakyThrows
    @SuppressWarnings("deprecation")
    public final Collection<File> apply(Artifact artifact) {
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(new Dependency(artifact, null));
        collectRequest.setRepositories(remoteRepos);

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
        DependencyResult dependencyResult = repositorySystem.resolveDependencies(mavenSession.getRepositorySession(), dependencyRequest);

        return dependencyResult
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
        }
    }
    protected void captureProjects(RunArgs args, Collection<MavenProject> projects) {
        projects.stream().filter(new Predicate<MavenProject>() {
            @Override
            public boolean test(MavenProject reactor) {
                return CollectionUtils.isEmpty(reactor.getModules());
            }
        }).forEach(new Consumer<MavenProject>() {
            @Override
            @SneakyThrows
            public void accept(MavenProject reactor) {
                MavenProjectWrapper wrapper = new MavenProjectWrapper();
                wrapper.setName(reactor.getName());
                wrapper.setDescription(reactor.getDescription());
                wrapper.setVersion(reactor.getVersion());
                wrapper.setBaseDirectory(reactor.getBasedir());
                wrapper.setOutputDirectory(new File(reactor.getBuild().getOutputDirectory()));

                File jacocoDestFile = autoDetectJacocoDestFile(reactor);
                if (jacocoDestFile.exists()) {
                    wrapper.setCoverage(Optional.of(jacocoDestFile));
                }

                reactor.getCompileSourceRoots().forEach(new Consumer<String>() {
                    @Override
                    public void accept(String root) {
                        File file = new File(root);
                        if (file.exists()) {
                            wrapper.getCompileSourceRoots().add(file);
                        }
                    }
                });
                reactor.getCompileClasspathElements().forEach(new Consumer<String>() {
                    @Override
                    public void accept(String element) {
                        File file = new File(element);
                        if (file.exists()) {
                            wrapper.getCompileClasspathElements().add(file);
                        }
                    }
                });
                reactor.getTestCompileSourceRoots().forEach(new Consumer<String>() {
                    @Override
                    public void accept(String root) {
                        File file = new File(root);
                        if (file.exists()) {
                            wrapper.getTestCompileSourceRoots().add(file);
                        }
                    }
                });
                reactor.getTestClasspathElements().forEach(new Consumer<String>() {
                    @Override
                    public void accept(String element) {
                        File file = new File(element);
                        if (file.exists()) {
                            wrapper.getTestClasspathElements().add(file);
                        }
                    }
                });

                args.getProjects().add(wrapper);
            }
        });

        getLog().info("configured projects for analysis: " + args.getProjects().stream().map(Project::getName).collect(Collectors.toList()));
    }
    protected File autoDetectJacocoDestFile(MavenProject reactor) {
        Predicate<Plugin> filter = new Predicate<Plugin>() {
            @Override
            public boolean test(Plugin plugin) {
                return BooleanUtils.and(new boolean[] {
                        JACOCO_GROUP_ID.equals(plugin.getGroupId()),
                        JACOCO_MAVEN_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())
                });
            }
        };

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
        request.addArgs(ImmutableList.of(
                "clean",
                "verify",
                "-DskipTests=false",
                "-DfailIfNoTests=false",
                "-Djacoco.skip=false",
                "-Dmaven.test.failure.ignore=true",
                "-Dmaven.javadoc.skip=true"));
        request.setBatchMode(true);
        request.setThreads(String.valueOf(mavenSession.getRequest().getDegreeOfConcurrency()));
        request.setOutputHandler(new InvocationOutputHandler() {
            @Override
            public void consumeLine(String line) throws IOException {
                System.out.println(line);
            }
        });
        request.setErrorHandler(new InvocationOutputHandler() {
            @Override
            public void consumeLine(String line) throws IOException {
                System.err.println(line);
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

    }
    protected void doExecute(RunArgs args) throws Exception {
        LogFactory logFactory = new MavenLogFactory(getLog());
        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load().block();

                MutableBoolean toApply = new MutableBoolean();
                DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, registry, args);
                CommitAnalysis analysis = analyzer.analyze();
                analysis.getFiles().forEach(new Consumer<FileAnalysis>() {
                    @Override
                    public void accept(FileAnalysis diff) {
                        if (FilenameUtils.isExtension(diff.getFile().getName(), registry.extensions())) {
                            toApply.setTrue();
                        }
                    }
                });
                if (toApply.isTrue()) {
                    IndexingSummary index = registry.index(analysis);
                    registry.collectAndCapture(index, analysis);
                }
            }
        }
    }
}