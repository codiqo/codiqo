package io.codiqo.api;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.revwalk.RevCommit;

import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.diff.FileRevisionInfo;

public interface DeltaAnalyzer {
    CommitAnalysis analyze() throws Exception;
    CommitAnalysis analyzeCommit(RevCommit commit) throws Exception;
    CommitAnalysis analyzeUncommittedNoHead(Status status) throws Exception;
    CommitAnalysis analyzeUncommitted(Status status) throws Exception;

    FileAnalysis analyzeFileDiff(DiffEntry diff, DiffFormatter formatter, RevCommit parent, RevCommit current) throws Exception;
    FileAnalysis analyzeUncommittedFileDiff(DiffEntry diff, DiffFormatter formatter, RevCommit headCommit) throws Exception;
    Optional<FileAnalysis> analyzeUntrackedFile(String filePath) throws Exception;

    Optional<String> getFileContentFromCommit(RevCommit commit, String filePath) throws Exception;
    Optional<String> getFileContentFromWorkingTree(String filePath) throws Exception;
    Optional<String> getFileContentAtRevision(String filePath, String revisionId) throws Exception;
    List<FileRevisionInfo> getFileHistory(String filePath, int maxRevisions) throws Exception;

    void analyzeSymbols(FileAnalysis analysis, File file, Collection<Integer> modifiedLines);
}
