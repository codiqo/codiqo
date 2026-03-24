package io.codiqo.api.code;

import java.math.BigDecimal;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SourceLocation {
    public static final int LSP_OFFSET = BigDecimal.ONE.intValue();
    public static final int GIT_OFFSET = BigDecimal.ONE.intValue();

    private final int startLine;
    private final int endLine;
    private final int startColumn;
    private final int endColumn;

    public Range toLspRange() {
        return new Range(
                new Position(startLine - LSP_OFFSET, startColumn - LSP_OFFSET),
                new Position(endLine - LSP_OFFSET, endColumn - LSP_OFFSET));
    }
    public Range toLspSelectionRange() {
        Position start = new Position(startLine - LSP_OFFSET, startColumn - LSP_OFFSET);
        return new Range(start, start);
    }
    public static SourceLocation fromLspRange(Range range) {
        return SourceLocation.builder()
                .startLine(range.getStart().getLine() + LSP_OFFSET)
                .startColumn(range.getStart().getCharacter() + LSP_OFFSET)
                .endLine(range.getEnd().getLine() + LSP_OFFSET)
                .endColumn(range.getEnd().getCharacter() + LSP_OFFSET)
                .build();
    }
}
