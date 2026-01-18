package io.codiqo.api.cpd;

import java.io.File;
import java.util.Optional;

import io.codiqo.api.DuplicateMark;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import lombok.Builder;
import lombok.Getter;
import net.sourceforge.pmd.cpd.Mark;

@Getter
@Builder
public class PmdDuplicationMark implements DuplicateMark {
    private final Mark mark;
    private final File file;
    private final String sourceCodeSlice;
    private final SourceLocation location;
    @Builder.Default
    private Optional<CodeBlockInfo> block = Optional.empty();

    @Override
    public int hashCode() {
        return mark.hashCode();
    }
    @Override
    public boolean equals(Object obj) {
        return mark.equals(((PmdDuplicationMark) obj).mark);
    }
    @Override
    public String toString() {
        return mark.toString();
    }
    @Override
    public void accept(CodeBlockInfo info) {
        this.block = Optional.of(info);
    }
    @Override
    public Optional<CodeBlockInfo> block() {
        return block;
    }
}
