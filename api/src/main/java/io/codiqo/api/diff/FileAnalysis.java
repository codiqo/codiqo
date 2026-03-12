package io.codiqo.api.diff;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jgit.diff.DiffEntry;
import org.jacoco.core.analysis.ILine;

import io.codiqo.api.ProjectSpec;
import net.sourceforge.pmd.lang.Language;

public interface FileAnalysis extends Consumer<ProjectSpec> {
    String getOldPath();
    String getNewPath();
    File getFile();
    Language getLanguage();
    DiffEntry.ChangeType getChangeType();
    String getContentBefore();
    String getContentAfter();
    String getDiffText();
    Set<AffectedSymbolInfo> getPotentiallyAffectedSymbols();
    boolean isTestFile();
    boolean isExtension(Language lang);
    Optional<ProjectSpec> project();
    Map<Integer, ILine> getLineCoverage();
    void lineCoverage(int lineNumber, ILine line);
}