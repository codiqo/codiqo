package io.codiqo.api.cpd;

import java.io.File;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Supplier;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.util.Lazy;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class PmdDuplicationMatch implements DuplicationMatch {
    private final int tokenCount;
    private final int lineCount;
    private final Collection<DuplicateMark> marks;
    @Builder.Default
    private final Set<CodeBlockInfo> blocks = new LinkedHashSet<>();
    @Builder.Default
    private final Set<File> files = new LinkedHashSet<>();
    private final Supplier<Boolean> crossFile = Lazy.of(this::computeCrossFile);

    @Override
    public void accept(CodeBlockInfo info) {
        blocks.add(info);
        files.add(info.getFile());
    }
    @Override
    public Iterator<DuplicateMark> iterator() {
        return marks.iterator();
    }
    @Override
    public boolean isCrossFile() {
        return crossFile.get();
    }
    private boolean computeCrossFile() {
        return marks.stream().map(DuplicateMark::getFile).distinct().count() > BigDecimal.ONE.intValue();
    }
}
