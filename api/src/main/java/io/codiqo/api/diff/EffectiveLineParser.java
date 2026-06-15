package io.codiqo.api.diff;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.lang3.BooleanUtils;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.util.RawParseUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import lombok.experimental.UtilityClass;

@UtilityClass
public class EffectiveLineParser {
    private static final byte ADDED_PREFIX = '+';
    private static final byte DELETED_PREFIX = '-';
    private static final byte LF = '\n';

    public enum LineKind {
        CONTEXT,
        ADDED,
        DELETED
    }

    @FunctionalInterface
    public interface DiffLineVisitor {
        void visit(LineKind kind, int newLine, String content);
    }

    public static Set<Integer> parseAddedLines(String diff) {
        Set<Integer> toReturn = Sets.newHashSet();
        for (FileHeader fileHeader : parsePatch(diff).getFiles()) {
            for (HunkHeader hunk : fileHeader.getHunks()) {
                for (Edit edit : hunk.toEditList()) {
                    for (int i = edit.getBeginB(); i < edit.getEndB(); i++) {
                        toReturn.add(i + 1);
                    }
                }
            }
        }
        return toReturn;
    }
    public static Set<Integer> parseEffectiveAddedLines(String diff, Predicate<String> ineffective) {
        Set<Integer> toReturn = Sets.newHashSet();
        walk(diff, (kind, newLine, content) -> {
            if (kind == LineKind.ADDED && isEffective(content.trim(), ineffective)) {
                toReturn.add(newLine);
            }
        });
        return toReturn;
    }
    public static Map<Integer, List<String>> parseEffectiveDeletedLineContents(String diff, Predicate<String> ineffective) {
        Map<Integer, List<String>> toReturn = Maps.newHashMap();
        walk(diff, (kind, newLine, content) -> {
            if (kind == LineKind.DELETED) {
                String trimmed = content.trim();
                if (isEffective(trimmed, ineffective)) {
                    toReturn.computeIfAbsent(newLine, k -> Lists.newArrayList()).add(trimmed);
                }
            }
        });
        return toReturn;
    }
    public static Map<Integer, Integer> parseEffectiveDeletionAnchors(String diff, Predicate<String> ineffective) {
        Map<Integer, Integer> toReturn = Maps.newHashMap();
        walk(diff, (kind, newLine, content) -> {
            if (kind == LineKind.DELETED && isEffective(content.trim(), ineffective)) {
                toReturn.merge(newLine, 1, Integer::sum);
            }
        });
        return toReturn;
    }
    public static void walk(String diff, DiffLineVisitor visitor) {
        walk(parsePatch(diff), visitor);
    }
    public static void walk(Patch patch, DiffLineVisitor visitor) {
        for (FileHeader fileHeader : patch.getFiles()) {
            for (HunkHeader hunk : fileHeader.getHunks()) {
                walkHunk(hunk, visitor);
            }
        }
    }
    public static Patch parsePatch(String diff) {
        Patch patch = new Patch();
        byte[] diffBytes = diff.getBytes(StandardCharsets.UTF_8);
        patch.parse(diffBytes, 0, diffBytes.length);
        return patch;
    }
    private static void walkHunk(HunkHeader hunk, DiffLineVisitor visitor) {
        byte[] buf = hunk.getBuffer();
        int end = hunk.getEndOffset();
        int newLine = hunk.getNewStartLine();

        int ptr = RawParseUtils.nextLF(buf, hunk.getStartOffset());
        while (ptr < end) {
            int lineEnd = RawParseUtils.nextLF(buf, ptr);
            byte type = buf[ptr];
            int contentStart = ptr + 1;
            int contentEnd = lineEnd;
            if (contentEnd > contentStart && buf[contentEnd - 1] == LF) {
                contentEnd--;
            }

            if (type == ADDED_PREFIX) {
                visitor.visit(LineKind.ADDED, newLine, RawParseUtils.decode(StandardCharsets.UTF_8, buf, contentStart, contentEnd));
                newLine++;
            } else if (type == DELETED_PREFIX) {
                visitor.visit(LineKind.DELETED, newLine, RawParseUtils.decode(StandardCharsets.UTF_8, buf, contentStart, contentEnd));
            } else if (type == ' ') {
                visitor.visit(LineKind.CONTEXT, newLine, RawParseUtils.decode(StandardCharsets.UTF_8, buf, contentStart, contentEnd));
                newLine++;
            }

            ptr = lineEnd;
        }
    }
    private static boolean isEffective(String trimmed, Predicate<String> ineffective) {
        return BooleanUtils.and(new boolean[] {
                BooleanUtils.negate(trimmed.isEmpty()),
                BooleanUtils.negate(ineffective.test(trimmed))
        });
    }
}
