package io.codiqo.submit.review;

/** A local review that was asked for and failed, ran out of time or was interrupted: the change it was to judge is unjudged. */
public class LocalReviewException extends Exception {
    private static final long serialVersionUID = 1L;

    public LocalReviewException(String message, Throwable cause) {
        super(message, cause);
    }
}
