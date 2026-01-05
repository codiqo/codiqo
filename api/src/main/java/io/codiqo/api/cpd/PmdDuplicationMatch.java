package io.codiqo.api.cpd;

import java.math.BigDecimal;
import java.util.Collection;

import org.apache.commons.lang3.builder.CompareToBuilder;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

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
    private final Supplier<Boolean> crossFile = Suppliers.memoize(new Supplier<Boolean>() {
        @Override
        public Boolean get() {
            return locations.stream().map(DuplicateMark::getFile).distinct().count() > BigDecimal.ONE.intValue();
        }
    });

    @Override
    public boolean isCrossFile() {
        return crossFile.get();
    }
    @Override
    public int compareTo(PmdDuplicationMatch o) {
        return new CompareToBuilder().append(tokenCount, o.tokenCount).append(lineCount, o.lineCount).toComparison();
    }
}
