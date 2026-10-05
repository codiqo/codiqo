package io.codiqo.lang.spec;

import java.util.Collection;
import java.util.List;

import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.jacoco.core.analysis.ILine;

import edu.umd.cs.findbugs.BugInstance;
import io.codiqo.api.code.CodeBlockInfo;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

public interface JavaCodeBlockInfo extends CodeBlockInfo {
    ASTTypeDeclaration getType();
    ASTTypeDeclaration getEnclosingType();
    JMethodSig getGenericSignature();
    IntObjectMap<ILine> getLineCoverage();
    void lineCoverage(int lineNumber, ILine line);
    void spotbug(BugInstance violation);
    Collection<BugInstance> getSpotbugs();
    Collection<JInvocationBlock> getInvocations();
    List<String> getModifiers();

    ASTExecutableDeclaration getDeclaration();
    int getArity();

    boolean isFinal();
    boolean isStatic();
    boolean isSynchronized();
    boolean isAbstract();
}
