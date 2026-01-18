package io.codiq.lang.spec;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.jacoco.core.analysis.ILine;

import edu.umd.cs.findbugs.BugInstance;
import io.codiqo.api.code.CodeBlockInfo;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;

public interface JavaCodeBlockInfo extends CodeBlockInfo, JavaBinarySignatureFormatter {
    ASTTypeDeclaration getType();
    ASTTypeDeclaration getEnclosingType();
    Map<Integer, ILine> getLineCoverage();
    void lineCoverage(int lineNumber, ILine line);
    void spotbug(BugInstance violation);
    Collection<BugInstance> getSpotbugs();
    Collection<JBinaryMethodSig> getMethodCalls();
    List<String> getModifiers();
    boolean isFinal();
    boolean isStatic();
    boolean isSynchronized();
    boolean isAbstract();
}
