package io.codiqo.api.diff;

import java.io.File;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.jgit.diff.DiffEntry;

import io.codiqo.api.ProjectSpec;
import io.codiqo.api.SourceLanguage;
import io.codiqo.api.coverage.LineCoverage;

public interface FileAnalysis extends Consumer<ProjectSpec> {
    String getOldPath();
    String getNewPath();
    File getFile();
    SourceLanguage getLanguage();
    DiffEntry.ChangeType getChangeType();
    String getContentBefore();
    String getContentAfter();
    String getDiffText();
    Set<AffectedSymbolInfo> getPotentiallyAffectedSymbols();
    /** the units the commit removed from the file, as they were in its previous revision */
    Set<AffectedSymbolInfo> getRemovedSymbols();
    boolean isTestFile();
    boolean isExtension(SourceLanguage language);
    Optional<ProjectSpec> project();
    IntObjectMap<LineCoverage> getLineCoverage();
    void lineCoverage(int lineNumber, LineCoverage line);
}