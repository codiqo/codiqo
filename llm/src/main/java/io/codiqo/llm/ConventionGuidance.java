package io.codiqo.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import lombok.experimental.UtilityClass;

/**
 * Reads the agent instruction files a project opted into, for use as advisory context in bug detection
 * and static-analysis severity re-review. The content is authored by the developers being scored, so the
 * prompt treats it as untrusted evidence about coding conventions and never lets it move a number.
 */
@UtilityClass
public class ConventionGuidance {
    /**
     * Agent instruction locations resolved against the repository root when auto-discovery is on, in this
     * order. An entry that is a directory contributes its text files sorted by path, so multi-file rule
     * sets stay byte-identical across runs.
     */
    private static final List<String> WELL_KNOWN_SOURCES = List.of(
            "AGENTS.md",
            "CLAUDE.md",
            ".claude/CLAUDE.md",
            ".github/copilot-instructions.md",
            ".cursorrules",
            ".cursor/rules",
            ".windsurfrules",
            ".clinerules",
            "GEMINI.md",
            ".junie/guidelines.md",
            "CONVENTIONS.md");

    // applied only when expanding a rules directory — an explicitly named file is taken whatever it is called
    private static final Set<String> TEXT_EXTENSIONS = Set.of("md", "mdc", "txt");

    // UTF-8 spends at most four bytes per char, so a byte total above cap × 4 cannot decode to a char total within cap
    private static final long MAX_BYTES_PER_CHAR = 4;

    /**
     * the user message fences the guidance between these markers so the model can tell repo-authored text
     * from the prompt itself; a file containing the closing marker would break out of that fence, so the
     * marker is removed from the content before it is appended.
     */
    private static final String END_MARKER = "<<<END PROJECT CONVENTIONS>>>";

    public static String read(RunArgs args, Log log) throws IOException {
        boolean anySource = BooleanUtils.or(new boolean[] {
                CollectionUtils.isNotEmpty(args.getLlmConventionFiles()),
                args.isAutoDiscoveryAgentInstructions() });
        boolean configured = BooleanUtils.and(new boolean[] { anySource, args.getLlmConventionFilesMaxChars() > 0 });
        if (configured) {
            /**
             * a replay without a checkout has no work tree holding the files — the guidance is then absent
             * from the prompt rather than failing the run
             */
            if (Objects.nonNull(args.getGit())) {
                return collect(args, log);
            }
            log.warn("agent instructions configured but no work tree is available; prompt guidance omitted");
        }
        return StringUtils.EMPTY;
    }
    private static String collect(RunArgs args, Log log) throws IOException {
        Path root = args.getGit().getWorkTree().toPath().toRealPath();
        Collection<Path> sources = resolveSources(args, root, log);
        int cap = args.getLlmConventionFilesMaxChars();

        rejectByFileSize(sources, cap);

        StringBuilder toReturn = new StringBuilder();
        for (Path file : sources) {
            append(toReturn, root, file, log);
        }

        /**
         * over-budget instructions fail the analysis rather than being silently trimmed: a truncated rule
         * set changes which findings the model suppresses, so the operator raises the ceiling on purpose
         */
        if (toReturn.length() > cap) {
            throw overBudget(toReturn.length() + " chars", cap);
        }

        log.info("agent instructions attached: %d file(s), %d of %d chars — %s",
                sources.size(), toReturn.length(), cap, describe(root, sources));
        return StringUtils.strip(toReturn.toString());
    }
    /**
     * Rejects on file metadata alone before anything is read, so a generated multi-hundred-megabyte file
     * under a rules directory reports the budget error instead of exhausting the heap.
     */
    private static void rejectByFileSize(Collection<Path> sources, int cap) throws IOException {
        long bytes = 0;
        for (Path file : sources) {
            bytes += Files.size(file);
        }
        if (bytes > MAX_BYTES_PER_CHAR * cap) {
            throw overBudget(bytes + " bytes", cap);
        }
    }
    private static Collection<Path> resolveSources(RunArgs args, Path root, Log log) {
        Set<Path> toReturn = new LinkedHashSet<>();
        for (String name : args.getLlmConventionFiles()) {
            collectSource(root, name, true, toReturn, log);
        }
        if (args.isAutoDiscoveryAgentInstructions()) {
            for (String name : WELL_KNOWN_SOURCES) {
                collectSource(root, name, false, toReturn, log);
            }
        }
        return toReturn;
    }
    /**
     * A configured entry that is absent is a misconfiguration worth reporting; an absent well-known
     * location is the normal case for every tool the project does not use, so it stays quiet.
     */
    private static void collectSource(Path root, String name, boolean configured, Set<Path> sink, Log log) {
        Path candidate = root.resolve(name);
        if (Files.isDirectory(candidate)) {
            expand(candidate, root, sink, log);
            return;
        }
        if (Files.exists(candidate)) {
            contained(candidate, root, log).ifPresent(sink::add);
            return;
        }
        if (configured) {
            log.warn("configured agent instruction path '%s' does not exist under the work tree; ignored", name);
        }
    }
    private static void expand(Path directory, Path root, Set<Path> sink, Log log) {
        try (Stream<Path> tree = Files.walk(directory)) {
            // terminal operation stays inside the try: Files.walk reports mid-traversal failures as UncheckedIOException
            for (Path file : tree.filter(Files::isRegularFile).filter(ConventionGuidance::isTextFile).sorted().toList()) {
                contained(file, root, log).ifPresent(sink::add);
            }
        } catch (IOException | UncheckedIOException err) {
            log.error("could not list agent instruction directory %s (%s)", directory, ExceptionUtils.getRootCauseMessage(err));
        }
    }
    /**
     * Containment is tested on the resolved real path, not the lexical one: the analysed repository can
     * commit an instruction file that is a symlink to a host secret, and both the file-type checks and the
     * read follow links, so a lexical guard would ship that secret to the model. Resolving also collapses
     * case-insensitive and symlinked aliases of one file to a single entry.
     */
    private static Optional<Path> contained(Path candidate, Path root, Log log) {
        try {
            Path real = candidate.toRealPath();
            if (real.startsWith(root)) {
                return Optional.of(real);
            }
            log.warn("agent instruction path %s resolves to %s outside the work tree; ignored", candidate, real);
        } catch (IOException err) {
            log.error("could not resolve agent instruction path %s (%s)", candidate, ExceptionUtils.getRootCauseMessage(err));
        }
        return Optional.empty();
    }
    private static void append(StringBuilder buffer, Path root, Path file, Log log) {
        try {
            String content = StringUtils.strip(StringUtils.remove(FileUtils.readFileToString(file.toFile(), StandardCharsets.UTF_8), END_MARKER));
            if (StringUtils.isNotBlank(content)) {
                buffer.append("### ").append(root.relativize(file)).append("\n\n").append(content).append("\n\n");
            }
        } catch (IOException err) {
            // advisory input: an unreadable instruction file degrades bug triage, it does not invalidate the score
            log.error("could not read agent instruction file %s (%s)", file, ExceptionUtils.getRootCauseMessage(err));
        }
    }
    private static String describe(Path root, Collection<Path> sources) {
        return sources.stream().map(file -> root.relativize(file).toString()).collect(Collectors.joining(", "));
    }
    private static boolean isTextFile(Path file) {
        return TEXT_EXTENSIONS.contains(FilenameUtils.getExtension(file.toString()).toLowerCase(Locale.ROOT));
    }
    private static IllegalStateException overBudget(String measured, int cap) {
        return new IllegalStateException(String.format(
                "agent instructions are %s, over the %d char budget — raise codiqo.llm.conventionFilesMaxChars, "
                        + "narrow codiqo.llm.conventionFiles, or turn off codiqo.llm.autoDiscoveryAgentInstructions",
                measured, cap));
    }
}
