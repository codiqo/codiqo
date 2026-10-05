package io.codiqo.api.cpd;

import java.io.File;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutableTriple;

public class CloneLocations extends ImmutableTriple<Integer, Integer, List<CloneLocations.Span>> {
    public CloneLocations(int lineCount, int tokenCount, List<Span> spans) {
        super(lineCount, tokenCount, List.copyOf(spans));
    }
    public int getLineCount() {
        return getLeft();
    }
    public int getTokenCount() {
        return getMiddle();
    }
    public List<Span> getSpans() {
        return getRight();
    }

    public static class Span extends ImmutableTriple<File, Integer, Integer> {
        public Span(File file, int startLine, int endLine) {
            super(file, startLine, endLine);
        }
        public File getFile() {
            return getLeft();
        }
        public int getStartLine() {
            return getMiddle();
        }
        public int getEndLine() {
            return getRight();
        }
    }
}
