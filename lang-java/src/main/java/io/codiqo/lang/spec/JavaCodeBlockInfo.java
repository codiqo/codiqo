package io.codiqo.lang.spec;

import java.util.Collection;
import java.util.List;

import org.eclipse.collections.api.map.primitive.IntObjectMap;

import edu.umd.cs.findbugs.BugInstance;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.coverage.LineCoverage;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.reporting.RuleViolation;

public interface JavaCodeBlockInfo extends CodeBlockInfo {
    ASTTypeDeclaration getType();
    ASTTypeDeclaration getEnclosingType();
    JMethodSig getGenericSignature();
    IntObjectMap<LineCoverage> getLineCoverage();
    void lineCoverage(int lineNumber, LineCoverage line);
    Collection<RuleViolation> getPmdViolations();
    void pmdViolation(RuleViolation violation);
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
