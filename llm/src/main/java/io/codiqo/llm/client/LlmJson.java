package io.codiqo.llm.client;

import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.StdDateFormat;

/** Reading is lenient on purpose: a model fences its JSON, adds fields, varies enum case and invents enum values. */
@UtilityClass
public class LlmJson {
    private static final String MARKDOWN_FENCE = "```";
    private static final ObjectMapper ANSWER_MAPPER = responseMapper();

    public ObjectMapper requestMapper() {
        return JsonMapper.builder()
                .defaultDateFormat(new StdDateFormat().withColonInTimeZone(true))
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL))
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT)
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
        return ANSWER_MAPPER.readValue(stripMarkdownFences(raw), type);
    }
    private static String stripMarkdownFences(String raw) {
        String trimmed = raw.strip();
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (trimmed.startsWith(MARKDOWN_FENCE) && trimmed.endsWith(MARKDOWN_FENCE)) {
            if (jsonStart > 0 && jsonEnd > jsonStart) {
                trimmed = trimmed.substring(jsonStart, jsonEnd + 1);
            }
        }
        return trimmed;
    }
}
