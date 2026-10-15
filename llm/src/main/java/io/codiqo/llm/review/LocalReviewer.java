package io.codiqo.llm.review;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;

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
    /** the labelling and the triage, each a follow-up turn after the build */
    private static final int FOLLOW_UPS = 2;
    /**
     * How many times a turn whose model answer failed on the way is resumed. OpenCode retries a provider error itself,
     * up to ten times, and continues an answer the relay reported cut short ({@code provider.internal}), but an answer
     * it reads as broken ({@code provider.invalid-output}) fails the turn at once, and a failed coordinator turn would
     * fail the whole review.
     */
    private static final int TURN_RESUMES = 2;
    /**
     * Every reviewer answer that cannot be read gets one correction, and this bounds them in one review. Each correction
     * is a turn the review's time budget ({@link #longestReview}) must hold, so the bound has to be finite. It was 2 per
     * review, which failed a production commit on 14 Oct 2026 after a 30-minute review whose third of nine reviewers answered
     * in an unreadable shape. Production reviews run at most 9 reviewers (95th percentile 8, measured 14 Oct 2026), so
     * 16 lets every reviewer of any review seen so far be corrected; a review needing more has a problem a correction
     * will not fix, and fails.
     */
    @VisibleForTesting
    static final int REVIEWER_REPAIRS = 16;
    /** the turns one reviewer's correction can take: the correction in its session, then the final answer in a fork */
    @VisibleForTesting
    public static final int CORRECTION_TURNS = 2;
    /** the title the coordinator is told to give a retry; models also write "Retry konquer steps", so the colon is optional */
    private static final String RETRY_TITLE_PREFIX = "retry";
    /** the errors of a request that was fine and an answer that broke on the way; a refused request is not resumed */
    private static final Set<String> RESUMABLE_ERRORS = Set.of(
            "provider.transport",
            "provider.internal",
            "provider.invalid-output",
            "provider.rate-limit",
            "provider.timeout",
            "provider.unknown");

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
                return new ReviewTarget(repository.getWorkTree().toPath(), resolved.name(), ReviewLanguages.namingRules(changedFiles(repository, resolved.name()), languages));
            }
            throw new IOException("unknown commit " + commit + " in " + repository.getWorkTree());
        }
    }
    private List<String> changedFiles(Repository repository, String sha) {
        try {
            return JGit.changedFileNames(repository, sha);
        } catch (IOException err) {
            log.warn("could not list the files %s changes (%s); the reviewers are given the naming rules of every language", sha, err.getMessage());
            return List.of();
        }
    }
    private ReviewOutcome runReview(
            Path workTree,
            String sha,
            List<String> namingRules,
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings) throws IOException {
        try (LlmRelay relay = LlmRelay.start(args, endpoint, log)) {
            try {
                return runReview(workTree, sha, namingRules, new ReviewEndpoint(relay.getUrl(), relay.getSecret(), null), units, findings);
            } catch (IOException | RuntimeException err) {
                /**
                 * A turn that timed out says only "timeout"; the provider failing underneath it is the cause worth
                 * reading, and it makes the failure the provider's rather than the commit's. On 14 Oct 2026 two reviews
                 * of real commits failed this way (one with 466 answers of 429, another with 102),
                 * and both commits were excluded and never retried. Requests the provider only refused (a prompt over
                 * the model's context) are named too but stay the commit's failure: halting the project for them would
                 * halt it again on every resume, since the same commit asks the same prompt.
                 */
                Optional<String> providerErrors = relay.providerErrors();
                if (providerErrors.isPresent()) {
                    String message = err.getMessage() + "; " + providerErrors.get();
                    if (relay.providerFailed()) {
                        throw new ReviewProviderException(message, err);
                    }
                    throw new IOException(message, err);
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
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings) throws IOException {
        StopWatch watch = StopWatch.createStarted();
        try (OpenCodeServer server = OpenCodeServer.start(args, OpenCodeReviewConfig.build(args, effective, conventionGuidance, namingRules, tags), workTree, log)) {
            try (OpenCodeClient client = new OpenCodeClient(server.getUrl(), server.getPassword(), args.getReviewTimeout())) {
                String sessionId = client.createSession(workTree, OpenCodeReviewConfig.COORDINATOR, "codiqo review " + StringUtils.left(sha, SHORT_SHA));
                List<String> forkIds = Lists.newArrayList();
                try {
                    return review(client, sessionId, forkIds, workTree, sha, units, findings, watch);
                } finally {
                    ReviewTrace.write(args, client, sha, sessionId, forkIds, log);
                }
            }
        }
    }
    private ReviewOutcome review(
            OpenCodeClient client,
            String sessionId,
            List<String> forkIds,
            Path workTree,
            String sha,
            Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings,
            StopWatch watch) throws IOException {
        LocalReview review;
        try (ProgressStage stage = ProgressStage.start(args, "review")) {
            log.info("reviewing %s with %s (coordinator session %s) and %s (reviewers)", sha, args.getReviewCoordinatorModel(), sessionId, args.getReviewReviewerModel());
            turn(client, sessionId, OpenCodeReviewConfig.reviewPrompt(sha), log);

            String answer = client.finalAnswer(sessionId);
            Optional<JacksonException> unreadable = unreadable(answer);
            if (unreadable.isPresent()) {
                /** One slip, such as an unclosed string in a long answer, must not cost a review the agents already finished. */
                log.warn("the coordinator's answer is not valid JSON (%s); asking it to correct the answer", unreadable.get().getOriginalMessage());
                turn(client, sessionId, OpenCodeReviewConfig.repairPrompt(unreadable.get().getOriginalMessage()), log);
                answer = client.finalAnswer(sessionId);
            }

            List<SessionUsage> sessions = Lists.newArrayList();
            sessions.add(withConfiguredModel(client.usage(sessionId)));
            for (SessionUsage child : client.childUsage(sessionId)) {
                sessions.add(withConfiguredModel(child));
            }

            ReviewerAnswers reviewers = reviewerAnswers(client, sessions, forkIds);
            if (CollectionUtils.isNotEmpty(reviewers.getUnanswered())) {
                log.warn("%d of %d reviewer sessions ended without an answer: %s",
                        reviewers.getUnanswered().size(),
                        reviewers.getTotal(),
                        reviewers.getUnanswered());
            }

            LlmScoringResponse.Bugs bugs;
            LlmScoringResponse assessment = null;
            try {
                bugs = readBugs(answer);
                assessment = LlmJson.readAnswerIgnoring(answer, LlmScoringResponse.class, BLOCK_CATEGORIES);
                assessment.setBlockCategories(reviewers.getBlockCategories());
            } catch (JacksonException err) {
                throw new IOException("the review answer holds no findings object: " + StringUtils.abbreviate(answer, RunArgs.REVIEW_ANSWER_PREVIEW), err);
            }
            review = new LocalReview(
                    sha,
                    bugs,
                    sessions,
                    watch.getDuration(),
                    answer,
                    assessment,
                    reviewers.getTotal(),
                    reviewers.getUnanswered(),
                    reviewers.getObservations(),
                    reviewers.getNotChecked(),
                    reviewers.getCorrected());
            stage.detail(progressDetail(review));
            stage.succeeded();
        }

        Optional<UnitLabels> labels = Optional.empty();
        if (units.isPresent()) {
            labels = label(client, sessionId, forkIds, sha, awaitBuild(units.get(), "code units"));
        }

        Optional<FindingTriage> triage = Optional.empty();
        if (findings.isPresent()) {
            triage = triage(client, sessionId, forkIds, awaitBuild(findings.get(), "static-analysis findings"));
        }
        return new ReviewOutcome(review, labels, triage);
    }
    private Optional<UnitLabels> label(OpenCodeClient client, String sessionId, List<String> forkIds, String sha, List<ReviewUnit> units) throws IOException {
        if (CollectionUtils.isEmpty(units)) {
            log.info("the analysis found no added or changed code units; nothing to label");
            return Optional.empty();
        }

        UnitLabels toReturn;
        try (ProgressStage stage = ProgressStage.start(args, "labels")) {
            String forkId = client.fork(sessionId);
            forkIds.add(forkId);
            log.info("labelling %d code units in session %s, a fork of %s", units.size(), forkId, sessionId);
            ForkAnswer<UnitLabels> answer = ask(client, forkId, OpenCodeReviewConfig.labelPrompt(sha, units), UnitLabels.class, "labelling");

            toReturn = answer.getParsed();
            /**
             * A model writes "labels": null, or a null among the labels, now and then although the JSON rules forbid it.
             * Either is no label; read as one, it threw a NullPointerException past the labelling's own failure handling,
             * which failed the whole review and with it the build.
             */
            toReturn.setLabels(CollectionUtils.emptyIfNull(toReturn.getLabels()).stream().filter(Objects::nonNull).toList());
            toReturn.setAnswer(answer.getAnswer());
            toReturn.setUsage(answer.getUsage());
            stage.detail(String.format("%d labels for %d units", toReturn.getLabels().size(), units.size()));
            stage.succeeded();
        }
        return Optional.of(toReturn);
    }
    private Optional<FindingTriage> triage(OpenCodeClient client, String sessionId, List<String> forkIds, List<StaticFinding> findings) throws IOException {
        if (CollectionUtils.isEmpty(findings)) {
            log.info("no static-analysis findings on the commit's added lines; nothing to triage");
            return Optional.empty();
        }

        FindingTriage toReturn;
        try (ProgressStage stage = ProgressStage.start(args, "triage")) {
            String forkId = client.fork(sessionId);
            forkIds.add(forkId);
            log.info("triaging %d static-analysis findings in session %s, a fork of %s", findings.size(), forkId, sessionId);
            ForkAnswer<FindingTriage> answer = ask(client, forkId, OpenCodeReviewConfig.triagePrompt(findings), FindingTriage.class, "triage");

            toReturn = answer.getParsed();
            toReturn.setAnswer(answer.getAnswer());
            toReturn.setUsage(answer.getUsage());
            toReturn.setFindings(findings);
            stage.detail(String.format("%d verdicts on %d findings", toReturn.getVerdicts().size(), findings.size()));
            stage.succeeded();
        }
        return Optional.of(toReturn);
    }
    private <T> ForkAnswer<T> ask(OpenCodeClient client, String forkId, String prompt, Class<T> type, String name) throws IOException {
        turn(client, forkId, prompt, log);

        String answer = client.finalAnswer(forkId);
        T parsed;
        try {
            parsed = LlmJson.readAnswer(answer, type);
        } catch (JacksonException err) {
            log.warn("the %s answer is not valid JSON (%s); asking for a corrected answer", name, err.getOriginalMessage());
            turn(client, forkId, OpenCodeReviewConfig.repairPrompt(err.getOriginalMessage()), log);
            answer = client.finalAnswer(forkId);
            try {
                parsed = LlmJson.readAnswer(answer, type);
            } catch (JacksonException again) {
                throw new IOException("the " + name + " answer is not the JSON object asked for: " + StringUtils.abbreviate(answer, RunArgs.REVIEW_ANSWER_PREVIEW), again);
            }
        }
        return new ForkAnswer<>(parsed, answer, withConfiguredModel(client.usage(forkId)));
    }
    @VisibleForTesting
    public static String progressDetail(LocalReview review) {
        LlmScoringResponse.Bugs bugs = review.getBugs();
        return String.format("%d bugs, %d sessions, %d of %d reviewers unanswered, %d input / %d output tokens",
                bugs.getBlocking().size() + bugs.getMajor().size() + bugs.getMinor().size(),
                review.getSessions().size(),
                review.getUnansweredReviewers().size(),
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
    private ReviewerAnswers reviewerAnswers(OpenCodeClient client, List<SessionUsage> sessions, List<String> forkIds) throws IOException {
        Map<Pair<String, String>, LlmScoringResponse.CodeBlockCategoryView> labels = Maps.newLinkedHashMap();
        /** the coordinator is told not to repeat them, so the reviewers' own answers are the only place they exist */
        Set<String> observations = Sets.newLinkedHashSet();
        Set<String> notChecked = Sets.newLinkedHashSet();
        List<String> unanswered = Lists.newArrayList();
        int total = 0;
        int repairs = 0;
        int answeredRetries = 0;
        for (SessionUsage session : sessions) {
            if (OpenCodeReviewConfig.REVIEWER.equals(session.getAgent())) {
                total++;
                String text;
                try {
                    text = client.finalAnswer(session.getSessionId());
                } catch (IOException err) {
                    log.warn("reviewer session %s ended with no answer at all: %s", session.getSessionId(), err.getMessage());
                    unanswered.add(session.getSessionId());
                    continue;
                }
                if (isRetry(client.session(session.getSessionId()).path("title").asString())) {
                    answeredRetries++;
                }

                ReviewerAnswer answer = readReviewerAnswer(client, session.getSessionId(), text, repairs < REVIEWER_REPAIRS, forkIds::add, log);
                if (answer.isCorrected()) {
                    repairs++;
                }
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
        int unreviewed = unreviewedAreas(unanswered.size(), answeredRetries);
        if (unreviewed > args.getReviewMaxUnreviewedAreas()) {
            log.error("%d of %d reviewer sessions ended without an answer and %d answered retries cover them: %d areas went unreviewed (%s); the review fails",
                    unanswered.size(),
                    total,
                    answeredRetries,
                    unreviewed,
                    unanswered);
            throw new IOException(unreviewed + " reviewer areas went unreviewed: sessions " + unanswered + " ended without an answer");
        }
        return new ReviewerAnswers(total, unanswered, Lists.newArrayList(labels.values()),
                observations.stream().limit(args.getReviewMaxObservations()).toList(),
                notChecked.stream().limit(args.getReviewMaxNotChecked()).toList(),
                repairs);
    }
    /**
     * Areas left without a review: each reviewer that ended with no answer, less each answered retry the coordinator
     * started for one. A retry may split an area in two, so this can undercount by one per split, never overcount.
     */
    @VisibleForTesting
    static int unreviewedAreas(int unanswered, int answeredRetries) {
        return Math.max(0, unanswered - answeredRetries);
    }
    @VisibleForTesting
    static boolean isRetry(String title) {
        return StringUtils.startsWithIgnoreCase(StringUtils.strip(title), RETRY_TITLE_PREFIX);
    }
    /**
     * A reviewer's labels, observations and what it could not verify. An answer that cannot be read, broken JSON, a label
     * written as a bare string or no "unitLabels" at all, gets a correction in its own session: the session's whole
     * history is an unchanged prefix, so the provider's prompt cache serves it, and the reviewer is told what was wrong.
     * One such slip in ten reviewers excluded whole commits. A correction that is still unreadable leaves the bad answer
     * in the context, where it anchors the model on its own mistake (a production commit, 14 Oct 2026: "Now..." again after
     * the correction), so the second attempt is a fork from before that answer: every tool result is kept, the prefix is
     * still cached, and the reviewer is asked only for its final answer. An answer still unreadable after both fails the
     * review, since dropping that reviewer's labels would judge with less.
     */
    @VisibleForTesting
    static ReviewerAnswer readReviewerAnswer(OpenCodeClient client, String sessionId, String text, boolean mayCorrect, Consumer<String> forks, Log log) throws IOException {
        String problem;
        try {
            return parseReviewerAnswer(text, false);
        } catch (JacksonException err) {
            if (BooleanUtils.negate(mayCorrect)) {
                log.error("reviewer session %s answered in an unreadable shape (%s) after %d corrections in this review; the review fails",
                        sessionId,
                        err.getOriginalMessage(),
                        REVIEWER_REPAIRS);
                throw err;
            }
            problem = err.getOriginalMessage();
            log.warn("reviewer session %s answered in an unreadable shape (%s); asking it to correct the answer", sessionId, problem);
        }

        String unreadableAnswer = client.finalAnswerId(sessionId);
        turn(client, sessionId, OpenCodeReviewConfig.repairPrompt(problem), log);
        String corrected = client.finalAnswer(sessionId);
        try {
            return parseReviewerAnswer(corrected, true);
        } catch (JacksonException err) {
            log.warn("reviewer session %s still answered in an unreadable shape after a correction (%s); asking a fork from before its answer for the final answer",
                    sessionId,
                    err.getOriginalMessage());
        }

        String forkId = client.fork(sessionId, unreadableAnswer);
        forks.accept(forkId);
        turn(client, forkId, OpenCodeReviewConfig.finalAnswerPrompt(), log);
        try {
            return parseReviewerAnswer(client.finalAnswer(forkId), true);
        } catch (JacksonException err) {
            log.error("reviewer session %s and its fork %s both answered in an unreadable shape (%s); the review fails", sessionId, forkId, err.getOriginalMessage());
            throw err;
        }
    }
    /**
     * An answer without "unitLabels" is unreadable, not one that labels nothing: a reviewer with no units sends [], and
     * one final answer of a production review (14 Oct 2026) was a single finding object, which read as no labels at all and
     * silently dropped that reviewer's judgement of its units.
     */
    private static ReviewerAnswer parseReviewerAnswer(String text, boolean corrected) {
        ReviewerFields fields = LlmJson.readAnswer(text, ReviewerFields.class);
        if (Objects.isNull(fields.getUnitLabels())) {
            throw MismatchedInputException.from((JsonParser) null, ReviewerFields.class,
                    "the answer has no \"unitLabels\" field; it is the object the ANSWER FORMAT shows, with [] when your paths hold no unit");
        }
        return new ReviewerAnswer(
                CollectionUtils.emptyIfNull(fields.getUnitLabels()).stream().toList(),
                CollectionUtils.emptyIfNull(fields.getObservations()).stream().toList(),
                CollectionUtils.emptyIfNull(fields.getNotChecked()).stream().toList(),
                corrected);
    }
    private SessionUsage withConfiguredModel(SessionUsage usage) {
        String model = usage.getModel();
        if (StringUtils.isBlank(model)) {
            model = OpenCodeReviewConfig.COORDINATOR.equals(usage.getAgent()) ? args.getReviewCoordinatorModel() : args.getReviewReviewerModel();
        }
        return usage.withModel(model);
    }
    /**
     * The longest a review can legitimately take, for a caller that waits for it. Every turn the agents take is one
     * {@link OpenCodeClient#awaitIdle} call, bounded on its own by {@link RunArgs#getReviewTimeout()}: the coordinator's
     * review, its optional repair round and, for each follow-up after the build (the labelling and the triage), its
     * prompt and optional repair round, each of them resumed up to {@value #TURN_RESUMES} times. The other requests
     * read finished sessions and return at once. A caller that
     * allowed only one review timeout failed the build while every turn was still inside its own limit, for example a
     * 28-minute review followed by a 6-minute triage.
     */
    public static Duration longestReview(RunArgs args) {
        int turns = (RunArgs.REVIEW_TURNS + REVIEWER_REPAIRS * CORRECTION_TURNS + FOLLOW_UPS * RunArgs.REVIEW_FOLLOW_UP_TURNS) * (1 + TURN_RESUMES);
        return args.getReviewStartupTimeout().plus(args.getReviewTimeout().multipliedBy(turns));
    }
    /**
     * One turn of a session, resumed in the same session when its model answer failed on the way: the session keeps
     * every step already taken, so only the broken step is redone. A turn that keeps failing, or fails on a request
     * the provider refused, is left as it ended, and reading its answer then fails the review.
     */
    @VisibleForTesting
    public static void turn(OpenCodeClient client, String sessionId, String text, Log log) throws IOException {
        client.prompt(sessionId, text);
        client.awaitIdle(sessionId);
        for (int resume = 1; resume <= TURN_RESUMES; resume++) {
            Optional<JsonNode> failed = client.turnError(sessionId).filter(error -> RESUMABLE_ERRORS.contains(error.path("type").asString()));
            if (failed.isEmpty()) {
                return;
            }
            String problem = failed.get().path("type").asString() + ": " + failed.get().path("message").asString();
            log.warn("session %s: the model's answer failed on the way (%s); resuming the turn, %d of %d", sessionId, problem, resume, TURN_RESUMES);
            client.prompt(sessionId, OpenCodeReviewConfig.resumePrompt(problem));
            client.awaitIdle(sessionId);
        }
    }
    @VisibleForTesting
    public static LlmScoringResponse.Bugs readBugs(String answer) {
        LlmScoringResponse.Bugs toReturn = LlmJson.readAnswer(answer, LlmScoringResponse.Bugs.class);
        toReturn.setBlocking(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getBlocking())));
        toReturn.setMajor(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getMajor())));
        toReturn.setMinor(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getMinor())));
        return toReturn;
    }
    private static Optional<JacksonException> unreadable(String answer) {
        try {
            LlmJson.readAnswer(answer, LlmScoringResponse.Bugs.class);
            LlmJson.readAnswerIgnoring(answer, LlmScoringResponse.class, BLOCK_CATEGORIES);
            return Optional.empty();
        } catch (JacksonException err) {
            return Optional.of(err);
        }
    }

    @Value
    public static class ReviewerAnswers {
        int total;
        List<String> unanswered;
        List<LlmScoringResponse.CodeBlockCategoryView> blockCategories;
        List<String> observations;
        List<String> notChecked;
        int corrected;
    }

    /**
     * The fields of a reviewer's answer beside its bugs. The labels are {@code unitLabels}, not the scoring response's
     * {@code blockCategories}: reviewers read that name as the topics of their review and filled it with sentences.
     */
    @Data
    @NoArgsConstructor
    public static class ReviewerFields {
        /** strict: an entry written as a sentence, or no field at all, fails the read, and the reviewer is asked for a correction */
        private List<LlmScoringResponse.CodeBlockCategoryView> unitLabels;

        /** a lone observation written as a string, not a one-item list, says the same thing and is read as one */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        private List<String> observations = Lists.newArrayList();

        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        private List<String> notChecked = Lists.newArrayList();
    }

    @Value
    static class ReviewerAnswer {
        List<LlmScoringResponse.CodeBlockCategoryView> labels;
        List<String> observations;
        List<String> notChecked;
        boolean corrected;
    }

    @FunctionalInterface
    public interface BuildSource<T> {
        List<T> await() throws Exception;
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
