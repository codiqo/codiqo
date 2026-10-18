package io.codiqo.llm.client;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;

import lombok.Value;
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
    /** where an answer object can start: an opening brace and a field name */
    private static final Pattern OBJECT_START = Pattern.compile("\\{\\s*\"");
    /** a JSON string, its escapes included */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
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
     * The JSON object inside a model's answer. Each opening brace followed by a field name is a candidate, which Jackson
     * reads as one object and no further, so a markdown fence and prose before or after the object do not matter
     * (coordinators routinely open with "All 7 reviewers reported..."), while a brace in prose ({@code if (x) { return; }})
     * or an empty example ({@code {}}) is never one. The longest object read wins, so a short example in the prose
     * cannot stand in for the answer, and the objects nested in one that was read are not candidates of their own.
     *
     * <p>
     * A candidate Jackson cannot read is a broken answer, and no object inside it stands in for it: a reviewer once wrote
     * {@code {"file":"file":...}} in one of its labels, and the label after the fault was taken for the whole answer,
     * which then failed for a missing {@code unitLabels} field. Inside means before its own closing brace or, when it
     * never closes (an unfinished example in the prose), before the fault. With no object left, the broken candidate
     * that read furthest is returned from its opening brace, so the caller's read fails with Jackson's error at the real
     * fault; with no candidate at all, the text is returned as it is.
     */
    private static String jsonObject(String raw) {
        String text = raw.strip();
        Map<Boolean, List<Candidate>> byCompleteness = candidates(text).stream().collect(Collectors.partitioningBy(Candidate::isComplete));
        List<Candidate> broken = byCompleteness.get(false);
        List<Range<Integer>> brokenSpans = broken.stream()
                .map(candidate -> Range.closedOpen(candidate.getStart(), closingBrace(text, candidate.getStart()).orElse(candidate.getEnd())))
                .toList();
        Optional<Candidate> answer = byCompleteness.get(true).stream()
                .filter(candidate -> brokenSpans.stream().noneMatch(span -> span.contains(candidate.getStart())))
                .max(Comparator.comparingInt(Candidate::length));
        if (answer.isPresent()) {
            return text.substring(answer.get().getStart(), answer.get().getEnd());
        }
        return broken.stream()
                .max(Comparator.comparingInt(Candidate::length))
                .map(candidate -> text.substring(candidate.getStart()))
                .orElse(text);
    }
    private static List<Candidate> candidates(String text) {
        List<Candidate> toReturn = Lists.newArrayList();
        Matcher start = OBJECT_START.matcher(text);
        int from = 0;
        while (start.find(from)) {
            Candidate candidate = read(text, start.start());
            toReturn.add(candidate);
            from = candidate.isComplete() ? candidate.getEnd() : start.start() + 1;
        }
        return toReturn;
    }
    private static Candidate read(String text, int start) {
        try (JsonParser parser = OBJECT_FINDER.createParser(text.substring(start))) {
            OBJECT_FINDER.readTree(parser);
            return new Candidate(start, start + Math.toIntExact(parser.currentLocation().getCharOffset()), true);
        } catch (JacksonException err) {
            long fault = Objects.isNull(err.getLocation()) ? 0 : Math.max(0, err.getLocation().getCharOffset());
            return new Candidate(start, start + Math.toIntExact(fault), false);
        }
    }
    /**
     * Just past the brace that closes the one at {@code start}, counted with the strings from there on blanked out, since
     * they hold code and its braces; empty when it never closes.
     */
    private static Optional<Integer> closingBrace(String text, int start) {
        String braces = STRING_LITERAL.matcher(text.substring(start)).replaceAll(literal -> StringUtils.repeat(' ', literal.group().length()));
        int depth = 0;
        for (int i = 0; i < braces.length(); i++) {
            depth += switch (braces.charAt(i)) {
                case '{' -> 1;
                case '}' -> -1;
                default -> 0;
            };
            if (depth == 0) {
                return Optional.of(start + i + 1);
            }
        }
        return Optional.empty();
    }

    @Value
    private static class Candidate {
        int start;
        /** past its closing brace when Jackson read it whole, otherwise where the read failed */
        int end;
        boolean complete;

        private int length() {
            return end - start;
        }
    }
}
