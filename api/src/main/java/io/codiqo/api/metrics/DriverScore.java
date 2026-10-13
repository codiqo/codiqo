package io.codiqo.api.metrics;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DriverScore {
    public static final double WEIGHT_LINES = 1.0;
    public static final double WEIGHT_NCSS = 1.0;
    /**
     * Invocations are ~1:1 collinear with statements (measured across BigDecimal/Collectors/
     * DateTimeFormatterBuilder: ~0.7–1.0 calls per NCSS), so they are not an independent effort
     * axis — a call is already counted as a line and a statement. Their only extra signal is
     * orchestration/fan-out density, which lines and NCSS miss. Half weight keeps that signal
     * without double-counting call-heavy glue code (or penalizing dense compute that makes few calls).
     */
    public static final double WEIGHT_INVOCATIONS = 0.5;
    public static final double TOTAL_WEIGHT = WEIGHT_LINES + WEIGHT_NCSS + WEIGHT_INVOCATIONS;
    public static final double MODIFY_WEIGHT = WEIGHT_LINES + WEIGHT_INVOCATIONS;

    public static double forNew(DriverScaler scaler, int lines, int ncss, int invocations) {
        if (scaler.isEmpty()) {
            return 0.0;
        }
        return weightedSum(lines, ncss * scaler.ncssFactor(), invocations * scaler.invocationsFactor()) / TOTAL_WEIGHT;
    }
    public static double forModify(DriverScaler scaler, int linesChanged, int invocationsChanged) {
        if (scaler.isEmpty()) {
            return 0.0;
        }
        return weightedSum(linesChanged, 0.0, invocationsChanged * scaler.invocationsFactor()) / MODIFY_WEIGHT;
    }
    /**
     * The weighted sum of a block's lines-equivalent components, the numerator of every driver score. Anything that
     * rescales a driver score by changing one of its components must weigh that component through this sum: summing
     * the components unweighted gives the invocation term twice its share of the driver, so a discount on moved calls
     * computed that way removes twice what the calls contributed.
     */
    public static double weightedSum(double projectedLines, double projectedNcss, double projectedInvocations) {
        return WEIGHT_LINES * projectedLines + WEIGHT_NCSS * projectedNcss + WEIGHT_INVOCATIONS * projectedInvocations;
    }
}
