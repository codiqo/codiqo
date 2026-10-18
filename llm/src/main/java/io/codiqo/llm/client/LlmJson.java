package io.codiqo.llm.client;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.experimental.UtilityClass;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.StdDateFormat;

/**
 * Reading is lenient on purpose: a model fences its JSON, writes a sentence before or after it, adds fields, varies enum
 * case and invents enum values.
 */
@UtilityClass
public class LlmJson {
    private static final ObjectMapper ANSWER_MAPPER = responseMapper();
    /** reads one value and stops there, so the text after an object does not decide whether the object parses */
    private static final ObjectReader OBJECT_FINDER = ANSWER_MAPPER.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ObjectMapper requestMapper() {
        return JsonMapper.builder()
                .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true))
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
    public ObjectMapper responseMapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
    }
    public <T> T readAnswer(String raw, Class<T> type) {
        return ANSWER_MAPPER.readValue(jsonObject(raw), type);
    }
    /**
     * Reads an answer whose field {@code ignored} is taken from elsewhere: a malformed value there (a list of strings
     * where objects were asked for) must not cost the rest of the answer.
     */
    public <T> T readAnswerIgnoring(String raw, Class<T> type, String ignored) {
        JsonNode answer = ANSWER_MAPPER.readTree(jsonObject(raw));
        if (answer instanceof ObjectNode object) {
            object.remove(ignored);
        }
        return ANSWER_MAPPER.treeToValue(answer, type);
    }
    /**
     * The JSON object inside a model's answer: the longest object that parses from some opening brace, read only as
     * far as that object ends. That covers a bare object, a markdown fence around it, and prose before or after it;
     * agent coordinators routinely open with "All 7 reviewers reported..." before the object, which a fence-only check
     * rejected. A brace inside the prose, such as a quoted {@code if (x) { return; }}, does not parse and is skipped,
     * wherever the prose is: cutting every candidate at the last closing brace of the whole text made trailing prose with
     * a brace in it fail every candidate, because Jackson 3 rejects trailing tokens by default. The longest object wins
     * so an example such as {@code {}} in the prose cannot stand in for the answer.
     *
     * <p>
     * An object nested in a broken one never stands in for it. A broken candidate that opens with a field name is a
     * broken answer, and everything up to its own closing brace (found by counting braces outside strings) lies inside
     * it: a reviewer once wrote {@code {"file":"file":...}} in one of its labels, and the label after it was read as the
     * whole answer, which then failed for a missing {@code unitLabels} field. A candidate that is never closed, and any
     * other broken candidate (a brace in prose), covers only what the parser read of it; an answer whose last string is
     * never closed is read to its end that way.
     * When no object is left, the broken candidate the parser read furthest is returned from its opening brace, so the
     * caller's read fails with Jackson's own error at the real fault rather than at the prose before it, and with the
     * answer as it is when nothing looks like an object at all.
     */
    private static String jsonObject(String raw) {
        String trimmed = raw.strip();
        Optional<String> toReturn = Optional.empty();
        String furthestBroken = trimmed;
        long furthest = -1;
        long brokenUntil = -1;
        int start = trimmed.indexOf('{');
        while (start >= 0) {
            Candidate candidate = objectAt(trimmed, start);
            int next = start + 1;
            boolean outsideBroken = start >= brokenUntil;
            if (candidate.getObject().isPresent()) {
                String object = candidate.getObject().get();
                if (BooleanUtils.and(new boolean[] { outsideBroken, object.length() > toReturn.map(String::length).orElse(0) })) {
                    toReturn = Optional.of(object);
                }
                /** the braces inside a parsed object only start the objects nested in it, never a longer one */
                next = start + object.length();
            } else {
                if (BooleanUtils.and(new boolean[] { outsideBroken, candidate.getFailedAt() - start > furthest })) {
                    furthest = candidate.getFailedAt() - start;
                    furthestBroken = trimmed.substring(start);
                }
                long covered = candidate.getFailedAt();
                if (opensWithField(trimmed, start)) {
                    covered = Math.max(covered, closingBrace(trimmed, start).orElse(0));
                }
                brokenUntil = Math.max(brokenUntil, covered);
            }
            start = trimmed.indexOf('{', next);
        }
        return toReturn.orElse(furthestBroken);
    }
    /** whether the first character after the brace at {@code start}, past white space, opens a field name */
    private static boolean opensWithField(String text, int start) {
        return Strings.CS.startsWith(StringUtils.stripStart(text.substring(start + 1), null), "\"");
    }
    /**
     * Just past the brace that closes the one at {@code start}, counting braces outside strings; empty when it is never
     * closed, since the text after an unclosed fragment of prose can still hold the whole answer.
     */
    private static OptionalInt closingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return OptionalInt.of(i + 1);
                }
            }
        }
        return OptionalInt.empty();
    }
    /** the object that starts at {@code start}, up to its own closing brace, or where in the text its parse failed */
    private static Candidate objectAt(String text, int start) {
        try (JsonParser parser = OBJECT_FINDER.createParser(text.substring(start))) {
            if (OBJECT_FINDER.readTree(parser) instanceof ObjectNode) {
                return new Candidate(Optional.of(text.substring(start, start + Math.toIntExact(parser.currentLocation().getCharOffset()))), start);
            }
            return new Candidate(Optional.empty(), start);
        } catch (JacksonException err) {
            long offset = Objects.isNull(err.getLocation()) ? 0 : Math.max(0, err.getLocation().getCharOffset());
            return new Candidate(Optional.empty(), start + offset);
        }
    }
    private static final class Candidate extends ImmutablePair<Optional<String>, Long> {
        private Candidate(Optional<String> object, long failedAt) {
            super(object, failedAt);
        }
        private Optional<String> getObject() {
            return getLeft();
        }
        private long getFailedAt() {
            return getRight();
        }
    }
}
