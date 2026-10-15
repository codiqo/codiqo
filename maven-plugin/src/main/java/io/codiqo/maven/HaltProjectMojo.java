package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.lib.Repository;

import io.codiqo.api.RunArgs;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenMessageReporter;
import io.codiqo.submit.CommitIndexPublisher;
import io.codiqo.submit.ProjectHalts;
import io.codiqo.util.JGit;

/**
 * Halts the project for a run that ended without halting it itself: one killed from outside its JVM (a runner's
 * out-of-memory killer, a step timeout), which leaves no exception for {@code submit-commit-analysis} to report. The
 * GitHub Action calls it when a submit step fails; the server keeps the first halt, so a run that did halt the project
 * keeps its own, more precise reason.
 */
@Mojo(name = "halt-project",
        threadSafe = true,
        aggregator = true,
        requiresProject = true)
public class HaltProjectMojo extends AbstractMojo {
    /** the request model's limit on the detail; the tail of a log is where a killed run's last words are */
    private static final int DETAIL_TAIL = 65536;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "codiqo.apiUrl", defaultValue = RunArgs.DEFAULT_API_URL)
    private String apiUrl;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    @Parameter(property = "codiqo.commitId", required = true)
    private String commitId;

    @Parameter(property = "codiqo.haltReason", required = true)
    private String reason;

    /** the failed run's log, whose tail travels as the halt's detail */
    @Parameter(property = "codiqo.haltLogFile")
    private File logFile;

    @Parameter(property = "codiqo.connectTimeoutSeconds", defaultValue = "30")
    private long connectTimeoutSeconds;

    @Parameter(property = "codiqo.readTimeoutSeconds", defaultValue = "60")
    private long readTimeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException {
        try (Repository repo = JGit.openRepository(project.getBasedir())) {
            boolean halted = ProjectHalts.halt(
                    new MavenMessageReporter(getLog()),
                    CommitIndexPublisher.buildClient(apiUrl, MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog()), connectTimeoutSeconds, readTimeoutSeconds),
                    apiUrl,
                    project.getGroupId() + ":" + project.getArtifactId(),
                    JGit.resolveCommit(repo, commitId),
                    reason,
                    detail());
            if (halted) {
                return;
            }
        } catch (IOException err) {
            throw new MojoExecutionException("cannot halt the project: " + err.getMessage(), err);
        }
        throw new MojoExecutionException("cannot halt the project: the server did not record the halt, see the error above");
    }
    /** only the tail is read: a build log runs to hundreds of megabytes */
    private String detail() throws IOException {
        String toReturn = StringUtils.EMPTY;
        if (Objects.nonNull(logFile) && logFile.isFile()) {
            try (RandomAccessFile log = new RandomAccessFile(logFile, "r")) {
                long start = Math.max(0L, log.length() - DETAIL_TAIL);
                byte[] tail = new byte[Math.toIntExact(log.length() - start)];
                log.seek(start);
                log.readFully(tail);
                toReturn = new String(tail, StandardCharsets.UTF_8);
            }
        }
        return toReturn;
    }
}
