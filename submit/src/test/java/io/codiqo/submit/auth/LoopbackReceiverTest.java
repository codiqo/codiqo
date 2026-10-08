package io.codiqo.submit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/** the page the browser shows once the login is answered, which the login template renders */
class LoopbackReceiverTest {
    private static final String PAGE_START = "<!doctype html><meta charset=utf-8><title>Codiqo</title> <body style=\"font-family:system-ui;margin:4rem\">";

    @Test
    void anApprovedLoginTellsTheUserToGoBackToTheTerminal() throws Exception {
        assertEquals(PAGE_START + "<h2>Codiqo is authorized</h2><p>You can close this tab and go back to the terminal.</p></body>",
                answer("?code=abc&state=xyz", 200));
    }
    @Test
    void aRefusedLoginShowsTheServersReasonEscaped() throws Exception {
        assertEquals(PAGE_START + "<h2>Codiqo was not authorized</h2><p>the user said &lt;no&gt; &amp; left</p></body>",
                answer("?error=access_denied&error_description=the+user+said+%3Cno%3E+%26+left", 400));
    }
    @Test
    void aRefusalWithoutDescriptionShowsItsErrorCode() throws Exception {
        assertEquals(PAGE_START + "<h2>Codiqo was not authorized</h2><p>access_denied</p></body>", answer("?error=access_denied", 400));
    }
    private static String answer(String query, int status) throws Exception {
        try (LoopbackReceiver receiver = LoopbackReceiver.start(); HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(receiver.redirectUri() + query)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            receiver.await(Duration.ofSeconds(5));

            assertEquals(status, response.statusCode());
            return response.body();
        }
    }
}
