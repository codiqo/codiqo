package io.codiqo.api.diff;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.codiqo.api.code.CodeBlockInfo;

public interface CommitAnalysis {
    String getCommitId();
    String getCommitIdShort();
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
    String getGpgSignature();
    String getEncoding();
    int getFilesChanged();
    Set<FileAnalysis> getFiles();
    Set<Path> paths();
    boolean isPresent(Path path, CodeBlockInfo block);
}