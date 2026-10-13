package io.codiqo.api.code;

import lombok.Value;

/**
 * A finding a compiler plugin reported while the analysed commit was built, such as an Error Prone check. The build
 * tool's integration reads it from the build's own output, since such a plugin writes no report file, and the
 * submission attaches the ones on the commit's added lines to their code units as diagnostics of {@link #getTool()}.
 */
@Value
public class CompilerFinding {
    /** the diagnostic tool value the finding is submitted under */
    String tool;
    String ruleId;
    /** relative to the work tree, with forward slashes, the form a changed file's path takes in the submission */
    String path;
    int line;
    String message;
}
