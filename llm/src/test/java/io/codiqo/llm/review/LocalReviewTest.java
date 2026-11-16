package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whole local reviews of one commit, run by scripted agents ({@link ScriptedAgents}) through the review's real answer
 * server and relay: the coordinator's turn and its reviewers, the labelling and triage forks once the build hands over
 * its units and findings, the verdict each way a review ends makes, and the trace every review leaves.
 */
class LocalReviewTest {
    private static final ReviewUnit UNIT = new ReviewUnit("U1", "a/Service.java", "handle(Request)", "MODIFY", "12-30");
    private static final StaticFinding FINDING = new StaticFinding("pmd", "GodClass", "warning", "a/Service.java", 3, "possible God Class");
    private static final String REVIEW = "{\"unitLabels\":[{\"file\":\"a/Service.java\",\"signature\":\"handle(Request)\",\"category\":\"ROUTINE\",\"reason\":\"glue\"}],"
            + "\"observations\":[\"the handler validates its request\"],\"notChecked\":[]}";
    private static final String ASSESSMENT = "{\"tasks\":[{\"paths\":[\"a/Service.java\"]}],\"summary\":\"Adds a request handler.\"}";
    private static final String LABELS = "{\"labels\":[{\"id\":\"U1\",\"category\":\"ROUTINE\",\"reason\":\"glue\"}]}";
    private static final String TRIAGE = "{\"verdicts\":[{\"tool\":\"pmd\",\"rule\":\"GodClass\",\"file\":\"a/Service.java\",\"line\":3,\"verdict\":\"harmless\",\"reason\":\"one small class\"}]}";
    private static final TurnEnd RATE_LIMITED = new TurnEnd.Failed("provider.rate-limit", "stream cut", false);

    private final JsonMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path repository;

    @TempDir
    Path traces;

    private UnavailableProvider provider;

    @BeforeEach
    void start() throws Exception {
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Path service = repository.resolve(UNIT.getPath());
            Files.createDirectories(service.getParent());
            Files.writeString(service, "class Service {\n    void handle(Request request) {\n    }\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add the service").setSign(false).call();
        }

        provider = UnavailableProvider.start();
    }
    @AfterEach
    void stop() {
        provider.close();
    }
    @Test
    void aReviewLabelsAndTriagesOnceTheBuildHandsOverItsUnitsAndFindings() throws Exception {
        ScriptedAgents agents = new ScriptedAgents(
                coordinator(1),
                turn -> answered(turn, AnswerTool.LABELS, LABELS),
                turn -> answered(turn, AnswerTool.TRIAGE, TRIAGE));

        ReviewVerdict.Succeeded succeeded = assertInstanceOf(ReviewVerdict.Succeeded.class, review(agents));

        LocalReviewer.ReviewOutcome outcome = succeeded.getOutcome();
        assertEquals(1, outcome.getReview().getReviewers());
        assertEquals(List.of("the handler validates its request"), outcome.getReview().getObservations());
        assertEquals(List.of("U1"), outcome.getLabels().orElseThrow().getLabels().stream().map(UnitLabels.UnitLabel::getId).toList());
        assertEquals(List.of(FindingVerdict.Verdict.HARMLESS), outcome.getTriage().orElseThrow().getVerdicts().stream().map(FindingVerdict::getVerdict).toList());
        assertEquals(3, agents.prompts.size(), "the review, the labelling and the triage");
        assertEquals(4, sessionsTraced().size(), "the coordinator, its reviewer and both forks");
        assertTrue(agents.isClosed());
        agents.assertEveryStepRan();
    }
    /** a coordinator that reviewed the commit itself reviewed none of it the way the review asks */
    @Test
    void aCoordinatorThatStartedNoReviewerFailsOnTheCommitAndLeavesItsTrace() throws Exception {
        ScriptedAgents agents = new ScriptedAgents(coordinator(0));

        ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class, review(agents));

        assertEquals("the coordinator started no reviewer, so nothing of the commit was reviewed", failed.getError().getMessage());
        assertEquals(1, sessionsTraced().size());
        assertTrue(agents.isClosed());
    }
    /** a reviewer cut off at its step cap left its area unreviewed: the relay's count decides whose failure that is */
    @Test
    void aReviewerCutOffIsTheProvidersFailureOnlyWhenTheProviderFailed() throws Exception {
        ReviewVerdict.CommitFailure unanswered = assertInstanceOf(ReviewVerdict.CommitFailure.class, review(new ScriptedAgents(reviewerCutOff(false))));
        assertTrue(unanswered.getError().getMessage().startsWith("1 of 2 reviewer sessions ended without an answer, so their areas went unreviewed"),
                unanswered.getError().getMessage());

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, review(new ScriptedAgents(reviewerCutOff(true))));
        assertEquals("the LLM provider answered 503 once during the review", failed.getProviderSummary());
    }
    /**
     * A reviewer the provider failed halts the review, so the commit is reviewed again rather than silently excluded, even
     * when a reviewer listed before it failed on its answer.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void aReviewerTheProviderFailedSettlesTheReviewersWhicheverOrderTheyAreListedIn(boolean refusedFirst) throws Exception {
        ScriptedAgents agents = new ScriptedAgents(turn -> {
            String first = turn.startReviewer();
            String second = turn.startReviewer();
            String refused = refusedFirst ? first : second;
            turn.call(refused, AnswerTool.REVIEW.getToolName(), "{}");
            turn.ends(refusedFirst ? second : first, RATE_LIMITED);
            turn.answer(AnswerTool.ANSWER, ASSESSMENT);
            return TurnEnd.Ending.STOPPED_ON_ITS_OWN;
        });

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, review(agents));

        assertTrue(failed.getError().getMessage().endsWith("ended its turn with provider.rate-limit: stream cut"), failed.getError().getMessage());
    }
    @Test
    void aRefusedLabellingAnswerFailsOnTheCommitAndNothingIsTriaged() throws Exception {
        ScriptedAgents agents = new ScriptedAgents(coordinator(1), turn -> {
            turn.call(turn.sessionId, AnswerTool.LABELS.getToolName(), "{\"labels\":[]}");
            return TurnEnd.Ending.STOPPED_ON_ITS_OWN;
        });

        ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class, review(agents));

        assertTrue(failed.getError().getMessage().contains("codiqo_submit_labels answer was refused: not one label per listed unit"), failed.getError().getMessage());
        assertEquals(2, agents.prompts.size(), "the triage is never asked for");
        assertEquals(3, sessionsTraced().size());
    }
    /**
     * A fork that did not end its turn within the review timeout gave no answer: the review is unanswered, nothing after it
     * is asked for, and the review still leaves its trace and stops its agents.
     */
    @Test
    void aForkThatRanOutOfTimeIsUnansweredAndNothingIsTriaged() throws Exception {
        ScriptedAgents agents = new ScriptedAgents(coordinator(1), turn -> {
            turn.runsOutOfTime();
            return TurnEnd.Ending.CUT_OFF;
        });

        ReviewVerdict.CommitFailure unanswered = assertInstanceOf(ReviewVerdict.CommitFailure.class, review(agents));

        assertTrue(unanswered.getError().getMessage().endsWith("did not end its turn within " + new RunArgs().getReviewTimeout()), unanswered.getError().getMessage());
        assertEquals(2, agents.prompts.size(), "the triage is never asked for");
        assertEquals(3, sessionsTraced().size());
        assertTrue(agents.isClosed());
    }
    /** the closing sentence after an accepted answer is one more call to the model, and its failure loses nothing */
    @Test
    void anAnswerGivenBeforeTheTurnFailedStands() throws Exception {
        ScriptedAgents agents = new ScriptedAgents(coordinator(1), turn -> {
            answered(turn, AnswerTool.LABELS, LABELS);
            return RATE_LIMITED;
        }, turn -> answered(turn, AnswerTool.TRIAGE, TRIAGE));

        assertInstanceOf(ReviewVerdict.Succeeded.class, review(agents));
    }
    /** an agent that never got to correct a refused answer failed on the provider, not on its answer */
    @Test
    void aProviderErrorAfterARefusedCallIsTheTurnsError() throws Exception {
        ScriptedAgents agents = new ScriptedAgents(coordinator(1), turn -> {
            turn.call(turn.sessionId, AnswerTool.LABELS.getToolName(), "{\"labels\":[]}");
            return RATE_LIMITED;
        });

        ReviewVerdict.ProviderFailure failed = assertInstanceOf(ReviewVerdict.ProviderFailure.class, review(agents));

        assertTrue(failed.getError().getMessage().endsWith("ended its turn with provider.rate-limit: stream cut"), failed.getError().getMessage());
    }
    /**
     * A labelling or triage turn runs after the coordinator's: a provider failure during an earlier turn, which OpenCode
     * retried, does not make this turn's lost answer the provider's, though the error still names it.
     */
    @Test
    void aForkCutOffAfterAnEarlierTurnsProviderFailureIsTheCommits() throws Exception {
        ScriptedAgents agents = new ScriptedAgents(turn -> {
            turn.callTheModel();
            return coordinator(1).act(turn);
        }, turn -> TurnEnd.Ending.CUT_OFF);

        ReviewVerdict.CommitFailure unanswered = assertInstanceOf(ReviewVerdict.CommitFailure.class, review(agents));

        assertTrue(unanswered.getReason().endsWith("was cut off before it called codiqo_submit_labels, so it gave no answer; the LLM provider answered 503 once during the review"),
                unanswered.getReason());
    }
    /**
     * An answer that could not be read on Codiqo's side is the commit's failure, never the provider's, even when the relay
     * counted a provider error during the review: decided by that count, one retried 429 turned it into a halt.
     */
    @Test
    void anAnswerCodiqoCouldNotReadIsTheCommitsFailureWhateverTheProviderDid() throws Exception {
        RunArgs args = args();
        try (LlmRelay relay = LlmRelay.start(args, provider.endpoint(), new NoopLog()); ReviewAnswers answers = ReviewAnswers.start(args, new NoopLog())) {
            ScriptedAgents agents = new ScriptedAgents(turn -> {
                answers.answerCall(turn.sessionId, AnswerTool.LABELS.getToolName(), mapper.readTree(LABELS));
                turn.callTheModel();
                return TurnEnd.Ending.STOPPED_ON_ITS_OWN;
            });
            try (ReviewSessions sessions = agents.start(repository, new ReviewEndpoint(relay.getUrl(), relay.getSecret(), null), answers.endpoint(), List.of())) {
                String sessionId = sessions.createSession(OpenCodeReviewConfig.COORDINATOR, "labels");
                answers.expect(sessionId, AnswerTool.LABELS, arguments -> {
                    throw new IllegalArgumentException("index out of date");
                });

                ReviewVerdict.CommitFailure failed = assertInstanceOf(ReviewVerdict.CommitFailure.class,
                        reviewer(args, agents).ask(sessions, answers, relay, sessionId, "Label the units.", AnswerTool.LABELS).orElseThrow());

                assertEquals("index out of date", failed.getError().getCause().getCause().getMessage());
                assertTrue(failed.getReason().endsWith("; the LLM provider answered 503 once during the review"), failed.getReason());
            }
        }
    }
    private ReviewVerdict review(ScriptedAgents agents) {
        return reviewer(args(), agents).reviewThenFollowUp(repository, "HEAD", Optional.of(() -> List.of(UNIT)), Optional.of(() -> List.of(FINDING)));
    }
    private LocalReviewer reviewer(RunArgs args, ScriptedAgents agents) {
        return new LocalReviewer(args, provider.endpoint(), List.of(), List.of(), new NoopLog(), agents);
    }
    private RunArgs args() {
        RunArgs toReturn = new RunArgs();
        toReturn.setReviewTraceDirectory(traces.toFile());
        return toReturn;
    }
    /** the sessions the review's one trace holds */
    private List<JsonNode> sessionsTraced() throws IOException {
        File[] written = traces.toFile().listFiles();
        assertEquals(1, written.length);
        return mapper.readTree(written[0]).path("sessions").valueStream().toList();
    }
    /** a coordinator that starts the given number of reviewers, each of which reviews its area, and then answers */
    private static ScriptedAgents.Step coordinator(int reviewers) {
        return turn -> {
            for (int i = 0; i < reviewers; i++) {
                turn.call(turn.startReviewer(), AnswerTool.REVIEW.getToolName(), REVIEW);
            }
            turn.answer(AnswerTool.ANSWER, ASSESSMENT);
            return TurnEnd.Ending.STOPPED_ON_ITS_OWN;
        };
    }
    /** two reviewers, the second cut off at its step cap without an answer, after the provider failed a call or not */
    private static ScriptedAgents.Step reviewerCutOff(boolean providerFailed) {
        return turn -> {
            turn.call(turn.startReviewer(), AnswerTool.REVIEW.getToolName(), REVIEW);
            turn.ends(turn.startReviewer(), TurnEnd.Ending.CUT_OFF);
            if (providerFailed) {
                turn.callTheModel();
            }
            turn.answer(AnswerTool.ANSWER, ASSESSMENT);
            return TurnEnd.Ending.STOPPED_ON_ITS_OWN;
        };
    }
    private static TurnEnd answered(ScriptedAgents.Turn turn, AnswerTool tool, String arguments) throws IOException {
        turn.answer(tool, arguments);
        return TurnEnd.Ending.STOPPED_ON_ITS_OWN;
    }
}
