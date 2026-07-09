package io.codiqo.lang.spec;

import java.util.Optional;
import java.util.function.Consumer;

import io.codiqo.api.JvmProjectSpec;
import io.github.classgraph.ClassInfo;
import net.sourceforge.pmd.lang.java.ast.MethodUsage;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;

public interface JInvocationBlock extends Consumer<JvmProjectSpec> {
    MethodUsage getUsage();
    JClassSymbol getDeclaringType();

    int getBeginLine();
    int getBeginColumn();
    int getEndLine();
    int getEndColumn();

    int getNameStartLine();
    int getNameStartColumn();

    int getModifiers();
    boolean isConstructor();
    boolean isAbstract();
    boolean isStatic();
    boolean isVarargs();

    String getName();
    String getDisplayName();
    String getOwnerClass();
    String getMethodDescriptor();
    String getSignature();

    boolean isInterfaceCall();
    boolean isMethodReference();
    boolean isExplicitConstructor();
    boolean isEnumConstant();

    Optional<ClassInfo> classInfo();
    Optional<String> artifactCoordinate();

    Optional<String> targetDescriptor();
    Optional<String> targetOwner();
}
