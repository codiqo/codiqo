package io.codiqo.api;

import java.io.File;
import java.util.Optional;
import java.util.function.Consumer;

import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.SourceLocation;
import net.sourceforge.pmd.cpd.Mark;

public interface DuplicateMark extends Consumer<CodeBlockInfo> {
    File getFile();
    Mark getMark();
    CharSequence getSourceCodeSlice();
    SourceLocation getLocation();
    Optional<CodeBlockInfo> block();
}
