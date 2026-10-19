package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.codiqo.api.code.CompilerFinding;
import io.codiqo.client.model.DiagnosticModel;
import lombok.experimental.UtilityClass;

/**
 * Reads the Error Prone findings of the forked build from its console output. Error Prone writes no report file: its
 * findings are javac diagnostics, which maven-compiler-plugin logs one per line as
 * {@code [WARNING] /abs/path/Foo.java:[12,27] [FormatString] extra format arguments: used 0, provided 2}, with the
 * explanation and the link to the check on the lines that follow. The fork demotes every Error Prone error to such a
 * warning (the build's {@code ErrorProneErrorsAsWarnings} extension), so this sees each finding, whatever its level.
 */
@UtilityClass
public class ErrorProneWarnings {
    private static final String WARNING_PREFIX = "[WARNING] ";
    /**
     * An Error Prone check is named in UpperCamelCase ({@code FormatString}, {@code UnusedVariable}), which is what
     * tells its warning apart from javac's own lint warnings: those carry their lowercase category in the same place
     * ({@code [deprecation]}, {@code [unchecked]}, {@code [removal]}), and reading them as Error Prone findings would
     * put every deprecated call of a commit before the triage.
     */
    private static final Pattern WARNING = Pattern.compile("^\\[WARNING\\] (.+?):\\[(\\d+),\\d+\\] \\[([A-Z][A-Za-z0-9]*)\\] (.+)$");

    public boolean isWarning(String line) {
        return Strings.CS.startsWith(line, WARNING_PREFIX) && WARNING.matcher(line).matches();
    }
    /**
     * The findings on files inside the work tree, by their path relative to it. Both sides are compared as real paths:
     * the build reports the path Maven resolved, which on macOS can be the {@code /private/var} form of a temporary
     * directory the analysis knows as {@code /var}. A warning repeated in the output (a module compiled twice) is kept
     * once.
     */
    public List<CompilerFinding> findings(Collection<String> lines, File workTree) throws IOException {
        Path root = workTree.toPath().toRealPath();
        Set<CompilerFinding> toReturn = Sets.newLinkedHashSet();
        for (String line : lines) {
            Matcher matcher = WARNING.matcher(line);
            if (matcher.matches()) {
                Path file = Paths.get(matcher.group(1));
                if (Files.isRegularFile(file)) {
                    Path real = file.toRealPath();
                    if (real.startsWith(root)) {
                        toReturn.add(new CompilerFinding(
                                DiagnosticModel.ToolEnum.ERRORPRONE.getValue(),
                                matcher.group(3),
                                FilenameUtils.separatorsToUnix(root.relativize(real).toString()),
                                Integer.parseInt(matcher.group(2)),
                                matcher.group(4).strip()));
                    }
                }
            }
        }
        return Lists.newArrayList(toReturn);
    }
}
