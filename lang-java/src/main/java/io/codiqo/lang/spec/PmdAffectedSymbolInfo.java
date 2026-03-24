package io.codiqo.lang.spec;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;

import com.google.common.collect.Lists;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.diff.AffectedSymbolInfo;
import io.codiqo.core.java.JavaBinaryFormat;
import lombok.Getter;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;

public class PmdAffectedSymbolInfo implements AffectedSymbolInfo {
    private final JavaCodeBlockInfo block;
    private final Language language;
    @Getter
    private final List<SymbolTag> tags = Lists.newLinkedList();
    @Getter
    private final List<CallHierarchyIncomingCall> incomingCalls = Lists.newLinkedList();

    public PmdAffectedSymbolInfo(JavaCodeBlockInfo block, Language language) {
        this.block = Objects.requireNonNull(block);
        this.language = Objects.requireNonNull(language);

        ASTExecutableDeclaration declaration = null;
        if (block instanceof JavaMethodBlockInfo) {
            declaration = ((JavaMethodBlockInfo) block).getMethod();
        } else if (block instanceof JavaConstructorBlockInfo) {
            declaration = ((JavaConstructorBlockInfo) block).getConstructor();
        }

        if (Objects.nonNull(declaration)) {
            if (declaration.isAnnotationPresent(java.lang.Deprecated.class.getName())) {
                tags.add(SymbolTag.Deprecated);
            }
        }
    }
    @Override
    public SymbolKind getKind() {
        if (block instanceof JavaMethodBlockInfo) {
            return SymbolKind.Method;
        } else if (block instanceof JavaConstructorBlockInfo) {
            return SymbolKind.Class;
        }
        throw new IllegalStateException("unsupported block type: " + block.getClass());
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
    public Language getLanguage() {
        return language;
    }
    @Override
    public SourceLocation getLocation() {
        return block.getLocation();
    }
    @Override
    public Optional<CodeBlockInfo> block() {
        return Optional.of(block);
    }
}
