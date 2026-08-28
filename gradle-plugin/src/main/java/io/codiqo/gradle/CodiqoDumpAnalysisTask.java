package io.codiqo.gradle;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.codiqo.gradle.model.AnalysisRequest;

/**
 * Forks the shared analysis engine into its own JVM against the model collected off the execution path (see
 * {@link GradleModelCollector}, wired at taskGraph.whenReady), so the task resolves nothing during execution and works
 * under Gradle's parallel execution.
 *
 * <p>The engine deliberately does NOT run inside the daemon: its heap would then be the analysed project's own
 * {@code org.gradle.jvmargs} — micrometer pins {@code -Xmx1g}, which OOMs the ClassGraph scan. Maven is immune because
 * its fork takes heap from {@code MAVEN_OPTS}, which codiqo controls; this fork is the equivalent seam.
 */
public abstract class CodiqoDumpAnalysisTask extends DefaultTask {
    private static final String SLF4J_SIMPLE_PREFIX = "org.slf4j.simpleLogger.";

    private AnalysisRequest request;
    private boolean submit;
    private List<Task> scheduledTasks = List.of();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    public void setRequest(AnalysisRequest request) {
        this.request = request;
    }
    public void setSubmit(boolean submit) {
        this.submit = submit;
    }
    public void setScheduledTasks(List<Task> scheduledTasks) {
        this.scheduledTasks = scheduledTasks;
    }
    @TaskAction
    public void dump() throws JsonProcessingException, URISyntaxException {
        if (Objects.isNull(request)) {
            throw new IllegalStateException(
                    "Codiqo model was not collected — apply the plugin to the root project (via the codiqo init script) so it can snapshot the build.");
        }

        if (BooleanUtils.and(new boolean[]{submit, StringUtils.isBlank(request.getApiKey())})) {
            throw new GradleException(
                    "codiqoSubmitAnalysis needs an API key: set codiqo.apiKey in the codiqo { } block, or pass -Pcodiqo.apiKey=env:CODIQO_API_KEY");
        }

        /**
         * the two tasks share one collected model, so the submit flag is overlaid on the serialized form rather than
         * set on the request — mutating a shared object per task would make the payload depend on execution order
         */
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.valueToTree(request);
        payload.put("submit", submit);

        /**
         * read here, not in the worker: task state lives in the daemon that ran the build, and by this point every
         * task the analysis is ordered after has finished
         */
        BuildFailures.detail(scheduledTasks).ifPresent(detail -> {
            getLogger().warn("codiqo: the build failed — the commit will be reported as build-failed rather than scored");
            payload.put("buildFailureDetail", detail);
        });
        byte[] bytes = mapper.writeValueAsBytes(payload);
        List<File> classpath = workerClasspath();
        getLogger().lifecycle("running codiqo analysis in a forked JVM (heap {}, {} classpath entries, submit={})",
                request.getAnalysisMaxHeap(), classpath.size(), submit);

        getExecOperations().javaexec(spec -> {
            spec.setExecutable(javaExecutable(request.getJavaHome()));
            spec.setClasspath(getObjectFactory().fileCollection().from(classpath));
            spec.getMainClass().set(CodiqoAnalysisWorkerMain.class.getName());
            spec.setMaxHeapSize(request.getAnalysisMaxHeap());
            spec.setStandardInput(new ByteArrayInputStream(bytes));

            /**
             * the worker's only console is the inherited stdout javaexec forwards into the build output, and the
             * binding it uses (slf4j-simple) is configured entirely by these properties — no logback.xml ships in the
             * plugin jar. INFO keeps the engine's progress visible without Gradle's --info raising every library too.
             */
            spec.systemProperty(SLF4J_SIMPLE_PREFIX + "defaultLogLevel", "info");
            spec.systemProperty(SLF4J_SIMPLE_PREFIX + "showThreadName", "false");
            spec.systemProperty(SLF4J_SIMPLE_PREFIX + "showLogName", "false");
            spec.systemProperty(SLF4J_SIMPLE_PREFIX + "showShortLogName", "true");
        });
    }
    /**
     * the jars Gradle already resolved for the plugin itself, reused verbatim as the worker's classpath. The plugin
     * classloader is a URLClassLoader whose URLs point at Gradle's uninstrumented transform variant, so they are safe
     * on a bare {@code java -cp}; resolving the same coordinates again would need repository access and could pick a
     * different version than the daemon is running.
     */
    private static List<File> workerClasspath() throws URISyntaxException {
        ClassLoader loader = CodiqoDumpAnalysisTask.class.getClassLoader();
        if (loader instanceof URLClassLoader urlLoader) {
            List<File> toReturn = new ArrayList<>();
            for (URL url : urlLoader.getURLs()) {
                toReturn.add(Paths.get(url.toURI()).toFile());
            }
            return toReturn;
        }
        throw new GradleException(String.format(
                "cannot fork the codiqo analysis: the plugin classloader is %s, not a URLClassLoader, so its classpath cannot be read. "
                        + "Apply the plugin through the codiqo init script rather than the plugins {} block.",
                loader.getClass().getName()));
    }
    private static String javaExecutable(String javaHome) {
        String java = SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java";
        return Paths.get(javaHome).normalize().resolve("bin").resolve(java).toFile().getAbsolutePath();
    }
}
