package io.codiqo.lang.spec;

import java.util.Collection;

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
    void lineCoverage(int lineNumber, LineCoverage line);
    void pmdViolation(RuleViolation violation);
    void spotbug(BugInstance violation);
    Collection<JInvocationBlock> getInvocations();

    ASTExecutableDeclaration getDeclaration();

    boolean isFinal();
    boolean isStatic();
    boolean isSynchronized();
    boolean isAbstract();
}
