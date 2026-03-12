package io.codiqo.core.diff;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.eclipse.jgit.diff.DiffEntry;
import org.jacoco.core.analysis.ILine;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.ProjectSpec;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.FileAnalysis;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import net.sourceforge.pmd.lang.Language;

@Setter
@Getter
@ToString
public class GitFileAnalysis implements FileAnalysis {
    private String oldPath;
    private String newPath;
    private File file;
    private Language language;
    private DiffEntry.ChangeType changeType;
    private String diffText;
    @ToString.Exclude
    private String contentBefore;
    @ToString.Exclude
    private String contentAfter;
    @ToString.Exclude
    private GitStructuredDiff structuredDiff;
    @ToString.Exclude
    private Set<AffectedSymbolInfo> potentiallyAffectedSymbols = Sets.newLinkedHashSet();
    @ToString.Exclude
    private Map<Integer, ILine> lineCoverage = Maps.newHashMap();
    private boolean testFile;
    @Getter(AccessLevel.NONE)
    private Optional<ProjectSpec> project = Optional.empty();

    @Override
    public void lineCoverage(int lineNumber, ILine line) {
        lineCoverage.put(lineNumber, line);
    }
    @Override
    public boolean isExtension(Language lang) {
        return FilenameUtils.isExtension(file.getName(), lang.getExtensions());
    }
    @Override
    public void accept(ProjectSpec spec) {
        project = Optional.of(spec);
    }
    @Override
    public Optional<ProjectSpec> project() {
        return project;
    }
    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(file)
                .append(changeType)
                .append(diffText)
                .toHashCode();
    }
    @Override
    public boolean equals(Object o) {
        GitFileAnalysis that = (GitFileAnalysis) o;
        return new EqualsBuilder()
                .append(file, that.file)
                .append(changeType, that.changeType)
                .append(diffText, that.diffText)
                .isEquals();
    }
}
