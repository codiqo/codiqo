package io.codiqo.core.diff;

import org.eclipse.jgit.diff.Edit;

import lombok.Data;

@Data
public class GitDiffHunk {
    private int oldStartLine;
    private int oldEndLine;
    private int newStartLine;
    private int newEndLine;
    private Edit.Type type;
}