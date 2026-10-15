package io.codiqo.maven.coverage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import lombok.extern.slf4j.Slf4j;

/**
 * Fails the forked build at the first module whose tests ran without the injected JaCoCo agent writing its exec file.
 * The codiqo plugin finds the same thing at coverage capture, but only after the whole reactor has built and tested,
 * while the commit's paid local review runs next to the build; stopping here cancels that review within minutes of the
 * first broken module instead of at the end of the build. The module is named in the file
 * {@link CoverageInjectorConfig#PROP_UNINSTRUMENTED_REPORT} points to, so the plugin fails the run as codiqo's failure
 * rather than scoring the commit as one its author broke. Inert unless both that property and the agent jar are set.
 */
@Slf4j
@Singleton
@Named("codiqo-uninstrumented-module-guard")
public class UninstrumentedModuleGuard implements MojoExecutionListener {
    private static final String MAVEN_PLUGINS_GROUP_ID = "org.apache.maven.plugins";
    private static final Set<String> TEST_PLUGINS = Set.of("maven-surefire-plugin", "maven-failsafe-plugin");
    private static final Set<String> TEST_GOALS = Set.of("test", "integration-test");
    /** the evaluated parameter both surefire and failsafe write their JUnit XML reports to */
    private static final String REPORTS_DIRECTORY = "reportsDirectory";
    private static final String JUNIT_REPORT_PREFIX = "TEST-";
    private static final String XML_EXTENSION = ".xml";

    @Override
    public void beforeMojoExecution(MojoExecutionEvent event) {
    }
    @Override
    public void afterMojoExecutionSuccess(MojoExecutionEvent event) throws MojoExecutionException {
        String report = System.getProperty(CoverageInjectorConfig.PROP_UNINSTRUMENTED_REPORT);
        String agentJar = System.getProperty(CoverageInjectorConfig.PROP_AGENT_JAR);
        MavenProject project = event.getProject();
        if (BooleanUtils.and(new boolean[] {
                StringUtils.isNotBlank(report),
                StringUtils.isNotBlank(agentJar),
                isTestRun(event.getExecution()),
                BooleanUtils.negate(JacocoAgentInjector.isAggregator(project)) })) {
            File destFile = JacocoAgentInjector.destFile(project);
            if (BooleanUtils.and(new boolean[] { BooleanUtils.negate(destFile.isFile()), reportsDirectory(event).filter(UninstrumentedModuleGuard::containsJunitReports).isPresent() })) {
                String module = project.getGroupId() + ":" + project.getArtifactId();
                record(report.trim(), module + " ran its tests but the agent wrote no coverage to " + destFile);
                throw new MojoExecutionException(String.format(
                        "[codiqo] the tests of %s ran but the JaCoCo agent wrote no coverage to %s: the agent did not attach to the test JVM",
                        module,
                        destFile));
            }
        }
    }
    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
    }
    private static boolean isTestRun(MojoExecution execution) {
        return BooleanUtils.and(new boolean[] {
                MAVEN_PLUGINS_GROUP_ID.equals(execution.getGroupId()),
                TEST_PLUGINS.contains(execution.getArtifactId()),
                TEST_GOALS.contains(execution.getGoal()) });
    }
    /**
     * Read from the mojo itself, where Maven has already evaluated it, so a project that moved its reports is checked
     * where its reports are. A plugin version without the field is not checked here; coverage capture still is.
     */
    private static Optional<File> reportsDirectory(MojoExecutionEvent event) {
        try {
            return Optional.ofNullable(FieldUtils.readField(event.getMojo(), REPORTS_DIRECTORY, true))
                    .filter(File.class::isInstance)
                    .map(File.class::cast);
        } catch (IllegalAccessException | IllegalArgumentException err) {
            log.debug("[codiqo] {} has no readable {}, coverage is checked at capture only", event.getExecution(), REPORTS_DIRECTORY, err);
            return Optional.empty();
        }
    }
    private static boolean containsJunitReports(File dir) {
        return ArrayUtils.isNotEmpty(dir.list((parent, name) -> BooleanUtils.and(new boolean[] { name.startsWith(JUNIT_REPORT_PREFIX), name.endsWith(XML_EXTENSION) })));
    }
    /** one line per problem, which the codiqo plugin reads back as the reason the run failed */
    public static void record(String report, String problem) {
        try {
            Files.writeString(Paths.get(report), problem + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException err) {
            log.warn("[codiqo] failed to record \"{}\" in {}", problem, report, err);
        }
    }
}
