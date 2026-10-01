package io.codiqo.maven.eventspy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.project.MavenProject;

import lombok.extern.slf4j.Slf4j;

/**
 * Core extension (loaded via maven.ext.class.path inside the codiqo-forked build) that appends one tab-separated line
 * per reactor lifecycle event to the file named by {@link BuildProgressConfig#PROP_PROGRESS_FILE}, so a watcher outside
 * the build can tell which modules finished and which are still running. Maven's console names a module when it starts
 * and only summarises completion once the whole reactor is done. The extension stays inert when the property is absent.
 *
 * <pre>
 * epochMillis  SESSION  moduleCount
 * epochMillis  STARTED  groupId:artifactId
 * epochMillis  SUCCESS|FAILED|SKIPPED  groupId:artifactId  durationMillis
 * </pre>
 */
@Slf4j
@Singleton
@Named("codiqo-build-progress-eventspy")
public class BuildProgressEventSpy extends AbstractEventSpy {
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    @Override
    public void onEvent(Object event) {
        String path = System.getProperty(BuildProgressConfig.PROP_PROGRESS_FILE);
        if (event instanceof ExecutionEvent execution && StringUtils.isNotBlank(path)) {
            progressLine(execution, System.currentTimeMillis()).ifPresent(line -> append(Paths.get(path.trim()), line));
        }
    }
    Optional<String> progressLine(ExecutionEvent execution, long now) {
        MavenProject project = execution.getProject();
        return switch (execution.getType()) {
            case SessionStarted -> Optional.ofNullable(execution.getSession())
                    .map(session -> now + "\tSESSION\t" + session.getProjects().size());
            case ProjectStarted -> Optional.ofNullable(project).map(started -> {
                startedAt.put(moduleId(started), now);
                return now + "\tSTARTED\t" + moduleId(started);
            });
            case ProjectSucceeded -> finished(project, "SUCCESS", now);
            case ProjectFailed -> finished(project, "FAILED", now);
            case ProjectSkipped -> finished(project, "SKIPPED", now);
            default -> Optional.empty();
        };
    }
    private Optional<String> finished(MavenProject project, String status, long now) {
        return Optional.ofNullable(project).map(done -> {
            String id = moduleId(done);
            // a skipped module never started, so it has no duration
            long duration = Optional.ofNullable(startedAt.remove(id)).map(start -> now - start).orElse(0L);
            return now + "\t" + status + "\t" + id + "\t" + duration;
        });
    }
    private synchronized void append(Path progressFile, String line) {
        try {
            Files.writeString(progressFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException err) {
            log.warn("[codiqo] failed to write build progress to {}", progressFile, err);
        }
    }
    private static String moduleId(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }
}
