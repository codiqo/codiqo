package io.codiqo.api.code;

import java.io.File;

import lombok.Value;

@Value
public class PreviousRevision {
    File file;
    String path;
    String content;
}
