package io.codiqo.llm.review;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import tools.jackson.databind.node.ObjectNode;

/**
 * The sessions of one local review's agents, as the review drives them: the coordinator's, the reviewers it starts and
 * the forks that label and triage. Every answer comes through the answer server ({@link ReviewAnswers}), never through a
 * session, so a session is only prompted, waited for and read. {@link OpenCodeSessions} runs them in OpenCode; a test
 * runs scripted ones. Closing stops the agents and deletes everything they recorded, so the trace is read before.
 */
public interface ReviewSessions extends Closeable {
    /** Creates a session of the named agent in the reviewed work tree and returns its id. */
    String createSession(String agent, String title) throws IOException;
    /** Creates a session that starts from everything the given session holds and returns its id. */
    String fork(String sessionId) throws IOException;
    /** Sends the prompt as the session's next turn, without waiting for the turn to end. */
    void prompt(String sessionId, String text) throws IOException;
    /**
     * Waits until the session's turn ended, every sub-agent it started included: false when it did not end within the
     * review timeout. A wait that failed sooner, an interrupt among them, is thrown.
     */
    boolean awaitTurn(String sessionId) throws IOException;
    /** How the session's last turn ended, read once the session is idle. */
    TurnEnd lastTurn(String sessionId) throws IOException;
    SessionUsage usage(String sessionId) throws IOException;
    /** The sessions the given one started, the reviewers of a coordinator, with what each used. */
    List<SessionUsage> children(String sessionId) throws IOException;
    /**
     * The session as the agents recorded it, for the trace the commit page shows: its own record as {@code session} and
     * every message, oldest first, as {@code messages}, each tool's output cut short enough for the trace to be sent.
     */
    ObjectNode trace(String sessionId) throws IOException;
    @Override
    void close();
}
