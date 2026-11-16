package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

import com.google.common.collect.Multimap;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.ParsedSources;
import io.codiqo.api.code.PreviousRevision;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.CommitAnalysis;
import net.sourceforge.pmd.lang.Language;

public interface LanguageSpec extends Closeable {
    /** the language as PMD knows it, which its copy-paste detection reads */
    Language lang();
    SourceLanguage language();
    boolean supportsCpd();
    default void load() {}
    ParsedSources parse(ProjectSpec owner, Collection<File> files) throws IOException;
    Multimap<File, CodeBlockInfo> parseRemoved(ProjectSpec owner, Collection<PreviousRevision> revisions) throws IOException;
    /**
     * The unit a block becomes in the commit's analysis, one the commit changed or removed. The block is one this
     * language's {@link #parse} or {@link #parseRemoved} returned. Each call makes a new unit, which the analysis keeps:
     * the callers found later are added to that one.
     */
    AffectedSymbolInfo symbol(CodeBlockInfo block);
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
}
