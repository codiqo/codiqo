package io.codiqo.api.metrics;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DriverScore {
    public static final double WEIGHT_LINES = 1.0;
    public static final double WEIGHT_NCSS = 1.0;
    public static final double WEIGHT_INVOCATIONS = 1.0;
    public static final double TOTAL_WEIGHT = WEIGHT_LINES + WEIGHT_NCSS + WEIGHT_INVOCATIONS;
    private static final double MODIFY_WEIGHT = WEIGHT_LINES + WEIGHT_INVOCATIONS;

    public static double forNew(DriverScaler scaler, int lines, int ncss, int invocations) {
        if (scaler.isEmpty()) {
            return 0.0;
        }
        double sum = WEIGHT_LINES * lines
                + WEIGHT_NCSS * ncss * scaler.ncssFactor()
                + WEIGHT_INVOCATIONS * invocations * scaler.invocationsFactor();
        return sum / TOTAL_WEIGHT;
    }
    public static double forModify(DriverScaler scaler, int linesChanged, int invocationsChanged) {
        if (scaler.isEmpty()) {
            return 0.0;
        }
        double sum = WEIGHT_LINES * linesChanged
                + WEIGHT_INVOCATIONS * invocationsChanged * scaler.invocationsFactor();
        return sum / MODIFY_WEIGHT;
    }
}
