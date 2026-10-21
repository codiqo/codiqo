package io.codiqo.util;

import java.io.IOException;

/**
 * A tool the run needs could not be downloaded, so the run stops without storing anything on the server: no halt, no
 * exclusion, no submission. The failure is the download host's, never the commit's or codiqo's, and it heals on its
 * own, so the next scheduled run simply tries again. When download.eclipse.org answered every path, its
 * {@code latest.txt} included, with a "Temporary shutdown" page for hours, the language-server download failed and
 * thirteen projects were halted until an administrator resumed each of them. A run that halts or excludes on this
 * failure brings that back.
 */
public final class DownloadFailedException extends IOException {
    public DownloadFailedException(String message) {
        super(message);
    }
    public DownloadFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
