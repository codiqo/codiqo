package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SecretRedactionTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TOKEN = "ghp_0123456789abcdef";

    @Test
    void scrubsAVariableWhoseNameSaysItIsASecretAtAnyDepth() {
        ObjectNode document = MAPPER.createObjectNode();
        document.putArray("sessions").addObject().putArray("output").add("token=" + TOKEN);

        SecretRedaction.redact(document, Map.of("GITHUB_TOKEN", TOKEN));

        assertEquals("token=" + SecretRedaction.PLACEHOLDER, document.path("sessions").get(0).path("output").get(0).stringValue());
    }
    @Test
    void scrubsTheVariablesTheActionNamesAndItsOwn() {
        ObjectNode document = MAPPER.createObjectNode().put("output", "registry-login-value and codiqo-api-key-value");
        Map<String, String> environment = Map.of(
                SecretRedaction.REDACT_NAMES_VARIABLE, "REGISTRY_LOGIN,\nOTHER",
                "REGISTRY_LOGIN", "registry-login-value",
                "CODIQO_API_KEY", "codiqo-api-key-value");

        SecretRedaction.redact(document, environment);

        assertEquals(SecretRedaction.PLACEHOLDER + " and " + SecretRedaction.PLACEHOLDER, document.path("output").stringValue());
    }
    @Test
    void scrubsTheBase64FormAndValuesThatNeedEscaping() {
        String secret = "pa\"ss\\word-1234";
        ObjectNode document = MAPPER.createObjectNode()
                .put("out", "echo " + secret)
                .put("auth", "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));

        SecretRedaction.redact(document, Map.of("DB_PASSWORD", secret));

        assertEquals(MAPPER.createObjectNode().put("out", "echo ***").put("auth", "Basic ***"), document);
    }
    @Test
    void leavesOrdinaryVariablesShortValuesAndOtherTypesAlone() {
        ObjectNode document = MAPPER.createObjectNode().put("home", "/home/runner").put("key", "abc").put("tokens", 42);
        ObjectNode before = document.deepCopy();

        SecretRedaction.redact(document, Map.of("HOME", "/home/runner", "SOME_TOKEN", "abc"));

        assertEquals(before, document);
    }
}
