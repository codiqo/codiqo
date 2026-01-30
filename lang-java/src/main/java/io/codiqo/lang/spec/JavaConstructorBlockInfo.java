package io.codiqo.lang.spec;

import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;

public interface JavaConstructorBlockInfo extends JavaCodeBlockInfo {
    ASTConstructorDeclaration getConstructor();
}
