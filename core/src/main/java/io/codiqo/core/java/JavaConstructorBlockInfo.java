package io.codiqo.core.java;

import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;

public interface JavaConstructorBlockInfo extends JavaCodeBlockInfo {
    ASTConstructorDeclaration getConstructor();
}
