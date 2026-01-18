package io.codiqo.core.java;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.maven.artifact.Artifact;

import com.google.common.collect.BiMap;

import io.codiq.lang.spec.JBinaryMethodSig;
import io.codiq.lang.spec.JavaBinarySignatureFormatter;
import io.codiqo.api.MavenProjectSpec;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import lombok.EqualsAndHashCode;
import lombok.experimental.Delegate;
import net.sourceforge.pmd.lang.java.ast.JavaNode;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class PmdJBinaryMethodSig implements JBinaryMethodSig {
    private final String methodName;
    private final String descriptor;
    private final JavaNode call;
    private final JClassSymbol symbol;
    private final boolean isConstructor;
    @Delegate
    @EqualsAndHashCode.Include
    private final JMethodSig signature;
    private Optional<ClassInfo> classInfo;
    private Optional<Artifact> artifact = Optional.empty();

    public PmdJBinaryMethodSig(JClassSymbol symbol, String methodName, JavaNode call, JMethodSig signature) {
        this.symbol = Objects.requireNonNull(symbol);
        this.methodName = Objects.requireNonNull(methodName);
        this.call = Objects.requireNonNull(call);
        this.signature = Objects.requireNonNull(signature);
        this.descriptor = JavaBinaryFormat.toMethodDescriptor(signature);
        this.isConstructor = BooleanUtils.or(new boolean[] {
                signature.isConstructor(),
                methodName.equals(JavaBinaryFormat.CONSTRUCTOR_NAME)
        });
    }
    @Override
    public void accept(MavenProjectSpec spec) {
        classInfo = Optional.ofNullable(spec.getClassInfo(symbol.getBinaryName()));
        classInfo.ifPresent(info -> {
            Resource resource = info.getResource();
            if (Objects.nonNull(resource)) {
                File file = resource.getClasspathElementFile();
                if (file.exists()) {
                    BiMap<File, Artifact> inverse = spec.getArtifacts().inverse();
                    artifact = Optional.ofNullable(inverse.get(file));
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
    public boolean isConstructor() {
        return isConstructor;
    }
    @Override
    public int getBeginLine() {
        return call.getBeginLine();
    }
    @Override
    public int getBeginColumn() {
        return call.getBeginColumn();
    }
    @Override
    public int getEndLine() {
        return call.getEndLine();
    }
    @Override
    public int getEndColumn() {
        return call.getEndColumn();
    }
    @Override
    public String getMethodName() {
        return methodName;
    }
    @Override
    public String getMethodDescriptor() {
        return descriptor;
    }
    @Override
    public JavaNode getCall() {
        return call;
    }
    @Override
    public String getSignature() {
        return JavaBinaryFormat.getInternalName(symbol.getBinaryName()) + "." + getMethodName() + getMethodDescriptor();
    }
    @Override
    public BinarySignatureData toBinarySignature() {
        return JavaBinarySignatureFormatter.BinarySignatureData.builder()
                .ownerClass(JavaBinaryFormat.getInternalName(symbol.getBinaryName()))
                .methodName(getMethodName())
                .descriptor(getMethodDescriptor())
                .build();
    }
}
