package io.codiqo.core.diff;

import java.util.List;
import java.util.LinkedList;

import org.eclipse.jgit.diff.DiffEntry;


import lombok.Data;

@Data
public class GitStructuredDiff {
    private String oldPath;
    private String newPath;
    private DiffEntry.ChangeType changeType;
    private List<GitDiffHunk> hunks = new LinkedList<>();
}