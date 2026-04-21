package io.codiqo.core.java;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import io.codiqo.lang.spec.JavaMethodBlockInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

@Setter
@Getter
@SuperBuilder
class JavaPmdMethodInfo extends AbstractJavaPmdDeclarationInfo implements JavaMethodBlockInfo {
    private final Supplier<String> signatureSupplier = Suppliers.memoize(() -> {
        org.objectweb.asm.Type ownerType = JavaBinaryFormat.toOwnerType(getMethod().getSymbol().getEnclosingClass());
        org.objectweb.asm.commons.Method method = JavaBinaryFormat.toMethod(getMethod().getGenericSignature());
        return ownerType.getInternalName() + "." + method;
    });

    @Override
    public ASTMethodDeclaration getMethod() {
        return (ASTMethodDeclaration) getNode();
    }
    @Override
    public JMethodSig getGenericSignature() {
        return getMethod().getGenericSignature();
    }
    @Override
    public String getSignature() {
        return signatureSupplier.get();
    }
}
