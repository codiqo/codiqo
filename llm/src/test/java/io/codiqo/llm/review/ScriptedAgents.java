package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;

import io.codiqo.util.RequestAuthorizer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Agents that act out a script in place of OpenCode, for tests of a whole local review. Each turn the review sends runs
 * the next scripted step, in the order the review sends them. A step answers through the review's own answer server, over
 * MCP with the calling session named as OpenCode names it, starts reviewers and calls the model through the review's
 * relay, at the end points the review starts its agents with. The sessions keep what OpenCode would record of them: the
 * agent, the children, the prompts and how each last turn ended.
 */
class ScriptedAgents implements ReviewAgents {
    /** how much of an unscripted prompt a failure shows */
    private static final int PROMPT_PREVIEW = 80;

    /** every prompt the review sent, in order */
    final List<String> prompts = Lists.newArrayList();

    private final Deque<Step> steps;
    private final Map<String, Session> sessions = Maps.newLinkedHashMap();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private ReviewEndpoint model;
    private ReviewAnswers.Endpoint answers;
    private boolean closed;
    private int nextId;

    ScriptedAgents(Step... steps) {
        this.steps = Queues.newArrayDeque(List.of(steps));
    }
    @Override
    public ReviewSessions start(Path workTree, ReviewEndpoint model, ReviewAnswers.Endpoint answers, List<String> namingRules) {
        this.model = model;
        this.answers = answers;
        return new Sessions();
    }
    /** whether the review stopped its agents */
    boolean isClosed() {
        return closed;
    }
    /** every step the test scripted was run */
    void assertEveryStepRan() {
        assertEquals(List.of(), Lists.newArrayList(steps), "steps the review never ran");
    }
    private String newSession(String agent, Optional<String> parent) {
        String toReturn = "ses_" + (++nextId) + "_" + agent;
        sessions.put(toReturn, new Session(agent, parent));
        return toReturn;
    }

    /** what one turn of a session does */
    @FunctionalInterface
    interface Step {
        TurnEnd act(Turn turn) throws IOException;
    }

    /** the turn a step acts in */
    final class Turn {
        final String sessionId;
        final String prompt;
        private boolean outOfTime;

        private Turn(String sessionId, String prompt) {
            this.sessionId = sessionId;
            this.prompt = prompt;
        }
        /** starts a reviewer sub-agent of this turn's session, which ends its turn on its own unless told otherwise */
        String startReviewer() {
            return newSession(OpenCodeReviewConfig.REVIEWER, Optional.of(sessionId));
        }
        /** how a reviewer's turn ended, for one that did not stop on its own */
        void ends(String reviewerId, TurnEnd end) {
            sessions.get(reviewerId).lastTurn = end;
        }
        /** this turn does not end within the review timeout */
        void runsOutOfTime() {
            outOfTime = true;
        }
        /** calls a tool of the answer server as the given session and returns the tool's result */
        JsonNode call(String callerId, String tool, String arguments) throws IOException {
            ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", ++nextId).put("method", "tools/call");
            ObjectNode params = request.putObject("params").put("name", tool);
            params.set("arguments", mapper.readTree(arguments));
            params.putObject("_meta").put(ReviewAnswers.SESSION_META, callerId);

            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(answers.getUrl()))
                    .header(HttpHeaderNames.AUTHORIZATION.toString(), RequestAuthorizer.bearer(answers.getSecret()))
                    .header(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString())));
            assertEquals(HttpResponseStatus.OK.code(), response.statusCode(), response.body());
            return mapper.readTree(response.body()).path("result");
        }
        /** answers this turn with its tool, after reading the reviewers' reports as a coordinator must */
        void answer(AnswerTool tool, String arguments) throws IOException {
            if (tool == AnswerTool.ANSWER) {
                call(sessionId, ReviewAnswers.REVIEWS_TOOL, "{}");
            }
            assertFalse(call(sessionId, tool.getToolName(), arguments).path("isError").asBoolean(), tool + " refused " + arguments);
        }
        /** one call to the model, which the provider behind the review's relay fails ({@link UnavailableProvider}) */
        void callTheModel() throws IOException {
            UnavailableProvider.callThrough(model.getBaseUrl(), model.getApiKey());
        }
        private HttpResponse<String> send(HttpRequest.Builder request) throws IOException {
            try {
                return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
                InterruptedIOException toThrow = new InterruptedIOException("interrupted while a scripted agent called " + request.build().uri());
                toThrow.initCause(err);
                throw toThrow;
            }
        }
    }

    private static final class Session {
        private final String agent;
        private final Optional<String> parent;
        private final List<String> prompts = Lists.newArrayList();
        private TurnEnd lastTurn = TurnEnd.Ending.STOPPED_ON_ITS_OWN;
        private Optional<String> pending = Optional.empty();

        private Session(String agent, Optional<String> parent) {
            this.agent = agent;
            this.parent = parent;
        }
    }

    private final class Sessions implements ReviewSessions {
        @Override
        public String createSession(String agent, String title) {
            return newSession(agent, Optional.empty());
        }
        @Override
        public String fork(String sessionId) {
            return newSession(sessions.get(sessionId).agent, Optional.empty());
        }
        @Override
        public void prompt(String sessionId, String text) {
            prompts.add(text);
            sessions.get(sessionId).prompts.add(text);
            sessions.get(sessionId).pending = Optional.of(text);
        }
        @Override
        public boolean awaitTurn(String sessionId) throws IOException {
            String prompt = sessions.get(sessionId).pending.orElseThrow(() -> new AssertionError("session " + sessionId + " was waited for without a prompt"));
            sessions.get(sessionId).pending = Optional.empty();
            assertFalse(steps.isEmpty(), "the review sent a turn the test did not script: " + StringUtils.abbreviate(prompt, PROMPT_PREVIEW));

            Turn turn = new Turn(sessionId, prompt);
            sessions.get(sessionId).lastTurn = steps.pop().act(turn);
            return BooleanUtils.negate(turn.outOfTime);
        }
        @Override
        public TurnEnd lastTurn(String sessionId) {
            return sessions.get(sessionId).lastTurn;
        }
        @Override
        public SessionUsage usage(String sessionId) {
            return new SessionUsage(sessionId, sessions.get(sessionId).agent, StringUtils.EMPTY, 0, 0, 0, 0);
        }
        @Override
        public List<SessionUsage> children(String sessionId) {
            return sessions.entrySet().stream()
                    .filter(session -> session.getValue().parent.filter(sessionId::equals).isPresent())
                    .map(session -> usage(session.getKey()))
                    .toList();
        }
        @Override
        public ObjectNode trace(String sessionId) {
            ObjectNode toReturn = mapper.createObjectNode();
            toReturn.putObject("session").put("id", sessionId).put("agent", sessions.get(sessionId).agent);
            ArrayNode messages = toReturn.putArray("messages");
            sessions.get(sessionId).prompts.forEach(prompt -> messages.addObject().put("type", "user").put("text", prompt));
            return toReturn;
        }
        @Override
        public void close() {
            closed = true;
            http.close();
        }
    }
}
