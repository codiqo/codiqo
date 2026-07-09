package io.codiqo.gradle;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;

import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.logging.Log;
import io.codiqo.client.model.DependencyModel;
import io.codiqo.client.model.MavenDependencyModel;
import io.codiqo.client.model.MavenModuleModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.gradle.model.DependencyData;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.SubmissionPopulator;
import io.codiqo.util.RepositoryUrls;
import lombok.RequiredArgsConstructor;

/**
 * Gradle counterpart of the Maven ProjectModelPopulator. Populates repository URLs, the resolved
 * dependency registry (Maven-ecosystem coordinates from Gradle's runtimeClasspath), and modules.
 */
@RequiredArgsConstructor
public class GradleProjectModelPopulator implements SubmissionPopulator {
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
                ctx.getProjectModel().getRepositoryUrls().add(RepositoryUrls.toUri(url));
            } catch (URISyntaxException err) {
                log.warn("failed to parse repository URL: %s", url);
            }
        });
    }
    private static void populateDependencies(SubmissionContext ctx) {
        ctx.getArgs().getProjects().forEach(spec -> {
            if (spec instanceof GradleProjectWrapper wrapper) {
                for (DependencyData dependency : wrapper.getDependencies()) {
                    MavenDependencyModel mavenDependencyModel = new MavenDependencyModel();
                    mavenDependencyModel.setGroupId(dependency.getGroupId());
                    mavenDependencyModel.setArtifactId(dependency.getArtifactId());
                    mavenDependencyModel.setClassifier(dependency.getClassifier());
                    mavenDependencyModel.setType(dependency.getType());

                    DependencyModel dependencyModel = new DependencyModel();
                    dependencyModel.setMavenInfo(mavenDependencyModel);
                    dependencyModel.setName(dependency.getCoordinate());
                    dependencyModel.setVersion(dependency.getVersion());

                    ctx.getDependencyRegistryModel().getArtifacts().put(dependency.getCoordinate(), dependencyModel);
                }
            }
        });
    }
    private static void populateModules(SubmissionContext ctx) {
        Path workTree = ctx.getWorkTree();
        ctx.getArgs().getProjects().forEach(spec -> {
            if (spec instanceof JvmProjectSpec jvm) {
                Path projectDir = workTree.relativize(jvm.getBaseDirectory().toPath()).normalize();

                MavenModuleModel mavenModuleModel = new MavenModuleModel();
                mavenModuleModel.setGroupId(jvm.getGroupId());
                mavenModuleModel.setArtifactId(jvm.getArtifactId());
                mavenModuleModel.setVersion(jvm.getVersion());
                mavenModuleModel.setPackaging(jvm.getPackaging());

                ModuleModel moduleModel = new ModuleModel();
                moduleModel.setMavenInfo(mavenModuleModel);
                moduleModel.setId(jvm.getId());
                moduleModel.setName(jvm.getName());
                moduleModel.setBaseDirectory(projectDir.toString());

                for (File file : jvm.getCompileSourceRoots()) {
                    moduleModel.getSourceRoots().add(workTree.resolve(projectDir).relativize(file.toPath()).normalize().toString());
                }
                for (File file : jvm.getTestCompileSourceRoots()) {
                    moduleModel.getTestSourceRoots().add(workTree.resolve(projectDir).relativize(file.toPath()).normalize().toString());
                }
                if (spec instanceof GradleProjectWrapper wrapper) {
                    for (DependencyData dependency : wrapper.getDependencies()) {
                        moduleModel.getDependencies().add(dependency.getCoordinate());
                    }
                }

                ctx.getProjectModel().getModules().add(moduleModel);
            }
        });
    }
}
