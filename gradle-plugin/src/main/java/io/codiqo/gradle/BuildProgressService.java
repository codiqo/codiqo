package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskOperationResult;

/**
 * The Gradle counterpart of the Maven fork's BuildProgressEventSpy: appends one tab-separated line per project
 * started and finished to the file named by {@code codiqo.buildProgressFile}, in the same format, so the same watcher
 * reads both. Gradle reports task completion only, so a project counts as started when its first task finishes (with
 * that task's start time) and as finished when the last of its scheduled tasks does, or when any of them fails.
 *
 * <pre>
 * epochMillis  SESSION  projectCount
 * epochMillis  STARTED  :project:path
 * epochMillis  SUCCESS|FAILED  :project:path  durationMillis
 * </pre>
 */
public abstract class BuildProgressService implements BuildService<BuildProgressService.Params>, OperationCompletionListener {
    private static final Logger LOG = Logging.getLogger(BuildProgressService.class);

    private final Map<String, Integer> remainingTasks = new HashMap<>();
    private final Map<String, String> moduleIds = new HashMap<>();
    private final Map<String, Long> startedAt = new HashMap<>();

    public interface Params extends BuildServiceParameters {
        RegularFileProperty getProgressFile();
    }

    /**
     * @param tasksByProject the number of scheduled tasks per project path
     * @param ids the module id written for each project path
     */
    public synchronized void expect(Map<String, Integer> tasksByProject, Map<String, String> ids) {
        remainingTasks.putAll(tasksByProject);
        moduleIds.putAll(ids);
        append(System.currentTimeMillis() + "\tSESSION\t" + tasksByProject.size());
    }
    @Override
    public synchronized void onFinish(FinishEvent event) {
        if (event instanceof TaskFinishEvent task) {
            String projectPath = projectPath(task.getDescriptor().getTaskPath());
            Integer remaining = remainingTasks.get(projectPath);
            // tasks outside the counted plan: the analysis tasks themselves, and included builds
            if (Objects.nonNull(remaining)) {
                TaskOperationResult result = task.getResult();
                String id = moduleIds.get(projectPath);
                if (Objects.isNull(startedAt.putIfAbsent(projectPath, result.getStartTime()))) {
                    append(result.getStartTime() + "\tSTARTED\t" + id);
                }
                /**
                 * a failed task finishes its project: the tasks that depend on it never run, so waiting for the count to
                 * reach zero would leave the project reported as running for the rest of the build
                 */
                boolean taskFailed = result instanceof TaskFailureResult;
                if (taskFailed || remaining <= 1) {
                    remainingTasks.remove(projectPath);
                    String status = taskFailed ? "FAILED" : "SUCCESS";
                    append(result.getEndTime() + "\t" + status + "\t" + id + "\t" + (result.getEndTime() - startedAt.get(projectPath)));
                } else {
                    remainingTasks.put(projectPath, remaining - 1);
                }
            }
        }
    }
    /** ":a:b:compileJava" belongs to ":a:b", and ":compileJava" to the root project ":" */
    static String projectPath(String taskPath) {
        int colon = taskPath.lastIndexOf(':');
        return colon <= 0 ? ":" : taskPath.substring(0, colon);
    }
    private void append(String line) {
        File progressFile = getParameters().getProgressFile().get().getAsFile();
        try {
            Files.writeString(progressFile.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException err) {
            LOG.warn("[codiqo] failed to write build progress to {}", progressFile, err);
        }
    }
}
