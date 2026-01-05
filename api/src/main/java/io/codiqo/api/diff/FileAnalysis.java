package io.codiqo.api.diff;

import java.io.File;
import java.util.Set;

import org.eclipse.jgit.diff.DiffEntry;

import net.sourceforge.pmd.lang.Language;

public interface FileAnalysis {
    Language getLanguage();
    File getFile();
    DiffEntry.ChangeType getChangeType();
    String getContentBefore();
    String getContentAfter();
    String getDiffText();
    Set<AffectedSymbolInfo> getPotentiallyAffectedSymbols();
    boolean isTestFile();
}