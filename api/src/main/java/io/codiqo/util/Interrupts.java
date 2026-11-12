package io.codiqo.util;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import lombok.experimental.UtilityClass;

/**
 * Whether a failure is the run being stopped rather than a failure of the run. An interrupt often surfaces as an I/O
 * error (ClosedByInterruptException, InterruptedIOException) whose root cause is not an InterruptedException, so the
 * thread's flag tells the two apart as well. A stopped review halts nothing and a stopped run halts nothing: the two
 * decisions read this one rule, and a rule kept twice let one of them halt a project over a run someone stopped.
 */
@UtilityClass
public class Interrupts {
    public boolean stopped(Throwable err) {
        return BooleanUtils.or(new boolean[] { Thread.currentThread().isInterrupted(), ExceptionUtils.getRootCause(err) instanceof InterruptedException });
    }
}
