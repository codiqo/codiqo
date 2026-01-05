package io.codiqo.core.diff;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.codiqo.api.diff.FileRevisionInfo;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GitFileRevisionInfo implements FileRevisionInfo {
    private String revisionId;
    private String author;
    private Instant timestamp;
    private String message;
    private String content;
}