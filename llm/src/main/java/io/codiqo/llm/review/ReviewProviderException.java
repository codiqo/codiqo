package io.codiqo.llm.review;

import java.io.IOException;

/**
 * A local review that failed while the model's provider answered errors (rate limits, outages). The failure is the
 * provider's, not the commit's: excluding the commit for it would leave it unscored for good, so the caller fails the
 * run instead, which halts the project until the provider is healthy and an administrator resumes it.
 */
public class ReviewProviderException extends IOException {
    private static final long serialVersionUID = 1L;

    public ReviewProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
