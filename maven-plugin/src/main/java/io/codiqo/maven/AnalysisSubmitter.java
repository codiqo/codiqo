package io.codiqo.maven;

import java.time.Duration;

import org.apache.commons.lang3.Strings;
import org.apache.maven.plugin.logging.Log;

import io.codiqo.client.ApiClient;
import io.codiqo.client.ApiException;
import io.codiqo.client.api.AnalysisApi;
import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import lombok.experimental.UtilityClass;

@UtilityClass
public class AnalysisSubmitter {
    private static final String API_KEY_HEADER = "X-API-Key";

    public static AnalysisAcceptedModel submit(
            String apiUrl,
            String apiKey,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            AnalysisSubmissionModel submission,
            Log log) throws ApiException {
        AnalysisApi client = buildClient(apiUrl, apiKey, connectTimeoutSeconds, readTimeoutSeconds);
        log.info("submitting analysis to " + apiUrl);

        return ApiRetry.call(log, "submitAnalysis", apiUrl, () -> client.submitAnalysis(submission));
    }
    public static AnalysisApi buildClient(String apiUrl, String apiKey, long connectTimeoutSeconds, long readTimeoutSeconds) {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(Strings.CS.removeEnd(apiUrl, "/"));
        apiClient.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        apiClient.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        apiClient.setRequestInterceptor(builder -> builder.header(API_KEY_HEADER, apiKey));
        return new AnalysisApi(apiClient);
    }
}
