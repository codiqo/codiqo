package io.codiqo.submit.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.submit.auth.CodiqoCredential;
import io.codiqo.util.RequestAuthorizer;

class LocalReviewRunEndpointTest {
    private static final String RESOURCE_URL = "https://mcp.codiqo.io/mcp";
    private static final String PROJECT = "com.acme:core";

    @Test
    void theProxyIsToldTheMemberAndTheProject() throws Exception {
        RunArgs args = new RunArgs();
        args.setReviewBaseUrl("https://mcp.codiqo.io/v1");

        ReviewEndpoint endpoint = LocalReviewRun.endpoint(args, RESOURCE_URL, PROJECT, LocalReviewRunEndpointTest::member);

        Map<String, String> headers = Maps.newLinkedHashMap();
        endpoint.getAuthorizer().accept(headers::put);
        assertEquals(Map.of("Authorization", RequestAuthorizer.bearer("member-token"), LocalReviewRun.PROJECT_HEADER, PROJECT), headers);
    }
    @Test
    void aDirectEndpointIsToldNeither() throws Exception {
        RunArgs args = new RunArgs();
        args.setReviewBaseUrl("http://localhost:11434/v1");

        ReviewEndpoint endpoint = LocalReviewRun.endpoint(args, RESOURCE_URL, PROJECT, LocalReviewRunEndpointTest::member);

        assertNull(endpoint.getAuthorizer());
    }
    private static CodiqoCredential member() {
        return new CodiqoCredential() {
            @Override
            public void accept(BiConsumer<String, String> header) {
                header.accept("Authorization", RequestAuthorizer.bearer("member-token"));
            }
            @Override
            public String describe() {
                return "a browser login";
            }
        };
    }
}
