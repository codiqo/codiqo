package io.codiqo.maven.populator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.plugin.logging.Log;

import com.google.common.collect.Maps;

import io.codiqo.api.MavenProjectSpec;
import io.codiqo.client.model.DependencyModel;
import io.codiqo.client.model.MavenDependencyModel;
import io.codiqo.client.model.MavenModuleModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.SnapshotMetadataModel;
import io.codiqo.maven.MavenProjectWrapper;
import io.codiqo.maven.timemachine.SnapshotMetadataStore;
import io.codiqo.util.RepositoryUrls;
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
                URI repositoryUri = RepositoryUrls.toUri(url);
                ctx.getProjectModel().getRepositoryUrls().add(repositoryUri);
            } catch (URISyntaxException err) {
                log.error("failed to parse repository URL: " + url, err);
            }
        });
    }
    private static void populateDependencies(SubmissionContext ctx) {
        Map<String, SnapshotMetadataModel> snapshotMetadataByCoordinate = loadSnapshotMetadata(ctx.getArgs().getTimeMachineMetaDir());
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
                            dependencyModel.setMavenInfo(mavenDependencyModel);
                            dependencyModel.setName(artifact.getId());
                            dependencyModel.setVersion(artifact.getVersion());
                            dependencyModel.setOptional(artifact.isOptional());
                            dependencyModel.setSnapshot(artifact.isSnapshot());
                            dependencyModel.setRelease(artifact.isRelease());

                            SnapshotMetadataModel snapshotMetadata = snapshotMetadataByCoordinate.get(coordinate(artifact));
                            if (Objects.nonNull(snapshotMetadata)) {
                                dependencyModel.setSnapshotMetadata(snapshotMetadata);
                            }

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
                        ExceptionUtils.wrapAndThrow(err);
                    }
                });
    }
    private static Map<String, SnapshotMetadataModel> loadSnapshotMetadata(File metaDir) {
        Map<String, SnapshotMetadataModel> toReturn = Maps.newHashMap();
        if (Objects.nonNull(metaDir) && metaDir.isDirectory()) {
            File[] files = metaDir.listFiles((dir, fileName) -> "properties".equals(FilenameUtils.getExtension(fileName)));
            if (ArrayUtils.isNotEmpty(files)) {
                for (File file : files) {
                    Properties props = new Properties();
                    try (InputStream is = Files.newInputStream(file.toPath())) {
                        props.load(is);
                    } catch (IOException err) {
                        ExceptionUtils.wrapAndThrow(err);
                        continue;
                    }

                    String coordinate = props.getProperty(SnapshotMetadataStore.KEY_COORDINATE);
                    if (StringUtils.isNotBlank(coordinate)) {
                        toReturn.put(coordinate, toSnapshotMetadataModel(props));
                    }
                }
            }
        }
        return toReturn;
    }
    private static void populateModules(SubmissionContext ctx) {
        Path workTree = ctx.getWorkTree();
        ctx.getArgs().getProjects().forEach(spec -> {
            if (spec instanceof MavenProjectWrapper mavenSpec) {
                MavenProjectSpec mavenProjectSpec = (MavenProjectSpec) spec;

                Path projectDir = workTree.relativize(mavenSpec.getBaseDirectory().toPath()).normalize();

                MavenModuleModel mavenModuleModel = new MavenModuleModel();
                mavenModuleModel.setGroupId(mavenSpec.getGroupId());
                mavenModuleModel.setArtifactId(mavenSpec.getArtifactId());
                mavenModuleModel.setVersion(mavenSpec.getVersion());
                mavenModuleModel.setPackaging(mavenSpec.getPackaging());
                mavenSpec.parent().ifPresent(parentId -> mavenModuleModel.setParent(parentId));

                ModuleModel moduleModel = new ModuleModel();
                moduleModel.setMavenInfo(mavenModuleModel);
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
    private static SnapshotMetadataModel toSnapshotMetadataModel(Properties props) {
        SnapshotMetadataModel toReturn = new SnapshotMetadataModel();
        toReturn.setResolvedVersion(props.getProperty(SnapshotMetadataStore.KEY_RESOLVED_VERSION));
        toReturn.setRepositoryId(props.getProperty(SnapshotMetadataStore.KEY_REPOSITORY_ID));

        String deployedAt = props.getProperty(SnapshotMetadataStore.KEY_DEPLOYED_AT);
        if (StringUtils.isNotBlank(deployedAt)) {
            toReturn.setDeployedAt(OffsetDateTime.parse(deployedAt));
        }

        String targetTimestamp = props.getProperty(SnapshotMetadataStore.KEY_TARGET_TIMESTAMP);
        if (StringUtils.isNotBlank(targetTimestamp)) {
            toReturn.setTargetTimestamp(OffsetDateTime.parse(targetTimestamp));
        }

        String buildNumber = props.getProperty(SnapshotMetadataStore.KEY_BUILD_NUMBER);
        if (StringUtils.isNotBlank(buildNumber)) {
            toReturn.setBuildNumber(Integer.valueOf(buildNumber));
        }

        String staleSeconds = props.getProperty(SnapshotMetadataStore.KEY_STALE_SECONDS);
        if (StringUtils.isNotBlank(staleSeconds)) {
            toReturn.setStaleSeconds(Long.valueOf(staleSeconds));
        }

        String repositoryUrl = props.getProperty(SnapshotMetadataStore.KEY_REPOSITORY_URL);
        if (StringUtils.isNotBlank(repositoryUrl)) {
            toReturn.setRepositoryUrl(URI.create(repositoryUrl));
        }

        return toReturn;
    }
    private static String coordinate(org.apache.maven.artifact.Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion();
    }
}
