package io.codiqo.core.java;

import java.util.function.Supplier;

import io.codiqo.lang.spec.JavaConstructorBlockInfo;
import io.codiqo.util.Lazy;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

@Setter
@Getter
@SuperBuilder
class JavaPmdConstructorInfo extends AbstractJavaPmdDeclarationInfo implements JavaConstructorBlockInfo {
    private final Supplier<String> signatureSupplier = Lazy.of(() -> {
        org.objectweb.asm.Type ownerType = JavaBinaryFormat.toOwnerType(getConstructor().getSymbol().getEnclosingClass());
        org.objectweb.asm.commons.Method method = JavaBinaryFormat.toMethod(getConstructor().getGenericSignature());
        return ownerType.getInternalName() + "." + method;
    });

    @Override
    public String getSignature() {
        return signatureSupplier.get();
    }
    @Override
    public ASTConstructorDeclaration getConstructor() {
        return (ASTConstructorDeclaration) getNode();
    }
    @Override
    public JMethodSig getGenericSignature() {
        return getConstructor().getGenericSignature();
    }
}
