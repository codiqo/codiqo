package io.codiqo.core.java;

import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;

public interface JavaMethodBlockInfo extends JavaCodeBlockInfo {
    ASTMethodDeclaration getMethod();
}
