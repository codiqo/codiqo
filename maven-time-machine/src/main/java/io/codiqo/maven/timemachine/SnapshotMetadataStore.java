package io.codiqo.maven.timemachine;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import lombok.Builder;
import lombok.Value;
import lombok.experimental.UtilityClass;

/**
 * Back-channel for time-machine snapshot resolution metadata. The resolver runs in a forked Maven JVM,
 * so the {@link SnapshotResolution} for each pinned snapshot is written to a sidecar properties file
 * (one per coordinate) that the parent JVM reads back when building the submission model. Property keys
 * mirror the {@code snapshot.*} keys persisted on the backend.
 *
 * <p>The parent reader ({@code ProjectModelPopulator}) lives in the plugin realm and cannot depend on
 * this module at runtime, so each file embeds its {@link #KEY_COORDINATE} ({@code groupId:artifactId:baseVersion})
 * — the reader keys off file content, not the filename.
 */
@UtilityClass
public class SnapshotMetadataStore {
    public static final String KEY_COORDINATE = "snapshot.coordinate";
    public static final String KEY_RESOLVED_VERSION = "snapshot.resolvedVersion";
    public static final String KEY_DEPLOYED_AT = "snapshot.deployedAt";
    public static final String KEY_BUILD_NUMBER = "snapshot.buildNumber";
    public static final String KEY_REPOSITORY_ID = "snapshot.repositoryId";
    public static final String KEY_REPOSITORY_URL = "snapshot.repositoryUrl";
    public static final String KEY_TARGET_TIMESTAMP = "snapshot.targetTimestamp";
    public static final String KEY_STALE_SECONDS = "snapshot.staleSeconds";

    private static final String COORDINATE_SEPARATOR = "__";
    private static final String FILE_SUFFIX = ".properties";
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    public static void write(File metaDir, String groupId, String artifactId, String baseVersion, SnapshotResolution resolution) {
        for (;;) {
            try {
                Path dir = metaDir.toPath();
                Files.createDirectories(dir);

                Properties props = new Properties();
                props.setProperty(KEY_COORDINATE, groupId + ":" + artifactId + ":" + baseVersion);
                props.setProperty(KEY_RESOLVED_VERSION, resolution.getResolvedVersion());
                props.setProperty(KEY_DEPLOYED_AT, DateTimeFormatter.ISO_INSTANT.format(resolution.getDeployedAt()));
                if (Objects.nonNull(resolution.getBuildNumber())) {
                    props.setProperty(KEY_BUILD_NUMBER, resolution.getBuildNumber().toString());
                }
                if (StringUtils.isNotBlank(resolution.getRepositoryId())) {
                    props.setProperty(KEY_REPOSITORY_ID, resolution.getRepositoryId());
                }
                if (StringUtils.isNotBlank(resolution.getRepositoryUrl())) {
                    props.setProperty(KEY_REPOSITORY_URL, resolution.getRepositoryUrl());
                }
                props.setProperty(KEY_TARGET_TIMESTAMP, DateTimeFormatter.ISO_INSTANT.format(resolution.getTargetTimestamp()));
                props.setProperty(KEY_STALE_SECONDS, Long.toString(resolution.getStaleSeconds()));

                Path tmp = Files.createTempFile(dir, "snapshot-", ".tmp");
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    props.store(os, "codiqo time-machine snapshot resolution");
                }
                Files.move(tmp, dir.resolve(fileName(groupId, artifactId, baseVersion)), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    public static Optional<SnapshotResolution> read(File metaDir, String groupId, String artifactId, String baseVersion) {
        for (;;) {
            try {
                Path file = metaDir.toPath().resolve(fileName(groupId, artifactId, baseVersion));
                if (Files.notExists(file)) {
                    return Optional.empty();
                }

                Properties props = new Properties();
                try (InputStream is = Files.newInputStream(file)) {
                    props.load(is);
                }

                String resolvedVersion = props.getProperty(KEY_RESOLVED_VERSION);
                String deployedAt = props.getProperty(KEY_DEPLOYED_AT);
                if (StringUtils.isAnyBlank(resolvedVersion, deployedAt)) {
                    return Optional.empty();
                }
                return Optional.of(SnapshotResolution.builder()
                        .resolvedVersion(resolvedVersion)
                        .deployedAt(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(deployedAt)))
                        .buildNumber(parseInteger(props.getProperty(KEY_BUILD_NUMBER)))
                        .repositoryId(props.getProperty(KEY_REPOSITORY_ID))
                        .repositoryUrl(props.getProperty(KEY_REPOSITORY_URL))
                        .targetTimestamp(parseInstant(props.getProperty(KEY_TARGET_TIMESTAMP)))
                        .staleSeconds(parseLong(props.getProperty(KEY_STALE_SECONDS)))
                        .build());
            } catch (Exception err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    private static String fileName(String groupId, String artifactId, String baseVersion) {
        String raw = groupId + COORDINATE_SEPARATOR + artifactId + COORDINATE_SEPARATOR + baseVersion;
        return UNSAFE_FILENAME_CHARS.matcher(raw).replaceAll("_") + FILE_SUFFIX;
    }
    private static Integer parseInteger(String value) {
        return StringUtils.isBlank(value) ? null : Integer.valueOf(value.trim());
    }
    private static Instant parseInstant(String value) {
        return StringUtils.isBlank(value) ? null : Instant.from(DateTimeFormatter.ISO_INSTANT.parse(value.trim()));
    }
    private static long parseLong(String value) {
        return StringUtils.isBlank(value) ? 0L : Long.parseLong(value.trim());
    }

    @Value
    @Builder
    public static class SnapshotResolution {
        String resolvedVersion;
        Instant deployedAt;
        Integer buildNumber;
        String repositoryId;
        String repositoryUrl;
        Instant targetTimestamp;
        long staleSeconds;
    }
}
