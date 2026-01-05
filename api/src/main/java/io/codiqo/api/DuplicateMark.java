package io.codiqo.api;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import net.sourceforge.pmd.cpd.Mark;

public interface DuplicateMark extends Consumer<CodeBlockInfo> {
    Path getPath();
    Mark getMark();
    CharSequence getSourceCodeSlice();
    SourceLocation getLocation();
    Optional<CodeBlockInfo> block();
}
