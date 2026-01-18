package io.codiqo.core.java;

import java.math.BigInteger;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import io.codiq.lang.spec.JavaBinarySignatureFormatter;
import io.codiq.lang.spec.JavaConstructorBlockInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;

@Setter
@Getter
@SuperBuilder
class JavaPmdConstructorInfo extends AbstractJavaPmdDeclarationInfo implements JavaConstructorBlockInfo {
    private final Supplier<Boolean> trivialSupplier = Suppliers.memoize(() -> countMethodCalls() <= BigInteger.ONE.intValue());
    private final Supplier<String> signatureSupplier = Suppliers.memoize(() -> {
        String ownerClass = JavaBinaryFormat.getInternalName(getConstructor().getSymbol().getEnclosingClass().getBinaryName());
        String descriptor = JavaBinaryFormat.toMethodDescriptor(getConstructor().getGenericSignature());
        return ownerClass + "." + JavaBinaryFormat.CONSTRUCTOR_NAME + descriptor;
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
    public BinarySignatureData toBinarySignature() {
        return JavaBinarySignatureFormatter.BinarySignatureData.builder()
                .ownerClass(JavaBinaryFormat.getInternalName(getConstructor().getSymbol().getEnclosingClass().getBinaryName()))
                .methodName(JavaBinaryFormat.CONSTRUCTOR_NAME)
                .descriptor(JavaBinaryFormat.toMethodDescriptor(getConstructor().getGenericSignature()))
                .build();
    }
}
