package io.codiqo.api.code;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SourceLocation {
    private final int startLine;
    private final int endLine;
    private final int startColumn;
    private final int endColumn;
}
