package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.CommitAnalysis;
import net.sourceforge.pmd.lang.Language;

public interface LanguageSpec extends Closeable {
    Language lang();
    boolean supportsCpd();
    default void load() {
    }
    Collection<CodeBlockInfo> parse(ProjectSpec owner, Collection<File> files) throws IOException;
    /**
     * the code units {@code file} had in {@code contentBefore} that are absent from {@code current}, its code units
     * now, as blocks located in that previous content. A language that cannot tell returns none.
     */
    default Collection<CodeBlockInfo> parseRemoved(ProjectSpec owner, File file, String contentBefore, Collection<CodeBlockInfo> current) throws IOException {
        return List.of();
    }
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
}
