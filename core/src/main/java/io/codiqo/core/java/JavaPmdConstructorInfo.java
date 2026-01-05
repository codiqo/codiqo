package io.codiqo.core.java;

import java.math.BigInteger;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;

@Setter
@Getter
@SuperBuilder
public class JavaPmdConstructorInfo extends AbstractJavaPmdDeclarationInfo implements JavaConstructorBlockInfo {
    private final Supplier<Boolean> trivialSupplier = Suppliers.memoize(new Supplier<Boolean>() {
        @Override
        public Boolean get() {
            return countMethodCalls() <= BigInteger.ONE.intValue();
        }
    });

    @Override
    public boolean isTrivial() {
        return trivialSupplier.get();
    }
    @Override
    public ASTConstructorDeclaration getConstructor() {
        return (ASTConstructorDeclaration) getNode();
    }
}
