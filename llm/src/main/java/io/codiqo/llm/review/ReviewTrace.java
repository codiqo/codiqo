package io.codiqo.llm.review;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every session a review ran, written as the agents recorded it before closing them deletes the record (OpenCode's home,
 * and with it the session database): the coordinator, its reviewer sub-agents and the forks that labelled and triaged,
 * each with its messages in order, every tool call's input, status and output included. It is for reading what the
 * agents did: the submission sends it to the server, which shows it on the commit page; nothing judges by it.
 */
@UtilityClass
public class ReviewTrace {
    private static final String FILE_PREFIX = "codiqo-review-trace-";
    private static final int SHORT_SHA = 8;
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /**
     * Writes the trace even while the review is being cancelled: the interrupt is held back for the few local reads it
     * takes, since a cancelled review is often the one worth reading, and restored afterwards. An earlier run's trace of
     * the same commit is deleted first, so it can never pass for this one's.
     */
    public void write(RunArgs args, ReviewSessions reviewSessions, String sha, String coordinatorId, Collection<String> forkIds, Log log) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            Files.deleteIfExists(file(args, sha).toPath());

            /** the sub-agents are listed here rather than taken from the review, which never lists them when it fails */
            List<String> sessionIds = Lists.newArrayList(coordinatorId);
            reviewSessions.children(coordinatorId).forEach(child -> sessionIds.add(child.getSessionId()));
            sessionIds.addAll(forkIds);

            ObjectNode trace = MAPPER.createObjectNode();
            trace.put("commit", sha);
            ArrayNode sessions = trace.putArray("sessions");
            for (String sessionId : sessionIds) {
                sessions.add(reviewSessions.trace(sessionId));
            }

            File file = file(args, sha);
            FileUtils.forceMkdir(file.getParentFile());
            MAPPER.writeValue(file, trace);
            log.info("review trace of %d sessions written to %s", sessionIds.size(), file);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
    /** where {@link #write} puts the trace of a commit's review, so the submission can send it to the server */
    public File file(RunArgs args, String sha) {
        File directory = Optional.ofNullable(args.getReviewTraceDirectory()).orElse(FileUtils.getTempDirectory());
        return new File(directory, FILE_PREFIX + StringUtils.left(sha, SHORT_SHA) + ".json");
    }
}
