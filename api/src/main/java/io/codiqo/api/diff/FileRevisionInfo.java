package io.codiqo.api.diff;

import java.time.Instant;

public interface FileRevisionInfo {
    String getRevisionId();
    String getAuthor();
    Instant getTimestamp();
    String getMessage();
    String getContent();
}