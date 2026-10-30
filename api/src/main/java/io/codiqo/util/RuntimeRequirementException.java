package io.codiqo.util;

import java.io.IOException;

/**
 * This machine cannot run what the analysis starts, such as a JDK older than the language server needs, found before
 * the commit's review is paid for. Only a change to the machine fixes it, so the run fails without halting the project:
 * a halt would add an administrator's resume to that change, while the next scheduled run fails the same way until the
 * machine is fixed and then simply proceeds.
 */
public final class RuntimeRequirementException extends IOException {
    public RuntimeRequirementException(String message, Throwable cause) {
        super(message, cause);
    }
}
