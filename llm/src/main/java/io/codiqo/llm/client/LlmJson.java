package io.codiqo.llm.client;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude.Include;

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
     * where objects were asked for) must not cost the rest of the answer. The mapper is the caller's, a
     * {@link #responseMapper()} that may read a format of its own.
     */
    public <T> T readAnswerIgnoring(String raw, ObjectMapper mapper, Class<T> type, String ignored) {
        JsonNode answer = mapper.readTree(jsonObject(raw));
        if (answer instanceof ObjectNode object) {
            object.remove(ignored);
        }
        return mapper.treeToValue(answer, type);
    }
    /**
     * The JSON object inside a model's answer: the largest structure Jackson reads from any opening brace, read one value
     * deep and no further. A markdown fence and prose before or after the object do not matter (coordinators routinely
     * open with "All 7 reviewers reported..."), and neither does a brace in that prose ({@code if (x) { return; }}, before
     * 59 of 1,645 answers in a week of reviews) or a whole object quoted from the reviewed code ({@code {"mcpServers": {}}},
     * before 2 of them): each is smaller than the answer. The objects nested in one that was read are not tried.
     *
     * <p>
     * When the largest structure is one Jackson could not read, the answer is broken, and it is returned from its opening
     * brace so the caller's read fails with Jackson's error at the real fault: a reviewer once wrote
     * {@code {"file":"file":...}} in one of its labels, and taking the next label for the answer failed the review for a
     * missing {@code unitLabels} field instead. Size is measured up to the fault, so an answer that breaks before most of
     * its text cannot be told from a fragment, and one of its nested objects is taken; that read then fails on a missing
     * field. Text with no brace is returned as it is.
     */
    private static String jsonObject(String raw) {
        String text = raw.strip();
        Optional<Candidate> largest = Optional.empty();
        int start = text.indexOf('{');
        while (start >= 0) {
            Candidate candidate = read(text, start);
            if (candidate.length() > largest.map(Candidate::length).orElse(0)) {
                largest = Optional.of(candidate);
            }
            start = text.indexOf('{', candidate.isComplete() ? candidate.getEnd() : start + 1);
        }
        return largest.map(candidate -> text.substring(candidate.getStart(), candidate.isComplete() ? candidate.getEnd() : text.length())).orElse(text);
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
