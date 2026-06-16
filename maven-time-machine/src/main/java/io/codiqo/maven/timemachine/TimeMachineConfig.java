package io.codiqo.maven.timemachine;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TimeMachineConfig {
    public static final String MAVEN_EXT_CLASS_PATH = "maven.ext.class.path";
    public static final String PROP_COMMIT_TIMESTAMP = "codiqo.commit.timestamp";
    public static final String PROP_HTTP_TIMEOUT_SECONDS = "codiqo.timemachine.httpTimeoutSeconds";
    public static final String PROP_MAX_STALENESS = "codiqo.timemachine.maxStaleness";
    public static final String PROP_META_DIR = "codiqo.timemachine.metaDir";

    private static final int DEFAULT_HTTP_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_MAX_STALENESS = "P90D";

    public static Optional<Instant> targetTimestamp() {
        String raw = System.getProperty(PROP_COMMIT_TIMESTAMP);
        if (isBlank(raw)) {
            return Optional.empty();
        }
        return Optional.of(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(raw.trim())));
    }
    public static Optional<File> metaDir() {
        String raw = System.getProperty(PROP_META_DIR);
        if (isBlank(raw)) {
            return Optional.empty();
        }
        return Optional.of(new File(raw.trim()));
    }
    public static int httpTimeoutSeconds() {
        String raw = System.getProperty(PROP_HTTP_TIMEOUT_SECONDS);
        if (isBlank(raw)) {
            return DEFAULT_HTTP_TIMEOUT_SECONDS;
        }
        return Integer.parseInt(raw.trim());
    }
    public static Duration maxStaleness() {
        String raw = System.getProperty(PROP_MAX_STALENESS);
        if (isBlank(raw)) {
            raw = DEFAULT_MAX_STALENESS;
        }
        return Duration.parse(raw);
    }
    private static boolean isBlank(String s) {
        return Objects.isNull(s) || s.isBlank();
    }
}
