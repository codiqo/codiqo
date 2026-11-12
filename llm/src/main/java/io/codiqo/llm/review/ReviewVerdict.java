package io.codiqo.llm.review;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import io.codiqo.util.Interrupts;
import lombok.Value;

/**
 * How a local review ended: it succeeded, failed on the commit, failed on the model provider, failed on Codiqo's side,
 * or was stopped. Only a failure on the commit excludes it, since every later run would fail on the commit the same
 * way. A failure on the provider or on Codiqo's side halts the project instead, since a run after the cause recovers
 * reviews the commit, and a stopped review halts nothing.
 *
 * <p>Each failure is decided where it happens, in {@link LocalReviewer} and where the caller waits for a review in the
 * background, never read back from the type of the exception it surfaced as. Read back, every failure the reader did
 * not know fell to whether the relay had counted a provider error during the review: one retried 429 turned an answer
 * Codiqo could not read into a halt that came back on every resume, and an OpenCode that would not start, before any
 * call to the provider, excluded the commit for a failure of Codiqo's own.
 */
public sealed interface ReviewVerdict permits ReviewVerdict.Succeeded, ReviewVerdict.Failed {
    /** the provider's summary for a turn OpenCode ended with a provider error that never reached the relay */
    String RELAY_COUNTED_NONE = "OpenCode recorded the provider's failure; the relay counted none";
    String REVIEW_FAILED = "local review failed: ";

    /**
     * Failed on the commit, with the provider's failed answers named in its error: a turn's own error, and an answer
     * cut off at a step cap, say nothing of the 429s that preceded it.
     */
    static ReviewVerdict commitFailure(Exception err, Optional<String> providerErrors) {
        return new CommitFailure(withProviderErrors(err, providerErrors));
    }
    static ReviewVerdict providerFailure(Exception err, Optional<String> providerErrors) {
        return new ProviderFailure(err, providerErrors.orElse(RELAY_COUNTED_NONE));
    }
    /**
     * A review that ended without an answer and without saying why: a session cut off at its step cap, a turn that did
     * not end within the review timeout, or a review its caller stopped waiting for. The parallel reviewers share the
     * relay, so the call that lost the answer cannot be found: the failure is the provider's when the relay counted a
     * provider failure during the review, and the commit's otherwise.
     */
    static ReviewVerdict unanswered(Exception err, boolean providerFailed, Optional<String> providerErrors) {
        if (providerFailed) {
            return providerFailure(err, providerErrors);
        }
        return commitFailure(err, providerErrors);
    }
    /**
     * A failure no part of the review settled: the relay, the answer server or OpenCode would not start or answer, the
     * trace of a review that succeeded could not be written, or a bug. It is Codiqo's, unless the review was being
     * stopped ({@link Interrupts#stopped}).
     */
    static ReviewVerdict unexpected(Exception err, Optional<String> providerErrors) {
        if (Interrupts.stopped(err)) {
            return new Stopped(err);
        }
        return new CodiqoFailure(withProviderErrors(err, providerErrors));
    }
    private static Exception withProviderErrors(Exception err, Optional<String> providerErrors) {
        return providerErrors.<Exception> map(summary -> new IOException(err.getMessage() + "; " + summary, err)).orElse(err);
    }

    @Value
    final class Succeeded implements ReviewVerdict {
        LocalReviewer.ReviewOutcome outcome;
    }

    /** a review that failed or was stopped */
    abstract sealed class Failed implements ReviewVerdict permits CommitFailure, ProviderFailure, CodiqoFailure, Stopped {
        private final Exception error;

        protected Failed(Exception error) {
            this.error = Objects.requireNonNull(error);
        }
        public Exception getError() {
            return error;
        }
        /** why the review failed, as the developer, an exclusion or a halt reads it */
        public String getReason() {
            return error.getMessage();
        }
    }

    /** failed on the commit: the commit is excluded */
    final class CommitFailure extends Failed {
        public CommitFailure(Exception error) {
            super(error);
        }
    }

    /** failed on the model provider: the project halts, with the provider's failed answers in the halt's reason */
    final class ProviderFailure extends Failed {
        /** kept apart from the error, which can fill a halt's reason on its own ({@link ReviewProviderException}) */
        private final String providerSummary;

        public ProviderFailure(Exception error, String providerSummary) {
            super(error);
            this.providerSummary = Objects.requireNonNull(providerSummary);
        }
        public String getProviderSummary() {
            return providerSummary;
        }
        /**
         * With the provider's failed answers: the error alone names only what was lost, and a reviewer cut off at its step
         * cap reads as the model's own failure without the 429s that preceded it.
         */
        @Override
        public String getReason() {
            return getError().getMessage() + "; " + providerSummary;
        }
    }

    /** failed on Codiqo's side: the project halts */
    final class CodiqoFailure extends Failed {
        public CodiqoFailure(Exception error) {
            super(error);
        }
    }

    /** the run was stopped while the review ran, which halts nothing */
    final class Stopped extends Failed {
        public Stopped(Exception error) {
            super(error);
        }
    }
}
