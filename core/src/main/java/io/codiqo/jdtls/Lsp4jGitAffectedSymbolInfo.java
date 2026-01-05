package io.codiqo.jdtls;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.DocumentSymbol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.collect.Lists;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.diff.AffectedSymbolInfo;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Lsp4jGitAffectedSymbolInfo implements AffectedSymbolInfo {
    private Path path;
    private DocumentSymbol symbol;
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
    public int hashCode() {
        return symbol.hashCode();
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        Lsp4jGitAffectedSymbolInfo that = (Lsp4jGitAffectedSymbolInfo) o;
        return symbol.equals(that.symbol);
    }
    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.NO_CLASS_NAME_STYLE)
                .append("kind", getSymbol().getKind().name())
                .append("name", getSymbol().getName())
                .append("location", getPath())
                .build();
    }
}
