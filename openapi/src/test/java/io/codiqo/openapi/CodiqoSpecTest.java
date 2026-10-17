package io.codiqo.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * What the generators cannot check in the spec. The Spring server interface receives the caller's credential through
 * {@code x-spring-provide-args}, which every secured operation repeats beside its {@code security} block: an operation
 * that leaves it out still compiles, and its controller then has no credential to authenticate the caller with. The
 * arguments also name the headers a second time, after the security schemes: a scheme renamed alone would have the
 * clients send a header the server never reads, so every call with an API key would arrive without one.
 */
class CodiqoSpecTest {
    private static final String SPEC = "/codiqo.yaml";
    private static final String CREDENTIAL_ARGS = "x-codiqo-credential-args";
    private static final String PROVIDE_ARGS = "x-spring-provide-args";
    private static final String AUTHORIZATION = "Authorization";
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final Pattern HEADER = Pattern.compile("@RequestHeader\\(value = \"([^\"]+)\"");

    @Test
    void everySecuredOperationAndOnlyThoseReceiveTheCredentialArguments() throws IOException {
        Map<String, Object> spec = spec();
        Object credentialArgs = spec.get(CREDENTIAL_ARGS);

        List<String> mismatched = new ArrayList<>();
        for (Object path : map(spec.get("paths")).values()) {
            for (Map.Entry<String, Object> operation : map(path).entrySet()) {
                if (METHODS.contains(operation.getKey())) {
                    Map<String, Object> body = map(operation.getValue());
                    boolean secured = body.get("security") instanceof List<?> security && security.size() > 0;
                    boolean receives = Objects.equals(credentialArgs, body.get(PROVIDE_ARGS));
                    if (secured != receives) {
                        mismatched.add(String.valueOf(body.get("operationId")));
                    }
                }
            }
        }
        assertEquals(List.of(), mismatched, "operations whose security and credential arguments disagree");
    }
    @Test
    void theCredentialArgumentsReadTheHeadersTheSecuritySchemesName() throws IOException {
        Map<String, Object> spec = spec();
        Map<String, Object> schemes = map(map(spec.get("components")).get("securitySchemes"));
        assertEquals("bearer", map(schemes.get("bearerAuth")).get("scheme"), "a bearer token travels in the Authorization header");

        List<String> headers = new ArrayList<>();
        for (Object argument : (List<?>) spec.get(CREDENTIAL_ARGS)) {
            Matcher matcher = HEADER.matcher(String.valueOf(argument));
            if (matcher.find()) {
                headers.add(matcher.group(1));
            }
        }
        assertEquals(List.of(String.valueOf(map(schemes.get("apiKey")).get("name")), AUTHORIZATION), headers);
    }
    private static Map<String, Object> spec() throws IOException {
        try (InputStream in = CodiqoSpecTest.class.getResourceAsStream(SPEC)) {
            return new Yaml().load(Objects.requireNonNull(in, SPEC));
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
