package io.codiqo.llm.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.base.Joiner;

import io.codiqo.llm.PromptFences;

/**
 * The project's conventions as a file the review agents read, instead of text in their system prompts. Inline, a
 * large instruction file took a share of every agent's context whether the agent needed it or not, so its size had to
 * be capped hard, and a repository whose CLAUDE.md outgrew the cap halted its project. The file sits outside the
 * analysis clone, so neither the commit's build nor git sees it, in a directory the review deletes when it ends; the
 * agents may read outside the repository ({@code external_directory} in {@link OpenCodeReviewConfig}).
 *
 * <p>
 * The text keeps the fences it had inline, and the prompt names the closing one as the end of the file: OpenCode's
 * read tool returns a long file in parts, so an agent knows it has read everything only when it has seen that line.
 */
public final class ConventionsFile extends ImmutablePair<Path, Integer> {
    public static final String FILE_NAME = "project-conventions.md";

    public ConventionsFile(Path path, int lines) {
        super(path, lines);
    }
    public Path getPath() {
        return getLeft();
    }
    public int getLines() {
        return getRight();
    }
    /** the conventions written under {@code directory}; empty, and nothing written, when there are none */
    public static Optional<ConventionsFile> write(String guidance, Path directory) throws IOException {
        String text = guidance.strip();
        if (StringUtils.isEmpty(text)) {
            return Optional.empty();
        }

        String content = Joiner.on(StringUtils.LF).join(PromptFences.CONVENTIONS_BEGIN, text, PromptFences.CONVENTIONS_END) + StringUtils.LF;
        Path file = Files.writeString(directory.resolve(FILE_NAME), content, StandardCharsets.UTF_8);
        return Optional.of(new ConventionsFile(file.toAbsolutePath(), StringUtils.countMatches(content, StringUtils.LF)));
    }
}
