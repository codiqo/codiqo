package io.codiqo.submit;

import java.io.File;
import java.util.Map;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.CommitIndexApi;
import io.codiqo.client.model.ProjectHaltRequestModel;
import io.codiqo.llm.review.ReviewProviderException;
import io.codiqo.llm.review.ReviewTrace;
import io.codiqo.util.DownloadFailedException;
import lombok.experimental.UtilityClass;

/**
 * The circuit breaker of scheduled analysis. A run that fails on codiqo's side submits nothing, so the next scheduled
 * run lists the same commit as missing and starts its paid local review again, every hour, for as long as nobody looks.
 * A failed run therefore halts the project on the server, and every later run refuses to start until an administrator
 * resumes it in codiqo-web.
 */
@UtilityClass
public class ProjectHalts {
    /** the request model's limits; a longer reason or detail would make the server refuse the halt itself */
    private static final int REASON_LIMIT = 2000;
    private static final int DETAIL_LIMIT = 65536;
    private static final String SEPARATOR = "; ";
    /** the shortest length {@link StringUtils#abbreviate(String, int)} accepts: room for one character and its ellipsis */
    private static final int MIN_ABBREVIATION = 4;

    /** where the failed commit's review trace is, for the run's watcher; the run's log when no review left one */
    public String traceOf(RunArgs args, String commitSha) {
        File trace = ReviewTrace.file(args, commitSha);
        return trace.isFile() ? "review trace " + trace.getAbsolutePath() : "no review trace; see the commit's analysis log";
    }
    /**
     * Whether a failed run leaves the project running. A run that was stopped is not a failure of codiqo's, and the
     * thread's flag tells it apart: an interrupt often surfaces as an I/O error (ClosedByInterruptException,
     * InterruptedIOException) whose root cause is not an InterruptedException. A build the author broke with
     * skipOnBuildFailure off is the author's failure ({@link CommitBuildFailedException}). A tool that could not be
     * downloaded is the download host's ({@link DownloadFailedException}): it heals on its own, and halting on it held
     * thirteen projects until an administrator resumed each one after download.eclipse.org went down.
     */
    public boolean haltsNothing(Throwable err) {
        return BooleanUtils.or(new boolean[] {
                Thread.currentThread().isInterrupted(),
                ExceptionUtils.getRootCause(err) instanceof InterruptedException,
                ExceptionUtils.indexOfThrowable(err, CommitBuildFailedException.class) >= 0,
                ExceptionUtils.indexOfThrowable(err, DownloadFailedException.class) >= 0 });
    }
    /** throws {@link ProjectHaltedException} when the server holds the project's runs */
    public void requireRunning(Log log, CommitIndexApi client, String apiUrl, String projectId) throws ApiException {
        CommitIndexPublisher.listMissingAnalyses(log, client, apiUrl, projectId, null, 1);
    }
    /**
     * Records the failure as the project's halt, and says whether it was recorded. A halt that cannot be recorded is
     * logged as an error and the caller still fails with the original cause: losing that cause to the halt's own failure
     * would leave nothing to investigate.
     */
    public boolean halt(Log log, CommitIndexApi client, String apiUrl, String projectId, String commitSha, Throwable cause, boolean sourceHidden) {
        return halt(log, client, apiUrl, projectId, commitSha, reasonOf(cause), ExceptionUtils.getStackTrace(cause), sourceHidden);
    }
    /**
     * As above, for a failure known only by its description: a run killed from outside, which left no exception. The
     * result matters to {@code halt-project}, whose whole job is the halt: a goal that succeeded without recording it
     * would hide from the GitHub Action that the next scheduled run will pay for the same commit's review again.
     */
    public boolean halt(Log log, CommitIndexApi client, String apiUrl, String projectId, String commitSha, String reason, String detail, boolean sourceHidden) {
        /**
         * The detail can be the tail of the run's log, written before the GitHub Action redacts its logs, and a test or
         * a transport trace there may echo a credential; administrators read the halt on the server. Scrubbed with the
         * Action's own rules, as the review trace is before it is sent.
         */
        Map<String, String> environment = System.getenv();
        ProjectHaltRequestModel request = new ProjectHaltRequestModel()
                .commitSha(commitSha)
                .reason(StringUtils.abbreviate(SecretRedaction.redactText(reason, environment), REASON_LIMIT))
                .detail(StringUtils.abbreviate(SecretRedaction.redactText(detail, environment), DETAIL_LIMIT));
        /**
         * A hidden-source run's halt keeps its reason without names and drops the detail, a log quoting the build. It
         * needs no key, so a halt whose key could not be read is still recorded: a lost halt makes the next scheduled run
         * pay for the same review again.
         */
        if (sourceHidden) {
            request.setSourceHidden(true);
            request.setReason(SourceHiding.reason(request.getReason()));
            request.setDetail(null);
        }
        try {
            ApiRetry.call(log, "haltProject", apiUrl, () -> {
                client.haltProject(projectId, request);
                return null;
            });
            log.error("project %s halted: scheduled runs stop until it is resumed in codiqo-web (commit %s: %s)", projectId, commitSha, request.getReason());
            return true;
        } catch (Exception err) {
            log.error(err, "project %s could not be halted after commit %s failed; the next scheduled run will try it again", projectId, commitSha);
            return false;
        }
    }
    /**
     * The root cause names the failure, except for a review the model's provider failed: the provider's answers are
     * added by the {@link ReviewProviderException} that wraps the root cause, so the reason is the root cause followed
     * by them. Nine projects once halted with "session ... finished without an answer" while the provider
     * answered 429 for eight hours, and only the detail said so, which the halt banner in codiqo-web does not show. The
     * failure is shortened rather than the summary: a failure that fills the reason's limit on its own would cut off
     * exactly the part the banner needs.
     */
    private String reasonOf(Throwable cause) {
        String failure = ExceptionUtils.getRootCauseMessage(cause);
        int provider = ExceptionUtils.indexOfType(cause, ReviewProviderException.class);
        if (provider >= 0) {
            String summary = SEPARATOR + ((ReviewProviderException) ExceptionUtils.getThrowables(cause)[provider]).getProviderSummary();
            return StringUtils.abbreviate(failure, Math.max(MIN_ABBREVIATION, REASON_LIMIT - summary.length())) + summary;
        }
        return failure;
    }
}
