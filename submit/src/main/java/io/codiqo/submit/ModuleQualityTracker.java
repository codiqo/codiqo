package io.codiqo.submit;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.mutable.MutableInt;


import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.client.model.DiagnosticModel;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class ModuleQualityTracker {
    private final MutableInt affectedFilesChanged = new MutableInt();
    private final MutableInt affectedCodeUnits = new MutableInt();
    private final MutableInt affectedTotalStatements = new MutableInt();
    private final MutableInt affectedPmdViolations = new MutableInt();
    private final MutableInt affectedSpotbugsIssues = new MutableInt();
    private final MutableDouble affectedTotalCoverage = new MutableDouble();
    private final MutableInt affectedCoverageCount = new MutableInt();
    private final MutableInt changedCoveredLines = new MutableInt();
    private final MutableInt changedExecutableLines = new MutableInt();
    private final MutableInt addedCoveredLines = new MutableInt();
    private final MutableInt addedExecutableLines = new MutableInt();
    private final MutableInt modifiedCoveredLines = new MutableInt();
    private final MutableInt modifiedExecutableLines = new MutableInt();
    private final MutableDouble affectedTotalComplexity = new MutableDouble();
    private final MutableInt affectedComplexityCount = new MutableInt();
    private final Set<String> moduleUniqueClasses = new HashSet<>();
    private final MutableInt moduleTotalMethods = new MutableInt();
    private final MutableInt moduleCoveredMethods = new MutableInt();
    private final MutableInt moduleTotalStatements = new MutableInt();
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
    private final List<DriverScaler.Sample> methodSamplesProd = new ArrayList<>();
    private final List<DriverScaler.Sample> methodSamplesTest = new ArrayList<>();
    private final List<DriverScaler.Sample> constructorSamplesProd = new ArrayList<>();
    private final List<DriverScaler.Sample> constructorSamplesTest = new ArrayList<>();
    private final SampleMaxTracker methodMaxProd = new SampleMaxTracker();
    private final SampleMaxTracker methodMaxTest = new SampleMaxTracker();
    private final SampleMaxTracker constructorMaxProd = new SampleMaxTracker();
    private final SampleMaxTracker constructorMaxTest = new SampleMaxTracker();
    private final MutableInt trivialMethodProd = new MutableInt();
    private final MutableInt trivialMethodTest = new MutableInt();
    private final MutableInt trivialConstructorProd = new MutableInt();
    private final MutableInt trivialConstructorTest = new MutableInt();
    private final List<DiagnosticModel> criticalViolations = new ArrayList<>();

    void incrementFilesChanged() {
        affectedFilesChanged.increment();
    }
    void incrementCodeUnits() {
        affectedCodeUnits.increment();
    }
    void addStatements(int statements) {
        affectedTotalStatements.add(statements);
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
    void addChangedLineCoverage(int covered, int executable) {
        changedCoveredLines.add(covered);
        changedExecutableLines.add(executable);
    }
    void addAddedLineCoverage(int covered, int executable) {
        addedCoveredLines.add(covered);
        addedExecutableLines.add(executable);
    }
    void addModifiedLineCoverage(int covered, int executable) {
        modifiedCoveredLines.add(covered);
        modifiedExecutableLines.add(executable);
    }
    void addComplexity(int complexity) {
        affectedTotalComplexity.add(complexity);
        affectedComplexityCount.increment();
    }
    double affectedAverageCoverage() {
        return affectedCoverageCount.intValue() > 0 ? affectedTotalCoverage.doubleValue() / affectedCoverageCount.intValue() : 0.0;
    }
    double affectedAverageComplexity() {
        return affectedComplexityCount.intValue() > 0 ? affectedTotalComplexity.doubleValue() / affectedComplexityCount.intValue() : 0.0;
    }
    void addModuleMethod(boolean hasCoverage) {
        moduleTotalMethods.increment();
        if (hasCoverage) {
            moduleCoveredMethods.increment();
        }
    }
    void addModuleStatements(int ncss) {
        moduleTotalStatements.add(ncss);
    }
    void addModuleMethodSample(String file, String block, DriverScaler.Sample sample, boolean isTest) {
        if (isTest) {
            methodSamplesTest.add(sample);
            methodMaxTest.update(file, block, sample);
        } else {
            methodSamplesProd.add(sample);
            methodMaxProd.update(file, block, sample);
        }
    }
    void addModuleConstructorSample(String file, String block, DriverScaler.Sample sample, boolean isTest) {
        if (isTest) {
            constructorSamplesTest.add(sample);
            constructorMaxTest.update(file, block, sample);
        } else {
            constructorSamplesProd.add(sample);
            constructorMaxProd.update(file, block, sample);
        }
    }
    void incrementTrivialMethod(boolean isTest) {
        if (isTest) {
            trivialMethodTest.increment();
        } else {
            trivialMethodProd.increment();
        }
    }
    void incrementTrivialConstructor(boolean isTest) {
        if (isTest) {
            trivialConstructorTest.increment();
        } else {
            trivialConstructorProd.increment();
        }
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
        if (Objects.nonNull(className)) {
            moduleUniqueClasses.add(className);
        }
    }
    void addModulePmdViolations(int count) {
        moduleTotalPmdViolations.add(count);
    }
    void addModuleSpotbugsIssues(int count) {
        moduleTotalSpotbugsIssues.add(count);
    }
    void addCriticalViolation(DiagnosticModel violation) {
        criticalViolations.add(violation);
    }
    int moduleUncoveredMethods() {
        return moduleTotalMethods.intValue() - moduleCoveredMethods.intValue();
    }
    int moduleTotalClasses() {
        return moduleUniqueClasses.size();
    }
    double moduleLineCoveragePercent() {
        int total = moduleTotalExecutableLines.intValue();
        return total > 0 ? moduleCoveredLines.intValue() * 100.0 / total : 0.0;
    }
    double moduleBranchCoveragePercent() {
        int total = moduleTotalBranches.intValue();
        return total > 0 ? moduleCoveredBranches.intValue() * 100.0 / total : 0.0;
    }
}
