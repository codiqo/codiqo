package io.codiqo.api.code;

import java.io.File;
import java.util.List;

import org.eclipse.collections.api.map.primitive.IntObjectMap;

import io.codiqo.api.coverage.CodeBlockCoverage;
import io.codiqo.api.coverage.LineCoverage;
import io.codiqo.api.metrics.CodeBlockMetrics;

public interface CodeBlockInfo {
    String getSignature();
    UnitKind getKind();
    /** the unit's name and parameter types as a person reads them, which the module's largest units are listed by */
    String getDisplaySignature();
    /** the qualified name of the type that declares the unit, by which the module's classes are counted */
    String getDeclaringType();
    List<String> getModifiers();
    int getArity();
    File getFile();
    SourceLocation getLocation();
    String getBody();
    boolean isTrivial();
    CodeBlockMetrics metrics();
    CodeBlockCoverage coverage();
    IntObjectMap<LineCoverage> getLineCoverage();
    /** the findings of the language's static-analysis tools on the unit, in the order the submission lists them */
    List<Finding> getFindings();
}
