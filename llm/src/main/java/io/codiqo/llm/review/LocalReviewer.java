package io.codiqo.llm.review;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.api.review.ReviewLanguage;
import io.codiqo.llm.client.LlmJson;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.util.JGit;
import io.codiqo.util.ProgressStage;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.StdConverter;

/**
 * Reviews one commit with OpenCode: a coordinator agent is given only the repository root and the commit id, works out
 * what changed with read-only git, splits the work across parallel reviewer sub-agents and merges their findings.
 *
 * <p>
 * No diff is prepared up front. Letting each reviewer fetch only its own paths with {@code git show <sha> -- <paths>}
 * used 43% fewer tokens on a 270-file commit than handing the agents prepared per-module patch files.
 */
public class LocalReviewer {
    private static final int SHORT_SHA = 8;
    private static final String BLOCK_CATEGORIES = "blockCategories";
    /** the follow-up turns after the build, the labelling and the triage, each in a fork of the coordinator */
    private static final int FOLLOW_UPS = 2;
    /**
     * The turn errors that follow from what the commit asks, so a review that fails on one is the commit's failure and
     * excludes it: a request the provider refused as invalid (a prompt larger than the model takes) and content its filter
     * refused. Every other turn error is taken for the provider's or Codiqo's and halts the project, a type a later
     * OpenCode version adds included. The two outcomes are not equally costly: a halt is reported and an administrator
     * resumes it, with the commit excluded if it fails again, while an exclusion is silent and leaves the commit unscored
     * until someone deletes its analysis. When the provider failures were the listed ones instead, provider.quota was
     * missing from the list, and an exhausted provider quota excluded 28 commits of one project within 14 minutes.
     */
    private static final Set<String> COMMIT_TURN_ERRORS = Set.of(
            "provider.invalid-request",
            "provider.content-filter");
    /**
     * Reads the coordinator's answer, whose blastRadiusAnalysis carries the signature changes flat ({@link FlatBlastRadius}):
     * the changed signatures as "signatureChanges", with hasBreakingChanges and breakingChangeType beside them, where
     * LlmScoringResponse nests all three in an object named "signatureChanges". Asked for that object, the coordinator model
     * wrote "signatureChanges" as the list and moved one of the other two fields up beside it in 2 of about 100 production
     * reviews, and each of those answers failed its review: a field named like a list that holds an object is a trap. The
     * mix-in changes this read only; LlmScoringResponse keeps its nested shape everywhere else, the server's own reads and
     * stored responses included.
     */
    private static final ObjectMapper COORDINATOR_ANSWER = LlmJson.responseMapper().rebuild()
            .addMixIn(LlmScoringResponse.class, CoordinatorAnswer.class)
            .build();
    private static final ObjectMapper ANSWER = LlmJson.responseMapper();

    private final RunArgs args;
    private final ReviewEndpoint endpoint;
    private final String conventionGuidance;
    private final ReviewTags tags;
    private final List<PreviousFinding> previousFindings;
    private final List<ReviewLanguage> languages;
    private final Log log;
    /**
     * The relay of the review, set once its agents are prompted and read by a caller that stopped waiting for the review
     * ({@link #timedOut}). Until then no agent can have lost an answer, so a review that ran out of time before it was
     * failed on Codiqo's side, never on the commit.
     */
    private final AtomicReference<LlmRelay> promptedRelay = new AtomicReference<>();

    public LocalReviewer(
            RunArgs args,
            ReviewEndpoint endpoint,
            String conventionGuidance,
            ReviewTags tags,
            List<PreviousFinding> previousFindings,
            List<ReviewLanguage> languages,
            Log log) {
        this.args = Objects.requireNonNull(args);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.conventionGuidance = Objects.requireNonNull(conventionGuidance);
        this.tags = Objects.requireNonNull(tags);
        this.previousFindings = List.copyOf(previousFindings);
        this.languages = List.copyOf(languages);
        this.log = Objects.requireNonNull(log);
    }
    public LocalReview review(Path directory, String commit) throws IOException {
        return switch (reviewThenFollowUp(directory, commit, Optional.empty(), Optional.empty())) {
            case ReviewVerdict.Succeeded succeeded -> succeeded.getOutcome().getReview();
            case ReviewVerdict.Failed failed -> throw new IOException(ReviewVerdict.REVIEW_FAILED + failed.getReason(), failed.getError());
        };
    }
    /**
     * Reviews the commit, then labels the build's units and triages its findings once the build hands them over. Every
     * failure is settled into the verdict where it happens, so nothing a review can fail with is thrown.
     */
    public ReviewVerdict reviewThenFollowUp(Path directory, String commit, Optional<BuildSource<ReviewUnit>> units, Optional<BuildSource<StaticFinding>> findings) {
        try {
            ReviewTarget target = target(directory, commit);
            try (LlmRelay relay = LlmRelay.start(args, endpoint, log); ReviewAnswers answers = ReviewAnswers.start(args, log)) {
                try {
                    return runReview(target.getWorkTree(), target.getSha(), target.getNamingRules(), relay, answers, units, findings);
                } catch (IOException | RuntimeException err) {
                    return ReviewVerdict.unexpected(err, relay.providerErrors());
                }
            }
        } catch (IOException | RuntimeException err) {
            return ReviewVerdict.unexpected(err, Optional.empty());
        }
    }
    /**
     * The verdict on this reviewer's review when its caller stopped waiting for it, settled as a turn that did not end in
     * time is ({@link ReviewVerdict#unanswered}) by what the relay has counted so far, once the agents were prompted.
     */
    public ReviewVerdict timedOut(Exception err) {
        Optional<LlmRelay> relay = Optional.ofNullable(promptedRelay.get());
        if (relay.isPresent()) {
            return ReviewVerdict.unanswered(err, relay.get().providerFailed(), relay.get().providerErrors());
        }
        return new ReviewVerdict.CodiqoFailure(err);
    }
    /**
     * Prompts the session once and settles its turn: empty when it answered through its tool, else the verdict its
     * failure makes ({@link #failureOf}). A turn that did not end within the review timeout and a session cut off
     * without an answer, at its step cap for one, end the review unanswered ({@link ReviewVerdict#unanswered}), decided
     * by the provider's failures during this turn alone. The labelling and triage turns run one after another, after the
     * coordinator's: counted over the whole review, a single 503 OpenCode retried during the coordinator's turn would make
     * a labelling turn cut off at its step cap the provider's failure, a halt that comes back on every resume when the
     * commit's units are what ran the turn to its cap. The coordinator's turn, during which the reviewers run, spans the
     * whole review up to it.
     */
    @VisibleForTesting
    public Optional<ReviewVerdict> ask(OpenCodeClient client, ReviewAnswers answers, LlmRelay relay, String sessionId, String prompt, AnswerTool tool) throws IOException {
        long failuresBefore = relay.providerFailures();
        client.prompt(sessionId, prompt);
        promptedRelay.compareAndSet(null, relay);
        StopWatch turn = StopWatch.createStarted();
        try {
            client.awaitIdle(sessionId);
        } catch (InterruptedIOException err) {
            /**
             * OkHttp ends a call that outlived its call timeout, which is the review timeout, with this exception, and
             * also a connect to OpenCode that timed out after seconds (a SocketTimeoutException) and an interrupted wait.
             * Only the first is a turn that did not end in time, and only it comes after the whole review timeout: the
             * others are Codiqo's failure or a stopped run, settled where the review is.
             */
            if (BooleanUtils.or(new boolean[] { Thread.currentThread().isInterrupted(), turn.getDuration().compareTo(args.getReviewTimeout()) < 0 })) {
                throw err;
            }
            return Optional.of(ReviewVerdict.unanswered(
                    new IOException("session " + sessionId + " did not end its turn within " + args.getReviewTimeout()),
                    relay.providerFailures() > failuresBefore,
                    relay.providerErrors()));
        }

        Optional<ReviewVerdict> toReturn = failureOf(client, answers, relay, sessionId, tool);
        if (toReturn.isEmpty() && answers.accepted(sessionId, Object.class).isEmpty()) {
            toReturn = Optional.of(ReviewVerdict.unanswered(
                    new IOException("session " + sessionId + " was cut off before it called " + tool.getOpenCodeName() + ", so it gave no answer"),
                    relay.providerFailures() > failuresBefore,
                    relay.providerErrors()));
        }
        return toReturn;
    }
    private ReviewTarget target(Path directory, String commit) throws IOException {
        try (Repository repository = new FileRepositoryBuilder().findGitDir(directory.toFile()).setMustExist(true).build()) {
            ObjectId resolved = repository.resolve(commit);
            if (Objects.nonNull(resolved)) {
                return new ReviewTarget(repository.getWorkTree().toPath(), resolved.name(), ReviewLanguages.namingRules(JGit.changedFileNames(repository, resolved.name()), languages));
            }
            throw new IOException("unknown commit " + commit + " in " + repository.getWorkTree());
        }
    }
    private ReviewVerdict runReview(
            Path workTree,
            String sha,
            List<String> namingRules,
            LlmRelay relay,
            ReviewAnswers answers,
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings) throws IOException {
        StopWatch watch = StopWatch.createStarted();
        /** a reviewer's session exists only once the coordinator started it, so a review is taken from any session */
        answers.expectFromAnySession(AnswerTool.REVIEW, LocalReviewer::readReviewerAnswer);
        ObjectNode config = OpenCodeReviewConfig.build(args, new ReviewEndpoint(relay.getUrl(), relay.getSecret(), null), answers.endpoint(), conventionGuidance, namingRules, tags);
        try (OpenCodeServer server = OpenCodeServer.start(args, config, workTree, log)) {
            try (OpenCodeClient client = new OpenCodeClient(server.getUrl(), server.getPassword(), args.getReviewTimeout())) {
                /** without the answer server no answer can be given, and every agent would run to its step cap for nothing */
                client.awaitMcpConnected(ReviewAnswers.SERVER, args.getReviewStartupTimeout());
                String sessionId = client.createSession(workTree, OpenCodeReviewConfig.COORDINATOR, "codiqo review " + StringUtils.left(sha, SHORT_SHA));
                answers.expect(sessionId, AnswerTool.ANSWER, LocalReviewer::readCoordinatorAnswer);
                List<String> forkIds = Lists.newArrayList();
                ReviewVerdict toReturn;
                try {
                    toReturn = review(client, answers, relay, sessionId, forkIds, sha, units, findings, watch);
                } catch (Throwable err) {
                    /**
                     * A failed review still leaves its trace, the record of what the agents did before it failed, an
                     * Error such as an OutOfMemoryError included: skipping it would also leave an earlier run's trace of
                     * the commit to pass for this one's.
                     */
                    writeTrace(client, sha, sessionId, forkIds, err);
                    throw err;
                }

                /**
                 * Every submitted review is sent with its trace (AnalysisSubmitter.putReviewTrace), so a trace that
                 * cannot be written fails a review that succeeded: it is thrown, and settled as Codiqo's failure.
                 */
                if (toReturn instanceof ReviewVerdict.Failed failed) {
                    writeTrace(client, sha, sessionId, forkIds, failed.getError());
                } else {
                    ReviewTrace.write(args, client, sha, sessionId, forkIds, log);
                }
                return toReturn;
            }
        }
    }
    /**
     * The trace of a review that failed. One that cannot be written is attached to the review's error rather than thrown
     * in its place, which would lose the cause the halt or the exclusion reports.
     */
    private void writeTrace(OpenCodeClient client, String sha, String sessionId, List<String> forkIds, Throwable failure) {
        try {
            ReviewTrace.write(args, client, sha, sessionId, forkIds, log);
        } catch (IOException | RuntimeException traceErr) {
            failure.addSuppressed(traceErr);
        }
    }
    private ReviewVerdict review(
            OpenCodeClient client,
            ReviewAnswers answers,
            LlmRelay relay,
            String sessionId,
            List<String> forkIds,
            String sha,
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings,
            StopWatch watch) throws IOException {
        ReviewVerdict toReturn = coordinate(client, answers, relay, sessionId, sha, watch);
        if (toReturn instanceof ReviewVerdict.Succeeded reviewed && units.isPresent()) {
            toReturn = label(client, answers, relay, sessionId, forkIds, sha, reviewed.getOutcome(), awaitBuild(units.get(), "code units"));
        }
        if (toReturn instanceof ReviewVerdict.Succeeded labelled && findings.isPresent()) {
            toReturn = triage(client, answers, relay, sessionId, forkIds, labelled.getOutcome(), awaitBuild(findings.get(), "static-analysis findings"));
        }
        return toReturn;
    }
    private ReviewVerdict coordinate(OpenCodeClient client, ReviewAnswers answers, LlmRelay relay, String sessionId, String sha, StopWatch watch) throws IOException {
        LocalReview review;
        try (ProgressStage stage = ProgressStage.start(args, "review")) {
            log.info("reviewing %s with %s (coordinator session %s) and %s (reviewers)", sha, args.getReviewCoordinatorModel(), sessionId, args.getReviewReviewerModel());
            Optional<ReviewVerdict> failed = ask(client, answers, relay, sessionId, OpenCodeReviewConfig.reviewPrompt(sha), AnswerTool.ANSWER);
            if (failed.isPresent()) {
                return failed.get();
            }
            ReviewAnswers.Accepted<CoordinatorReport> answer = answers.accepted(sessionId, CoordinatorReport.class).orElseThrow();

            List<SessionUsage> sessions = Lists.newArrayList();
            sessions.add(withConfiguredModel(client.usage(sessionId)));
            for (SessionUsage child : client.childUsage(sessionId)) {
                sessions.add(withConfiguredModel(child));
            }

            List<String> reviewerIds = sessions.stream()
                    .filter(session -> OpenCodeReviewConfig.REVIEWER.equals(session.getAgent()))
                    .map(SessionUsage::getSessionId)
                    .toList();
            failed = settleReviewers(client, answers, relay, reviewerIds);
            if (failed.isPresent()) {
                return failed.get();
            }
            ReviewerAnswers reviewers = reviewerAnswers(answers, reviewerIds);

            LlmScoringResponse assessment = answer.getParsed().getAssessment();
            assessment.setBlockCategories(reviewers.getBlockCategories());
            log.info("the coordinator split the review of %s into %d areas", sha, answer.getParsed().getTasks().size());
            review = new LocalReview(
                    sha,
                    answer.getParsed().getBugs(),
                    sessions,
                    watch.getDuration(),
                    answer.getArguments(),
                    assessment,
                    reviewers.getTotal(),
                    reviewers.getObservations(),
                    reviewers.getNotChecked());
            stage.detail(progressDetail(review));
            stage.succeeded();
        }
        return new ReviewVerdict.Succeeded(new ReviewOutcome(review, Optional.empty(), Optional.empty()));
    }
    private ReviewVerdict label(
            OpenCodeClient client,
            ReviewAnswers answers,
            LlmRelay relay,
            String sessionId,
            List<String> forkIds,
            String sha,
            ReviewOutcome reviewed,
            List<ReviewUnit> units) throws IOException {
        if (CollectionUtils.isEmpty(units)) {
            log.info("the analysis found no added or changed code units; nothing to label");
            return new ReviewVerdict.Succeeded(reviewed);
        }

        UnitLabels labels;
        try (ProgressStage stage = ProgressStage.start(args, "labels")) {
            String forkId = client.fork(sessionId);
            forkIds.add(forkId);
            log.info("labelling %d code units in session %s, a fork of %s", units.size(), forkId, sessionId);
            answers.expect(forkId, AnswerTool.LABELS, arguments -> {
                UnitLabels read = ANSWER.treeToValue(arguments, UnitLabels.class);
                List<String> problems = UnitLabelling.problems(units, read);
                if (CollectionUtils.isNotEmpty(problems)) {
                    throw new ReviewAnswers.RefusedAnswerException("not one label per listed unit: " + Joiner.on("; ").join(problems));
                }
                return read;
            });
            Optional<ReviewVerdict> failed = ask(client, answers, relay, forkId, OpenCodeReviewConfig.labelPrompt(args, sha, units), AnswerTool.LABELS);
            if (failed.isPresent()) {
                return failed.get();
            }
            ForkAnswer<UnitLabels> answer = forkAnswer(client, answers, forkId, UnitLabels.class);

            labels = answer.getParsed();
            labels.setAnswer(answer.getAnswer());
            labels.setUsage(answer.getUsage());
            stage.detail(String.format("%d labels for %d units", labels.getLabels().size(), units.size()));
            stage.succeeded();
        }
        return new ReviewVerdict.Succeeded(new ReviewOutcome(reviewed.getReview(), Optional.of(labels), reviewed.getTriage()));
    }
    private ReviewVerdict triage(
            OpenCodeClient client,
            ReviewAnswers answers,
            LlmRelay relay,
            String sessionId,
            List<String> forkIds,
            ReviewOutcome reviewed,
            List<StaticFinding> findings) throws IOException {
        if (CollectionUtils.isEmpty(findings)) {
            log.info("no static-analysis findings on the commit's added lines; nothing to triage");
            return new ReviewVerdict.Succeeded(reviewed);
        }

        FindingTriage triage;
        try (ProgressStage stage = ProgressStage.start(args, "triage")) {
            String forkId = client.fork(sessionId);
            forkIds.add(forkId);
            log.info("triaging %d static-analysis findings in session %s, a fork of %s", findings.size(), forkId, sessionId);
            answers.expect(forkId, AnswerTool.TRIAGE, arguments -> {
                FindingTriage read = ANSWER.treeToValue(arguments, FindingTriage.class);
                List<String> problems = read.problems(findings);
                if (CollectionUtils.isNotEmpty(problems)) {
                    throw new ReviewAnswers.RefusedAnswerException("not one verdict per listed finding: " + Joiner.on("; ").join(problems));
                }
                return read;
            });
            Optional<ReviewVerdict> failed = ask(client, answers, relay, forkId, OpenCodeReviewConfig.triagePrompt(args, findings, previousFindings), AnswerTool.TRIAGE);
            if (failed.isPresent()) {
                return failed.get();
            }
            ForkAnswer<FindingTriage> answer = forkAnswer(client, answers, forkId, FindingTriage.class);

            triage = answer.getParsed();
            triage.setAnswer(answer.getAnswer());
            triage.setUsage(answer.getUsage());
            triage.setFindings(findings);
            stage.detail(String.format("%d verdicts on %d findings", triage.getVerdicts().size(), findings.size()));
            stage.succeeded();
        }
        return new ReviewVerdict.Succeeded(new ReviewOutcome(reviewed.getReview(), reviewed.getLabels(), Optional.of(triage)));
    }
    private <T> ForkAnswer<T> forkAnswer(OpenCodeClient client, ReviewAnswers answers, String forkId, Class<T> type) throws IOException {
        ReviewAnswers.Accepted<T> answer = answers.accepted(forkId, type).orElseThrow();
        return new ForkAnswer<>(answer.getParsed(), answer.getArguments(), withConfiguredModel(client.usage(forkId)));
    }
    @VisibleForTesting
    public static String progressDetail(LocalReview review) {
        LlmScoringResponse.Bugs bugs = review.getBugs();
        return String.format("%d bugs, %d sessions, %d reviewers, %d input / %d output tokens",
                bugs.getBlocking().size() + bugs.getMajor().size() + bugs.getMinor().size(),
                review.getSessions().size(),
                review.getReviewers(),
                review.totalInputTokens(),
                review.totalOutputTokens());
    }
    private static <T> List<T> awaitBuild(BuildSource<T> source, String what) throws IOException {
        try {
            return source.await();
        } catch (IOException err) {
            throw err;
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            InterruptedIOException toThrow = new InterruptedIOException("interrupted while waiting for the build's " + what);
            toThrow.initCause(err);
            throw toThrow;
        } catch (Exception err) {
            throw new IOException("the build's " + what + " never arrived", err);
        }
    }
    /** the reviewers' answers, every one of them accepted ({@link #settleReviewers}) */
    private ReviewerAnswers reviewerAnswers(ReviewAnswers answers, List<String> reviewerIds) {
        Map<Pair<String, String>, LlmScoringResponse.CodeBlockCategoryView> labels = Maps.newLinkedHashMap();
        /** the coordinator is told not to repeat them, so the reviewers' own answers are the only place they exist */
        Set<String> observations = Sets.newLinkedHashSet();
        Set<String> notChecked = Sets.newLinkedHashSet();
        for (String reviewerId : reviewerIds) {
            ReviewerAnswer answer = answers.accepted(reviewerId, ReviewerAnswer.class).orElseThrow().getParsed();
            if (answer.getLabels().isEmpty()) {
                log.warn("reviewer session %s labelled none of its code units", reviewerId);
            }
            answer.getLabels().forEach(block -> labels.putIfAbsent(Pair.of(block.getFile(), block.getSignature()), block));

            for (String observation : answer.getObservations()) {
                if (StringUtils.isNotBlank(observation)) {
                    observations.add(observation.strip());
                }
            }
            for (String note : answer.getNotChecked()) {
                if (StringUtils.isNotBlank(note)) {
                    notChecked.add(note.strip());
                }
            }
        }
        return new ReviewerAnswers(reviewerIds.size(), Lists.newArrayList(labels.values()),
                observations.stream().limit(args.getReviewMaxObservations()).toList(),
                notChecked.stream().limit(args.getReviewMaxNotChecked()).toList());
    }
    /**
     * A reviewer's labels, observations and what it could not verify. An answer without "unitLabels" is unreadable, not
     * one that labels nothing: a reviewer with no units sends [], and a production reviewer once answered with a
     * single finding object, which read as no labels at all and silently dropped its judgement of its units.
     */
    @VisibleForTesting
    public static ReviewerAnswer readReviewerAnswer(JsonNode arguments) {
        ReviewerFields fields = ANSWER.treeToValue(arguments, ReviewerFields.class);
        if (Objects.isNull(fields.getUnitLabels())) {
            throw MismatchedInputException.from((JsonParser) null, ReviewerFields.class,
                    "the answer has no \"unitLabels\" field; it is the object the ANSWER FORMAT shows, with [] when your paths hold no unit");
        }
        if (fields.getUnitLabels().contains(null)) {
            throw MismatchedInputException.from((JsonParser) null, ReviewerFields.class, "\"unitLabels\" holds a null entry; every entry is a label object");
        }
        return new ReviewerAnswer(
                fields.getUnitLabels().stream().toList(),
                CollectionUtils.emptyIfNull(fields.getObservations()).stream().toList(),
                CollectionUtils.emptyIfNull(fields.getNotChecked()).stream().toList());
    }
    private SessionUsage withConfiguredModel(SessionUsage usage) {
        String model = usage.getModel();
        if (StringUtils.isBlank(model)) {
            model = OpenCodeReviewConfig.COORDINATOR.equals(usage.getAgent()) ? args.getReviewCoordinatorModel() : args.getReviewReviewerModel();
        }
        return usage.withModel(model);
    }
    /**
     * The longest a review can legitimately take, for a caller that waits for it: the startup, then the coordinator's
     * review and each follow-up after the build, every one a {@link OpenCodeClient#awaitIdle} call bounded on its own by
     * {@link RunArgs#getReviewTimeout()}. A caller that allowed only one review timeout failed the build while every turn
     * was still inside its own limit, for example a 28-minute review followed by a 6-minute triage.
     */
    public static Duration longestReview(RunArgs args) {
        return args.getReviewStartupTimeout().plus(args.getReviewTimeout().multipliedBy(1 + FOLLOW_UPS));
    }
    /** the coordinator's answer whole: a part that cannot be read refuses the answer, so it is corrected within the turn */
    @VisibleForTesting
    public static CoordinatorReport readCoordinatorAnswer(JsonNode arguments) {
        return new CoordinatorReport(readBugs(arguments), readAssessment(arguments), LocalReviewModels.tasks(arguments));
    }
    @VisibleForTesting
    public static LlmScoringResponse.Bugs readBugs(JsonNode arguments) {
        LlmScoringResponse.Bugs toReturn = ANSWER.treeToValue(arguments, LlmScoringResponse.Bugs.class);
        toReturn.setBlocking(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getBlocking())));
        toReturn.setMajor(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getMajor())));
        toReturn.setMinor(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getMinor())));
        return toReturn;
    }
    /**
     * The coordinator's assessment, read through {@code COORDINATOR_ANSWER} without its blockCategories. The unit labels
     * are the reviewers' own ({@code reviewerAnswers}), which replace that field after the read, and a coordinator once
     * collapsed every reviewer's labels into bare category names, a list of strings where label objects belong: read with
     * the rest, that field alone failed an answer whose every field the review keeps was well formed.
     */
    @VisibleForTesting
    public static LlmScoringResponse readAssessment(JsonNode arguments) {
        ObjectNode answer = (ObjectNode) arguments.deepCopy();
        answer.remove(BLOCK_CATEGORIES);
        return COORDINATOR_ANSWER.treeToValue(answer, LlmScoringResponse.class);
    }
    /**
     * Why a session gave no answer through its tool, settled in this order. An accepted answer stands whatever the turn
     * did after it: the agent's closing sentence is one more call to the model, and a provider error on that call loses
     * nothing. Without one, a turn OpenCode ended with an error fails with that error, a refused call before it included,
     * since the agent never got to correct it; OpenCode retries a provider error itself before it gives a turn up, so the
     * error is final ({@code provider.invalid-output}, {@code provider.transport}), and the provider's unless the
     * commit caused it ({@link #COMMIT_TURN_ERRORS}). A refused answer is the commit's failure, and so is a turn the
     * model ended on its own without the call: the prose-only answers that failed reviews before answers were tool
     * calls. Empty when the session answered, and when it was cut off without either, at its step cap for one, which
     * its caller settles.
     */
    private static Optional<ReviewVerdict> failureOf(OpenCodeClient client, ReviewAnswers answers, LlmRelay relay, String sessionId, AnswerTool tool) throws IOException {
        boolean answered;
        try {
            answered = answers.accepted(sessionId, Object.class).isPresent();
        } catch (IllegalStateException err) {
            /**
             * The answer could not be read on Codiqo's side: a reader failed on its own, or OpenCode named no calling
             * session, so no answer could be matched to its session. It fails the same way on every later run, so it is
             * the commit's failure whatever the provider did: decided by whether the relay counted a provider error
             * during the review, one retried 429 turned it into a project halt that came back on every resume.
             */
            return Optional.of(ReviewVerdict.commitFailure(new IOException(err.getMessage(), err), relay.providerErrors()));
        }
        if (answered) {
            return Optional.empty();
        }

        List<List<JsonNode>> turns = client.turns(sessionId);
        Optional<JsonNode> turnError = Optional.empty();
        if (CollectionUtils.isNotEmpty(turns)) {
            turnError = OpenCodeClient.errorOf(turns.getLast());
        }
        if (turnError.isPresent()) {
            String type = turnError.get().path("type").asString();
            IOException err = new IOException("session " + sessionId + " ended its turn with " + type + ": " + turnError.get().path("message").asString());
            if (COMMIT_TURN_ERRORS.contains(type)) {
                return Optional.of(ReviewVerdict.commitFailure(err, relay.providerErrors()));
            }
            return Optional.of(ReviewVerdict.providerFailure(err, relay.providerErrors()));
        }

        Optional<String> refused = answers.refusal(sessionId);
        if (refused.isPresent()) {
            return Optional.of(ReviewVerdict.commitFailure(
                    new IOException("session " + sessionId + "'s " + tool.getOpenCodeName() + " answer was refused: " + refused.get()),
                    relay.providerErrors()));
        }
        if (CollectionUtils.isNotEmpty(turns) && OpenCodeClient.stoppedOnItsOwn(turns.getLast())) {
            return Optional.of(ReviewVerdict.commitFailure(
                    new IOException("session " + sessionId + " ended its turn without calling " + tool.getOpenCodeName() + ", so it gave no answer"),
                    relay.providerErrors()));
        }
        return Optional.empty();
    }
    /**
     * Settles every reviewer's session: empty when each gave its answer. A coordinator that started no reviewer reviewed
     * nothing of the commit, which is the commit's failure; reviewers cut off without an answer left their areas
     * unreviewed ({@link ReviewVerdict#unanswered}). Reviewers that failed differently settle the review as the
     * provider's failure when one of them failed on the provider, whatever order OpenCode lists them in: the halt is
     * reported and the commit reviewed again on resume, where an exclusion would leave it unscored over a review the
     * provider broke.
     */
    @VisibleForTesting
    public static Optional<ReviewVerdict> settleReviewers(OpenCodeClient client, ReviewAnswers answers, LlmRelay relay, List<String> reviewerIds) throws IOException {
        if (reviewerIds.isEmpty()) {
            return Optional.of(ReviewVerdict.commitFailure(new IOException("the coordinator started no reviewer, so nothing of the commit was reviewed"), relay.providerErrors()));
        }

        List<ReviewVerdict> failures = Lists.newArrayList();
        List<String> unanswered = Lists.newArrayList();
        for (String reviewerId : reviewerIds) {
            Optional<ReviewVerdict> failed = failureOf(client, answers, relay, reviewerId, AnswerTool.REVIEW);
            failed.ifPresent(failures::add);
            if (BooleanUtils.and(new boolean[] { failed.isEmpty(), answers.accepted(reviewerId, ReviewerAnswer.class).isEmpty() })) {
                unanswered.add(reviewerId);
            }
        }
        if (CollectionUtils.isNotEmpty(unanswered)) {
            failures.add(ReviewVerdict.unanswered(
                    new IOException(unanswered.size() + " of " + reviewerIds.size() + " reviewer sessions ended without an answer, so their areas went unreviewed: " + unanswered),
                    relay.providerFailed(),
                    relay.providerErrors()));
        }
        return failures.stream()
                .filter(ReviewVerdict.ProviderFailure.class::isInstance)
                .findFirst()
                .or(() -> failures.stream().findFirst());
    }
    @Value
    public static class ReviewerAnswers {
        int total;
        List<LlmScoringResponse.CodeBlockCategoryView> blockCategories;
        List<String> observations;
        List<String> notChecked;
    }

    /**
     * The fields of a reviewer's answer beside its bugs. The labels are {@code unitLabels}, not the scoring response's
     * {@code blockCategories}: reviewers read that name as the topics of their review and filled it with sentences.
     */
    @Data
    @NoArgsConstructor
    public static class ReviewerFields {
        /** strict: an entry written as a sentence, or no field at all, fails the read and with it the review */
        private List<LlmScoringResponse.CodeBlockCategoryView> unitLabels;

        /** a lone observation written as a string, not a one-item list, says the same thing and is read as one */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        private List<String> observations = Lists.newArrayList();

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        private List<String> notChecked = Lists.newArrayList();
    }

    @Value
    public static class ReviewerAnswer {
        List<LlmScoringResponse.CodeBlockCategoryView> labels;
        List<String> observations;
        List<String> notChecked;
    }

    @FunctionalInterface
    public interface BuildSource<T> {
        List<T> await() throws Exception;
    }

    /**
     * The coordinator's answer reads its blastRadiusAnalysis as a {@link FlatBlastRadius}, see {@link #COORDINATOR_ANSWER}.
     * Unwrapping the nested object onto its parent cannot express this format: Jackson does not unwrap a property named
     * like a property of the object it holds, and the list is "signatureChanges" in both.
     */
    private interface CoordinatorAnswer {
        @JsonDeserialize(converter = FlatBlastRadiusConverter.class)
        void setBlastRadiusAnalysis(LlmScoringResponse.BlastRadiusAnalysis blastRadiusAnalysis);
    }

    /**
     * The coordinator's blastRadiusAnalysis as its prompt asks for it. Only these fields are taken from the review; the
     * caller counts are the analysis's own. The nested object written the old way fails the read: its "signatureChanges"
     * is not a list. The signature fields stay null when the answer leaves them out, so a blastRadiusAnalysis without any
     * of them carries no signature changes at all rather than a judgment of none, as an answer without the nested object
     * did. One that names only some of them gets the defaults the nested object gave a left-out field: not breaking, no
     * signatures. Both answers that failed on the nested object left one out, one of them hasBreakingChanges beside a
     * breakingChangeType while its explanation said no caller breaks; requiring the field would fail such answers, and
     * reading its absence as breaking would charge the breaking-change penalty the explanation rules out.
     */
    @Data
    @NoArgsConstructor
    private static class FlatBlastRadius {
        private LlmScoringResponse.ModuleType moduleType;
        private List<String> signatureChanges;
        private Boolean hasBreakingChanges;
        private LlmScoringResponse.BreakingChangeType breakingChangeType;
        private String explanation;
    }

    private static final class FlatBlastRadiusConverter extends StdConverter<FlatBlastRadius, LlmScoringResponse.BlastRadiusAnalysis> {
        @Override
        public LlmScoringResponse.BlastRadiusAnalysis convert(FlatBlastRadius flat) {
            LlmScoringResponse.BlastRadiusAnalysis toReturn = LlmScoringResponse.BlastRadiusAnalysis.builder()
                    .moduleType(flat.getModuleType())
                    .explanation(flat.getExplanation())
                    .build();

            if (BooleanUtils.or(new boolean[] { Objects.nonNull(flat.getSignatureChanges()), Objects.nonNull(flat.getHasBreakingChanges()), Objects.nonNull(flat.getBreakingChangeType()) })) {
                toReturn.setSignatureChanges(LlmScoringResponse.SignatureChanges.builder()
                        .hasBreakingChanges(Boolean.TRUE.equals(flat.getHasBreakingChanges()))
                        .changedSignatures(Lists.newArrayList(CollectionUtils.emptyIfNull(flat.getSignatureChanges())))
                        .breakingChangeType(flat.getBreakingChangeType())
                        .build());
            }
            return toReturn;
        }
    }

    private static final class ReviewTarget extends ImmutableTriple<Path, String, List<String>> {
        private ReviewTarget(Path workTree, String sha, List<String> namingRules) {
            super(workTree, sha, namingRules);
        }
        public Path getWorkTree() {
            return getLeft();
        }
        public String getSha() {
            return getMiddle();
        }
        public List<String> getNamingRules() {
            return getRight();
        }
    }
    private static final class ForkAnswer<T> extends ImmutableTriple<T, String, SessionUsage> {
        private ForkAnswer(T parsed, String answer, SessionUsage usage) {
            super(parsed, answer, usage);
        }
        public T getParsed() {
            return getLeft();
        }
        public String getAnswer() {
            return getMiddle();
        }
        public SessionUsage getUsage() {
            return getRight();
        }
    }
    /** the coordinator's answer as the review takes it: its findings, its assessment and its work split */
    public static final class CoordinatorReport extends ImmutableTriple<LlmScoringResponse.Bugs, LlmScoringResponse, List<LocalReviewModels.Task>> {
        private CoordinatorReport(LlmScoringResponse.Bugs bugs, LlmScoringResponse assessment, List<LocalReviewModels.Task> tasks) {
            super(bugs, assessment, tasks);
        }
        public LlmScoringResponse.Bugs getBugs() {
            return getLeft();
        }
        public LlmScoringResponse getAssessment() {
            return getMiddle();
        }
        public List<LocalReviewModels.Task> getTasks() {
            return getRight();
        }
    }
    public static final class ReviewOutcome extends ImmutableTriple<LocalReview, Optional<UnitLabels>, Optional<FindingTriage>> {
        private ReviewOutcome(LocalReview review, Optional<UnitLabels> labels, Optional<FindingTriage> triage) {
            super(review, labels, triage);
        }
        public LocalReview getReview() {
            return getLeft();
        }
        public Optional<UnitLabels> getLabels() {
            return getMiddle();
        }
        public Optional<FindingTriage> getTriage() {
            return getRight();
        }
    }
}
