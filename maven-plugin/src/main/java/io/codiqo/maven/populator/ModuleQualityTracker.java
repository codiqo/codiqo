package io.codiqo.maven.populator;

import java.util.Set;

import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.mutable.MutableInt;

import com.google.common.collect.Sets;

import lombok.Getter;

@Getter
public class ModuleQualityTracker {
    // ========================================================================
    // AFFECTED METRICS (commit-level: only what changed in this commit)
    // ========================================================================

    private final MutableInt affectedFilesChanged = new MutableInt();
    private final MutableInt affectedCodeUnits = new MutableInt();
    private final MutableInt affectedTotalLines = new MutableInt();
    private final MutableInt affectedPmdViolations = new MutableInt();
    private final MutableInt affectedSpotbugsIssues = new MutableInt();
    private final MutableDouble affectedTotalCoverage = new MutableDouble();
    private final MutableInt affectedCoverageCount = new MutableInt();
    private final MutableDouble affectedTotalComplexity = new MutableDouble();
    private final MutableInt affectedComplexityCount = new MutableInt();

    // ========================================================================
    // MODULE METRICS (full module: ALL code units, not just changed)
    // ========================================================================

    private final Set<String> moduleUniqueClasses = Sets.newHashSet();

    private final MutableInt moduleTotalMethods = new MutableInt();
    private final MutableInt moduleCoveredMethods = new MutableInt();
    private final MutableInt moduleTotalLines = new MutableInt();
    private final MutableInt moduleTotalExecutableLines = new MutableInt();
    private final MutableInt moduleCoveredLines = new MutableInt();
    private final MutableInt moduleMissedLines = new MutableInt();
    private final MutableInt moduleTotalBranches = new MutableInt();
    private final MutableInt moduleCoveredBranches = new MutableInt();
    private final MutableInt moduleMissedBranches = new MutableInt();
    private final MutableDouble moduleTotalComplexity = new MutableDouble();
    private final MutableInt moduleComplexityCount = new MutableInt();
    private final MutableInt moduleTotalPmdViolations = new MutableInt();
    private final MutableInt moduleTotalSpotbugsIssues = new MutableInt();

    void incrementFilesChanged() {
        affectedFilesChanged.increment();
    }
    void incrementCodeUnits() {
        affectedCodeUnits.increment();
    }
    void addLines(int lines) {
        affectedTotalLines.add(lines);
    }
    void addPmdViolations(int count) {
        affectedPmdViolations.add(count);
    }
    void addSpotbugsIssues(int count) {
        affectedSpotbugsIssues.add(count);
    }
    void addCoverage(double coveragePercent) {
        affectedTotalCoverage.add(coveragePercent);
        affectedCoverageCount.increment();
    }
    void addComplexity(int complexity) {
        affectedTotalComplexity.add(complexity);
        affectedComplexityCount.increment();
    }
    double getAffectedAverageCoverage() {
        return affectedCoverageCount.intValue() > 0 ? affectedTotalCoverage.doubleValue() / affectedCoverageCount.intValue() : 0.0;
    }
    double getAffectedAverageComplexity() {
        return affectedComplexityCount.intValue() > 0 ? affectedTotalComplexity.doubleValue() / affectedComplexityCount.intValue() : 0.0;
    }
    void addModuleMethod(boolean hasCoverage) {
        moduleTotalMethods.increment();
        if (hasCoverage) {
            moduleCoveredMethods.increment();
        }
    }
    void addModuleLines(int lines) {
        moduleTotalLines.add(lines);
    }
    void addModuleCoverageLines(int covered, int missed) {
        moduleCoveredLines.add(covered);
        moduleMissedLines.add(missed);
        moduleTotalExecutableLines.add(covered + missed);
    }
    void addModuleCoverageBranches(int covered, int missed) {
        moduleCoveredBranches.add(covered);
        moduleMissedBranches.add(missed);
        moduleTotalBranches.add(covered + missed);
    }
    void addModuleComplexity(int complexity) {
        if (complexity > 0) {
            moduleTotalComplexity.add(complexity);
            moduleComplexityCount.increment();
        }
    }
    void addModuleUniqueClass(String className) {
        if (className != null) {
            moduleUniqueClasses.add(className);
        }
    }
    void addModulePmdViolations(int count) {
        moduleTotalPmdViolations.add(count);
    }
    void addModuleSpotbugsIssues(int count) {
        moduleTotalSpotbugsIssues.add(count);
    }
    int getModuleUncoveredMethods() {
        return moduleTotalMethods.intValue() - moduleCoveredMethods.intValue();
    }
    int getModuleTotalClasses() {
        return moduleUniqueClasses.size();
    }
    double getModuleLineCoveragePercent() {
        int total = moduleTotalExecutableLines.intValue();
        return total > 0 ? moduleCoveredLines.intValue() * 100.0 / total : 0.0;
    }
    double getModuleBranchCoveragePercent() {
        int total = moduleTotalBranches.intValue();
        return total > 0 ? moduleCoveredBranches.intValue() * 100.0 / total : 100.0;
    }
}
