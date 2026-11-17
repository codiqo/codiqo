package io.codiqo.gradle;

import static java.util.function.Predicate.not;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
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
    private static final String VERSIONS_RESOURCE = "codiqo.versions";
    private static final String LOMBOK_MODULE = "org.projectlombok:lombok";

    /** @param unresolved receives every module classpath that could not be resolved, by its configuration's path */
    public AnalysisRequest collect(Project root, CodiqoExtension ext, Map<String, Throwable> unresolved) {
        AnalysisRequest request = new AnalysisRequest();
        request.setRootDir(root.getProjectDir().getAbsolutePath());
        request.setRootCode(root.getGroup() + ":" + root.getName());
        request.setRootName(root.getName());
        request.setRootPath(root.getPath());
        request.setRootVersion(String.valueOf(root.getVersion()));
        request.setGradleVersion(root.getGradle().getGradleVersion());

        request.setCommitId(stringProp(root, "codiqo.commitId", null));
        request.setAnalysisMaxHeap(stringProp(root, "codiqo.analysisMaxHeap", ext.getAnalysisMaxHeap()));

        request.setApiUrl(stringProp(root, "codiqo.apiUrl", ext.getApiUrl()));
        resolveApiKey(root, ext).ifPresent(request::setApiKey);
        request.setAuthUrl(stringProp(root, "codiqo.authUrl", ext.getAuthUrl()));
        request.setResourceUrl(stringProp(root, "codiqo.resourceUrl", ext.getResourceUrl()));

        request.setSourceHidden(GradleSettings.flag(root, "codiqo.sourceHidden", ext.isSourceHidden()));
        /** only a hidden run reads the key: a plain run must not fail on a key it never uses */
        if (request.isSourceHidden()) {
            resolveSourceHashKey(root, ext).ifPresent(request::setSourceHashKey);
        }

        /**
         * The fork needs a JDK and the analysis a directory before the worker starts, and only the daemon knows the
         * defaults: the JDK running the build and the build's own output directory.
         */
        Map<String, String> settings = GradleSettings.of(root, ext);
        settings.put(GradleSettings.JAVA_HOME, StringUtils.defaultIfBlank(settings.get(GradleSettings.JAVA_HOME), System.getProperty("java.home")));
        settings.put(GradleSettings.OUTPUT_DIRECTORY, StringUtils.defaultIfBlank(settings.get(GradleSettings.OUTPUT_DIRECTORY),
                new File(root.getLayout().getBuildDirectory().getAsFile().get(), CODIQO_OUTPUT_DIR).getAbsolutePath()));
        RunArgs args = GradleSettings.runArgs(settings);
        request.setSettings(settings);

        if (args.isJdtLombokAgent()) {
            resolveLombokAgent(root, request);
        }
        for (Project project : root.getAllprojects()) {
            Map<String, Throwable> moduleUnresolved = Maps.newLinkedHashMap();
            collectModule(project, moduleUnresolved).ifPresent(request.getModules()::add);
            /** a module the run excludes is left out of the analysis, so a classpath of its that does not resolve fails nothing */
            if (BooleanUtils.negate(args.isExcludedProject(String.valueOf(project.getGroup()), project.getName()))) {
                unresolved.putAll(moduleUnresolved);
            }
        }
        return request;
    }
    /**
     * Empty for anything that is not an analysable leaf. Container projects are skipped (Maven parity: an empty
     * reactor.getModules()) because an ancestor whose directory contains a subproject's sources would otherwise
     * become that file's owner through RunArgs.owner()'s containment lookup.
     */
    private static Optional<ModuleData> collectModule(Project project, Map<String, Throwable> unresolved) {
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
        for (File file : files(project, main.getCompileClasspath(), main.getCompileClasspathConfigurationName(), unresolved)) {
            toReturn.getCompileClasspathElements().add(file.getAbsolutePath());
        }

        SourceSet test = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
        if (Objects.nonNull(test)) {
            for (File dir : test.getJava().getSrcDirs()) {
                toReturn.getTestCompileSourceRoots().add(dir.getAbsolutePath());
            }
            for (File file : files(project, test.getRuntimeClasspath(), test.getRuntimeClasspathConfigurationName(), unresolved)) {
                toReturn.getTestClasspathElements().add(file.getAbsolutePath());
            }
        }

        /**
         * Every Test task, not just {@code test}: an integrationTest task writes its own report directory, and a module
         * whose only executed tests live there must still count as a module whose test fork ran.
         */
        for (Test testTask : project.getTasks().withType(Test.class)) {
            toReturn.getTestReportDirectories().add(GradleBuildSupport.junitXmlDir(testTask).getAbsolutePath());
        }

        collectDependencies(project, toReturn);
        return Optional.of(toReturn);
    }
    /**
     * A classpath's files, or none when it does not resolve, with the failure recorded. The model is collected while the
     * task graph is built, before any task runs, so a failure must not be thrown here: it would fail the whole build before
     * codiqoSubmitAnalysis runs, and the commit would be neither excluded nor halted, so every scheduled run would try it
     * again. The analysis task reports it as the build's failure (BuildFailures), and a commit with a failed build is not
     * analysed in full, the only reader of these files. Any of Gradle's failures, not only a ResolveException: a
     * dependency that fails its verification (verification-metadata.xml) throws a GradleException of its own.
     */
    private static Set<File> files(Project project, FileCollection classpath, String configurationName, Map<String, Throwable> unresolved) {
        try {
            return classpath.getFiles();
        } catch (GradleException err) {
            unresolved.put(project.absoluteProjectPath(configurationName), err);
            return Set.of();
        }
    }
    /**
     * The ClassGraph scan uses compile and test classpaths alike, so coordinates are resolved for all of them —
     * runtimeClasspath alone drops compileOnly/provided and test-only dependencies whose classes are scanned, losing
     * their artifact attribution. An artifact belongs to the first configuration that resolves it, which is also its
     * scope; it is direct there when it is one of that configuration's first-level dependencies. A constraint edge
     * (a {@code constraints} block, a platform, the Kotlin plugin's stdlib alignment) declares nothing, so it is
     * skipped. A dependency whose Gradle module metadata redirects its variant ({@code available-at}, as
     * kotlinx-coroutines-core does to kotlinx-coroutines-core-jvm) gets its artifact from the component the variant
     * points to, so that component counts as direct too; otherwise every such declared dependency reads as transitive.
     */
    private static void collectDependencies(Project project, ModuleData module) {
        Set<String> seenFiles = Sets.newHashSet();
        for (String configName : DEPENDENCY_CONFIGURATIONS) {
            Configuration config = project.getConfigurations().findByName(configName);
            if (Objects.nonNull(config) && config.isCanBeResolved()) {
                Set<ComponentIdentifier> direct = Sets.newHashSet();
                config.getIncoming().getResolutionResult().getRoot().getDependencies().stream()
                        .filter(not(DependencyResult::isConstraint))
                        .filter(ResolvedDependencyResult.class::isInstance)
                        .map(ResolvedDependencyResult.class::cast)
                        .forEach(resolved -> {
                            direct.add(resolved.getSelected().getId());
                            resolved.getResolvedVariant().getExternalVariant().map(ResolvedVariantResult::getOwner).ifPresent(direct::add);
                        });
                /**
                 * Leniently, for the same reason files() does not throw: the attribution skips what does not resolve, and a
                 * main or test classpath that does not resolve is reported there. What is skipped is logged, never dropped
                 * silently; Gradle throws even from a lenient view for a dependency that fails its verification.
                 */
                ArtifactCollection artifacts = config.getIncoming().artifactView(view -> view.setLenient(true)).getArtifacts();
                try {
                    for (ResolvedArtifactResult artifact : artifacts) {
                        if (artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier component) {
                            String filePath = artifact.getFile().getAbsolutePath();
                            if (seenFiles.add(filePath)) {
                                DependencyData dependency = dependency(component, filePath);
                                dependency.setScope(configName);
                                dependency.setTransitive(BooleanUtils.negate(direct.contains(component)));
                                module.getDependencies().add(dependency);
                            }
                        }
                    }
                    if (CollectionUtils.isNotEmpty(artifacts.getFailures())) {
                        project.getLogger().warn("codiqo: {} dependencies of {} did not resolve and are not attributed: {}",
                                artifacts.getFailures().size(), project.absoluteProjectPath(configName), ExceptionUtils.getRootCauseMessage(artifacts.getFailures().iterator().next()));
                    }
                } catch (GradleException err) {
                    project.getLogger().warn("codiqo: the dependencies of {} are not attributed: {}", project.absoluteProjectPath(configName), ExceptionUtils.getRootCauseMessage(err));
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
        toReturn.setCoordinate(Joiner.on(':').join(component.getGroup(), component.getModule(), JAR_PACKAGING, component.getVersion()));
        return toReturn;
    }
    /**
     * Resolved in the daemon so the worker never has to know about the {@code env:VAR} form, and so a key naming an
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
    /** resolved like the API key, so a key naming an unset variable fails here and not after a paid review */
    private static Optional<String> resolveSourceHashKey(Project root, CodiqoExtension ext) {
        String configured = stringProp(root, "codiqo.sourceHashKey", ext.getSourceHashKey());
        if (StringUtils.isBlank(configured)) {
            return Optional.empty();
        }
        return Optional.of(Env.resolve(configured).orElseThrow(() -> new GradleException(
                "codiqo.sourceHashKey is set to '" + configured + "' but resolves to nothing — export that variable, or pass the key directly")));
    }
    private static String stringProp(Project project, String name, String fallback) {
        return Optional.ofNullable(project.findProperty(name)).map(Object::toString).orElse(fallback);
    }
    /**
     * The lombok jar the Maven goal attaches to the language server as well, at the version pinned in this plugin's
     * codiqo.versions. It is resolved as Gradle's jacoco plugin resolves the JaCoCo agent codiqo pins, from the
     * repositories of a project that applies the Java plugin: a root that only holds subprojects often declares no
     * repository of its own, and resolving there failed every Gradle analysis of such a build. The first Java project
     * that resolves it wins.
     *
     * <p>When none does, the reason travels to the worker instead of failing here, before the build: an excluded or
     * build-failed commit never starts the language server and must still be reported, while a full analysis fails
     * before its review starts, naming the switch. Starting the language server without lombok instead would stop call
     * hierarchy at every member lombok generates in a project that uses it, and nothing would say so.
     */
    private static void resolveLombokAgent(Project root, AnalysisRequest request) {
        String coordinate = Joiner.on(':').join(LOMBOK_MODULE, pinnedVersion("lombok.version"));
        String failure = "no project of the build applies the Java plugin";
        for (Project project : root.getAllprojects()) {
            if (Objects.nonNull(project.getExtensions().findByType(JavaPluginExtension.class))) {
                Configuration config = project.getConfigurations().detachedConfiguration(project.getDependencies().create(coordinate));
                config.setTransitive(false);
                try {
                    request.setLombokAgentJar(config.getSingleFile().getAbsolutePath());
                    return;
                } catch (ResolveException err) {
                    failure = project.getPath() + ": " + ExceptionUtils.getRootCauseMessage(err);
                }
            }
        }
        request.setLombokAgentError(String.format("codiqo attaches %s to the language server and no Java project of the build could resolve it (%s): "
                + "declare a repository that serves it, or set codiqo.jdtLombokAgent=false for a project that does not use lombok", coordinate, failure));
    }
    private static String pinnedVersion(String key) {
        for (;;) {
            try (InputStream stream = GradleModelCollector.class.getClassLoader().getResourceAsStream(VERSIONS_RESOURCE)) {
                Properties versions = new Properties();
                versions.load(Objects.requireNonNull(stream, VERSIONS_RESOURCE + " missing from the plugin classpath"));
                return Objects.requireNonNull(versions.getProperty(key), key + " missing from " + VERSIONS_RESOURCE);
            } catch (IOException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
