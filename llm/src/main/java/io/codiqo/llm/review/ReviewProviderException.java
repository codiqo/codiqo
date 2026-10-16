package io.codiqo.llm.review;

import java.io.IOException;

/**
 * A local review that failed while the model's provider answered errors (rate limits, outages). The failure is the
 * provider's, not the commit's: excluding the commit for it would leave it unscored for good, so the caller fails the
 * run instead, which halts the project until the provider is healthy and an administrator resumes it.
 */
public class ReviewProviderException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * The provider's failed answers, kept apart from the failure they are appended to: a halt's reason is limited, and
     * the failure's own message can fill that limit (a turn whose error carries a whole HTTP body), which would cut off
     * exactly the summary the halt banner needs.
     */
    private final String providerSummary;

    public ReviewProviderException(String failure, String providerSummary, Throwable cause) {
        super(failure + "; " + providerSummary, cause);
        this.providerSummary = providerSummary;
    }
    public String getProviderSummary() {
        return providerSummary;
    }
}
