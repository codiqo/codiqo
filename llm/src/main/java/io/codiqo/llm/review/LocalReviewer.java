package io.codiqo.llm.review;

import java.io.IOException;
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
import org.apache.commons.lang3.tuple.ImmutablePair;
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
    private final List<ReviewLanguage> languages;
    private final Log log;

    public LocalReviewer(RunArgs args, ReviewEndpoint endpoint, String conventionGuidance, List<ReviewLanguage> languages, Log log) {
        this.args = Objects.requireNonNull(args);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.conventionGuidance = Objects.requireNonNull(conventionGuidance);
        this.languages = List.copyOf(languages);
        this.log = Objects.requireNonNull(log);
    }
    public LocalReview review(Path directory, String commit) throws IOException {
        return reviewThenTriage(directory, commit, Optional.empty()).getReview();
    }
    /**
     * Reviews the commit, then, while the review's server is still up, waits for the build's static-analysis findings
     * and asks a fork of the coordinator's session which of them are real defects. The fork keeps everything the
     * coordinator read, and the server's home (with every session) is deleted when the review closes, so the triage has
     * to happen here and not in a later run.
     */
    public ReviewAndTriage reviewThenTriage(Path directory, String commit, Optional<FindingsSource> findings) throws IOException {
        ReviewTarget target = target(directory, commit);
        return runReview(target.getWorkTree(), target.getSha(), target.getNamingRules(), findings);
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
    private ReviewAndTriage runReview(Path workTree, String sha, List<String> namingRules, Optional<FindingsSource> findings) throws IOException {
        if (Objects.nonNull(endpoint.getAuthorizer())) {
            try (LlmRelay relay = LlmRelay.start(args, endpoint, log)) {
                return runReview(workTree, sha, namingRules, new ReviewEndpoint(relay.getUrl(), relay.getSecret(), null), findings);
            }
        }
        return runReview(workTree, sha, namingRules, endpoint, findings);
    }
    private ReviewAndTriage runReview(Path workTree, String sha, List<String> namingRules, ReviewEndpoint effective, Optional<FindingsSource> findings) throws IOException {
        StopWatch watch = StopWatch.createStarted();
        try (OpenCodeServer server = OpenCodeServer.start(args, OpenCodeReviewConfig.build(args, effective, conventionGuidance, namingRules), workTree, log)) {
            try (OpenCodeClient client = new OpenCodeClient(server.getUrl(), server.getPassword(), args.getReviewTimeout())) {
                String sessionId = client.createSession(workTree, OpenCodeReviewConfig.COORDINATOR, "codiqo review " + StringUtils.left(sha, SHORT_SHA));
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

                Optional<FindingTriage> triage = Optional.empty();
                if (findings.isPresent()) {
                    triage = triage(client, sessionId, awaitFindings(findings.get()));
                }
                return new ReviewAndTriage(review, triage);
            }
        }
    }
    private Optional<FindingTriage> triage(OpenCodeClient client, String sessionId, List<StaticFinding> findings) throws IOException {
        if (CollectionUtils.isEmpty(findings)) {
            log.info("no static-analysis findings on the commit's added lines; nothing to triage");
            return Optional.empty();
        }

        FindingTriage toReturn;
        try (ProgressStage stage = ProgressStage.start(args, "triage")) {
            String forkId = client.fork(sessionId);
            log.info("triaging %d static-analysis findings in session %s, a fork of %s", findings.size(), forkId, sessionId);
            client.prompt(forkId, OpenCodeReviewConfig.triagePrompt(findings));
            client.awaitIdle(forkId);

            String answer = client.finalAnswer(forkId);
            try {
                toReturn = LlmJson.readAnswer(answer, FindingTriage.class);
            } catch (JacksonException err) {
                log.warn("the triage answer is not valid JSON (%s); asking for a corrected answer", err.getOriginalMessage());
                client.prompt(forkId, OpenCodeReviewConfig.repairPrompt(err.getOriginalMessage()));
                client.awaitIdle(forkId);
                answer = client.finalAnswer(forkId);
                try {
                    toReturn = LlmJson.readAnswer(answer, FindingTriage.class);
                } catch (JacksonException again) {
                    throw new IOException("the triage answer holds no verdicts: " + StringUtils.abbreviate(answer, RunArgs.REVIEW_ANSWER_PREVIEW), again);
                }
            }
            toReturn.setAnswer(answer);
            toReturn.setUsage(withConfiguredModel(client.usage(forkId)));
            toReturn.setFindings(findings);
            stage.detail(String.format("%d verdicts on %d findings", toReturn.getVerdicts().size(), findings.size()));
            stage.succeeded();
        }
        return Optional.of(toReturn);
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
    private static List<StaticFinding> awaitFindings(FindingsSource source) throws IOException {
        try {
            return source.await();
        } catch (IOException err) {
            throw err;
        } catch (Exception err) {
            throw new IOException("the build's static-analysis findings never arrived", err);
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
     * review, its optional repair round and, with triage, the triage and its optional repair round. The other requests
     * read finished sessions and return at once. A caller that allowed only one review timeout failed the build while
     * every turn was still inside its own limit, for example a 28-minute review followed by a 6-minute triage.
     */
    public static Duration longestReview(RunArgs args, boolean triage) {
        int turns = triage ? RunArgs.REVIEW_TURNS_WITH_TRIAGE : RunArgs.REVIEW_TURNS;
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
    public interface FindingsSource {
        List<StaticFinding> await() throws Exception;
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
    public static final class ReviewAndTriage extends ImmutablePair<LocalReview, Optional<FindingTriage>> {
        private ReviewAndTriage(LocalReview review, Optional<FindingTriage> triage) {
            super(review, triage);
        }
        public LocalReview getReview() {
            return getLeft();
        }
        public Optional<FindingTriage> getTriage() {
            return getRight();
        }
    }
}
