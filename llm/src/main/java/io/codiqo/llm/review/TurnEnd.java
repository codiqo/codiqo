package io.codiqo.llm.review;

import org.apache.commons.lang3.tuple.ImmutableTriple;

/**
 * How the last turn of a review session ended, as the agents recorded it. Whether the session answered is not part of
 * it: an answer is a call to the answer server ({@link ReviewAnswers}), and {@link LocalReviewer} reads it there first,
 * since an accepted answer stands whatever the turn did after it.
 */
public sealed interface TurnEnd permits TurnEnd.Ending, TurnEnd.Failed {
    /** A turn that ended without an error. */
    enum Ending implements TurnEnd {
        /** The model ended the turn on its own, with no tool call left to make. */
        STOPPED_ON_ITS_OWN,
        /**
         * Neither on its own nor with an error: the session was cut off, at its step cap for one, or ran no turn at all.
         */
        CUT_OFF
    }

    /**
     * The agents ended the turn with an error of this type. OpenCode retries a provider error itself before it gives a turn
     * up, so the error is final.
     */
    final class Failed extends ImmutableTriple<String, String, Boolean> implements TurnEnd {
        public Failed(String type, String message, boolean causedByTheCommit) {
            super(type, message, causedByTheCommit);
        }
        public String getType() {
            return getLeft();
        }
        public String getMessage() {
            return getMiddle();
        }
        /**
         * Whether the error follows from what the commit asks, so it fails the same way on every run and is the commit's
         * failure; any other error is the provider's or Codiqo's, whichever runtime reports it.
         */
        public boolean isCausedByTheCommit() {
            return getRight();
        }
    }
}
