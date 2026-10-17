package io.codiqo.submit.review;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.collections4.CollectionUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.llm.review.FindingTriage;
import io.codiqo.llm.review.FindingVerdict;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewModels;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.llm.review.ReviewUnit;
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
    /** enough unmatched labels to show the drift, few enough to keep the log readable */
    private static final int UNMATCHED_LABEL_SAMPLE = 10;

    private final BackgroundReview<LocalReviewer.ReviewOutcome> review;
    /** completed with the analysis's findings just before the review is joined; the review waits for it to triage */
    private final CompletableFuture<List<StaticFinding>> buildFindings;
    private final CompletableFuture<List<ReviewUnit>> buildUnits;
    private final List<ReviewLanguage> languages;
    private final LogFactory logFactory;
    private final Log log;

    private LocalReviewRun(
            BackgroundReview<LocalReviewer.ReviewOutcome> review,
            CompletableFuture<List<StaticFinding>> buildFindings,
            CompletableFuture<List<ReviewUnit>> buildUnits,
            List<ReviewLanguage> languages,
            LogFactory logFactory) {
        this.review = review;
        this.buildFindings = buildFindings;
        this.buildUnits = buildUnits;
        this.languages = languages;
        this.logFactory = logFactory;
        this.log = logFactory.getLogger(LocalReviewRun.class);
    }
    /**
     * Hands the review the analysis's units and findings, waits for it and attaches its judgment to the submission. The
     * review is mapped before anything is attached, and its labels are applied all or nothing, so a failure while mapping
     * leaves no half-attached review behind.
     *
     * @throws LocalReviewException when the review failed or ran out of time; nothing is attached then
     */
    public void attachTo(SubmissionContext ctx) throws LocalReviewException {
        UnitLabelling.Checklist checklist = UnitLabelling.checklist(ctx.getSubmissionModel());
        buildUnits.complete(checklist.getListed());
        buildFindings.complete(StaticFindings.introduced(ctx.getSubmissionModel(), languages));

        LocalReviewer.ReviewOutcome reviewed = review.await(LocalReviewer.longestReview(ctx.getArgs()));
        LocalReview finished = reviewed.getReview();
        reviewed.getTriage().ifPresent(this::logTriage);

        LocalReviewModel model = LocalReviewModels.toModel(finished, reviewed.getTriage());
        if (reviewed.getLabels().isPresent()) {
            labelById(ctx.getSubmissionModel(), checklist, finished, reviewed.getLabels().get());
        } else {
            labelByName(ctx.getSubmissionModel(), finished);
        }
        ctx.getSubmissionModel().setLocalReview(model);
    }
    @Override
    public void close() throws InterruptedException {
        review.close();
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
     * Starts the review of {@code sha} in {@code workTree}, next to the analysis. The tag vocabulary is fetched on the
     * review's own thread, so the build does not wait for it.
     */
    public static LocalReviewRun start(
            RunArgs args,
            Path workTree,
            String sha,
            ReviewEndpoint endpoint,
            String guidance,
            Callable<ReviewTags> tags,
            List<ReviewLanguage> languages,
            LogFactory logFactory) {
        CompletableFuture<List<StaticFinding>> findings = new CompletableFuture<>();
        CompletableFuture<List<ReviewUnit>> units = new CompletableFuture<>();
        BackgroundReview<LocalReviewer.ReviewOutcome> review = BackgroundReview.start(() -> {
            LocalReviewer reviewer = new LocalReviewer(args, endpoint, guidance, tags.call(), languages, logFactory.getLogger(LocalReviewer.class));
            return reviewer.reviewThenFollowUp(workTree, sha, Optional.of(units::get), Optional.of(findings::get));
        });
        return new LocalReviewRun(review, findings, units, languages, logFactory);
    }
    /** the endpoint the review's models are called at, with the member's credential when it is Codiqo's own proxy */
    public static ReviewEndpoint endpoint(RunArgs args, String resourceUrl, Callable<CodiqoCredential> credential) throws Exception {
        ReviewEndpoint toReturn = new ReviewEndpoint(args.getReviewBaseUrl(), null, null);
        if (toReturn.isProxiedBy(resourceUrl)) {
            toReturn = new ReviewEndpoint(args.getReviewBaseUrl(), null, credential.call());
        }
        return toReturn;
    }
    @VisibleForTesting
    public static LocalReviewRun of(BackgroundReview<LocalReviewer.ReviewOutcome> review, List<ReviewLanguage> languages, LogFactory logFactory) {
        return new LocalReviewRun(review, new CompletableFuture<>(), new CompletableFuture<>(), languages, logFactory);
    }
}
