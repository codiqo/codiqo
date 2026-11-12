package io.codiqo.submit.outcome;

import java.util.Objects;

import io.codiqo.client.model.AnalysisAcceptedModel;
import io.codiqo.client.model.AnalysisExcludeModel;
import lombok.Value;

/**
 * What one run did with one commit: submitted it for scoring, excluded it, or failed on it, deferring the commit to a
 * later run or halting the project ({@code GLOSSARY.md}). A run that failed hands its build tool the failure to fail
 * with; the halt, when there is one, is already recorded.
 */
public sealed interface CommitOutcome permits CommitOutcome.Submitted, CommitOutcome.Excluded, CommitOutcome.Failed {
    @Value
    final class Submitted implements CommitOutcome {
        AnalysisAcceptedModel accepted;
    }

    @Value
    final class Excluded implements CommitOutcome {
        AnalysisExcludeModel exclusion;
    }

    /** the run failed on the commit: its build tool fails the run with the cause */
    abstract sealed class Failed implements CommitOutcome permits Halted, Deferred {
        private final Exception cause;

        protected Failed(Exception cause) {
            this.cause = Objects.requireNonNull(cause);
        }
        public Exception getCause() {
            return cause;
        }
    }

    /** a failure on Codiqo's or the model provider's side: the project is halted until an administrator resumes it */
    final class Halted extends Failed {
        /** a halt the server refused, or that had no credential, is logged; the run fails with its cause all the same */
        private final boolean recorded;

        public Halted(Exception cause, boolean recorded) {
            super(cause);
            this.recorded = recorded;
        }
        public boolean isRecorded() {
            return recorded;
        }
    }

    /**
     * A failure that halts nothing: the run was stopped, a tool could not be downloaded, or the author's build broke on a
     * run told not to tolerate that. A later run analyses the commit again.
     */
    final class Deferred extends Failed {
        public Deferred(Exception cause) {
            super(cause);
        }
    }
}
