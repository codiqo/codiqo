package io.codiqo.api.code;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SourceLocation {
    public static final int GIT_OFFSET = BigDecimal.ONE.intValue();

    private final int startLine;
    private final int endLine;
    private final int startColumn;
    private final int endColumn;
}
