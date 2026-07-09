package io.codiqo.llm;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import io.codiqo.util.Split;
import lombok.Value;

/**
 * One LLM-cited relocation beyond the server-offered move candidates, serialized as
 * {@code fromFile:fromLine->toFile:toLine} (old-file line -> new-file line). An unparseable
 * citation yields {@link Optional#empty()} and is rejected by validation or dropped by
 * sanitization — never guessed at.
 */
@Value
public class MovedPair {
    private static final String ARROW = "->";
    String fromFile;
    int fromLine;
    String toFile;
    int toLine;

    public String format() {
        return fromFile + ":" + fromLine + ARROW + toFile + ":" + toLine;
    }
    public static Optional<MovedPair> parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Optional.empty();
        }

        List<String> sides = Split.on(raw, ARROW);
        if (sides.size() != 2) {
            return Optional.empty();
        }

        Iterator<String> iterator = sides.iterator();
        Optional<FileLine> from = parseSide(iterator.next());
        Optional<FileLine> to = parseSide(iterator.next());
        if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MovedPair(from.get().getFile(), from.get().getLine(), to.get().getFile(), to.get().getLine()));
    }
    private static Optional<FileLine> parseSide(String side) {
        int colon = side.lastIndexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }

        String file = side.substring(0, colon).trim();
        int line = NumberUtils.toInt(side.substring(colon + 1).trim(), -1);
        if (StringUtils.isBlank(file) || line <= 0) {
            return Optional.empty();
        }
        return Optional.of(new FileLine(file, line));
    }

    @Value
    private static class FileLine {
        String file;
        int line;
    }
}
