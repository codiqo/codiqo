package io.codiqo.maven.eventspy;

import lombok.experimental.UtilityClass;

@UtilityClass
public class BuildProgressConfig {
    public static final String PROP_PROGRESS_FILE = "codiqo.buildProgressFile";
    /**
     * The field separator of every line in the build progress file. This core extension cannot depend on codiqo-api,
     * so it keeps its own copy of {@code ProgressStage.FIELD_SEPARATOR}: the analysis stages and the Gradle build's
     * events append to the same file, and its watcher splits each line on this one character, so the two must stay
     * equal. commons-lang3 publishes no tab constant.
     */
    public static final char FIELD_SEPARATOR = '\t';
}
