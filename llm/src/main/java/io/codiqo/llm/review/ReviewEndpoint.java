package io.codiqo.llm.review;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RequestAuthorizer;
import okhttp3.HttpUrl;

/**
 * The OpenAI-compatible endpoint a local review's agents call; everything else about the review is in {@link RunArgs}.
 * The models are called through the backend's LLM proxy with the member's Codiqo credential, so nobody needs a
 * provider key; a local Ollama daemon signed in to a paying account works as well, with
 * {@link RunArgs#DEFAULT_LOCAL_LLM_URL} and no authorizer.
 */
public final class ReviewEndpoint extends ImmutableTriple<String, String, RequestAuthorizer> {
    public ReviewEndpoint(String baseUrl, String apiKey, RequestAuthorizer authorizer) {
        super(baseUrl, apiKey, authorizer);
    }
    public String getBaseUrl() {
        return getLeft();
    }
    public String getApiKey() {
        return getMiddle();
    }
    public RequestAuthorizer getAuthorizer() {
        return getRight();
    }
    public boolean isProxiedBy(String resourceUrl) {
        return origin(getBaseUrl()).equals(origin(resourceUrl));
    }
    private static Triple<String, String, Integer> origin(String url) {
        HttpUrl parsed = HttpUrl.get(url);
        return Triple.of(parsed.scheme(), parsed.host(), parsed.port());
    }
}
