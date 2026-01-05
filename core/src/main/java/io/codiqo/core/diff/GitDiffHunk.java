package io.codiqo.core.diff;

import org.eclipse.jgit.diff.Edit;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GitDiffHunk {
    private int oldStartLine;
    private int oldEndLine;
    private int newStartLine;
    private int newEndLine;
    private Edit.Type type;
}