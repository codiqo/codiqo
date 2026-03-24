package io.codiqo.core.java;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import io.codiqo.api.MavenProjectSpec;
import io.codiqo.lang.spec.JInvocationBlock;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.sourceforge.pmd.lang.java.ast.ASTEnumConstant;
import net.sourceforge.pmd.lang.java.ast.ASTExplicitConstructorInvocation;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.MethodUsage;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class PmdJInvocationBlock implements JInvocationBlock {
    @Getter
    @EqualsAndHashCode.Include
    private final MethodUsage usage;
    private final JMethodSig signature;
    @Getter
    private final JClassSymbol declaringType;
    private final Type ownerType;
    private final String displayName;
    private Method method;
    private Optional<ClassInfo> classInfo;
    private Optional<Artifact> artifact = Optional.empty();

    protected PmdJInvocationBlock(MethodUsage usage) {
        this.usage = Objects.requireNonNull(usage);
        this.signature = usage.getOverloadSelectionInfo().getMethodType();
        this.declaringType = (JClassSymbol) signature.getDeclaringType().getSymbol();
        this.ownerType = JavaBinaryFormat.toOwnerType(declaringType);
        this.method = JavaBinaryFormat.toMethod(signature);
        this.displayName = JavaBinaryFormat.toDisplayName(signature, false);
    }
    @Override
    public void accept(MavenProjectSpec spec) {
        classInfo = Optional.ofNullable(spec.getClassInfo(declaringType.getBinaryName()));
        classInfo.ifPresent(info -> {
            Resource resource = info.getResource();
            if (Objects.nonNull(resource)) {
                File file = resource.getClasspathElementFile();
                if (Objects.nonNull(file)) {
                    artifact = Optional.ofNullable(spec.getArtifacts().inverse().get(file));
                }
            }
        });
    }
    @Override
    public Optional<ClassInfo> classInfo() {
        return classInfo;
    }
    @Override
    public Optional<Artifact> artifact() {
        return artifact;
    }
    @Override
    public int getBeginLine() {
        return usage.getBeginLine();
    }
    @Override
    public int getBeginColumn() {
        return usage.getBeginColumn();
    }
    @Override
    public int getEndLine() {
        return usage.getEndLine();
    }
    @Override
    public int getEndColumn() {
        return usage.getEndColumn();
    }
    @Override
    public int getModifiers() {
        return signature.getModifiers();
    }
    @Override
    public boolean isConstructor() {
        return signature.isConstructor();
    }
    @Override
    public boolean isAbstract() {
        return signature.isAbstract();
    }
    @Override
    public boolean isStatic() {
        return signature.isStatic();
    }
    @Override
    public boolean isVarargs() {
        return signature.isVarargs();
    }
    @Override
    public String getName() {
        return method.getName();
    }
    @Override
    public String getDisplayName() {
        return displayName;
    }
    @Override
    public String getMethodDescriptor() {
        return method.getDescriptor();
    }
    @Override
    public String getOwnerClass() {
        return ownerType.getInternalName();
    }
    @Override
    public String getSignature() {
        return ownerType.getInternalName() + "." + method;
    }
    @Override
    public boolean isInterfaceCall() {
        return declaringType.isInterface();
    }
    @Override
    public boolean isMethodReference() {
        return usage instanceof ASTMethodReference;
    }
    @Override
    public boolean isExplicitConstructor() {
        return usage instanceof ASTExplicitConstructorInvocation;
    }
    @Override
    public boolean isEnumConstant() {
        return usage instanceof ASTEnumConstant;
    }
}
