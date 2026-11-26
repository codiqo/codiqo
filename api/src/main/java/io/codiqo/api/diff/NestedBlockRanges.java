package io.codiqo.api.diff;

import java.util.List;

import org.apache.commons.lang3.BooleanUtils;

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
    /**
     * Whether a unit spanning {@code startLine..endLine} is billed the deleted line anchored at {@code anchor}: the anchor
     * lies in the unit's range, up to one line past its end, and in none of the units nested in it. The client bills a
     * deleted line's calls to a unit by this rule (EffectiveChangePopulator), and the scorer takes a moved-out line's
     * calls back from the units it says (FinalScoreCalculator, through the anchors SubmissionToRequestMapper records on
     * each block); deciding the two apart placed the discount on a unit that was never billed for it. The end slack lets
     * two adjacent units both claim the line between them, and both are billed.
     */
    public static boolean billsDeletion(int startLine, int endLine, List<int[]> nestedRanges, int anchor) {
        return anchor >= startLine && anchor <= endLine + 1 && BooleanUtils.negate(coversAnchor(nestedRanges, anchor));
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
