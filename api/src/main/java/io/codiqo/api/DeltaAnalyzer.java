package io.codiqo.api;

import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.revwalk.RevCommit;

import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.diff.FileRevisionInfo;

public interface DeltaAnalyzer {
    CommitAnalysis analyze() throws Exception;
    CommitAnalysis analyzeCommit(RevCommit commit) throws Exception;

    Optional<FileAnalysis> analyzeFileDiff(DiffEntry diff, DiffFormatter formatter, Optional<RevCommit> parent, RevCommit current) throws Exception;

    Optional<String> fileContentFromCommit(RevCommit commit, String filePath) throws Exception;
    Optional<String> fileContentAtRevision(String filePath, String revisionId) throws Exception;
    List<FileRevisionInfo> getFileHistory(String filePath, int maxRevisions) throws Exception;
}
