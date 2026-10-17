package io.codiqo.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Joiner;

import io.codiqo.api.RunArgs;

/**
 * One analysis stage — the language server load, CPD, coverage, the submission — recorded in the build progress file
 * the forked build's module events also go to, so a watcher outside the JVM sees where the analysis is, not only the
 * build. Inert when {@code RunArgs.getBuildProgressFile()} is unset.
 *
 * <pre>
 * epochMillis  STAGE_STARTED  name
 * epochMillis  STAGE_FINISHED|STAGE_FAILED  name  durationMillis  detail
 * </pre>
 *
 * A stage that is closed without {@link #succeeded()} is recorded as failed, so a try-with-resources block reports an
 * exception without catching it.
 */
public final class ProgressStage implements AutoCloseable {
    /**
     * The field separator of every line in the build progress file. The analysis stages written here, the Gradle
     * build's task events ({@code BuildProgressService}) and the forked Maven build's module events
     * ({@code BuildProgressEventSpy}, which cannot depend on this module and keeps its own copy in
     * {@code BuildProgressConfig}) all append to the same file, and its watcher splits each line on this one
     * character, so changing it here without changing that copy makes the watcher misread the build's lines.
     * commons-lang3 publishes no tab constant.
     */
    public static final char FIELD_SEPARATOR = '\t';
    private static final Object LOCK = new Object();

    private final File progressFile;
    private final String name;
    private final long startedAt;

    private boolean succeeded;
    private String detail = StringUtils.EMPTY;

    private ProgressStage(File progressFile, String name) {
        this.progressFile = progressFile;
        this.name = name;
        this.startedAt = System.currentTimeMillis();
    }
    public void succeeded() {
        succeeded = true;
    }
    /** a short note shown after the duration, such as the stage's peak heap */
    public void detail(String text) {
        detail = StringUtils.defaultString(text);
    }
    @Override
    public void close() {
        long now = System.currentTimeMillis();
        String event = succeeded ? "STAGE_FINISHED" : "STAGE_FAILED";
        append(progressFile, Joiner.on(FIELD_SEPARATOR).join(now, event, name, now - startedAt, detail));
    }
    public static ProgressStage start(RunArgs args, String name) {
        ProgressStage toReturn = new ProgressStage(args.getBuildProgressFile(), name);
        append(toReturn.progressFile, Joiner.on(FIELD_SEPARATOR).join(toReturn.startedAt, "STAGE_STARTED", name));
        return toReturn;
    }
    /**
     * Tells the run's watcher to stop the whole run: the project was halted because the analysis cannot work (the LLM
     * cannot be reached, coverage cannot be measured), so every further commit of the run would fail the same way. The
     * GitHub Action reads this line from the progress file it already watches, stops its loop whatever its
     * stop-on-first-failure input says, and shows the cause and the trace in the job's log and summary.
     *
     * <pre>
     * epochMillis  ABORT  cause  trace
     * </pre>
     */
    public static void abort(RunArgs args, String cause, String trace) {
        append(args.getBuildProgressFile(), Joiner.on(FIELD_SEPARATOR).join(System.currentTimeMillis(), "ABORT", oneField(cause), oneField(trace)));
    }
    private static void append(File progressFile, String line) {
        if (Objects.nonNull(progressFile)) {
            synchronized (LOCK) {
                try {
                    Files.writeString(
                            progressFile.toPath(),
                            line + System.lineSeparator(),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException err) {
                    /**
                     * The progress file only feeds a watcher outside the JVM (the GitHub Action's log loop), and the
                     * analysis it describes does not depend on it. A full disk or a removed directory losing one line
                     * must not fail a commit's analysis, so the failure is printed where the build log shows it.
                     */
                    System.err.println("[codiqo] failed to write build progress to " + progressFile + ": " + err);
                }
            }
        }
    }
    /** a value kept to one field of one line: the watcher splits on the separator and reads line by line */
    private static String oneField(String value) {
        return StringUtils.normalizeSpace(StringUtils.replaceChars(StringUtils.defaultString(value), FIELD_SEPARATOR, ' '));
    }
}
