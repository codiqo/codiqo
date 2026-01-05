package io.codiqo.api.pmd;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.CompareToBuilder;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

@Data
@Builder
@Getter
public class PmdDuplicationMatch implements DuplicationMatch {
    private final int tokenCount;
    private final int lineCount;
    private final Collection<DuplicateMark> locations;
    private final CodeBlockInfo block;

    @Override
    public Collection<Path> getPaths() {
        return locations.stream().map(DuplicateMark::getPath).distinct().collect(Collectors.toList());
    }
    @Override
    public boolean isCrossFile() {
        return getPaths().size() > BigDecimal.ONE.intValue();
    }
    @Override
    public int compareTo(PmdDuplicationMatch o) {
        return new CompareToBuilder().append(tokenCount, o.tokenCount).append(lineCount, o.lineCount).toComparison();
    }
}
