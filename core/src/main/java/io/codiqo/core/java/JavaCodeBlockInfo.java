package io.codiqo.core.java;

import io.codiqo.api.code.CodeBlockInfo;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import reactor.core.publisher.Mono;

public interface JavaCodeBlockInfo extends CodeBlockInfo {
    ASTTypeDeclaration getType();
    boolean hasMethodCalls();
    int countMethodCalls();
    Mono<JavaCodeBlockMetrics> metrics();
}
