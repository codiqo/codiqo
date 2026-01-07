package io.codiqo.api.diff;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.codiqo.api.code.CodeBlockInfo;

public interface CommitAnalysis {
    String getCommitId();
    String getMessage();
    String getAuthor();
    String getAuthorEmail();
    Instant getAuthorTimestamp();
    String getCommitter();
    String getCommitterEmail();
    Instant getCommitTimestamp();
    List<String> getParentIds();
    List<String> getBranches();
    boolean isMergeCommit();
    int getFilesChanged();
    Set<FileAnalysis> getFiles();
    Set<File> files();
    boolean isPresent(File file, CodeBlockInfo block);
}