package io.codiqo.core.diff;

import java.io.File;
import java.util.Set;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.eclipse.jgit.diff.DiffEntry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.collect.Sets;

import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.api.diff.FileAnalysis;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import net.sourceforge.pmd.lang.Language;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@ToString
public class GitFileAnalysis implements FileAnalysis {
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
    private boolean testFile;

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
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        GitFileAnalysis that = (GitFileAnalysis) o;
        return new EqualsBuilder()
                .append(file, that.file)
                .append(changeType, that.changeType)
                .append(diffText, that.diffText)
                .isEquals();
    }
}
