package io.codiqo.lang.spec;

import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;

public interface JavaMethodBlockInfo extends JavaCodeBlockInfo {
    ASTMethodDeclaration getMethod();
}
