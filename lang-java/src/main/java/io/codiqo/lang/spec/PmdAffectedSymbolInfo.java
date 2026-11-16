package io.codiqo.lang.spec;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;

import com.google.common.collect.Lists;

import io.codiqo.api.code.Caller;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.code.UnitKind;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.InvocationModel;
import io.codiqo.client.model.JavaAnnotationModel;
import io.codiqo.client.model.JavaInfoModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.SignatureFormatModel;
import io.codiqo.core.java.JavaBinaryFormat;
import net.sourceforge.pmd.lang.java.ast.ASTAnnotation;
import net.sourceforge.pmd.lang.java.ast.ASTAnonymousClassDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;

public class PmdAffectedSymbolInfo implements AffectedSymbolInfo {
    private static final EnumSet<CodeUnitModel.OperationEnum> CALLING_OPERATIONS = EnumSet.complementOf(EnumSet.of(CodeUnitModel.OperationEnum.DELETE));

    private final JavaCodeBlockInfo block;
    private final List<SymbolTag> tags = Lists.newLinkedList();
    private final List<CallHierarchyIncomingCall> incomingCalls = Lists.newLinkedList();

    public PmdAffectedSymbolInfo(JavaCodeBlockInfo block) {
        this.block = Objects.requireNonNull(block);

        if (block.getDeclaration().isAnnotationPresent(java.lang.Deprecated.class.getName())) {
            tags.add(SymbolTag.Deprecated);
        }
    }
    @Override
    public UnitKind getKind() {
        return block.getKind();
    }
    /**
     * Converted as the submission reads them, not as the call hierarchy answers: the resolver logs and drops a query that
     * fails, while a caller whose location cannot be read must fail the submission rather than leave the unit with fewer
     * callers than it has, which would understate its blast radius.
     */
    @Override
    public List<Caller> getCallers() {
        return incomingCalls.stream().map(LspSymbols::caller).flatMap(Optional::stream).toList();
    }
    @Override
    public boolean isDeprecated() {
        return LspSymbols.isDeprecated(tags);
    }
    @Override
    public String getName() {
        return JavaBinaryFormat.toDisplayName(block.getGenericSignature(), false);
    }
    @Override
    public File getFile() {
        return block.getFile();
    }
    @Override
    public SourceLocation getLocation() {
        return block.getLocation();
    }
    @Override
    public Optional<CodeBlockInfo> block() {
        return Optional.of(block);
    }
    /**
     * Writes the unit's {@code javaInfo} and marks its signature a JVM descriptor. A removed unit lists no calls: the
     * server stores a unit's calls as what the code calls at this commit, which its API consumers are read from, and a
     * removed unit calls nothing any more.
     */
    @Override
    public void describe(CodeUnitModel unit) {
        JavaInfoModel info = new JavaInfoModel();
        info.setIsAnonymous(block.getType() instanceof ASTAnonymousClassDeclaration);
        info.setIsAbstract(block.isAbstract());
        info.setIsFinal(block.isFinal());
        info.setIsStatic(block.isStatic());
        info.setIsSynchronized(block.isSynchronized());
        info.setEnclosingClass(block.getEnclosingType().getSimpleName());
        if (Objects.nonNull(block.getType())) {
            info.setPackageName(block.getType().getPackageName());
            info.setClassName(block.getType().getSimpleName());
        }
        if (isDeprecated()) {
            info.setIsDeprecated(true);
        }

        if (CALLING_OPERATIONS.contains(unit.getOperation())) {
            block.getInvocations().stream().map(PmdAffectedSymbolInfo::invocation).forEach(info.getInvocations()::add);
        }

        ASTExecutableDeclaration declaration = block.getDeclaration();
        Optional.ofNullable(declaration.getTypeParameters()).ifPresent(t -> t.forEach(tp -> info.getTypeParameters().add(tp.getName())));
        Optional.ofNullable(declaration.getThrowsList()).ifPresent(l -> l.forEach(tt -> info.getThrowsTypes().add(tt.getSimpleName())));
        for (ASTAnnotation annotation : declaration.getDeclaredAnnotations()) {
            JavaAnnotationModel annotationModel = new JavaAnnotationModel();
            annotationModel.setName(annotation.getSimpleName());
            annotationModel.setQualifiedName(annotation.getTypeMirror().getSymbol().getBinaryName());
            info.getAnnotations().add(annotationModel);
        }

        unit.setSignatureFormat(SignatureFormatModel.JVM_DESCRIPTOR);
        unit.setJavaInfo(info);
    }
    /**
     * The symbol as the language server's call hierarchy is asked about it: a constructor is asked about as its class,
     * which jdt.ls answers with the class's instantiations.
     */
    public SymbolKind lspKind() {
        if (UnitKind.CONSTRUCTOR == getKind()) {
            return SymbolKind.Class;
        }
        return SymbolKind.Method;
    }
    public List<SymbolTag> lspTags() {
        return tags;
    }
    public List<CallHierarchyIncomingCall> lspIncomingCalls() {
        return incomingCalls;
    }
    private static InvocationModel invocation(JInvocationBlock call) {
        InvocationModel toReturn = new InvocationModel();
        toReturn.setOwner(call.getOwnerClass());
        toReturn.setName(call.getName());
        toReturn.setDescriptor(call.getMethodDescriptor());
        toReturn.setIsStatic(call.isStatic());
        toReturn.setIsConstructor(call.isConstructor());
        toReturn.setIsMethodReference(call.isMethodReference());
        toReturn.setIsExplicitConstructor(call.isExplicitConstructor());
        toReturn.setIsEnumConstant(call.isEnumConstant());
        toReturn.setInvocationKind(invocationKind(call));
        call.targetDescriptor().ifPresent(toReturn::setTargetDescriptor);
        call.targetOwner().ifPresent(toReturn::setTargetOwner);

        LocationModel location = new LocationModel();
        location.setStartLine(call.getBeginLine());
        location.setStartColumn(call.getBeginColumn());
        location.setEndLine(call.getEndLine());
        location.setEndColumn(call.getEndColumn());
        toReturn.setLocation(location);

        toReturn.setNameStartLine(call.getNameStartLine());
        toReturn.setNameStartColumn(call.getNameStartColumn());

        call.artifactCoordinate().ifPresent(toReturn::setArtifact);
        return toReturn;
    }
    private static InvocationModel.InvocationKindEnum invocationKind(JInvocationBlock call) {
        if (call.isConstructor()) {
            return InvocationModel.InvocationKindEnum.INVOKESPECIAL;
        }
        if (call.isMethodReference()) {
            return InvocationModel.InvocationKindEnum.REFERENCE;
        }
        if (call.isStatic()) {
            return InvocationModel.InvocationKindEnum.INVOKESTATIC;
        }
        if (call.isInterfaceCall()) {
            return InvocationModel.InvocationKindEnum.INVOKEINTERFACE;
        }
        return InvocationModel.InvocationKindEnum.INVOKEVIRTUAL;
    }
}
