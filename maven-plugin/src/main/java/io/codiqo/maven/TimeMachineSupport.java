package io.codiqo.maven;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableSupplier;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.ProjectBuildingException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import io.codiqo.api.RunArgs;
import io.codiqo.maven.timemachine.TimeMachineConfig;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TimeMachineSupport {
    public static Instant resolveCommitTimestamp(RunArgs args) throws IOException {
        ObjectId objectId = args.getGit().resolve(args.getCommitId());
        try (RevWalk walk = new RevWalk(args.getGit())) {
            RevCommit commit = walk.parseCommit(objectId);
            return commit.getCommitterIdent().getWhenAsInstant();
        }
    }
    /**
     * pins host-side ProjectBuilder/Aether snapshot resolution to the commit instant (minus the back-off offset of
     * the attempt) for the duration of the action. only the inlined TimeMachineConfig String constants are referenced
     * — codiqo-maven-time-machine is provided-scope, so its classes are not loadable from the plugin realm; the
     * properties are inert unless the extension is also on the host Maven's maven.ext.class.path. a null offset means
     * the (successful) build resolved latest snapshots, so host-side resolution stays unpinned for consistency.
     *
     * <p>a pinned attempt that fails with a ProjectBuildingException — e.g. the registry exposes no deploy history
     * (CI without gcloud/ADC) and the resolver refuses a far-forward pick — is retried unpinned, preserving
     * pre-pinning behavior; only an unpinned failure reaches the caller's classification.
     */
    public static <T> T withHostPinning(RunArgs args, Log log, FailableSupplier<T, Exception> action) throws Exception {
        Duration offset = args.getTimeMachineTargetOffset();
        if (BooleanUtils.and(new boolean[]{Objects.nonNull(offset), StringUtils.isNotBlank(args.getCommitId())})) {
            System.setProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP, DateTimeFormatter.ISO_INSTANT.format(resolveCommitTimestamp(args)));
            if (offset.compareTo(Duration.ZERO) > 0) {
                System.setProperty(TimeMachineConfig.PROP_TARGET_OFFSET, offset.toString());
            }
            try {
                return action.get();
            } catch (ProjectBuildingException pbe) {
                log.warn(String.format("host model building failed under time-machine pinning (%s), retrying unpinned",
                        StringUtils.abbreviate(pbe.getMessage(), 200)));
            } finally {
                System.clearProperty(TimeMachineConfig.PROP_COMMIT_TIMESTAMP);
                System.clearProperty(TimeMachineConfig.PROP_TARGET_OFFSET);
            }
        }
        return action.get();
    }
}
