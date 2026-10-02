package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.ParsedSources;
import io.codiqo.api.code.PreviousRevision;
import io.codiqo.api.diff.CommitAnalysis;
import net.sourceforge.pmd.lang.Language;

public interface LanguageSpec extends Closeable {
    Language lang();
    boolean supportsCpd();
    default void load() {
    }
    ParsedSources parse(ProjectSpec owner, Collection<File> files) throws IOException;
    /**
     * per revision, the code units its previous content had that the file no longer declares, as blocks located in
     * that previous content. Only called for files the index parsed, all owned by {@code owner}, so a language can
     * share one parser setup across them. A language that cannot tell returns none.
     */
    default MultiValuedMap<File, CodeBlockInfo> parseRemoved(ProjectSpec owner, Collection<PreviousRevision> revisions) throws IOException {
        return new ArrayListValuedHashMap<>();
    }
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
}
