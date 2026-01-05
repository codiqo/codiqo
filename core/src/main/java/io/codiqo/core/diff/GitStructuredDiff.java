package io.codiqo.core.diff;

import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.collect.Lists;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GitStructuredDiff {
    private String oldPath;
    private String newPath;
    private DiffEntry.ChangeType changeType;
    private List<GitDiffHunk> hunks = Lists.newLinkedList();
}