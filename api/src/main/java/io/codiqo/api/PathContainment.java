package io.codiqo.api;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import lombok.experimental.UtilityClass;

/**
 * Whether one path lies under another, tolerant of the two sides being different spellings of the same
 * directory.
 *
 * <p>{@code normalize()} collapses {@code .} and {@code ..} but never follows a symlink, and the two sides
 * arrive here by different routes — the language server reports a location through the resolved path while
 * the build tool reports its directories as they were given. On macOS a temporary work tree is
 * {@code /var/folders/...} to the build tool and {@code /private/var/folders/...} to the language server, so
 * a purely lexical test found nothing under anything: every caller fell outside every module and outside
 * every test source root, and callers silently lost their test/production classification — the blast radius
 * then read as entirely production.
 *
 * <p>Shared by module ownership and test-root membership because both compare a build-tool directory against
 * a language-server path, and fixing only one of them leaves the same bug in the other.
 */
@UtilityClass
public class PathContainment {
    private static final Map<Path, Path> RESOLVED = new ConcurrentHashMap<>();

    /**
     * The lexical comparison is tried first and is the answer whenever the two sides already agree on
     * spelling — which is every build outside a symlinked work tree. This sits on a per-file × per-module hot
     * loop ({@code RunArgs.owner} streams every module, and both the indexer and the caller populator call
     * it), so a 10k-file / 40-module reactor makes it hundreds of thousands of calls; resolving
     * unconditionally turned that into millions of stat/realpath syscalls where there had been none.
     *
     * <p>When it fails, both spellings of both sides are compared rather than only the resolved pair: a path
     * that cannot be resolved keeps its original form, and pairing a resolved root with an unresolved
     * candidate would reintroduce the very mismatch this class exists to remove.
     */
    public boolean isUnder(File root, File candidate) {
        Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        if (candidatePath.startsWith(rootPath)) {
            return true;
        }

        Path resolvedCandidate = resolve(candidatePath);
        Path resolvedRoot = resolve(rootPath);
        return resolvedCandidate.startsWith(resolvedRoot)
                || resolvedCandidate.startsWith(rootPath)
                || candidatePath.startsWith(resolvedRoot);
    }
    /**
     * Resolves through the nearest ancestor that exists rather than the path itself, because containment is
     * asked about deleted files too — a diff carries them. Resolving only what is on disk and re-appending
     * the remainder keeps both sides on the same spelling. Memoized because the same module roots and source
     * files are tested repeatedly within one run; the map is bounded by the distinct paths one analysis touches.
     */
    private static Path resolve(Path absolute) {
        return RESOLVED.computeIfAbsent(absolute, path -> {
            for (Path existing = path; Objects.nonNull(existing); existing = existing.getParent()) {
                if (Files.exists(existing)) {
                    try {
                        return existing.toRealPath().resolve(existing.relativize(path));
                    } catch (IOException err) {
                        return path;
                    }
                }
            }
            return path;
        });
    }
}
