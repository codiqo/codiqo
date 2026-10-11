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

    private final RunArgs args;
    private final ReviewEndpoint endpoint;
    private final String conventionGuidance;
    private final ReviewTags tags;
    private final List<ReviewLanguage> languages;
    private final Log log;

    /** @param tags the project's tag vocabulary for an assessing coordinator, {@link ReviewTags#NONE} when none is known */
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
    /**
     * Reviews the commit, then, while the review's server is still up, waits for what the build produced and asks forks
     * of the coordinator's session about it: one labels the analysis's own code units by id, another judges which
     * static-analysis findings are real defects. A fork keeps everything the coordinator read, and the server's home
     * (with every session) is deleted when the review closes, so both have to happen here and not in a later run.
     */
    public ReviewOutcome reviewThenFollowUp(Path directory, String commit, Optional<BuildSource<ReviewUnit>> units, Optional<BuildSource<StaticFinding>> findings) throws IOException {
        ReviewTarget target = target(directory, commit);
        return runReview(target.getWorkTree(), target.getSha(), target.getNamingRules(), units, findings);
    }
    /**
     * The commit and the naming rules of the languages it changes, read with a repository that is closed again before
     * the review starts: listing the changes loads the clone's pack indexes, which would otherwise stay in memory for the
     * whole review and, with the triage, until the build finishes (about 13 MB on a large repository).
     */
    private ReviewTarget target(Path directory, String commit) throws IOException {
        try (Repository repository = new FileRepositoryBuilder().findGitDir(directory.toFile()).setMustExist(true).build()) {
            ObjectId resolved = repository.resolve(commit);
            if (Objects.nonNull(resolved)) {
                return new ReviewTarget(repository.getWorkTree().toPath(), resolved.name(), ReviewLanguages.namingRules(changedFiles(repository, resolved.name()), languages));
            }
            throw new IOException("unknown commit " + commit + " in " + repository.getWorkTree());
        }
    }
    /**
     * The files the commit changes, which only choose the naming rules the agents are given. The review itself needs no
     * local diff, since OpenCode's git fetches what a partial clone lacks, so a diff that cannot be computed here (a
     * {@code --filter=tree:0} clone has no parent tree to compare, and JGit does not fetch it) must not fail the review:
     * no files means every registered rule is given.
     */
    private List<String> changedFiles(Repository repository, String sha) {
        try {
            return JGit.changedFileNames(repository, sha);
        } catch (IOException err) {
            log.warn("could not list the files %s changes (%s); the reviewers are given the naming rules of every language", sha, err.getMessage());
            return List.of();
        }
    }
    private ReviewOutcome runReview(Path workTree, String sha, List<String> namingRules, Optional<BuildSource<ReviewUnit>> units, Optional<BuildSource<StaticFinding>> findings)
            throws IOException {
        /**
         * Every review goes through the relay, a direct endpoint as well as the Codiqo proxy: it is where the agents'
         * requests get the scoring prompt's sampling, which OpenCode drops from every place its configuration offers.
         */
        try (LlmRelay relay = LlmRelay.start(args, endpoint, log)) {
            return runReview(workTree, sha, namingRules, new ReviewEndpoint(relay.getUrl(), relay.getSecret(), null), units, findings);
        }
    }
    private ReviewOutcome runReview(Path workTree, String sha, List<String> namingRules, ReviewEndpoint effective, Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings) throws IOException {
        StopWatch watch = StopWatch.createStarted();
        try (OpenCodeServer server = OpenCodeServer.start(args, OpenCodeReviewConfig.build(args, effective, conventionGuidance, namingRules, tags), workTree, log)) {
            try (OpenCodeClient client = new OpenCodeClient(server.getUrl(), server.getPassword(), args.getReviewTimeout())) {
                String sessionId = client.createSession(workTree, OpenCodeReviewConfig.COORDINATOR, "codiqo review " + StringUtils.left(sha, SHORT_SHA));
                List<String> forkIds = Lists.newArrayList();
                try {
                    return review(client, sessionId, forkIds, workTree, sha, units, findings, watch);
                } finally {
                    if (args.isReviewTrace()) {
                        ReviewTrace.write(args, client, sha, sessionId, forkIds, log);
                    }
                }
            }
        }
    }
    private ReviewOutcome review(OpenCodeClient client, String sessionId, List<String> forkIds, Path workTree, String sha, Optional<BuildSource<ReviewUnit>> units,
            Optional<BuildSource<StaticFinding>> findings, StopWatch watch) throws IOException {
        LocalReview review;
        try (ProgressStage stage = ProgressStage.start(args, "review")) {
            log.info("reviewing %s with %s (coordinator session %s) and %s (reviewers)", sha, args.getReviewCoordinatorModel(), sessionId, args.getReviewReviewerModel());
            client.prompt(sessionId, OpenCodeReviewConfig.reviewPrompt(sha));
            client.awaitIdle(sessionId);

            String answer = client.finalAnswer(sessionId);
            Optional<JacksonException> unreadable = unreadable(answer, args.isReviewAssess());
            if (unreadable.isPresent()) {
                /** One slip, such as an unclosed string in a long answer, must not cost a review the agents already finished. */
                log.warn("the coordinator's answer is not valid JSON (%s); asking it to correct the answer", unreadable.get().getOriginalMessage());
                client.prompt(sessionId, OpenCodeReviewConfig.repairPrompt(unreadable.get().getOriginalMessage()));
                client.awaitIdle(sessionId);
                answer = client.finalAnswer(sessionId);
            }

            List<SessionUsage> sessions = Lists.newArrayList();
            sessions.add(withConfiguredModel(client.usage(sessionId)));
            for (SessionUsage child : client.childUsage(sessionId)) {
                sessions.add(withConfiguredModel(child));
            }

            ReviewerAnswers reviewers = reviewerAnswers(client, sessions);
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
                if (args.isReviewAssess()) {
                    assessment = LlmJson.readAnswerIgnoring(answer, LlmScoringResponse.class, BLOCK_CATEGORIES);
                    assessment.setBlockCategories(reviewers.getBlockCategories());
                }
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
                    reviewers.getObservations());
            stage.detail(progressDetail(review));
            stage.succeeded();
        }

        Optional<UnitLabels> labels = Optional.empty();
        if (BooleanUtils.and(new boolean[] { units.isPresent(), args.isReviewAssess() })) {
            List<ReviewUnit> listed = awaitBuild(units.get(), "code units");
            /**
             * A failed labelling costs the labels only: the reviewers' own labels stand in, and the review is kept.
             * An interrupt is not such a failure but the cancellation of the whole review (a failed or excluded
             * build closes it): swallowed here, a review with triage went on to wait for findings the cancelled
             * build never delivers, and its OpenCode server outlived the checkout it was reading. The thread's
             * flag tells the two apart, never the exception's type: OkHttp reports a turn that outran the review
             * timeout as an InterruptedIOException too, and that is a failed labelling like any other.
             */
            try {
                labels = label(client, sessionId, forkIds, sha, listed);
            } catch (IOException err) {
                if (Thread.currentThread().isInterrupted()) {
                    throw err;
                }
                log.warn("the labelling turn failed (%s); the reviewers' own labels are used instead", err.getMessage());
            }
        }

        Optional<FindingTriage> triage = Optional.empty();
        if (findings.isPresent()) {
            triage = triage(client, sessionId, forkIds, awaitBuild(findings.get(), "static-analysis findings"));
        }
        return new ReviewOutcome(review, labels, triage);
    }
    /** Asks a fork of the coordinator's session to label the analysis's units, listed by id. */
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
    /**
     * Sends one follow-up prompt to a fork and reads its answer. Only broken JSON is sent back, once, as for the review:
     * what the answer leaves out stays out, and what it says is recorded as it is.
     */
    private <T> ForkAnswer<T> ask(OpenCodeClient client, String forkId, String prompt, Class<T> type, String name) throws IOException {
        client.prompt(forkId, prompt);
        client.awaitIdle(forkId);

        String answer = client.finalAnswer(forkId);
        T parsed;
        try {
            parsed = LlmJson.readAnswer(answer, type);
        } catch (JacksonException err) {
            log.warn("the %s answer is not valid JSON (%s); asking for a corrected answer", name, err.getOriginalMessage());
            client.prompt(forkId, OpenCodeReviewConfig.repairPrompt(err.getOriginalMessage()));
            client.awaitIdle(forkId);
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
    /**
     * Waits for what the build hands a follow-up. An interrupt is the review being cancelled: it is kept on the thread
     * and thrown as {@link InterruptedIOException}, since a future's wait clears the flag and a caller that saw only
     * "never arrived" would carry on into the next wait, which a cancelled build never ends.
     */
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
    /**
     * Every reviewer's own answer, read once: whether it answered at all, and the code units it labelled. Repeating
     * the labels through the coordinator costs an output token per label and, when it was tried, lost which unit a
     * label belonged to. The observations and the labels are read separately, so a malformed {@code blockCategories}
     * (a list of strings where objects were asked for) loses only that reviewer's labels: reading both as one
     * {@link LlmScoringResponse} dropped its observations as well and counted a reviewer that did answer as unanswered.
     */
    private ReviewerAnswers reviewerAnswers(OpenCodeClient client, List<SessionUsage> sessions) {
        Map<Pair<String, String>, LlmScoringResponse.CodeBlockCategoryView> labels = Maps.newLinkedHashMap();
        /** the coordinator is told not to repeat them, so the reviewers' own answers are the only place they exist */
        Set<String> observations = Sets.newLinkedHashSet();
        List<String> unanswered = Lists.newArrayList();
        int total = 0;
        for (SessionUsage session : sessions) {
            if (OpenCodeReviewConfig.REVIEWER.equals(session.getAgent())) {
                total++;
                String text = null;
                try {
                    text = client.finalAnswer(session.getSessionId());
                    for (String observation : CollectionUtils.emptyIfNull(LlmJson.readAnswer(text, Observations.class).getObservations())) {
                        if (StringUtils.isNotBlank(observation)) {
                            observations.add(observation.strip());
                        }
                    }
                    List<LlmScoringResponse.CodeBlockCategoryView> sessionLabels = readLabels(session.getSessionId(), text);
                    if (BooleanUtils.and(new boolean[] { args.isReviewAssess(), sessionLabels.isEmpty() })) {
                        log.warn("reviewer session %s labelled none of its code units", session.getSessionId());
                    }
                    sessionLabels.forEach(block -> labels.putIfAbsent(Pair.of(block.getFile(), block.getSignature()), block));
                } catch (IOException err) {
                    log.warn("reviewer session %s ended with no answer at all: %s", session.getSessionId(), err.getMessage());
                    unanswered.add(session.getSessionId());
                } catch (JacksonException err) {
                    log.warn("reviewer session %s answered something that is not the JSON object (%s), starting: %s",
                            session.getSessionId(),
                            err.getOriginalMessage(),
                            StringUtils.abbreviate(text, RunArgs.REVIEW_ANSWER_PREVIEW));
                    unanswered.add(session.getSessionId());
                }
            }
        }
        return new ReviewerAnswers(total, unanswered, Lists.newArrayList(labels.values()),
                observations.stream().limit(args.getReviewMaxObservations()).toList());
    }
    private List<LlmScoringResponse.CodeBlockCategoryView> readLabels(String sessionId, String answer) {
        try {
            return CollectionUtils.emptyIfNull(LlmJson.readAnswer(answer, LlmScoringResponse.class).getBlockCategories()).stream().toList();
        } catch (JacksonException err) {
            log.warn("reviewer session %s labelled its code units in an unreadable shape (%s); its labels are left out", sessionId, err.getOriginalMessage());
            return List.of();
        }
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
     * review, its optional repair round and, for each follow-up after the build (the labelling of an assessing review,
     * the triage), its prompt and optional repair round. The other requests
     * read finished sessions and return at once. A caller that allowed only one review timeout failed the build while
     * every turn was still inside its own limit, for example a 28-minute review followed by a 6-minute triage.
     */
    public static Duration longestReview(RunArgs args, boolean labelling, boolean triage) {
        int turns = RunArgs.REVIEW_TURNS;
        if (labelling) {
            turns += RunArgs.REVIEW_FOLLOW_UP_TURNS;
        }
        if (triage) {
            turns += RunArgs.REVIEW_FOLLOW_UP_TURNS;
        }
        return args.getReviewStartupTimeout().plus(args.getReviewTimeout().multipliedBy(turns));
    }
    /** a severity the coordinator left out, or wrote as null, is a severity with no findings */
    @VisibleForTesting
    public static LlmScoringResponse.Bugs readBugs(String answer) {
        LlmScoringResponse.Bugs toReturn = LlmJson.readAnswer(answer, LlmScoringResponse.Bugs.class);
        toReturn.setBlocking(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getBlocking())));
        toReturn.setMajor(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getMajor())));
        toReturn.setMinor(Lists.newArrayList(CollectionUtils.emptyIfNull(toReturn.getMinor())));
        return toReturn;
    }
    private static Optional<JacksonException> unreadable(String answer, boolean assess) {
        try {
            LlmJson.readAnswer(answer, LlmScoringResponse.Bugs.class);
            if (assess) {
                LlmJson.readAnswerIgnoring(answer, LlmScoringResponse.class, BLOCK_CATEGORIES);
            }
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
    }

    @Data
    @NoArgsConstructor
    public static class Observations {
        private List<String> observations = Lists.newArrayList();
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
