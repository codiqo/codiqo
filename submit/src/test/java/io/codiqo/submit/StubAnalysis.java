package io.codiqo.submit;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.slf4j.event.Level;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.coverage.ExcludedCoverageClass;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;
import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;

/**
 * A CommitAnalysis with nothing in it, for the tests that assert what a populator sequence writes onto the
 * submission rather than what a particular delta contained.
 */
class StubAnalysis implements CommitAnalysis {
    static final LogFactory LOGS = clazz -> new NoopLog();

    private final List<ExcludedCoverageClass> excluded = new ArrayList<>();

    StubAnalysis withExcludedCoverageClass(ExcludedCoverageClass value) {
        excluded.add(value);
        return this;
    }
    @Override
    public Iterator<FileAnalysis> iterator() {
        return Collections.<FileAnalysis> emptyList().iterator();
    }
    @Override
    public String getCommitId() {
        return "0000000000000000000000000000000000000000";
    }
    @Override
    public String getMessage() {
        return "test";
    }
    @Override
    public String getAuthor() {
        return "Test Author";
    }
    @Override
    public String getAuthorEmail() {
        return "test@example.com";
    }
    @Override
    public Date getAuthorTimestamp() {
        return new Date();
    }
    @Override
    public String getCommitter() {
        return getAuthor();
    }
    @Override
    public String getCommitterEmail() {
        return getAuthorEmail();
    }
    @Override
    public Date getCommitTimestamp() {
        return getAuthorTimestamp();
    }
    @Override
    public List<String> getParentIds() {
        return List.of();
    }
    @Override
    public boolean isHistoryIncomplete() {
        return false;
    }
    @Override
    public List<String> getBranches() {
        return List.of();
    }
    @Override
    public boolean isMergeCommit() {
        return false;
    }
    @Override
    public boolean isRevertCommit() {
        return false;
    }
    @Override
    public String getRevertedCommitId() {
        return null;
    }
    @Override
    public int getFilesChanged() {
        return 0;
    }
    @Override
    public boolean isPresent(File file, CodeBlockInfo block) {
        return false;
    }
    @Override
    public Collection<File> locations() {
        return List.of();
    }
    @Override
    public Collection<CopyPasteDetectionSummary> cpd() {
        return List.of();
    }
    @Override
    public Collection<ExcludedCoverageClass> excludedCoverageClasses() {
        return excluded;
    }

    private static final class NoopLog implements Log {
        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public void log(Level level, String message, Object... formatArgs) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
