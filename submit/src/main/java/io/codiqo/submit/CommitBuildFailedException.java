package io.codiqo.submit;

/**
 * The commit's own build failed, and {@code codiqo.skipOnBuildFailure} is off, so the run fails instead of excluding or
 * scoring the commit. It is the author's failure, not codiqo's, and must not halt the project: a halt would hold every
 * later commit, including the one that fixes the build, until an administrator resumes the project.
 */
public class CommitBuildFailedException extends Exception {
    public CommitBuildFailedException(String message) {
        super(message);
    }
    public CommitBuildFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
