package io.codiqo.core.java;

import java.math.BigInteger;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import io.codiqo.lang.spec.JavaConstructorBlockInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

@Setter
@Getter
@SuperBuilder
class JavaPmdConstructorInfo extends AbstractJavaPmdDeclarationInfo implements JavaConstructorBlockInfo {
    private final Supplier<Boolean> trivialSupplier = Suppliers.memoize(() -> getInvocations().size() <= BigInteger.ONE.intValue());
    private final Supplier<String> signatureSupplier = Suppliers.memoize(() -> {
        org.objectweb.asm.Type ownerType = JavaBinaryFormat.toOwnerType(getConstructor().getSymbol().getEnclosingClass());
        org.objectweb.asm.commons.Method method = JavaBinaryFormat.toMethod(getConstructor().getGenericSignature());
        return ownerType.getInternalName() + "." + method;
    });

    @Override
    public boolean isTrivial() {
        return trivialSupplier.get();
    }
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
