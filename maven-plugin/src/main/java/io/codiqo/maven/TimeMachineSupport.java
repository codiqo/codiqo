package io.codiqo.maven;

import java.io.IOException;
import java.time.Instant;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import io.codiqo.api.RunArgs;
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
}
