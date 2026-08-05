package io.codiqo.gradle;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import io.codiqo.gradle.model.AnalysisRequest;
import io.codiqo.gradle.model.DependencyData;
import io.codiqo.gradle.model.ModuleData;
import lombok.experimental.UtilityClass;

/**
 * Snapshots the Gradle project model into a plain {@link AnalysisRequest}. Runs off the task
 * execution path (taskGraph.whenReady) so resolving subproject classpaths does not trip Gradle's
 * cross-project resolution guard during parallel task execution.
 */
@UtilityClass
public class GradleModelCollector {
    private static final List<String> DEPENDENCY_CONFIGURATIONS = List.of(
            "compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath");

    public AnalysisRequest collect(Project root, CodiqoExtension ext) {
        AnalysisRequest request = new AnalysisRequest();
        request.setRootDir(root.getProjectDir().getAbsolutePath());
        request.setRootCode(root.getGroup() + ":" + root.getName());
        request.setRootName(root.getName());
        request.setGradleVersion(root.getGradle().getGradleVersion());

        request.setCommitId(stringProp(root, "codiqo.commitId", null));
        request.setOutputDirectory(resolveOutputDirectory(root, ext));
        request.setJavaHome(resolveJavaHome(root, ext));

        request.setIgnoreCoverage(boolProp(root, "codiqo.ignoreCoverage", ext.isIgnoreCoverage()));
        request.setIgnoreCpd(boolProp(root, "codiqo.ignoreCpd", ext.isIgnoreCpd()));
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
            // skip container/aggregator projects (Maven parity: reactor.getModules() empty) — an
            // ancestor whose dir contains a subproject's sources must not become that file's owner
            // via RunArgs.owner()'s contains()+findAny() lookup
            if (CollectionUtils.isNotEmpty(project.getSubprojects())) {
                continue;
            }
            JavaPluginExtension javaExt = project.getExtensions().findByType(JavaPluginExtension.class);
            if (javaExt == null) {
                continue;
            }
            SourceSetContainer sourceSets = javaExt.getSourceSets();
            SourceSet main = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
            if (main == null) {
                continue;
            }
            SourceSet test = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);

            ModuleData module = new ModuleData();
            module.setId(project.getPath());
            module.setGroupId(String.valueOf(project.getGroup()));
            module.setArtifactId(project.getName());
            module.setVersion(String.valueOf(project.getVersion()));
            module.setPackaging("jar");
            module.setDescription(project.getDescription());
            module.setBaseDirectory(project.getProjectDir().getAbsolutePath());
            module.setOutputDirectory(main.getJava().getClassesDirectory().get().getAsFile().getAbsolutePath());
            module.setCoveragePath(GradleBuildSupport.jacocoExec(project).getAbsolutePath());

            for (File dir : main.getJava().getSrcDirs()) {
                module.getCompileSourceRoots().add(dir.getAbsolutePath());
            }
            for (File file : main.getCompileClasspath().getFiles()) {
                module.getCompileClasspathElements().add(file.getAbsolutePath());
            }
            if (test != null) {
                for (File dir : test.getJava().getSrcDirs()) {
                    module.getTestCompileSourceRoots().add(dir.getAbsolutePath());
                }
                for (File file : test.getRuntimeClasspath().getFiles()) {
                    module.getTestClasspathElements().add(file.getAbsolutePath());
                }
            }
            collectDependencies(project, module);

            request.getModules().add(module);
        }
        return request;
    }
    private static void collectDependencies(Project project, ModuleData module) {
        // the ClassGraph scan (AnalysisEngine.buildProjects) uses compile + test classpaths, so
        // resolve coordinates for all of them — runtimeClasspath alone drops compileOnly/provided
        // and test-only deps whose classes are scanned, losing their artifact attribution
        Set<String> seenFiles = new HashSet<>();
        for (String configName : DEPENDENCY_CONFIGURATIONS) {
            Configuration config = project.getConfigurations().findByName(configName);
            if (config != null && config.isCanBeResolved()) {
                for (ResolvedArtifactResult artifact : config.getIncoming().getArtifacts()) {
                    if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier component) {
                        String filePath = artifact.getFile().getAbsolutePath();
                        if (seenFiles.add(filePath)) {
                            DependencyData dependency = new DependencyData();
                            dependency.setGroupId(component.getGroup());
                            dependency.setArtifactId(component.getModule());
                            dependency.setVersion(component.getVersion());
                            dependency.setType("jar");
                            dependency.setFilePath(filePath);
                            dependency.setCoordinate(component.getGroup() + ":" + component.getModule() + ":jar:" + component.getVersion());
                            module.getDependencies().add(dependency);
                        }
                    }
                }
            }
        }
    }
    private static String resolveJavaHome(Project root, CodiqoExtension ext) {
        String javaHome = stringProp(root, "codiqo.javaHome", ext.getJavaHome());
        return StringUtils.isNotBlank(javaHome) ? javaHome : System.getProperty("java.home");
    }
    private static String resolveOutputDirectory(Project root, CodiqoExtension ext) {
        String outputDirectory = stringProp(root, "codiqo.outputDirectory", ext.getOutputDirectory());
        return StringUtils.isNotBlank(outputDirectory)
                ? outputDirectory
                : new File(root.getLayout().getBuildDirectory().getAsFile().get(), "codiqo").getAbsolutePath();
    }
    private static String stringProp(Project project, String name, String fallback) {
        Object value = project.findProperty(name);
        return value != null ? value.toString() : fallback;
    }
    private static boolean boolProp(Project project, String name, boolean fallback) {
        Object value = project.findProperty(name);
        return value != null ? Boolean.parseBoolean(value.toString()) : fallback;
    }
    private static long longProp(Project project, String name, long fallback) {
        Object value = project.findProperty(name);
        return value != null ? Long.parseLong(value.toString()) : fallback;
    }
}
