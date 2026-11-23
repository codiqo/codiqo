package io.codiqo.llm.review;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

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
     * Waits until the session's turn ended, every sub-agent it started included, and every run their reports woke it for:
     * false when it did not end within the review timeout. A shell command one of its sessions runs in the background
     * holds an unanswered turn open until its result has woken that session, and is stopped once it has run the review's
     * limit. Once {@code answered} holds, the turn ended as soon as none of its sessions runs, whatever command is left.
     * A wait that failed sooner, an interrupt among them, is thrown.
     */
    boolean awaitTurn(String sessionId, BooleanSupplier answered) throws IOException;
    /**
     * The sub-agents the session started that are still running, or that wait for a shell command running in the
     * background: a coordinator's answer waits for those that have not reviewed yet, since a sub-agent that runs in the
     * background reports after the coordinator's response ended.
     */
    Set<String> runningSubagents(String sessionId) throws IOException;
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
