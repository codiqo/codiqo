package io.codiqo.maven;

import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.google.common.collect.ImmutableSet;

import io.codiqo.client.model.AnalysisExcludeCategory;
import kr.motd.maven.os.Detector;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Maven {
    private static final String JACOCO_GROUP_ID = "org.jacoco";
    private static final String JACOCO_MAVEN_PLUGIN_ARTIFACT_ID = "jacoco-maven-plugin";
    private static final String JACOCO_MAVEN_PLUGIN_DEST_FILE = "destFile";
    private static final String JACOCO_MAVEN_PLUGIN_DEST_FILE_DEFAULT_VALUE = "jacoco.exec";

    private static final Pattern HELP_URL_PATTERN = Pattern.compile("cwiki\\.apache\\.org/confluence/display/MAVEN/([A-Za-z]+Exception)");

    /**
     * Common exceptions that indicate dependency resolution failure, which is a common cause of fork failure.
     * See: https://cwiki.apache.org/confluence/display/MAVEN/Errors+and+Solutions
     */
    private static final Set<String> DEPENDENCY_RESOLUTION_EXCEPTIONS = ImmutableSet.of(
            UnresolvableModelException.class.getSimpleName(),
            ArtifactResolutionException.class.getSimpleName(),
            DependencyResolutionException.class.getSimpleName(),
            DependencyResolutionRequiredException.class.getSimpleName(),
            ArtifactNotFoundException.class.getSimpleName());

    private static final EnumSet<ModelProblem.Severity> SEVERE_MODEL_PROBLEM_SEVERITIES = EnumSet.of(ModelProblem.Severity.FATAL, ModelProblem.Severity.ERROR);

    public static ProjectBuildingRequest buildingRequest(MavenSession session) {
        ProjectBuildingRequest toReturn = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        toReturn.setResolveDependencies(true);
        toReturn.setProcessPlugins(true);
        return toReturn;
    }
    public static File autoDetectJacocoDestFile(MavenProject reactor) {
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
    public static Optional<String> severeProblem(Stream<ModelProblem> problems) {
        return problems
                .filter(p -> SEVERE_MODEL_PROBLEM_SEVERITIES.contains(p.getSeverity()))
                .sorted(Comparator.comparing((ModelProblem p) -> p.getSeverity().ordinal()).thenComparingInt(ModelProblem::getLineNumber))
                .findFirst()
                .map(Maven::formatProblem);
    }
    public static String formatProblem(ModelProblem p) {
        String src = StringUtils.defaultIfBlank(p.getSource(), "unknown");
        String modelId = StringUtils.defaultIfBlank(p.getModelId(), "?");
        int line = p.getLineNumber();
        int col = p.getColumnNumber();
        return String.format("broken POM at %s%s%s [modelId=%s, severity=%s]: %s",
                src,
                line > 0 ? ":" + line : "",
                col > 0 ? ":" + col : "",
                modelId,
                p.getSeverity(),
                p.getMessage());
    }
    public static Properties detectOsProperties() {
        return new SilentDetector().capture();
    }
    public static AnalysisExcludeCategory classifyForkFailure(List<String> capturedHelpLines) {
        for (String line : capturedHelpLines) {
            Matcher m = HELP_URL_PATTERN.matcher(line);
            if (m.find() && DEPENDENCY_RESOLUTION_EXCEPTIONS.contains(m.group(1))) {
                return AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE;
            }
        }
        return AnalysisExcludeCategory.BUILD_FAILURE;
    }
    public static List<String> unresolvedDependencyCoords(ProjectBuildingException pbe) {
        if (CollectionUtils.isEmpty(pbe.getResults())) {
            return Collections.emptyList();
        }
        return pbe.getResults().stream()
                .map(ProjectBuildingResult::getDependencyResolutionResult)
                .filter(Objects::nonNull)
                .flatMap(r -> r.getUnresolvedDependencies().stream())
                .map(d -> d.getArtifact().toString())
                .distinct()
                .collect(Collectors.toList());
    }

    private static final class SilentDetector extends Detector {
        Properties capture() {
            Properties toReturn = new Properties();
            detect(toReturn, Collections.emptyList());
            return toReturn;
        }
        @Override
        protected void log(String message) {}
        @Override
        protected void logProperty(String name, String value) {}
    }
}
