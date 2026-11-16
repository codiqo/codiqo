package io.codiqo.core.java;

import java.util.function.Supplier;

import com.google.common.base.Suppliers;

import io.codiqo.api.code.UnitKind;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

@Setter
@Getter
@SuperBuilder
public class JavaPmdConstructorInfo extends AbstractJavaPmdDeclarationInfo {
    private final Supplier<String> signatureSupplier = Suppliers.memoize(() -> {
        org.objectweb.asm.Type ownerType = JavaBinaryFormat.toOwnerType(getConstructor().getSymbol().getEnclosingClass());
        org.objectweb.asm.commons.Method method = JavaBinaryFormat.toMethod(getConstructor().getGenericSignature());
        return ownerType.getInternalName() + "." + method;
    });

    @Override
    public UnitKind getKind() {
        return UnitKind.CONSTRUCTOR;
    }
    @Override
    public String getSignature() {
        return signatureSupplier.get();
    }
    public ASTConstructorDeclaration getConstructor() {
        return (ASTConstructorDeclaration) getNode();
    }
    @Override
    public JMethodSig getGenericSignature() {
        return getConstructor().getGenericSignature();
    }
}
