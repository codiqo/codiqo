package io.codiqo.api;

import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * A language the analysis reads, as the language declares it: the value the submission's file language carries on the
 * wire, and the file extensions that are written in it.
 */
public final class SourceLanguage extends ImmutablePair<String, List<String>> {
    private static final long serialVersionUID = 1L;

    public SourceLanguage(String wireId, List<String> extensions) {
        super(wireId, List.copyOf(extensions));
    }
    public String getWireId() {
        return getLeft();
    }
    public List<String> getExtensions() {
        return getRight();
    }
}
