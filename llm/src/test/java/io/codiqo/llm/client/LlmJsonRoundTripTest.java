package io.codiqo.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.BugSource;
import io.codiqo.llm.schema.LlmScoringResponse.BugType;
import io.codiqo.llm.schema.LlmScoringResponse.ChangeClassification;
import io.codiqo.llm.schema.LlmScoringResponse.RiskLevel;
import io.codiqo.llm.schema.LlmScoringResponse.TaskType;
import io.codiqo.llm.schema.RecapAwardsResponse;
import io.codiqo.llm.schema.SkipRequestResponse;
import io.codiqo.llm.schema.TagConsolidationResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LlmJsonRoundTripTest {
    private static final ObjectMapper REQUEST = LlmJson.requestMapper();
    private static final ObjectMapper RESPONSE = LlmJson.responseMapper();
    private static final ObjectMapper OLLAMA = new ObjectMapper();

    static Stream<Arguments> documents() throws Exception {
        return Stream.of(
                Arguments.of("scoring response", RESPONSE, LlmScoringResponse.class),
                Arguments.of("tag consolidation response", RESPONSE, TagConsolidationResponse.class),
                Arguments.of("recap awards response", RESPONSE, RecapAwardsResponse.class),
                Arguments.of("skip request response", RESPONSE, SkipRequestResponse.class),
                Arguments.of("web search tool call", RESPONSE, WebSearchTool.class),
                Arguments.of("ollama search request", OLLAMA, Class.forName(OllamaWebSearchClient.class.getName() + "$SearchRequest")),
                Arguments.of("ollama search response", OLLAMA, Class.forName(OllamaWebSearchClient.class.getName() + "$SearchResponse")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    void everyFieldSurvivesTheRoundTrip(String label, ObjectMapper mapper, Class<?> type) throws Exception {
        Object original = new JsonFixtures().populated(type);
        String json = mapper.writeValueAsString(original);
        String again = mapper.writeValueAsString(mapper.readValue(json, type));

        assertEquals(mapper.readTree(json), mapper.readTree(again), () -> label + " changed on the way through JSON:\n" + json + "\nread back as:\n" + again);
    }
    @Test
    void aModelAnswerIsReadTheWayTheClientReadsIt() throws Exception {
        String answer = """
                ```json
                {
                  "score": 42.5,
                  "changeClassification": "medium",
                  "taskTypes": ["feature", "Bug_Fix", "SOMETHING_NEW"],
                  "taskComplexity": 7,
                  "riskAssessment": {"riskScore": 61, "riskLevel": "very_high"},
                  "bugs": {
                    "blocking": [{"type": "null_pointer", "title": "NPE on empty cart", "file": "src/main/java/a/Cart.java",
                                  "line": 42, "confidence": "high", "source": "llm"}],
                    "major": [],
                    "minor": []
                  },
                  "effortBreakdown": {
                    "diffClassification": {
                      "movedPairs": ["src/A.java:10 -> src/B.java:12"],
                      "perFile": [{"file": "src/A.java", "blockKinds": {"m1": "moved"},
                                   "inPlaceModifyPairs": [{"deleted": 3, "added": 4}]}]
                    }
                  },
                  "tags": {"technical": ["kafka"], "functional": ["checkout"]},
                  "inventedByTheModel": {"anything": [1, 2, 3]}
                }
                ```""";

        LlmScoringResponse response = LlmJson.readAnswer(answer, LlmScoringResponse.class);

        assertEquals(42.5, response.getScore());
        assertEquals(ChangeClassification.MEDIUM, response.getChangeClassification());
        assertEquals(TaskType.FEATURE, response.getTaskTypes().get(0));
        assertEquals(TaskType.BUG_FIX, response.getTaskTypes().get(1));
        assertNull(response.getTaskTypes().get(2), "an enum value the model invents reads as null, not as a failure");
        assertEquals(RiskLevel.VERY_HIGH, response.getRiskAssessment().getRiskLevel());

        LlmScoringResponse.Bug bug = response.getBugs().getBlocking().get(0);
        assertEquals(BugType.NULL_POINTER, bug.getType());
        assertEquals(BugSource.LLM, bug.getSource());
        assertEquals(Integer.valueOf(42), bug.getLine());

        LlmScoringResponse.FileDiffClassification file = response.getEffortBreakdown().getDiffClassification().getPerFile().get(0);
        assertEquals(Map.of("m1", "moved"), file.getBlockKinds());
        assertEquals(4, file.getInPlaceModifyPairs().get(0).getAdded());
        assertEquals(List.of("checkout"), response.getTags().getFunctional());
    }
    /**
     * The request is only ever written, never read back, so it cannot round-trip: every populated field of the object
     * is checked against the written JSON instead.
     */
    @Test
    void thePromptCarriesEveryFieldOfTheRequest() throws Exception {
        LlmScoringRequest request = new JsonFixtures().populated(LlmScoringRequest.class);
        JsonNode prompt = REQUEST.readTree(REQUEST.writeValueAsString(request));

        assertWritten(request, prompt, "$");
        assertNoTupleShapes(prompt, "$");
        for (String scaler : List.of("methodScalerProd", "methodScalerTest", "constructorScalerProd", "constructorScalerTest")) {
            assertFalse(prompt.has(scaler), scaler + " must not reach the prompt");
        }
        assertFalse(prompt.get("fileChanges").get(0).has("lineFilter"), "lineFilter must not reach the prompt");
    }

    private static void assertNoTupleShapes(JsonNode node, String path) {
        if (node.isObject()) {
            List<String> names = node.propertyNames().stream().toList();
            assertFalse(names.contains("left") && names.contains("right"), () -> path + " looks like a serialised Pair/Triple: " + names);
            node.properties().forEach(entry -> assertNoTupleShapes(entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoTupleShapes(node.get(i), path + "[" + i + "]");
            }
        }
    }
    private static void assertWritten(Object bean, JsonNode node, String path) throws Exception {
        assertTrue(node.isObject(), () -> path + " should be an object: " + node);
        for (Field field : JsonFixtures.fields(bean.getClass())) {
            Object value = field.get(bean);
            if (value != null) {
                String name = propertyName(field, node);
                JsonNode written = node.get(name);
                assertNotNull(written, () -> path + "." + field.getName() + " is missing from the prompt: " + node.propertyNames());
                if (isCodiqoBean(value.getClass())) {
                    assertWritten(value, written, path + "." + name);
                } else if (value instanceof List<?> list && !list.isEmpty() && isCodiqoBean(list.get(0).getClass())) {
                    assertEquals(list.size(), written.size(), () -> path + "." + name + " lost elements");
                    assertWritten(list.get(0), written.get(0), path + "." + name + "[0]");
                }
            }
        }
    }
    /**
     * Lombok names the getter of a boolean field {@code isFoo} {@code isFoo()}, and Jackson writes that as the
     * property {@code foo}, so looking the field name up as-is would report such a field as missing.
     */
    private static String propertyName(Field field, JsonNode node) {
        String name = field.getName();
        if (node.has(name)) {
            return name;
        }
        if (field.getType() == boolean.class && name.startsWith("is") && name.length() > 2) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return name;
    }
    private static boolean isCodiqoBean(Class<?> type) {
        return type.getName().startsWith("io.codiqo.") && !type.isEnum();
    }
}
