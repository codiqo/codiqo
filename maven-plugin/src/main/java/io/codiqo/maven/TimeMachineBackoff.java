package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import io.codiqo.maven.timemachine.SnapshotMetadataStore;
import lombok.experimental.UtilityClass;

/**
 * Back-off ladder decisions for the time-machine retry loop. Developers' local repositories lag behind the
 * snapshot repository (Maven's default updatePolicy is daily), so a snapshot deployed shortly before the
 * commit may never have reached the developer's build — stepping the resolution target back simulates that
 * staleness. A rung is worth a build only if it would actually change at least one pick.
 */
@UtilityClass
public class TimeMachineBackoff {
    /**
     * first ladder rung strictly greater than {@code previousOffset} whose window {@code (commitTs - offset, commitTs]}
     * contains a picked deploy — stepping back excludes that deploy, so resolution changes. forward picks
     * (deployed after the commit) can never be improved by stepping back and are ignored.
     */
    public static Optional<Duration> nextOffset(List<Duration> ladder, Duration previousOffset, Instant commitTimestamp, Collection<Instant> pickedDeploys) {
        for (Duration offset : ladder) {
            if (BooleanUtils.and(new boolean[]{offset.compareTo(previousOffset) > 0, changesAnyPick(offset, commitTimestamp, pickedDeploys)})) {
                return Optional.of(offset);
            }
        }
        return Optional.empty();
    }
    /**
     * reads the picked deploy timestamps from a failed attempt's sidecar dir. parses the properties files
     * directly — codiqo-maven-time-machine is provided-scope, so only its inlined String constants are usable
     * from the plugin realm at runtime (same pattern as ProjectModelPopulator). a null/absent dir is a
     * legitimate state: the fork may have died before resolving anything.
     */
    public static List<Instant> readPickedDeploys(File metaDir) {
        List<Instant> toReturn = new ArrayList<>();
        if (Objects.nonNull(metaDir) && metaDir.isDirectory()) {
            File[] files = metaDir.listFiles((dir, fileName) -> "properties".equals(FilenameUtils.getExtension(fileName)));
            if (ArrayUtils.isNotEmpty(files)) {
                for (File file : files) {
                    Properties props = new Properties();
                    try (InputStream is = Files.newInputStream(file.toPath())) {
                        props.load(is);
                    } catch (IOException err) {
                        ExceptionUtils.wrapAndThrow(err);
                    }

                    String deployedAt = props.getProperty(SnapshotMetadataStore.KEY_DEPLOYED_AT);
                    if (StringUtils.isNotBlank(deployedAt)) {
                        toReturn.add(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(deployedAt.trim())));
                    }
                }
            }
        }
        return toReturn;
    }
    private static boolean changesAnyPick(Duration offset, Instant commitTimestamp, Collection<Instant> pickedDeploys) {
        Instant steppedTarget = commitTimestamp.minus(offset);
        return pickedDeploys.stream().anyMatch(deployedAt ->
                BooleanUtils.and(new boolean[]{deployedAt.isAfter(steppedTarget), deployedAt.compareTo(commitTimestamp) <= 0}));
    }
}
