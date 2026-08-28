package io.codiqo.gradle;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;

import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.DependencyData;
import io.codiqo.gradle.model.ModuleData;
import io.codiqo.util.Env;
import lombok.experimental.UtilityClass;

/**
 * Snapshots the Gradle project model into a plain {@link AnalysisRequest}. Runs off the task execution path
 * (taskGraph.whenReady) so resolving subproject classpaths does not trip Gradle's cross-project resolution guard
 * during parallel task execution.
 */
@UtilityClass
public class GradleModelCollector {
    private static final List<String> DEPENDENCY_CONFIGURATIONS = List.of(
            "compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath");
    private static final String JAR_PACKAGING = "jar";
    private static final String CODIQO_OUTPUT_DIR = "codiqo";

    public AnalysisRequest collect(Project root, CodiqoExtension ext) {
        AnalysisRequest request = new AnalysisRequest();
        request.setRootDir(root.getProjectDir().getAbsolutePath());
        request.setRootCode(root.getGroup() + ":" + root.getName());
        request.setRootName(root.getName());
        request.setRootPath(root.getPath());
        request.setRootVersion(String.valueOf(root.getVersion()));
        request.setGradleVersion(root.getGradle().getGradleVersion());

        request.setCommitId(stringProp(root, "codiqo.commitId", null));
        request.setOutputDirectory(resolveOutputDirectory(root, ext));
        request.setJavaHome(resolveJavaHome(root, ext));
        request.setAnalysisMaxHeap(stringProp(root, "codiqo.analysisMaxHeap", ext.getAnalysisMaxHeap()));

        request.setApiUrl(stringProp(root, "codiqo.apiUrl", ext.getApiUrl()));
        resolveApiKey(root, ext).ifPresent(request::setApiKey);
        request.setConnectTimeoutSeconds(longProp(root, "codiqo.connectTimeoutSeconds", ext.getConnectTimeoutSeconds()));
        request.setReadTimeoutSeconds(longProp(root, "codiqo.readTimeoutSeconds", ext.getReadTimeoutSeconds()));

        request.setSkipOnBuildFailure(boolProp(root, "codiqo.skipOnBuildFailure", ext.isSkipOnBuildFailure()));
        request.setScoreOnBuildFailure(boolProp(root, "codiqo.scoreOnBuildFailure", ext.isScoreOnBuildFailure()));

        request.setFirstParentOnly(boolProp(root, "codiqo.firstParentOnly", ext.isFirstParentOnly()));
        request.setExcludeRevertedCommits(boolProp(root, "codiqo.excludeRevertedCommits", ext.isExcludeRevertedCommits()));
        request.setIncludeBranches(stringProp(root, "codiqo.includeBranches", ext.getIncludeBranches()));
        request.setIncludeAuthorEmails(stringProp(root, "codiqo.includeAuthorEmails", ext.getIncludeAuthorEmails()));
        request.setExcludeAuthorEmails(stringProp(root, "codiqo.excludeAuthorEmails", ext.getExcludeAuthorEmails()));

        request.setIgnoreCoverage(boolProp(root, "codiqo.ignoreCoverage", ext.isIgnoreCoverage()));
        request.setIgnoreCpd(boolProp(root, "codiqo.ignoreCpd", ext.isIgnoreCpd()));
        request.setExcludeProjects(stringProp(root, "codiqo.excludeProjects", ext.getExcludeProjects()));
        request.setExcludePaths(stringProp(root, "codiqo.excludePaths", ext.getExcludePaths()));
        request.setIgnoreDiagnostics(boolProp(root, "codiqo.ignoreDiagnostics", ext.isIgnoreDiagnostics()));
        request.setIgnoreComplexity(boolProp(root, "codiqo.ignoreComplexity", ext.isIgnoreComplexity()));
        request.setFailOnJdtlsError(ext.isFailOnJdtlsError());
        request.setFailOnUninstrumentedModule(boolProp(root, "codiqo.failOnUninstrumentedModule", ext.isFailOnUninstrumentedModule()));

        request.setJdtlsVersion(stringProp(root, "codiqo.jdtlsVersion", ext.getJdtlsVersion()));
        request.setJdtlsUseSnapshot(boolProp(root, "codiqo.jdtlsUseSnapshot", ext.isJdtlsUseSnapshot()));
        request.setJdtUseSharedIndex(boolProp(root, "codiqo.jdtUseSharedIndex", ext.isJdtUseSharedIndex()));
        request.setJdtIncludeDecompiledSources(boolProp(root, "codiqo.jdtIncludeDecompiledSources", ext.isJdtIncludeDecompiledSources()));
        request.setImportTimeoutMinutes(longProp(root, "codiqo.importTimeoutMinutes", ext.getImportTimeoutMinutes()));
        request.setLspQueryTimeoutSeconds(longProp(root, "codiqo.lspQueryTimeoutSeconds", ext.getLspQueryTimeoutSeconds()));

        for (Project project : root.getAllprojects()) {
            collectModule(project).ifPresent(request.getModules()::add);
        }
        return request;
    }
    /**
     * empty for anything that is not an analysable leaf. Container projects are skipped (Maven parity: an empty
     * reactor.getModules()) because an ancestor whose directory contains a subproject's sources would otherwise
     * become that file's owner through RunArgs.owner()'s containment lookup.
     */
    private static Optional<ModuleData> collectModule(Project project) {
        if (CollectionUtils.isNotEmpty(project.getSubprojects())) {
            return Optional.empty();
        }
        JavaPluginExtension javaExt = project.getExtensions().findByType(JavaPluginExtension.class);
        if (Objects.isNull(javaExt)) {
            return Optional.empty();
        }
        SourceSetContainer sourceSets = javaExt.getSourceSets();
        SourceSet main = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        if (Objects.isNull(main)) {
            return Optional.empty();
        }

        ModuleData toReturn = new ModuleData();
        toReturn.setId(project.getPath());
        toReturn.setGroupId(String.valueOf(project.getGroup()));
        toReturn.setArtifactId(project.getName());
        toReturn.setVersion(String.valueOf(project.getVersion()));
        toReturn.setPackaging(JAR_PACKAGING);
        toReturn.setDescription(project.getDescription());
        toReturn.setBaseDirectory(project.getProjectDir().getAbsolutePath());
        toReturn.setOutputDirectory(main.getJava().getClassesDirectory().get().getAsFile().getAbsolutePath());
        toReturn.setCoveragePath(GradleBuildSupport.jacocoExec(project).getAbsolutePath());

        for (File dir : main.getJava().getSrcDirs()) {
            toReturn.getCompileSourceRoots().add(dir.getAbsolutePath());
        }
        for (File file : main.getCompileClasspath().getFiles()) {
            toReturn.getCompileClasspathElements().add(file.getAbsolutePath());
        }

        SourceSet test = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
        if (Objects.nonNull(test)) {
            for (File dir : test.getJava().getSrcDirs()) {
                toReturn.getTestCompileSourceRoots().add(dir.getAbsolutePath());
            }
            for (File file : test.getRuntimeClasspath().getFiles()) {
                toReturn.getTestClasspathElements().add(file.getAbsolutePath());
            }
        }

        /**
         * every Test task, not just `test`: an integrationTest task writes its own report directory, and a module
         * whose only executed tests live there must still count as a module whose test fork ran
         */
        for (Test testTask : project.getTasks().withType(Test.class)) {
            toReturn.getTestReportDirectories().add(GradleBuildSupport.junitXmlDir(testTask).getAbsolutePath());
        }

        collectDependencies(project, toReturn);
        return Optional.of(toReturn);
    }
    /**
     * the ClassGraph scan uses compile and test classpaths alike, so coordinates are resolved for all of them —
     * runtimeClasspath alone drops compileOnly/provided and test-only dependencies whose classes are scanned, losing
     * their artifact attribution.
     */
    private static void collectDependencies(Project project, ModuleData module) {
        Set<String> seenFiles = new HashSet<>();
        for (String configName : DEPENDENCY_CONFIGURATIONS) {
            Configuration config = project.getConfigurations().findByName(configName);
            if (Objects.nonNull(config) && config.isCanBeResolved()) {
                for (ResolvedArtifactResult artifact : config.getIncoming().getArtifacts()) {
                    if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier component) {
                        String filePath = artifact.getFile().getAbsolutePath();
                        if (seenFiles.add(filePath)) {
                            module.getDependencies().add(dependency(component, filePath));
                        }
                    }
                }
            }
        }
    }
    private static DependencyData dependency(ModuleComponentIdentifier component, String filePath) {
        DependencyData toReturn = new DependencyData();
        toReturn.setGroupId(component.getGroup());
        toReturn.setArtifactId(component.getModule());
        toReturn.setVersion(component.getVersion());
        toReturn.setType(JAR_PACKAGING);
        toReturn.setFilePath(filePath);
        toReturn.setCoordinate(StringUtils.joinWith(":", component.getGroup(), component.getModule(), JAR_PACKAGING, component.getVersion()));
        return toReturn;
    }
    /**
     * resolved in the daemon so the worker never has to know about the {@code env:VAR} form, and so a key naming an
     * unset variable fails here with a clear message rather than as a 401 after a full analysis. A configured key
     * that resolves to nothing is a misconfiguration, never an invitation to continue unauthenticated.
     */
    private static Optional<String> resolveApiKey(Project root, CodiqoExtension ext) {
        String configured = stringProp(root, "codiqo.apiKey", ext.getApiKey());
        if (StringUtils.isBlank(configured)) {
            return Optional.empty();
        }
        return Optional.of(Env.resolve(configured).orElseThrow(() -> new GradleException(
                "codiqo.apiKey is set to '" + configured + "' but resolves to nothing — export that variable, or pass the key directly")));
    }
    private static String resolveJavaHome(Project root, CodiqoExtension ext) {
        String javaHome = stringProp(root, "codiqo.javaHome", ext.getJavaHome());
        if (StringUtils.isNotBlank(javaHome)) {
            return javaHome;
        }
        return System.getProperty("java.home");
    }
    private static String resolveOutputDirectory(Project root, CodiqoExtension ext) {
        String outputDirectory = stringProp(root, "codiqo.outputDirectory", ext.getOutputDirectory());
        if (StringUtils.isNotBlank(outputDirectory)) {
            return outputDirectory;
        }
        return new File(root.getLayout().getBuildDirectory().getAsFile().get(), CODIQO_OUTPUT_DIR).getAbsolutePath();
    }
    private static String stringProp(Project project, String name, String fallback) {
        return Optional.ofNullable(project.findProperty(name)).map(Object::toString).orElse(fallback);
    }
    private static boolean boolProp(Project project, String name, boolean fallback) {
        return Optional.ofNullable(project.findProperty(name)).map(Object::toString).map(Boolean::parseBoolean).orElse(fallback);
    }
    private static long longProp(Project project, String name, long fallback) {
        return Optional.ofNullable(project.findProperty(name)).map(Object::toString).map(Long::parseLong).orElse(fallback);
    }
}
