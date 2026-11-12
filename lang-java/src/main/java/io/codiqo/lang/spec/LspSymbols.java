package io.codiqo.lang.spec;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;

import io.codiqo.api.code.Caller;
import io.codiqo.api.code.SourceLocation;
import io.codiqo.api.code.UnitKind;
import lombok.experimental.UtilityClass;

/** The language server's symbols as the analysis's neutral units read them */
@UtilityClass
public class LspSymbols {
    /** the language server counts lines and columns from zero, the analysis from one */
    public static final int LSP_OFFSET = BigDecimal.ONE.intValue();

    /**
     * The language server's symbol kinds as unit kinds. LSP has more kinds than a unit has (26 against 11), so every
     * value is mapped explicitly and the switch stays exhaustive: an unmapped kind aborts the whole run, and jdt.ls, for
     * example, reports enum constants that call a changed member as EnumMember, which did abort runs before it was mapped.
     */
    public UnitKind unitKind(SymbolKind kind) {
        return switch (kind) {
            case Class, Object, TypeParameter -> UnitKind.CLASS;
            case Interface -> UnitKind.INTERFACE;
            case Enum -> UnitKind.ENUM;
            case EnumMember -> UnitKind.ENUM_MEMBER;
            case Struct -> UnitKind.RECORD;
            case Method, Operator -> UnitKind.METHOD;
            case Constructor -> UnitKind.CONSTRUCTOR;
            case Function -> UnitKind.FUNCTION;
            case Field, Property, Variable, Event, Key -> UnitKind.FIELD;
            case Constant, String, Number, Boolean, Array, Null -> UnitKind.CONSTANT;
            case Module, Namespace, Package, File -> UnitKind.MODULE;
        };
    }
    /** an incoming call as the code unit that makes it, or none when the language server named no caller */
    public Optional<Caller> caller(CallHierarchyIncomingCall call) {
        return Optional.ofNullable(call.getFrom()).map(from -> new Caller(
                from.getName(),
                from.getDetail(),
                path(from.getUri()),
                Optional.ofNullable(from.getKind()).map(LspSymbols::unitKind),
                isDeprecated(from.getTags()),
                Optional.ofNullable(from.getRange()).map(LspSymbols::location),
                CollectionUtils.emptyIfNull(call.getFromRanges()).stream().map(LspSymbols::location).toList()));
    }
    public boolean isDeprecated(List<SymbolTag> tags) {
        return CollectionUtils.emptyIfNull(tags).contains(SymbolTag.Deprecated);
    }
    public Range range(SourceLocation location) {
        return new Range(
                new Position(location.getStartLine() - LSP_OFFSET, location.getStartColumn() - LSP_OFFSET),
                new Position(location.getEndLine() - LSP_OFFSET, location.getEndColumn() - LSP_OFFSET));
    }
    public Range selectionRange(SourceLocation location) {
        Position start = new Position(location.getStartLine() - LSP_OFFSET, location.getStartColumn() - LSP_OFFSET);
        return new Range(start, start);
    }
    public SourceLocation location(Range range) {
        return SourceLocation.builder()
                .startLine(range.getStart().getLine() + LSP_OFFSET)
                .startColumn(range.getStart().getCharacter() + LSP_OFFSET)
                .endLine(range.getEnd().getLine() + LSP_OFFSET)
                .endColumn(range.getEnd().getCharacter() + LSP_OFFSET)
                .build();
    }
    private Optional<Path> path(String uri) {
        return Optional.ofNullable(uri).filter(StringUtils::isNotEmpty).map(present -> Paths.get(URI.create(present)));
    }
}
