package io.codiqo.core.java;

import java.util.Collection;

import edu.umd.cs.findbugs.BugInstance;
import io.codiqo.api.code.CodeBlockInfo;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;

public interface JavaCodeBlockInfo extends CodeBlockInfo {
    ASTTypeDeclaration getType();
    void spotbug(BugInstance violation);
    Collection<BugInstance> getSpotbugs();
}
