package io.codiqo.lang.spec;

import java.io.File;
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
import io.codiqo.core.java.JavaBinaryFormat;

public class PmdAffectedSymbolInfo implements AffectedSymbolInfo {
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
        if (block.getGenericSignature().isConstructor()) {
            return UnitKind.CONSTRUCTOR;
        }
        return UnitKind.METHOD;
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
     * The symbol as the language server's call hierarchy is asked about it: a constructor is asked about as its class,
     * which jdt.ls answers with the class's instantiations.
     */
    public SymbolKind lspKind() {
        if (block.getGenericSignature().isConstructor()) {
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
}
