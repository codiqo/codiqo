package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
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

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;

import io.codiqo.api.DeltaAnalyzer;
import io.codiqo.api.IndexingSummary;
import io.codiqo.api.LanguageProcessors;
import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.core.Fetch;
import io.codiqo.core.JGitDeltaAnalyzer;
import io.codiqo.core.MavenProjectWrapper;
import io.codiqo.maven.logging.MavenLogFactory;
import lombok.SneakyThrows;

@Mojo(name = "analyze-commit",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class AnalyzeCommitMojo extends AbstractMojo {
    @Inject
    private RepositorySystem repositorySystem;

    @Inject
    private MavenSession mavenSession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    private List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    @Parameter(property = "commitId", required = true)
    private String commitId;

    private final Function<Artifact, List<Path>> dependencyResolver = new Function<Artifact, List<Path>>() {
        @Override
        @SneakyThrows
        @SuppressWarnings("deprecation")
        public List<Path> apply(Artifact artifact) {
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRoot(new Dependency(artifact, null));
            collectRequest.setRepositories(remoteRepos);

            DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
            DependencyResult dependencyResult = repositorySystem.resolveDependencies(mavenSession.getRepositorySession(), dependencyRequest);

            ImmutableList.Builder<Artifact> artifacts = ImmutableList.builder();
            for (ArtifactResult artifactResult : dependencyResult.getArtifactResults()) {
                artifacts.add(artifactResult.getArtifact());
            }
            return artifacts.build().stream().map(Artifact::getFile).map(File::toPath).collect(ImmutableList.toImmutableList());
        }
    };

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        LogFactory logFactory = new MavenLogFactory(getLog());

        RunArgs args = new RunArgs();
        args.setRepo(project.getBasedir().toPath());
        args.setCommitId(commitId);

        try {
            URL resource = Resources.getResource("codiqo-dependency-versions.properties");
            try (InputStream stream = resource.openStream()) {
                Properties versions = new Properties();
                versions.load(stream);

                for (DefaultArtifact agent : new DefaultArtifact[] {
                        new DefaultArtifact("org.projectlombok:lombok:" + versions.get("lombok.version"))
                }) {
                    args.getAgents().addAll(dependencyResolver.apply(agent));
                }
            }

            for (MavenProject reactor : reactorProjects) {
                if (CollectionUtils.isEmpty(reactor.getModules())) {
                    MavenProjectWrapper wrapper = new MavenProjectWrapper();
                    wrapper.setName(reactor.getName());
                    wrapper.setDescription(reactor.getDescription());
                    wrapper.setVersion(reactor.getVersion());
                    wrapper.setBaseDirectory(reactor.getBasedir());
                    wrapper.setOutputDirectory(reactor.getBuild().getOutputDirectory());

                    //
                    // ~ try to find coverage executable and if present assume the coverage data is available
                    //
                    Plugin jacoco = project.getBuild().getPluginsAsMap().get("org.jacoco:jacoco-maven-plugin");
                    if (Objects.nonNull(jacoco)) {
                        Xpp3Dom config = (Xpp3Dom) jacoco.getConfiguration();
                        if (config != null) {
                            Xpp3Dom destFile = config.getChild("destFile");
                            Path binary = Paths.get(reactor.getBuild().getDirectory(), Objects.nonNull(destFile) ? destFile.getValue() : "jacoco.exec");
                            File file = binary.toFile();
                            if (file.exists()) {
                                wrapper.setCoverage(Optional.of(file));
                            }
                        }
                    }

                    for (String element : reactor.getTestClasspathElements()) {
                        File file = new File(element);
                        if (file.exists()) {
                            wrapper.getTestClasspathElements().add(file);
                        }
                    }
                    for (String element : reactor.getCompileClasspathElements()) {
                        File file = new File(element);
                        if (file.exists()) {
                            wrapper.getCompileClasspathElements().add(file);
                        }
                    }
                    for (String root : reactor.getTestCompileSourceRoots()) {
                        File file = new File(root);
                        if (file.exists()) {
                            wrapper.getTestCompileSourceRoots().add(file);
                        }
                    }
                    for (String root : reactor.getCompileSourceRoots()) {
                        File file = new File(root);
                        if (file.exists()) {
                            wrapper.getCompileSourceRoots().add(file);
                        }
                    }
                    args.getProjects().add(wrapper);
                }
            }
        } catch (Exception err) {
            throw new MojoFailureException(err);
        }

        try (Fetch fetch = new Fetch(args)) {
            try (LanguageProcessors registry = new DefaultLanguageProcessors(logFactory, args, fetch)) {
                registry.load().block();

                try (DeltaAnalyzer analyzer = new JGitDeltaAnalyzer(logFactory, registry, args)) {
                    CommitAnalysis analysis = analyzer.analyze(commitId);
                    IndexingSummary index = registry.index(analysis);
                    registry.detectCopyPaste(index, analysis);
                    registry.captureComplexity(index, analysis);
                    registry.captureViolations(index, analysis);
                    registry.captureCoverage(index, analysis);
                }
            }
        } catch (IOException err) {
            throw new MojoFailureException(err);
        }
    }
}
