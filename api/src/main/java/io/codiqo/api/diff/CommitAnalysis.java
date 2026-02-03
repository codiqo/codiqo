package io.codiqo.api.diff;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.cpd.CopyPasteDetectionSummary;

public interface CommitAnalysis extends Iterable<FileAnalysis> {
    String getCommitId();
    String getMessage();
    String getAuthor();
    String getAuthorEmail();
    Date getAuthorTimestamp();
    String getCommitter();
    String getCommitterEmail();
    Date getCommitTimestamp();
    List<String> getParentIds();
    List<String> getBranches();
    boolean isMergeCommit();
    boolean isRevertCommit();
    String getRevertedCommitId();
    int getFilesChanged();
    boolean isPresent(File file, CodeBlockInfo block);

    Collection<File> locations();
    Collection<CopyPasteDetectionSummary> cpd();
}