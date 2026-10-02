package io.codiqo.api.code;

import java.io.File;

import lombok.Value;

/** what a file the commit kept looked like before it: its content then, and the path it lived at */
@Value
public class PreviousRevision {
    File file;
    String path;
    String content;
}
