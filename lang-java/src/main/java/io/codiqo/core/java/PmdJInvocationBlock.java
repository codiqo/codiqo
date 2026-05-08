package io.codiqo.core.java;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.BooleanUtils;

import org.apache.maven.artifact.Artifact;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.MavenProjectSpec;
import io.codiqo.lang.spec.JInvocationBlock;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.sourceforge.pmd.lang.ast.impl.javacc.JavaccToken;
import net.sourceforge.pmd.lang.document.FileLocation;
import net.sourceforge.pmd.lang.java.ast.ASTArgumentList;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTEnumConstant;
import net.sourceforge.pmd.lang.java.ast.ASTExplicitConstructorInvocation;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.JavaTokenKinds;
import net.sourceforge.pmd.lang.java.ast.MethodUsage;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class PmdJInvocationBlock implements JInvocationBlock {
    private static final String METHOD_DESCRIPTOR_PREFIX = "(";
    private static final Set<Integer> CTOR_KEYWORD_KINDS = ImmutableSet.of(JavaTokenKinds.THIS, JavaTokenKinds.SUPER);

    @Getter
    @EqualsAndHashCode.Include
    private final MethodUsage usage;
    private final JMethodSig signature;
    @Getter
    private final JClassSymbol declaringType;
    private final Type ownerType;
    private final String displayName;
    private final Method method;
    private Optional<ClassInfo> classInfo;
    private Optional<Artifact> artifact = Optional.empty();
    private Optional<String> targetDescriptor = Optional.empty();
    private Optional<String> targetOwner = Optional.empty();
    private final Supplier<FileLocation> nameLocation = Suppliers.memoize(this::computeNameLocation);

    protected PmdJInvocationBlock(MethodUsage usage) {
        this.usage = Objects.requireNonNull(usage);
        this.signature = usage.getOverloadSelectionInfo().getMethodType();
        this.declaringType = (JClassSymbol) signature.getDeclaringType().getSymbol();
        this.ownerType = JavaBinaryFormat.toOwnerType(declaringType);
        this.method = JavaBinaryFormat.toMethod(signature.getSymbol().getGenericSignature());
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

            Map<ClassGraphSpec.MethodKey, ClassGraphSpec.MethodEntry> cache = isConstructor() ? spec.getConstructors(info) : spec.getMethods(info);
            ClassGraphSpec.MethodEntry entry = cache.get(new ClassGraphSpec.MethodKey(method.getName(), method.getDescriptor()));

            // inner class constructors: bytecode has synthetic enclosing-instance first param that PMD doesn't see
            if (Objects.isNull(entry) && isConstructor()) {
                JClassSymbol enclosing = declaringType.getEnclosingClass();
                if (Objects.nonNull(enclosing) && BooleanUtils.isFalse(declaringType.isStatic())) {
                    String outerDesc = Type.getObjectType(JavaBinaryFormat.getInternalName(enclosing.getBinaryName())).getDescriptor();
                    entry = cache.get(new ClassGraphSpec.MethodKey(method.getName(),
                            METHOD_DESCRIPTOR_PREFIX + outerDesc + method.getDescriptor().substring(METHOD_DESCRIPTOR_PREFIX.length())));
                }
            }

            targetDescriptor = Optional.ofNullable(entry).map(ClassGraphSpec.MethodEntry::getDescriptor);
            targetOwner = Optional.of(JavaBinaryFormat.getInternalName(info.getName()));
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
    public Optional<String> targetDescriptor() {
        return targetDescriptor;
    }
    @Override
    public Optional<String> targetOwner() {
        return targetOwner;
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
    public int getNameStartLine() {
        return nameLocation.get().getStartLine();
    }
    @Override
    public int getNameStartColumn() {
        return nameLocation.get().getStartColumn();
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
    private FileLocation computeNameLocation() {
        if (usage instanceof ASTMethodCall) {
            return findIdentifierBeforeArgs((ASTMethodCall) usage, method.getName()).getReportLocation();
        }
        if (usage instanceof ASTConstructorCall) {
            return ((ASTConstructorCall) usage).getTypeNode().getReportLocation();
        }
        if (usage instanceof ASTExplicitConstructorInvocation) {
            return findKeywordBeforeArgs((ASTExplicitConstructorInvocation) usage).getReportLocation();
        }
        if (usage instanceof ASTMethodReference) {
            return ((ASTMethodReference) usage).getLastToken().getReportLocation();
        }
        if (usage instanceof ASTEnumConstant) {
            return findFirstIdentifier((ASTEnumConstant) usage).getReportLocation();
        }
        return usage.getReportLocation();
    }
    private static JavaccToken findIdentifierBeforeArgs(ASTMethodCall call, String name) {
        ASTArgumentList args = call.getArguments();
        JavaccToken stopToken = args.getFirstToken();
        JavaccToken cursor = call.getFirstToken();
        JavaccToken nameToken = null;

        while (Objects.nonNull(cursor) && cursor != stopToken) {
            if (cursor.kind == JavaTokenKinds.IDENTIFIER && name.equals(cursor.getImage())) {
                nameToken = cursor;
            }
            cursor = cursor.getNext();
        }
        return Optional.ofNullable(nameToken).orElse(call.getFirstToken());
    }
    private static JavaccToken findKeywordBeforeArgs(ASTExplicitConstructorInvocation ctor) {
        ASTArgumentList args = ctor.getArguments();
        JavaccToken stopToken = args.getFirstToken();
        JavaccToken cursor = ctor.getFirstToken();
        JavaccToken keywordToken = null;

        while (Objects.nonNull(cursor) && cursor != stopToken) {
            if (CTOR_KEYWORD_KINDS.contains(cursor.kind)) {
                keywordToken = cursor;
            }
            cursor = cursor.getNext();
        }
        return Optional.ofNullable(keywordToken).orElse(ctor.getFirstToken());
    }
    private static JavaccToken findFirstIdentifier(ASTEnumConstant constant) {
        JavaccToken cursor = constant.getFirstToken();
        while (Objects.nonNull(cursor) && cursor.kind != JavaTokenKinds.IDENTIFIER) {
            cursor = cursor.getNext();
        }
        return Optional.ofNullable(cursor).orElse(constant.getFirstToken());
    }
}
