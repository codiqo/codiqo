package io.codiqo.maven.eventspy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

import lombok.extern.slf4j.Slf4j;

/**
 * Core extension (loaded via maven.ext.class.path inside the codiqo-forked build) that records structured build
 * failures — the failing module, goal, mojo detail and exception — to the file named by
 * {@link BuildFailureConfig#PROP_REPORT_FILE}. The plugin reads that file back after the fork and attaches it as the
 * exclusion detail, instead of scraping the console log. The extension stays inert when the property is absent.
 */
@Slf4j
@Singleton
@Named("codiqo-build-failure-eventspy")
public class BuildFailureEventSpy extends AbstractEventSpy {
    private static final EnumSet<ExecutionEvent.Type> FAILURE_TYPES = EnumSet.of(
            ExecutionEvent.Type.MojoFailed,
            ExecutionEvent.Type.ForkFailed,
            ExecutionEvent.Type.ProjectFailed);

    @Override
    public void onEvent(Object event) {
        if (event instanceof ExecutionEvent execution
                && FAILURE_TYPES.contains(execution.getType())
                && Objects.nonNull(execution.getException())) {
            String path = System.getProperty(BuildFailureConfig.PROP_REPORT_FILE);
            if (StringUtils.isNotBlank(path)) {
                writeFailure(Paths.get(path.trim()), execution);
            }
        }
    }
    private synchronized void writeFailure(Path reportFile, ExecutionEvent execution) {
        Throwable error = execution.getException();

        StringBuilder entry = new StringBuilder();
        entry.append("=== ").append(execution.getType()).append(" ===").append(System.lineSeparator());

        MavenProject project = execution.getProject();
        if (Objects.nonNull(project)) {
            entry.append("module: ").append(project.getId()).append(System.lineSeparator());
        }

        MojoExecution mojo = execution.getMojoExecution();
        if (Objects.nonNull(mojo)) {
            entry.append("goal: ")
                    .append(mojo.getGroupId()).append(':')
                    .append(mojo.getArtifactId()).append(':')
                    .append(mojo.getVersion()).append(':')
                    .append(mojo.getGoal())
                    .append(" (").append(mojo.getExecutionId()).append(')')
                    .append(System.lineSeparator());
        }

        /**
         * ahead of the stack trace, which is long enough to hit the reader's capture limit and silent about what
         * failed: a mojo reports its per-item findings only in the long message, while the exception's own message
         * stays a bare "Compilation failure"
         */
        mojoDetail(error).ifPresent(detail -> entry
                .append("detail:").append(System.lineSeparator())
                .append(detail).append(System.lineSeparator()));

        entry.append(ExceptionUtils.getStackTrace(error)).append(System.lineSeparator());

        try {
            Files.writeString(reportFile, entry.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException err) {
            log.warn("[codiqo] failed to write build-failure report to {}", reportFile, err);
        }
    }
    private static Optional<String> mojoDetail(Throwable error) {
        return ExceptionUtils.getThrowableList(error).stream()
                .filter(AbstractMojoExecutionException.class::isInstance)
                .map(AbstractMojoExecutionException.class::cast)
                .map(AbstractMojoExecutionException::getLongMessage)
                .filter(StringUtils::isNotBlank)
                .map(String::strip)
                .distinct()
                .reduce((first, second) -> first + System.lineSeparator() + second);
    }
}
