package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.review.FindingTriage;
import io.codiqo.llm.review.FindingVerdict;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.StaticFinding;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenLogFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reviews one commit for bugs with OpenCode, on this machine: the agents read the repository the goal runs in and look
 * at the commit with read-only git, so nothing is built and nothing is submitted. The findings are logged and written
 * to a JSON report together with the tokens every agent session used.
 *
 * <p>Needs the {@code opencode} executable on the PATH. The models are called through the backend's LLM proxy with the
 * member's Codiqo credential (a configured key, or the browser login), counted against the organization; a local
 * Ollama daemon works too, with {@code -Dcodiqo.review.baseUrl=http://127.0.0.1:11434/v1}.
 */
@Mojo(name = "review", requiresProject = false, aggregator = true, threadSafe = true)
public class ReviewCommitMojo extends AbstractMojo {
    @Parameter(property = "codiqo.review.commit", defaultValue = "HEAD")
    private String commit;

    @Parameter(property = "codiqo.review.directory", defaultValue = "${basedir}")
    private File directory;

    @Parameter(property = "codiqo.review.output", defaultValue = "${basedir}/target/codiqo-review.json")
    private File output;

    /** each review setting left unset keeps its {@link RunArgs} default */
    @Parameter(property = "codiqo.review.executable")
    private String executable;

    @Parameter(property = "codiqo.review.baseUrl")
    private String baseUrl;

    @Parameter(property = "codiqo.review.coordinatorModel")
    private String coordinatorModel;

    @Parameter(property = "codiqo.review.reviewerModel")
    private String reviewerModel;

    @Parameter(property = "codiqo.review.timeoutMinutes")
    private Integer timeoutMinutes;

    /** retries of a call through the Codiqo proxy that failed on the way (network error, timeout, 429 or 5xx) */
    @Parameter(property = "codiqo.review.relayRetries")
    private Integer relayRetries;

    /** how long a call through the Codiqo proxy may take to start answering */
    @Parameter(property = "codiqo.review.relayTimeoutSeconds")
    private Integer relayTimeoutSeconds;

    /** also have the agents assess the commit (block categories, summary, tags, task types, quality dimensions) */
    @Parameter(property = "codiqo.review.assess")
    private Boolean assess;

    /**
     * A JSON array of static-analysis findings on the commit's added lines ({@code StaticFinding}): when given, a fork
     * of the review's session judges each one after the review, and the verdicts go into the report as {@code triage}.
     */
    @Parameter(property = "codiqo.review.findings")
    private File findings;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    @Override
    public void execute() throws MojoExecutionException {
        RunArgs args = new RunArgs();
        Optional.ofNullable(assess).ifPresent(args::setReviewAssess);
        Optional.ofNullable(executable).ifPresent(args::setReviewExecutable);
        Optional.ofNullable(baseUrl).ifPresent(args::setReviewBaseUrl);
        Optional.ofNullable(coordinatorModel).ifPresent(args::setReviewCoordinatorModel);
        Optional.ofNullable(reviewerModel).ifPresent(args::setReviewReviewerModel);
        Optional.ofNullable(timeoutMinutes).ifPresent(minutes -> args.setReviewTimeout(Duration.ofMinutes(minutes)));
        Optional.ofNullable(relayRetries).ifPresent(args::setReviewRelayRetries);
        Optional.ofNullable(relayTimeoutSeconds).ifPresent(seconds -> args.setReviewRelayRequestTimeout(Duration.ofSeconds(seconds)));

        ReviewEndpoint endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, null);
        if (endpoint.isProxiedBy(resourceUrl)) {
            endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog()));
        }

        try {
            Optional<LocalReviewer.FindingsSource> source = Optional.empty();
            if (Objects.nonNull(findings)) {
                List<StaticFinding> given = JsonMapper.builder().build().readerForListOf(StaticFinding.class).readValue(findings);
                source = Optional.of(() -> given);
            }
            LocalReviewer reviewer = new LocalReviewer(args, endpoint, LocalReviewGuidance.read(directory, getLog()),
                    new MavenLogFactory(getLog()).getLogger(LocalReviewer.class));
            LocalReviewer.ReviewAndTriage result = reviewer.reviewThenTriage(directory.toPath(), commit, source);
            logFindings(result.getReview());
            result.getTriage().ifPresent(this::logTriage);
            writeReport(result.getReview(), result.getTriage());
        } catch (Exception err) {
            throw new MojoExecutionException("local review of " + commit + " failed: " + err.getMessage(), err);
        }
    }
    private void logFindings(LocalReview review) {
        LlmScoringResponse.Bugs bugs = review.getBugs();
        Map<String, List<LlmScoringResponse.Bug>> bySeverity = Maps.newLinkedHashMap();
        bySeverity.put("blocking", bugs.getBlocking());
        bySeverity.put("major", bugs.getMajor());
        bySeverity.put("minor", bugs.getMinor());

        int total = 0;
        for (Map.Entry<String, List<LlmScoringResponse.Bug>> severity : bySeverity.entrySet()) {
            for (LlmScoringResponse.Bug bug : severity.getValue()) {
                getLog().warn(String.format("[%s] %s:%s %s", severity.getKey(), bug.getFile(), bug.getLine(), bug.getTitle()));
                total++;
            }
        }
        getLog().info(String.format("review of %s: %d finding(s) in %ds; tokens: %,d input (+%,d cached), %,d output across %d session(s); "
                + "%d of %d reviewers answered",
                review.getCommit(),
                total,
                review.getTook().toSeconds(),
                review.totalInputTokens(),
                review.totalCachedInputTokens(),
                review.totalOutputTokens(),
                review.getSessions().size(),
                review.getReviewers() - review.getUnansweredReviewers().size(),
                review.getReviewers()));
    }
    private void logTriage(FindingTriage triage) {
        for (FindingVerdict verdict : triage.getVerdicts()) {
            getLog().info(String.format("[%s%s] %s %s:%d %s",
                    verdict.getVerdict(),
                    Objects.nonNull(verdict.getSeverity()) ? " " + verdict.getSeverity() : StringUtils.EMPTY,
                    verdict.getRule(),
                    verdict.getFile(),
                    verdict.getLine(),
                    verdict.getReason()));
        }
    }
    private void writeReport(LocalReview review, Optional<FindingTriage> triage) throws IOException {
        Map<String, Object> report = Maps.newLinkedHashMap();
        report.put("commit", review.getCommit());
        report.put("tookSeconds", review.getTook().toSeconds());
        report.put("bugs", review.getBugs());
        report.put("sessions", review.getSessions());
        report.put("reviewers", review.getReviewers());
        report.put("unansweredReviewers", review.getUnansweredReviewers());
        report.put("answer", review.getAnswer());
        if (Objects.nonNull(review.getAssessment())) {
            report.put("assessment", review.getAssessment());
        }
        triage.ifPresent(found -> report.put("triage", found));

        FileUtils.forceMkdirParent(output);
        JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValue(output, report);
        getLog().info("review report written to " + output);
    }
}
