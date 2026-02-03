package io.codiqo.maven.populator;

import io.codiqo.maven.MavenProjectWrapper;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.jgit.transport.URIish;

import io.codiqo.api.MavenProjectSpec;
import io.codiqo.client.model.DependencyModel;
import io.codiqo.client.model.MavenDependencyModel;
import io.codiqo.client.model.MavenModuleModel;
import io.codiqo.client.model.ModuleModel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProjectModelPopulator implements SubmissionPopulator {
    private final Log log;

    @Override
    public void accept(SubmissionContext ctx) {
        if (StringUtils.isNotEmpty(ctx.getArgs().getDefaultBranch())) {
            ctx.getProjectModel().setDefaultBranch(ctx.getArgs().getDefaultBranch());
        }
        populateRepositoryUrls(ctx);
        populateDependencies(ctx);
        populateModules(ctx);
        ctx.getSubmissionModel().setProject(ctx.getProjectModel());
    }
    private void populateRepositoryUrls(SubmissionContext ctx) {
        ctx.getArgs().getRemoteUrls().forEach(url -> {
            try {
                URIish urIish = new URIish(url);
                if (StringUtils.isNotEmpty(urIish.getScheme())) {
                    URI repositoryUri = URI.create(urIish.toString());
                    ctx.getProjectModel().getRepositoryUrls().add(repositoryUri);
                } else {
                    String path = Strings.CS.removeStart(urIish.getPath(), "/");
                    URI repositoryUri = URI.create(String.format("https://%s/%s", urIish.getHost(), path));
                    ctx.getProjectModel().getRepositoryUrls().add(repositoryUri);
                }
            } catch (URISyntaxException err) {
                log.error("failed to parse repository URL: " + url, err);
            }
        });
    }
    private void populateDependencies(SubmissionContext ctx) {
        ctx.getArgs().getProjects().stream()
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
                            ctx.getDependencyRegistryModel().getArtifacts().put(artifact.getId(), dependencyModel);
                        }
                    } catch (URISyntaxException err) {
                        log.error("failed to parse dependency URL in project: " + mavenSpec.getName(), err);
                    }
                });
    }
    private static void populateModules(SubmissionContext ctx) {
        Path workTree = ctx.getWorkTree();
        ctx.getArgs().getProjects().forEach(spec -> {
            if (spec instanceof MavenProjectWrapper) {
                MavenProjectWrapper mavenSpec = (MavenProjectWrapper) spec;
                MavenProjectSpec mavenProjectSpec = (MavenProjectSpec) spec;

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

                for (File file : mavenProjectSpec.getCompileSourceRoots()) {
                    moduleModel.getSourceRoots().add(workTree.resolve(projectDir).relativize(file.toPath()).normalize().toString());
                }
                for (File file : mavenProjectSpec.getTestCompileSourceRoots()) {
                    moduleModel.getTestSourceRoots().add(workTree.resolve(projectDir).relativize(file.toPath()).normalize().toString());
                }
                for (org.apache.maven.artifact.Artifact artifact : mavenSpec.getArtifacts().keySet()) {
                    moduleModel.getDependencies().add(artifact.getId());
                }

                ctx.getProjectModel().getModules().add(moduleModel);
            }
        });
    }
}
