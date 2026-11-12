package io.codiqo.api.coverage;

import java.util.Locale;

import org.eclipse.collections.api.map.primitive.IntObjectMap;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CodeBlockCoverage {
    public static final CodeBlockCoverage NONE = CodeBlockCoverage.builder().build();

    @Builder.Default
    int covered = 0;
    @Builder.Default
    int missed = 0;
    @Builder.Default
    int partial = 0;
    @Builder.Default
    int empty = 0;
    @Builder.Default
    int coveredBranches = 0;
    @Builder.Default
    int missedBranches = 0;

    public int executable() {
        return covered + missed + partial;
    }
    public int total() {
        return covered + missed + partial + empty;
    }
    public int totalBranches() {
        return coveredBranches + missedBranches;
    }
    public double lineCoverageRatio() {
        int exec = executable();
        if (exec == 0) {
            return 0.0;
        }
        return (double) (covered + partial) / exec;
    }
    public double lineCoveragePercent() {
        return lineCoverageRatio() * 100.0;
    }
    public double branchCoverageRatio() {
        int total = totalBranches();
        if (total == 0) {
            return 1.0;
        }
        return (double) coveredBranches / total;
    }
    public double branchCoveragePercent() {
        return branchCoverageRatio() * 100.0;
    }
    public boolean isFullyCovered() {
        return missed == 0 && partial == 0 && covered > 0;
    }
    public boolean isPartiallyCovered() {
        return (covered > 0 || partial > 0) && missed > 0;
    }
    public boolean isUncovered() {
        return covered == 0 && partial == 0 && missed > 0;
    }
    public boolean hasCoverageData() {
        return executable() > 0;
    }
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%.1f%% lines (%d/%d, %d partial, %d missed)",
                lineCoveragePercent(),
                covered,
                executable(),
                partial,
                missed));

        if (totalBranches() > 0) {
            sb.append(String.format(Locale.ROOT, ", %.1f%% branches (%d/%d)",
                    branchCoveragePercent(),
                    coveredBranches,
                    totalBranches()));
        }

        return sb.toString();
    }
    public static CodeBlockCoverage from(IntObjectMap<LineCoverage> coverageByLine) {
        if (coverageByLine.isEmpty()) {
            return NONE;
        }

        int covered = 0;
        int missed = 0;
        int partial = 0;
        int empty = 0;
        int coveredBranches = 0;
        int missedBranches = 0;

        for (LineCoverage line : coverageByLine.values()) {
            switch (line.getStatus()) {
                case COVERED -> covered++;
                case PARTIAL -> partial++;
                case MISSED -> missed++;
                case EMPTY -> empty++;
                default -> throw new IllegalArgumentException("Unknown line status: " + line.getStatus());
            }

            coveredBranches += line.getCoveredBranches();
            missedBranches += line.getMissedBranches();
        }

        return CodeBlockCoverage.builder()
                .covered(covered)
                .missed(missed)
                .partial(partial)
                .empty(empty)
                .coveredBranches(coveredBranches)
                .missedBranches(missedBranches)
                .build();
    }
}
