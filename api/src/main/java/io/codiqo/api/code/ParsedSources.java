package io.codiqo.api.code;

import java.io.File;
import java.util.List;
import java.util.Set;

import lombok.Value;

@Value
public class ParsedSources {
    List<CodeBlockInfo> blocks;
    Set<File> parsedFiles;
}
