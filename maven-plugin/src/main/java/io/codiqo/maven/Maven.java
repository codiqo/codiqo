package io.codiqo.maven;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.DefaultSessionData;


import io.codiqo.client.model.AnalysisExcludeCategory;
import kr.motd.maven.os.Detector;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Maven {

    private static final Pattern HELP_URL_PATTERN = Pattern.compile("cwiki\\.apache\\.org/confluence/display/MAVEN/([A-Za-z]+Exception)");

    /**
     * Common exceptions that indicate dependency resolution failure, which is a common cause of fork failure.
     * See: https://cwiki.apache.org/confluence/display/MAVEN/Errors+and+Solutions
     */
    private static final Set<String> DEPENDENCY_RESOLUTION_EXCEPTIONS = Set.of(
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
    /**
     * host-side model building under time-machine pinning must not share resolution/model caches with the host
     * session — the host may already have resolved the same snapshot coordinates to their latest deploys
     */
    public static void isolateRepositorySession(ProjectBuildingRequest request) {
        DefaultRepositorySystemSession derived = new DefaultRepositorySystemSession(request.getRepositorySession());
        derived.setCache(new DefaultRepositoryCache());
        derived.setData(new DefaultSessionData());

        request.setRepositorySession(derived);
    }
    /**
     * host-side model building interpolates ${maven.multiModuleProjectDirectory} from the request's system properties,
     * which carry the HOST session root — clone-POM values rooted at the reactor directory would otherwise interpolate
     * into the host tree instead of the analyzed clone the fork actually built
     */
    public static void pinMultiModuleProjectDirectory(ProjectBuildingRequest request, File analyzedRoot) {
        Properties systemProperties = new Properties();
        if (Objects.nonNull(request.getSystemProperties())) {
            systemProperties.putAll(request.getSystemProperties());
        }
        systemProperties.setProperty("maven.multiModuleProjectDirectory", analyzedRoot.getAbsolutePath());

        request.setSystemProperties(systemProperties);
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
                line > 0 ? ":" + line : StringUtils.EMPTY,
                col > 0 ? ":" + col : StringUtils.EMPTY,
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
