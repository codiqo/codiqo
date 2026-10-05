package io.codiqo.api.code;

import java.io.File;

import org.apache.commons.lang3.tuple.ImmutableTriple;

public final class PreviousRevision extends ImmutableTriple<File, String, String> {
    public PreviousRevision(File file, String path, String content) {
        super(file, path, content);
    }
    public File getFile() {
        return getLeft();
    }
    public String getPath() {
        return getMiddle();
    }
    public String getContent() {
        return getRight();
    }
}
