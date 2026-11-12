package io.codiqo.submit.review;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.client.model.AnalysisBuildFailureModel;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.llm.review.FindingTriage;
import io.codiqo.llm.review.FindingVerdict;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewModels;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.llm.review.PreviousFinding;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.llm.review.ReviewUnit;
import io.codiqo.llm.review.ReviewVerdict;
import io.codiqo.llm.review.StaticFinding;
import io.codiqo.llm.review.StaticFindings;
import io.codiqo.llm.review.UnitLabelling;
import io.codiqo.llm.review.UnitLabels;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.auth.CodiqoCredential;

/**
 * One change's local review, run beside its analysis: started before the build, handed the analysis's units and static
 * findings to label and triage once the build has them, and joined into the submission. A submitted commit and a
 * developer's uncommitted changes are reviewed the same way.
 */
public final class LocalReviewRun implements AutoCloseable {
    /**
     * Tells the backend's LLM proxy which project a review is for, by the code the REST API names the project by. A
     * project with an LLM key of its own pays for its reviews with that key, provided the member owns the project or has
     * committed to it; without the header every review is paid for by the organization's key.
     */
    public static final String PROJECT_HEADER = "X-Codiqo-Project";

    /** enough unmatched labels to show the drift, few enough to keep the log readable */
    private static final int UNMATCHED_LABEL_SAMPLE = 10;

    private final BackgroundReview<ReviewVerdict> review;
    /** the review's reviewer once the review's thread made one, which settles a review that ran out of time */
    private final AtomicReference<LocalReviewer> reviewer;
    /** completed with the analysis's findings just before the review is joined; the review waits for it to triage */
    private final CompletableFuture<List<StaticFinding>> buildFindings;
    private final CompletableFuture<List<ReviewUnit>> buildUnits;
    private final List<ReviewLanguage> languages;
    private final Log log;

    private LocalReviewRun(
            BackgroundReview<ReviewVerdict> review,
            AtomicReference<LocalReviewer> reviewer,
            CompletableFuture<List<StaticFinding>> buildFindings,
            CompletableFuture<List<ReviewUnit>> buildUnits,
            List<ReviewLanguage> languages,
            LogFactory logFactory) {
        this.review = review;
        this.reviewer = reviewer;
        this.buildFindings = buildFindings;
        this.buildUnits = buildUnits;
        this.languages = languages;
        this.log = logFactory.getLogger(LocalReviewRun.class);
    }
    /**
     * Hands the review the analysis's units and findings, waits for it and, when it succeeded, attaches its judgment to
     * the submission; returns how it ended, and a review that failed attaches nothing ({@link #exclusionOf} says what
     * that does to the commit). The review is mapped before anything is attached, and its labels are applied all or
     * nothing, so a failure while mapping leaves no half-attached review behind.
     */
    public ReviewVerdict attachTo(SubmissionContext ctx) {
        UnitLabelling.Checklist checklist = UnitLabelling.checklist(ctx.getSubmissionModel());
        buildUnits.complete(checklist.getListed());
        buildFindings.complete(StaticFindings.introduced(ctx.getSubmissionModel(), languages));

        ReviewVerdict toReturn = await(LocalReviewer.longestReview(ctx.getArgs()));
        if (toReturn instanceof ReviewVerdict.Succeeded succeeded) {
            LocalReviewer.ReviewOutcome reviewed = succeeded.getOutcome();
            LocalReview finished = reviewed.getReview();
            reviewed.getTriage().ifPresent(this::logTriage);

            LocalReviewModel model = LocalReviewModels.toModel(finished, reviewed.getLabels(), reviewed.getTriage());
            if (reviewed.getLabels().isPresent()) {
                labelById(ctx.getSubmissionModel(), checklist, finished, reviewed.getLabels().get());
            } else {
                labelByName(ctx.getSubmissionModel(), finished);
            }
            ctx.getSubmissionModel().setLocalReview(model);
        }
        return toReturn;
    }
    @Override
    public void close() throws InterruptedException {
        review.close();
    }
    /**
     * The review's verdict, settled here for what only the wait sees: the review's thread failed before a reviewer could
     * settle anything (the project's tag vocabulary could not be fetched), the review outlived every limit of its own,
     * or the run was stopped while it waited. A review that ran out of time before a reviewer existed never reached the
     * model, so it failed on Codiqo's side ({@link LocalReviewer#timedOut} settles the others). The timeout carries no
     * message of its own, and a halt's reason is its failure's root cause, so it is not kept as the cause.
     */
    private ReviewVerdict await(Duration timeout) {
        try {
            return review.await(timeout);
        } catch (ExecutionException err) {
            Exception failure = err;
            if (err.getCause() instanceof Exception cause) {
                failure = cause;
            }
            return ReviewVerdict.unexpected(failure, Optional.empty());
        } catch (TimeoutException err) {
            IOException timedOut = new IOException("local review did not finish within " + timeout);
            return Optional.ofNullable(reviewer.get())
                    .map(started -> started.timedOut(timedOut))
                    .orElseGet(() -> new ReviewVerdict.CodiqoFailure(timedOut));
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return new ReviewVerdict.Stopped(err);
        }
    }
    /**
     * The labelling turn's labels, by id, which the review already checked to be one per listed unit. The reviewers' own
     * labels are only matched here, so the log shows how much of the commit each would have labelled.
     */
    private void labelById(AnalysisSubmissionModel submission, UnitLabelling.Checklist checklist, LocalReview finished, UnitLabels labels) {
        LocalReviewModels.Labelling byName = LocalReviewModels.matchBlockCategories(submission, finished, languages).getRight();
        UnitLabelling.Applied applied = UnitLabelling.apply(checklist, labels);
        log.info("local review labelled %d of the submission's %d code units by id, from %d labels; the reviewers' own labels would have named %d",
                applied.getLabelled(),
                applied.getUnits(),
                labels.getLabels().size(),
                byName.getApplied());
    }
    private void labelByName(AnalysisSubmissionModel submission, LocalReview finished) {
        LocalReviewModels.Labelling labelling = LocalReviewModels.applyBlockCategories(submission, finished, languages);
        log.info("local review labelled %d of the submission's %d code units, from %d labels",
                labelling.getApplied(), labelling.getUnits(), labelling.getReturned());
        if (labelling.getReturned() == 0) {
            log.warn("the local review returned no code-unit labels: every unit of this commit is scored with the prompt's label or as MECHANICAL for an organisation scored without the prompt");
        }
        if (CollectionUtils.isNotEmpty(labelling.getUnmatched())) {
            log.warn("%d local review label(s) named no code unit of the submission, or several without a container to choose by, and were dropped: %s",
                    labelling.getUnmatched().size(),
                    labelling.getUnmatched().stream().limit(UNMATCHED_LABEL_SAMPLE).toList());
        }
    }
    private void logTriage(FindingTriage triage) {
        Multiset<FindingVerdict.Verdict> verdicts = HashMultiset.create(triage.getVerdicts().stream().map(FindingVerdict::getVerdict).toList());
        log.info("local review triaged %d static-analysis findings: %d defects, %d false positives, %d harmless",
                verdicts.size(), verdicts.count(FindingVerdict.Verdict.DEFECT), verdicts.count(FindingVerdict.Verdict.FALSE_POSITIVE),
                verdicts.count(FindingVerdict.Verdict.HARMLESS));
    }
    /**
     * Starts the review of {@code sha} in {@code workTree}, next to the analysis. The tag vocabulary and the commit's
     * previous findings are fetched on the review's own thread, so the build does not wait for them.
     */
    public static LocalReviewRun start(
            RunArgs args,
            Path workTree,
            String sha,
            ReviewEndpoint endpoint,
            String guidance,
            Callable<ReviewTags> tags,
            Callable<List<PreviousFinding>> previousFindings,
            List<ReviewLanguage> languages,
            LogFactory logFactory) {
        CompletableFuture<List<StaticFinding>> findings = new CompletableFuture<>();
        CompletableFuture<List<ReviewUnit>> units = new CompletableFuture<>();
        AtomicReference<LocalReviewer> reviewer = new AtomicReference<>();
        BackgroundReview<ReviewVerdict> review = BackgroundReview.start(() -> {
            reviewer.set(new LocalReviewer(args, endpoint, guidance, tags.call(), previousFindings.call(), languages, logFactory.getLogger(LocalReviewer.class)));
            return reviewer.get().reviewThenFollowUp(workTree, sha, Optional.of(units::get), Optional.of(findings::get));
        });
        return new LocalReviewRun(review, reviewer, findings, units, languages, logFactory);
    }
    /**
     * The endpoint the review's models are called at. When it is Codiqo's own proxy, every call carries the member's
     * credential and names the project ({@link #PROJECT_HEADER}); a direct endpoint gets neither.
     */
    public static ReviewEndpoint endpoint(RunArgs args, String resourceUrl, String projectCode, Callable<CodiqoCredential> credential) throws Exception {
        ReviewEndpoint toReturn = new ReviewEndpoint(args.getReviewBaseUrl(), null, null);
        if (toReturn.isProxiedBy(resourceUrl)) {
            CodiqoCredential member = credential.call();
            toReturn = new ReviewEndpoint(args.getReviewBaseUrl(), null, header -> {
                member.accept(header);
                header.accept(PROJECT_HEADER, projectCode);
            });
        }
        return toReturn;
    }
    @VisibleForTesting
    public static LocalReviewRun of(BackgroundReview<ReviewVerdict> review, List<ReviewLanguage> languages, LogFactory logFactory) {
        return new LocalReviewRun(review, new AtomicReference<>(), new CompletableFuture<>(), new CompletableFuture<>(), languages, logFactory);
    }
    /**
     * A build-failed commit keeps its build failure, with its files and metrics, as the exclusion a failed build without
     * a review records: an operator's re-inclusion of a build failure survives a re-analysis only under that category,
     * and the files are what the server stores as the excluded commit's per-file changes.
     */
    public static AnalysisExcludeModel exclusion(ReviewVerdict.CommitFailure failed, SubmissionContext ctx, Log log) {
        String reason = ReviewVerdict.REVIEW_FAILED + failed.getReason();
        log.error(failed.getError(), "commit %s excluded: %s", ctx.getArgs().getCommitId(), reason);

        AnalysisBuildFailureModel buildFailure = ctx.getSubmissionModel().getBuildFailure();
        if (Objects.nonNull(buildFailure)) {
            return new AnalysisExcludeModel()
                    .reason(buildFailure.getReason())
                    .category(buildFailure.getCategory())
                    .detail(buildFailure.getDetail())
                    .files(ctx.getSubmissionModel().getFiles())
                    .projectMetrics(ctx.getSubmissionModel().getProjectMetrics());
        }
        return new AnalysisExcludeModel()
                .reason(reason)
                .category(AnalysisExcludeCategory.LOCAL_REVIEW_FAILURE)
                .detail(ExceptionUtils.getStackTrace(failed.getError()))
                .files(Lists.newArrayList());
    }
}
