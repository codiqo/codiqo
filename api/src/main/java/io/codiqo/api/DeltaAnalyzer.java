package io.codiqo.api;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.revwalk.RevCommit;

import io.codiqo.api.diff.CommitAnalysis;
import io.codiqo.api.diff.FileAnalysis;
import io.codiqo.api.diff.FileRevisionInfo;

public interface DeltaAnalyzer extends Closeable {
    CommitAnalysis analyze(String commitId);
    CommitAnalysis analyzeHead();
    CommitAnalysis analyzeCommit(RevCommit commit);

    FileAnalysis analyzeFileDiff(DiffEntry diff, DiffFormatter formatter, RevCommit parent, RevCommit current);
    void analyzeSymbols(FileAnalysis analysis, Path path, Collection<Integer> modifiedLines);

    String getFileContentFromCommit(RevCommit commit, String filePath);
    String getFileContentAtRevision(String filePath, String revisionId);
    List<FileRevisionInfo> getFileHistory(String filePath, int maxRevisions);
}
