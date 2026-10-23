package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.NoopLog;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A turn is sent once, and how it ended is settled with the answer the session gave through its tool. OpenCode records
 * a turn it gave up as idle, its newest assistant message finished with {@code error}; such a turn fails the review
 * with that error rather than being prompted again, unless the answer was given before it.
 */
class TurnTest {
    private static final String SESSION = "ses_coordinator";
    private static final String PROMPT = "Review commit 1a2b3c of this repository.";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> prompts = Lists.newCopyOnWriteArrayList();
    private HttpServer server;
    private ReviewAnswers answers;
    private String url;
    /** the error type the turn ends with, or none for a turn that ended on its own */
    private Optional<String> error = Optional.empty();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        url = RunArgs.loopbackUrl(server.getAddress().getPort()).build().toString();
        answers = ReviewAnswers.start(new RunArgs(), new NoopLog());
        answers.expect(SESSION, AnswerTool.LABELS, arguments -> {
            if (arguments.path("labels").isEmpty()) {
                throw new ReviewAnswers.RefusedAnswerException("not labelled: [U1]");
            }
            return arguments;
        });
    }
    @AfterEach
    void stop() {
        server.stop(0);
        answers.close();
    }
    @Test
    void aTurnIsSentOnce() throws Exception {
        answer("{\"labels\":[{\"id\":\"U1\"}]}");

        assertTrue(answerOf().isPresent());
        assertEquals(List.of(PROMPT), prompts);
    }
    @Test
    void aTurnThatEndedWithAnErrorFailsWithIt() {
        error = Optional.of("provider.invalid-output");

        IOException err = assertThrows(IOException.class, this::answerOf);

        assertEquals("session ses_coordinator ended its turn with provider.invalid-output: stream cut", err.getMessage());
        assertEquals(List.of(PROMPT), prompts, "the turn is not prompted again");
    }
    /** the closing sentence after an accepted answer is one more call to the model, and its failure loses nothing */
    @Test
    void anAnswerGivenBeforeTheTurnFailedStands() throws Exception {
        answer("{\"labels\":[{\"id\":\"U1\"}]}");
        error = Optional.of("provider.rate-limit");

        assertTrue(answerOf().isPresent());
    }
    /** an agent that never got to correct a refused answer failed on the provider, not on its answer */
    @Test
    void aProviderErrorAfterARefusedCallIsTheTurnsError() {
        answer("{\"labels\":[]}");
        error = Optional.of("provider.rate-limit");

        IOException err = assertThrows(IOException.class, this::answerOf);

        assertEquals("session ses_coordinator ended its turn with provider.rate-limit: stream cut", err.getMessage());
    }
    @Test
    void aRefusedAnswerFailsOnItsAnswer() {
        answer("{\"labels\":[]}");

        IOException err = assertThrows(IOException.class, this::answerOf);

        assertEquals("session ses_coordinator's codiqo_submit_labels answer was refused: not labelled: [U1]", err.getMessage());
    }
    /** the prose-only answers that failed reviews before: a turn the model ended on its own without the call */
    @Test
    void aTurnThatStoppedWithoutTheCallFailsOnItsAnswer() {
        IOException err = assertThrows(IOException.class, this::answerOf);

        assertEquals("session ses_coordinator ended its turn without calling codiqo_submit_labels, so it gave no answer", err.getMessage());
    }
    /**
     * An answer that could not be read on Codiqo's side is the commit's failure, never the provider's, even when the
     * relay counted a provider error during the review: decided by that count, one retried 429 turned it into a halt.
     */
    @Test
    void anAnswerCodiqoCouldNotReadIsTheCommitsFailureWhateverTheProviderDid() {
        answers.expect(SESSION, AnswerTool.LABELS, arguments -> {
            throw new IllegalArgumentException("index out of date");
        });
        answer("{\"labels\":[{\"id\":\"U1\"}]}");

        IOException err = assertThrows(IOException.class, this::answerOf);

        assertEquals("index out of date", err.getCause().getCause().getMessage());
        assertFalse(LocalReviewer.isProviderFailure(err, true), "a provider error during the review does not make it the provider's");
    }
    private void answer(String arguments) {
        answers.answerCall(SESSION, AnswerTool.LABELS.getToolName(), mapper.readTree(arguments));
    }
    private Optional<ReviewAnswers.Accepted<JsonNode>> answerOf() throws IOException {
        try (OpenCodeClient client = new OpenCodeClient(url, "pw", Duration.ofSeconds(10))) {
            LocalReviewer.turn(client, SESSION, PROMPT);
            return LocalReviewer.answerOf(client, answers, SESSION, AnswerTool.LABELS, JsonNode.class);
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        String body = "{}";
        if (route.equals("POST /api/session/" + SESSION + "/prompt")) {
            prompts.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("text").asString());
        } else if (route.equals("GET /api/session/" + SESSION + "/message")) {
            body = messages();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(HttpResponseStatus.OK.code(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
    /** the session's one turn, oldest first: the prompt, then an assistant message that stopped or failed */
    private String messages() {
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("type", "assistant");
        if (error.isPresent()) {
            message.put("finish", "error");
            message.putObject("error").put("type", error.get()).put("message", "stream cut");
        } else {
            message.put("finish", "stop").put("text", "All units are labelled.");
        }
        ObjectNode toReturn = JsonNodeFactory.instance.objectNode();
        toReturn.putArray("data").add(JsonNodeFactory.instance.objectNode().put("type", "user").put("text", PROMPT)).add(message);
        toReturn.putObject("cursor").putNull("next");
        return toReturn.toString();
    }
}
