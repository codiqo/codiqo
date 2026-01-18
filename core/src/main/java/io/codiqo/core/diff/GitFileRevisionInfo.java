package io.codiqo.core.diff;

import java.time.Instant;

import io.codiqo.api.diff.FileRevisionInfo;
import lombok.Data;

@Data
public class GitFileRevisionInfo implements FileRevisionInfo {
    private String revisionId;
    private String author;
    private Instant timestamp;
    private String message;
    private String content;
}