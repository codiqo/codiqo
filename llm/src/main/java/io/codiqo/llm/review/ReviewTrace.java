package io.codiqo.llm.review;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every session a review ran, written as OpenCode recorded it before the server's home, and with it the session
 * database, is deleted: the coordinator, its reviewer sub-agents and the forks that labelled and triaged, each with its
 * messages in order, every tool call's input, status and output included. It is for reading what the agents did: the
 * submission sends it to the server, which shows it on the commit page; nothing judges by it.
 */
@UtilityClass
public class ReviewTrace {
    private static final String FILE_PREFIX = "codiqo-review-trace-";
    /** a tool's output is mostly file contents and command output the repository already holds; the start shows what it was */
    private static final int MAX_TOOL_OUTPUT_CHARS = 4_000;
    private static final int SHORT_SHA = 8;
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /**
     * A trace that cannot be written costs the trace only, never the review. It is written even while the review is
     * being cancelled: the interrupt is held back for the few local reads it takes, since a cancelled review is often
     * the one worth reading, and restored afterwards.
     */
    public void write(RunArgs args, OpenCodeClient client, String sha, String coordinatorId, Collection<String> forkIds, Log log) {
        boolean interrupted = Thread.interrupted();
        /** an earlier run's trace of the same commit must not pass for this one's when this one cannot be written */
        FileUtils.deleteQuietly(file(args, sha));
        try {
            /** the sub-agents are listed here rather than taken from the review, which never lists them when it fails */
            List<String> sessionIds = Lists.newArrayList(coordinatorId);
            client.childUsage(coordinatorId).forEach(child -> sessionIds.add(child.getSessionId()));
            sessionIds.addAll(forkIds);

            ObjectNode trace = MAPPER.createObjectNode();
            trace.put("commit", sha);
            ArrayNode sessions = trace.putArray("sessions");
            for (String sessionId : sessionIds) {
                ObjectNode session = sessions.addObject();
                session.set("session", client.session(sessionId));
                ArrayNode messages = session.putArray("messages");
                for (JsonNode message : client.messages(sessionId)) {
                    messages.add(abbreviateToolOutput(message));
                }
            }

            File file = file(args, sha);
            FileUtils.forceMkdir(file.getParentFile());
            MAPPER.writeValue(file, trace);
            log.info("review trace of %d sessions written to %s", sessionIds.size(), file);
        } catch (IOException | RuntimeException err) {
            log.warn("could not write the review trace of %s: %s", sha, err.getMessage());
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
    private JsonNode abbreviateToolOutput(JsonNode message) {
        for (JsonNode part : message.path("content")) {
            if ("tool".equals(part.path("type").asString())) {
                abbreviate(part.path("state").path("content"));
            }
        }
        return message;
    }
    private void abbreviate(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().isString()) {
                    object.put(field.getKey(), StringUtils.abbreviate(field.getValue().asString(), MAX_TOOL_OUTPUT_CHARS));
                } else {
                    abbreviate(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(ReviewTrace::abbreviate);
        }
    }
}
