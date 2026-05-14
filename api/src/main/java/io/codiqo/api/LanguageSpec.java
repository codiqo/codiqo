package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.diff.CommitAnalysis;
import net.sourceforge.pmd.lang.Language;
import reactor.core.publisher.Mono;

public interface LanguageSpec extends Closeable {
    Language lang();
    boolean supportsCpd();
    default Mono<?> load() {
        return Mono.empty();
    }
    Collection<CodeBlockInfo> parse(ProjectSpec owner, Collection<File> files) throws IOException;
    void captureCoverage(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureViolations(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
    void captureIncomingCalls(IndexingSummary summary, CommitAnalysis analysis) throws IOException;
}
