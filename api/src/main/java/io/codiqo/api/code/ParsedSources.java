package io.codiqo.api.code;

import java.io.File;
import java.util.List;
import java.util.Set;

import lombok.Value;

/**
 * the code units a parse produced, and the files it actually parsed. The two differ: a file that parsed into zero code
 * units is in {@link #parsedFiles}, while a file the parser could not analyze is not — so an empty block list never
 * has to stand in for "this file has no code units".
 */
@Value
public class ParsedSources {
    List<CodeBlockInfo> blocks;
    Set<File> parsedFiles;
}
