package io.codiqo.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

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
    public static ProgressStage start(RunArgs args, String name) {
        ProgressStage toReturn = new ProgressStage(args.getBuildProgressFile(), name);
        toReturn.append(toReturn.startedAt + "\tSTAGE_STARTED\t" + name);
        return toReturn;
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
        append(now + "\t" + (succeeded ? "STAGE_FINISHED" : "STAGE_FAILED") + "\t" + name + "\t" + (now - startedAt) + "\t" + detail);
    }
    private void append(String line) {
        if (Objects.nonNull(progressFile)) {
            synchronized (LOCK) {
                try {
                    Files.writeString(progressFile.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException err) {
                    // progress is for a watcher; losing a line must not fail the analysis it describes
                    System.err.println("[codiqo] failed to write build progress to " + progressFile + ": " + err);
                }
            }
        }
    }
}
