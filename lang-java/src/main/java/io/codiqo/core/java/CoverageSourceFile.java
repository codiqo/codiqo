package io.codiqo.core.java;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.apache.commons.io.FilenameUtils;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;

import lombok.Value;

/**
 * The one source file a JaCoCo coverage record describes, identified the way JaCoCo itself identifies it: the VM-form
 * package name plus the file name from the class's {@code SourceFile} attribute. Class records and source-file records
 * carry the same two halves, so binding them into one key is what makes a lookup between them comparable at all —
 * assembling the halves into a string at each call site invites two sites to assemble them differently.
 */
@Value
public class CoverageSourceFile {
    String packageName;
    String fileName;

    static CoverageSourceFile of(ISourceFileCoverage source) {
        return new CoverageSourceFile(source.getPackageName(), source.getName());
    }
    /**
     * Empty when the class was compiled without the {@code SourceFile} attribute. JaCoCo creates a source-file record
     * only for classes that carry it ({@code CoverageBuilder.visitCoverage}), so a class without one has no source
     * coverage anywhere to be matched against — there is nothing to derive from the class name.
     */
    static Optional<CoverageSourceFile> of(IClassCoverage cls) {
        return Optional.ofNullable(cls.getSourceFileName()).map(name -> new CoverageSourceFile(cls.getPackageName(), name));
    }
    /** the path of the source file below its source root, for resolving against one */
    Path relativePath() {
        return Paths.get(packageName, fileName);
    }
    /**
     * The same path in the unix form both JaCoCo and SpotBugs report source locations in. Derived from
     * {@link #relativePath()} instead of joined by hand because JaCoCo names the default package with the empty string
     * ({@code ClassCoverageImpl.getPackageName}), and "" + '/' + "Foo.java" yields a leading separator that matches
     * nothing a source root relativizes to.
     */
    String unixPath() {
        return FilenameUtils.separatorsToUnix(relativePath().toString());
    }
    @Override
    public String toString() {
        return unixPath();
    }
}
