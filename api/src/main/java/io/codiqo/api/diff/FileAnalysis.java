package io.codiqo.api.diff;

import java.nio.file.Path;
import java.util.Set;

import org.eclipse.jgit.diff.DiffEntry;

import net.sourceforge.pmd.lang.Language;

public interface FileAnalysis {
    Language getLanguage();
    Path getPath();
    DiffEntry.ChangeType getChangeType();
    String getContentBefore();
    String getContentAfter();
    String getDiffText();
    Set<AffectedSymbolInfo> getPotentiallyAffectedSymbols();
}