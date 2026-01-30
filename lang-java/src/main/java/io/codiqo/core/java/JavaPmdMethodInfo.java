package io.codiqo.core.java;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.BooleanUtils;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;

import io.codiqo.lang.spec.JavaBinarySignatureFormatter;
import io.codiqo.lang.spec.JavaMethodBlockInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;

@Setter
@Getter
@SuperBuilder
class JavaPmdMethodInfo extends AbstractJavaPmdDeclarationInfo implements JavaMethodBlockInfo {
    private static final Pattern GETTER = Pattern.compile("^(get|is)[A-Z].*");
    private static final Pattern SETTER = Pattern.compile("^set[A-Z].*");
    private static final Set<String> OBJECT_METHODS = Sets.newHashSet();

    static {
        for (Method m : Object.class.getDeclaredMethods()) {
            OBJECT_METHODS.add(m.getName().toLowerCase());
        }
    }

    private final Supplier<Boolean> trivialSupplier = Suppliers.memoize(() -> {
        String name = getMethod().getName();
        int count = countMethodCalls();
        if (count == BigInteger.ZERO.intValue()) {
            return true;
        }
        return BooleanUtils.or(
                new boolean[] {
                        GETTER.matcher(name).matches() && count <= BigInteger.ONE.intValue(),
                        SETTER.matcher(name).matches() && count <= BigInteger.ONE.intValue(),
                        OBJECT_METHODS.contains(name.toLowerCase())
                });
    });
    private final Supplier<String> signatureSupplier = Suppliers.memoize(() -> {
        String ownerClass = JavaBinaryFormat.getInternalName(getMethod().getSymbol().getEnclosingClass().getBinaryName());
        String methodName = getMethod().getName();
        String descriptor = JavaBinaryFormat.toMethodDescriptor(getMethod().getGenericSignature());
        return ownerClass + "." + methodName + descriptor;
    });

    @Override
    public boolean isTrivial() {
        return trivialSupplier.get();
    }
    @Override
    public ASTMethodDeclaration getMethod() {
        return (ASTMethodDeclaration) getNode();
    }
    @Override
    public String getSignature() {
        return signatureSupplier.get();
    }
    @Override
    public BinarySignatureData toBinarySignature() {
        return JavaBinarySignatureFormatter.BinarySignatureData.builder()
                .ownerClass(JavaBinaryFormat.getInternalName(getMethod().getSymbol().getEnclosingClass().getBinaryName()))
                .methodName(getMethod().getName())
                .descriptor(JavaBinaryFormat.toMethodDescriptor(getMethod().getGenericSignature()))
                .build();
    }
}
