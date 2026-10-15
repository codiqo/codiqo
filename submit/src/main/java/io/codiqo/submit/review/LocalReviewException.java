package io.codiqo.submit.review;

import org.apache.commons.lang3.exception.ExceptionUtils;

import io.codiqo.llm.review.ReviewProviderException;

/** A local review that was asked for and failed, ran out of time or was interrupted: the change it was to judge is unjudged. */
public class LocalReviewException extends Exception {
    private static final long serialVersionUID = 1L;

    public LocalReviewException(String message, Throwable cause) {
        super(message, cause);
    }
    /** the review failed while the model's provider answered errors, which is no judgement of the commit */
    public boolean isProviderFailure() {
        return ExceptionUtils.indexOfType(this, ReviewProviderException.class) >= 0;
    }
}
