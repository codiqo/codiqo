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
     * The turn errors OpenCode records when a request that was fine got an answer that broke on the way. OpenCode has
     * already retried the provider when it records one, so a review that fails on it is the provider's failure; any other
     * turn error, a refused request among them, follows from what the commit asks and is the commit's.
     */
    private static final Set<String> PROVIDER_TURN_ERRORS = Set.of(
            "provider.transport",
            "provider.internal",
            "provider.invalid-output",
            "provider.rate-limit",
            "provider.timeout",
            "provider.unknown");
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
    private final List<ReviewLanguage> languages;
    private final Log log;

    public LocalReviewer(RunArgs args, ReviewEndpoint endpoint, String conventionGuidance, ReviewTags tags, List<ReviewLanguage> languages, Log log) {
        this.args = Objects.requireNonNull(args);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.conventionGuidance = Objects.requireNonNull(conventionGuidance);
        this.tags = Objects.requireNonNull(tags);
        this.languages = List.copyOf(languages);
        this.log = Objects.requireNonNull(log);
    }
    public LocalReview review(Path directory, String commit) throws IOException {
        return reviewThenFollowUp(directory, commit, Optional.empty(), Optional.empty()).getReview();
    }
    public ReviewOutcome reviewThenFollowUp(Path directory, String commit, Optional<BuildSource<ReviewUnit>> units, Optional<BuildSource<StaticFinding>> findings) throws IOException {
        ReviewTarget target = target(directory, commit);
        return runReview(target.getWorkTree(), target.getSha(), target.getNamingRules(), units, findings);
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
    private ReviewOutcome runReview(
            Path workTree,
            String sha,
            List<String> namingRules,
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings) throws IOException {
        try (LlmRelay relay = LlmRelay.start(args, endpoint, log); ReviewAnswers answers = ReviewAnswers.start(args, log)) {
            try {
                return runReview(workTree, sha, namingRules, new ReviewEndpoint(relay.getUrl(), relay.getSecret(), null), answers, units, findings);
            } catch (IOException | RuntimeException err) {
                /**
                 * A turn that timed out says only "timeout", so the provider's failed answers are added to the error.
                 * Whether the review is the provider's failure, a ReviewProviderException that halts the project instead
                 * of excluding the commit, follows from what failed (isProviderFailure). The parallel reviewers share
                 * the relay, so a provider failure cannot be traced to the call that lost a reviewer's answer: the
                 * provider is held responsible for any failure that is not the model's own answer.
                 */
                Optional<String> providerErrors = relay.providerErrors();
                if (isProviderFailure(err, relay.providerFailed())) {
                    throw new ReviewProviderException(err.getMessage(), providerErrors.orElse("OpenCode recorded the provider's failure; the relay counted none"), err);
                }
                if (providerErrors.isPresent()) {
                    throw new IOException(err.getMessage() + "; " + providerErrors.get(), err);
                }
                throw err;
            }
        }
    }
    private ReviewOutcome runReview(
            Path workTree,
            String sha,
            List<String> namingRules,
            ReviewEndpoint effective,
            ReviewAnswers answers,
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings) throws IOException {
        StopWatch watch = StopWatch.createStarted();
        /** a reviewer's session exists only once the coordinator started it, so a review is taken from any session */
        answers.expectFromAnySession(AnswerTool.REVIEW, LocalReviewer::readReviewerAnswer);
        ObjectNode config = OpenCodeReviewConfig.build(args, effective, answers.endpoint(), conventionGuidance, namingRules, tags);
        try (OpenCodeServer server = OpenCodeServer.start(args, config, workTree, log)) {
            try (OpenCodeClient client = new OpenCodeClient(server.getUrl(), server.getPassword(), args.getReviewTimeout())) {
                /** without the answer server no answer can be given, and every agent would run to its step cap for nothing */
                client.awaitMcpConnected(ReviewAnswers.SERVER, args.getReviewStartupTimeout());
                String sessionId = client.createSession(workTree, OpenCodeReviewConfig.COORDINATOR, "codiqo review " + StringUtils.left(sha, SHORT_SHA));
                answers.expect(sessionId, AnswerTool.ANSWER, LocalReviewer::readCoordinatorAnswer);
                List<String> forkIds = Lists.newArrayList();
                ReviewOutcome toReturn;
                try {
                    toReturn = review(client, answers, sessionId, forkIds, sha, units, findings, watch);
                } catch (Throwable err) {
                    /**
                     * A failed review still leaves its trace, the record of what the agents did before it failed, an
                     * Error such as an OutOfMemoryError included: skipping it would also leave an earlier run's trace of
                     * the commit to pass for this one's. A trace that cannot be written then is attached to the review's
                     * error rather than thrown in its place, which would lose the cause the halt or exclusion reports.
                     */
                    try {
                        ReviewTrace.write(args, client, sha, sessionId, forkIds, log);
                    } catch (IOException | RuntimeException traceErr) {
                        err.addSuppressed(traceErr);
                    }
                    throw err;
                }
                ReviewTrace.write(args, client, sha, sessionId, forkIds, log);
                return toReturn;
            }
        }
    }
    private ReviewOutcome review(
            OpenCodeClient client,
            ReviewAnswers answers,
            String sessionId,
            List<String> forkIds,
            String sha,
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings,
            StopWatch watch) throws IOException {
        LocalReview review;
        try (ProgressStage stage = ProgressStage.start(args, "review")) {
            log.info("reviewing %s with %s (coordinator session %s) and %s (reviewers)", sha, args.getReviewCoordinatorModel(), sessionId, args.getReviewReviewerModel());
            turn(client, sessionId, OpenCodeReviewConfig.reviewPrompt(sha));
            ReviewAnswers.Accepted<CoordinatorReport> answer = require(client, answers, sessionId, AnswerTool.ANSWER, CoordinatorReport.class);

            List<SessionUsage> sessions = Lists.newArrayList();
            sessions.add(withConfiguredModel(client.usage(sessionId)));
            for (SessionUsage child : client.childUsage(sessionId)) {
                sessions.add(withConfiguredModel(child));
            }

            ReviewerAnswers reviewers = reviewerAnswers(client, answers, sessions);

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

        Optional<UnitLabels> labels = Optional.empty();
        if (units.isPresent()) {
            labels = label(client, answers, sessionId, forkIds, sha, awaitBuild(units.get(), "code units"));
        }

        Optional<FindingTriage> triage = Optional.empty();
        if (findings.isPresent()) {
            triage = triage(client, answers, sessionId, forkIds, awaitBuild(findings.get(), "static-analysis findings"));
        }
        return new ReviewOutcome(review, labels, triage);
    }
    private Optional<UnitLabels> label(OpenCodeClient client, ReviewAnswers answers, String sessionId, List<String> forkIds, String sha, List<ReviewUnit> units) throws IOException {
        if (CollectionUtils.isEmpty(units)) {
            log.info("the analysis found no added or changed code units; nothing to label");
            return Optional.empty();
        }

        UnitLabels toReturn;
        try (ProgressStage stage = ProgressStage.start(args, "labels")) {
            String forkId = client.fork(sessionId);
            forkIds.add(forkId);
            log.info("labelling %d code units in session %s, a fork of %s", units.size(), forkId, sessionId);
            answers.expect(forkId, AnswerTool.LABELS, arguments -> {
                UnitLabels labels = ANSWER.treeToValue(arguments, UnitLabels.class);
                List<String> problems = UnitLabelling.problems(units, labels);
                if (CollectionUtils.isNotEmpty(problems)) {
                    throw new ReviewAnswers.RefusedAnswerException("not one label per listed unit: " + Joiner.on("; ").join(problems));
                }
                return labels;
            });
            ForkAnswer<UnitLabels> answer = ask(client, answers, forkId, OpenCodeReviewConfig.labelPrompt(args, sha, units), AnswerTool.LABELS, UnitLabels.class);

            toReturn = answer.getParsed();
            toReturn.setAnswer(answer.getAnswer());
            toReturn.setUsage(answer.getUsage());
            stage.detail(String.format("%d labels for %d units", toReturn.getLabels().size(), units.size()));
            stage.succeeded();
        }
        return Optional.of(toReturn);
    }
    private Optional<FindingTriage> triage(OpenCodeClient client, ReviewAnswers answers, String sessionId, List<String> forkIds, List<StaticFinding> findings) throws IOException {
        if (CollectionUtils.isEmpty(findings)) {
            log.info("no static-analysis findings on the commit's added lines; nothing to triage");
            return Optional.empty();
        }

        FindingTriage toReturn;
        try (ProgressStage stage = ProgressStage.start(args, "triage")) {
            String forkId = client.fork(sessionId);
            forkIds.add(forkId);
            log.info("triaging %d static-analysis findings in session %s, a fork of %s", findings.size(), forkId, sessionId);
            answers.expect(forkId, AnswerTool.TRIAGE, arguments -> {
                FindingTriage triage = ANSWER.treeToValue(arguments, FindingTriage.class);
                List<String> problems = triage.problems(findings);
                if (CollectionUtils.isNotEmpty(problems)) {
                    throw new ReviewAnswers.RefusedAnswerException("not one verdict per listed finding: " + Joiner.on("; ").join(problems));
                }
                return triage;
            });
            ForkAnswer<FindingTriage> answer = ask(client, answers, forkId, OpenCodeReviewConfig.triagePrompt(args, findings), AnswerTool.TRIAGE, FindingTriage.class);

            toReturn = answer.getParsed();
            toReturn.setAnswer(answer.getAnswer());
            toReturn.setUsage(answer.getUsage());
            toReturn.setFindings(findings);
            stage.detail(String.format("%d verdicts on %d findings", toReturn.getVerdicts().size(), findings.size()));
            stage.succeeded();
        }
        return Optional.of(toReturn);
    }
    private <T> ForkAnswer<T> ask(OpenCodeClient client, ReviewAnswers answers, String forkId, String prompt, AnswerTool tool, Class<T> type) throws IOException {
        turn(client, forkId, prompt);
        ReviewAnswers.Accepted<T> answer = require(client, answers, forkId, tool, type);
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
    private ReviewerAnswers reviewerAnswers(OpenCodeClient client, ReviewAnswers answers, List<SessionUsage> sessions) throws IOException {
        Map<Pair<String, String>, LlmScoringResponse.CodeBlockCategoryView> labels = Maps.newLinkedHashMap();
        /** the coordinator is told not to repeat them, so the reviewers' own answers are the only place they exist */
        Set<String> observations = Sets.newLinkedHashSet();
        Set<String> notChecked = Sets.newLinkedHashSet();
        List<String> unanswered = Lists.newArrayList();
        int total = 0;
        for (SessionUsage session : sessions) {
            if (OpenCodeReviewConfig.REVIEWER.equals(session.getAgent())) {
                total++;
                Optional<ReviewAnswers.Accepted<ReviewerAnswer>> accepted = answerOf(client, answers, session.getSessionId(), AnswerTool.REVIEW, ReviewerAnswer.class);
                if (accepted.isEmpty()) {
                    unanswered.add(session.getSessionId());
                    continue;
                }

                ReviewerAnswer answer = accepted.get().getParsed();
                if (answer.getLabels().isEmpty()) {
                    log.warn("reviewer session %s labelled none of its code units", session.getSessionId());
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
        }
        if (total == 0) {
            throw new ReviewAnswerException("the coordinator started no reviewer, so nothing of the commit was reviewed");
        }
        if (CollectionUtils.isNotEmpty(unanswered)) {
            throw new IOException(unanswered.size() + " of " + total + " reviewer sessions ended without an answer, so their areas went unreviewed: " + unanswered);
        }
        return new ReviewerAnswers(total, Lists.newArrayList(labels.values()),
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
    /** one turn of a session, prompted once: how it ended is read with its answer ({@link #answerOf}) */
    @VisibleForTesting
    public static void turn(OpenCodeClient client, String sessionId, String text) throws IOException {
        client.prompt(sessionId, text);
        client.awaitIdle(sessionId);
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
     * The answer a session gave through its tool, settled in this order. An accepted answer stands whatever the turn did
     * after it: the agent's closing sentence is one more call to the model, and a provider error on that call loses
     * nothing. Without one, a turn OpenCode ended with an error fails with that error, a refused call before it included,
     * since the agent never got to correct it; OpenCode retries a provider error itself before it gives a turn up, so
     * the error is final ({@code provider.invalid-output}, {@code provider.transport}). A refused answer is the
     * commit's failure, and so is a turn the model ended on its own without the call: the prose-only answers that
     * failed reviews before answers were tool calls. Empty for a session cut off without either, at its step cap for one.
     */
    @VisibleForTesting
    public static <T> Optional<ReviewAnswers.Accepted<T>> answerOf(OpenCodeClient client, ReviewAnswers answers, String sessionId, AnswerTool tool, Class<T> type)
            throws IOException {
        Optional<ReviewAnswers.Accepted<T>> toReturn;
        try {
            toReturn = answers.accepted(sessionId, type);
        } catch (IllegalStateException err) {
            /**
             * The answer could not be read on Codiqo's side: a reader failed on its own, or OpenCode named no calling
             * session, so no answer could be matched to its session. Neither is the provider's failure, and left as a
             * RuntimeException it reached isProviderFailure's last case, which decides by whether the relay counted
             * any provider error during the review: one retried 429 turned the same failure from an excluded commit
             * into a project halt that repeated on every resume. It is the commit's failure, whatever the provider did.
             */
            throw new ReviewAnswerException(err.getMessage(), err);
        }
        if (toReturn.isEmpty()) {
            List<List<JsonNode>> turns = client.turns(sessionId);
            if (CollectionUtils.isNotEmpty(turns)) {
                requireSucceeded(sessionId, OpenCodeClient.errorOf(turns.getLast()));
            }
            Optional<String> refused = answers.refusal(sessionId);
            if (refused.isPresent()) {
                throw new ReviewAnswerException("session " + sessionId + "'s " + tool.getOpenCodeName() + " answer was refused: " + refused.get());
            }
            if (CollectionUtils.isNotEmpty(turns) && OpenCodeClient.stoppedOnItsOwn(turns.getLast())) {
                throw new ReviewAnswerException("session " + sessionId + " ended its turn without calling " + tool.getOpenCodeName() + ", so it gave no answer");
            }
        }
        return toReturn;
    }
    private static <T> ReviewAnswers.Accepted<T> require(OpenCodeClient client, ReviewAnswers answers, String sessionId, AnswerTool tool, Class<T> type) throws IOException {
        return answerOf(client, answers, sessionId, tool, type)
                .orElseThrow(() -> new IOException("session " + sessionId + " was cut off before it called " + tool.getOpenCodeName() + ", so it gave no answer"));
    }
    private static void requireSucceeded(String sessionId, Optional<JsonNode> failed) throws TurnFailedException {
        if (failed.isPresent()) {
            throw new TurnFailedException(sessionId, failed.get().path("type").asString(), failed.get().path("message").asString());
        }
    }
    /**
     * A turn OpenCode ended with an error is the provider's failure exactly when the error is one of the provider's own
     * ({@link #PROVIDER_TURN_ERRORS}). An answer in the wrong shape is the model's, so the commit's failure whatever the
     * provider did: halting the project for it would halt it again on every resume. Any other failure, a reviewer that
     * ended without an answer or a turn that timed out, is the provider's when it failed during the review.
     */
    @VisibleForTesting
    public static boolean isProviderFailure(Throwable err, boolean providerFailed) {
        if (err instanceof TurnFailedException turn) {
            return PROVIDER_TURN_ERRORS.contains(turn.getType());
        }
        if (err instanceof ReviewAnswerException) {
            return false;
        }
        return providerFailed;
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

    /** an answer of the model in another shape than its prompt asks for, which is the commit's failure, never the provider's */
    private static final class ReviewAnswerException extends IOException {
        private static final long serialVersionUID = 1L;

        private ReviewAnswerException(String message) {
            super(message);
        }
        private ReviewAnswerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    /** a turn that OpenCode ended with an error, which names the session, the error's type and its message */
    private static final class TurnFailedException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String type;

        private TurnFailedException(String sessionId, String type, String message) {
            super("session " + sessionId + " ended its turn with " + type + ": " + message);
            this.type = type;
        }
        public String getType() {
            return type;
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
