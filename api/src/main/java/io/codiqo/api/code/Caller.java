package io.codiqo.api.code;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import lombok.Value;

/**
 * A code unit that calls an affected one, as the language reports it: its name, its symbol (the enclosing type for
 * Java), the file it is in, its kind, whether it is deprecated, where it is, and the places it makes the call.
 */
@Value
public class Caller {
    String name;
    String symbol;
    Optional<Path> path;
    Optional<UnitKind> kind;
    boolean deprecated;
    Optional<SourceLocation> location;
    List<SourceLocation> callSites;
}
