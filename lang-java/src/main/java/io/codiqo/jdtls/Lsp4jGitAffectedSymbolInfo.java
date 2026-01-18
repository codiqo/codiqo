package io.codiqo.jdtls;

import java.io.File;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;

import com.google.common.collect.Lists;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.sourceforge.pmd.lang.Language;

@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Lsp4jGitAffectedSymbolInfo implements Lsp4jAffectedSymbolInfo {
    @EqualsAndHashCode.Include
    private DocumentSymbol symbol;
    private Language language;
    private File file;
    private SourceLocation location;
    private List<CallHierarchyIncomingCall> incomingCalls = Lists.newLinkedList();
    private Optional<CodeBlockInfo> block = Optional.empty();

    @Override
    public void accept(CodeBlockInfo info) {
        this.block = Optional.of(info);
    }
    @Override
    public Optional<CodeBlockInfo> block() {
        return block;
    }
    @Override
    public SymbolKind getKind() {
        return symbol.getKind();
    }
    @Override
    public String getName() {
        return symbol.getName();
    }
    @Override
    public List<SymbolTag> getTags() {
        return symbol.getTags();
    }
    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.NO_CLASS_NAME_STYLE)
                .append("kind", getSymbol().getKind().name())
                .append("name", getSymbol().getName())
                .append("location", getFile())
                .append("incomingCalls", getIncomingCalls().size())
                .build();
    }
}
