package io.codiqo.api.diff;

import java.util.List;

import com.google.common.collect.Lists;

import lombok.experimental.UtilityClass;

/**
 * Attributes changed lines to the innermost enclosing code block. Methods of anonymous/local
 * classes (and lambdas) are extracted as their own code blocks, yet the lexically enclosing
 * method's line range still spans them — without exclusion the same physical change would be
 * counted in both blocks.
 */
@UtilityClass
public class NestedBlockRanges {
    public static List<int[]> nestedWithin(int startLine, int endLine, List<int[]> blockRanges) {
        List<int[]> toReturn = Lists.newArrayList();
        for (int[] range : blockRanges) {
            boolean inside = range[0] >= startLine && range[1] <= endLine;
            boolean smaller = range[1] - range[0] < endLine - startLine;
            if (inside && smaller) {
                toReturn.add(range);
            }
        }
        return toReturn;
    }
    public static boolean coversLine(List<int[]> nestedRanges, int line) {
        for (int[] range : nestedRanges) {
            if (line >= range[0] && line <= range[1]) {
                return true;
            }
        }
        return false;
    }
    /** Deletion anchors point at the next surviving line, so a range claims anchors up to endLine + 1. */
    public static boolean coversAnchor(List<int[]> nestedRanges, int anchor) {
        for (int[] range : nestedRanges) {
            if (anchor >= range[0] && anchor <= range[1] + 1) {
                return true;
            }
        }
        return false;
    }
}
