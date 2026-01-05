package io.codiqo.core.java;

import io.codiqo.api.code.CodeBlockInfo;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;

public interface JavaCodeBlockInfo extends CodeBlockInfo {
    ASTTypeDeclaration getType();
}
