package io.codiqo.maven.populator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.math3.util.Precision;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.lsp4j.SymbolKind;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.google.common.collect.Lists;

import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.api.metrics.DriverScaler.DimensionStats;
import io.codiqo.api.metrics.DriverScore;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitModel.OperationEnum;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.submit.ModuleQualityTracker;
import io.codiqo.submit.SampleMaxTracker;
import io.codiqo.submit.SubmissionContext;
import io.codiqo.submit.SubmissionPopulator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Logs the driver-score calibration of a submission, laid out by the submission-summary template. The public nested
 * classes are that template's view model: their accessors are read by the template alone, which is why Java shows no
 * caller for most of them.
 */
@RequiredArgsConstructor
public class SubmissionSummaryPrinter implements SubmissionPopulator {
    private static final String TITLE = "Codiqo — Driver Score Calibration";
    private static final String TEMPLATE_NAME = "submission-summary";
    private static final int ROUNDING = 2;
    private static final int COMMIT_SHA_LENGTH = 12;
    private static final String NONE = "-";
    private static final String NOT_APPLICABLE = "—";

    private static final TemplateEngine TEMPLATE_ENGINE;

    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

        TEMPLATE_ENGINE = new TemplateEngine();
        TEMPLATE_ENGINE.setTemplateResolver(resolver);
        TEMPLATE_ENGINE.addDialect(new TextLayoutDialect());
    }

    private final Log log;

    @Override
    public void accept(SubmissionContext ctx) {
        String text = TEMPLATE_ENGINE.process(TEMPLATE_NAME, templateContext(ctx));
        for (String line : StringUtils.splitPreserveAllTokens(Strings.CS.removeEnd(text, StringUtils.LF), CharUtils.LF)) {
            log.info(line);
        }
    }
    private static Context templateContext(SubmissionContext ctx) {
        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        CommitModel commit = submission.getCommit();
        TrivialCounts trivials = aggregateTrivialCounts(ctx);
        double capMultiplier = ctx.getArgs().getDriverScoreCapMultiplier();
        Context toReturn = new Context(Locale.ENGLISH);

        toReturn.setVariable("title", TITLE);
        toReturn.setVariable("projectId", submission.getProject().getCode());
        toReturn.setVariable("commitSha", Objects.nonNull(commit) ? StringUtils.substring(commit.getSha(), 0, COMMIT_SHA_LENGTH) : null);
        toReturn.setVariable("commitAuthor", Objects.nonNull(commit) ? commit.getAuthor() : null);
        toReturn.setVariable("files", countFiles(submission));
        toReturn.setVariable("blocks", countChangedBlocks(submission));

        toReturn.setVariable("rows", Arrays.asList(
                new ScalerRow("method/prod", trivials.methodProd(), ctx.getMethodCapQuantileProd(), ctx.getMethodScalerProd(), capMultiplier),
                new ScalerRow("method/test", trivials.methodTest(), ctx.getMethodCapQuantileTest(), ctx.getMethodScalerTest(), capMultiplier),
                new ScalerRow("constructor/prod", trivials.ctorProd(), ctx.getConstructorCapQuantileProd(), ctx.getConstructorScalerProd(), capMultiplier),
                new ScalerRow("constructor/test", trivials.ctorTest(), ctx.getConstructorCapQuantileTest(), ctx.getConstructorScalerTest(), capMultiplier)));
        toReturn.setVariable("quantilePercent", (int) Math.round(ctx.getArgs().getStatsQuantile() * 100));
        toReturn.setVariable("capMultiplier", capMultiplier);
        toReturn.setVariable("weightLines", DriverScore.WEIGHT_LINES);
        toReturn.setVariable("weightNcss", DriverScore.WEIGHT_NCSS);
        toReturn.setVariable("weightInvocations", DriverScore.WEIGHT_INVOCATIONS);
        toReturn.setVariable("totalWeight", DriverScore.TOTAL_WEIGHT);

        toReturn.setVariable("maxContributors", Arrays.asList(
                maxContributors("method/prod", ctx.getMethodMaxProd()),
                maxContributors("method/test", ctx.getMethodMaxTest()),
                maxContributors("constructor/prod", ctx.getConstructorMaxProd()),
                maxContributors("constructor/test", ctx.getConstructorMaxTest())));

        RowBundle rowBundle = buildChangedBlockRows(ctx, submission);
        toReturn.setVariable("changedBlocks", rowBundle.nonTrivial());
        toReturn.setVariable("trivialBlocks", rowBundle.trivial());
        toReturn.setVariable("outliers", rowBundle.nonTrivial().stream()
                .filter(BlockRow::outlier)
                .sorted(Comparator.comparingDouble((BlockRow r) -> Math.max(r.deviationNcss(), r.deviationInvocations())).reversed())
                .collect(Collectors.toList()));
        toReturn.setVariable("maxDeviation", ctx.getArgs().getDriverFactorMaxDeviation());
        toReturn.setVariable("outlierCounts", countOutliersByBucket(rowBundle.nonTrivial()));
        return toReturn;
    }
    private static OutlierBucketCounts countOutliersByBucket(List<BlockRow> rows) {
        OutlierBucketCounts counts = new OutlierBucketCounts();
        for (BlockRow row : rows) {
            if (BooleanUtils.negate(row.outlier())) {
                continue;
            }
            boolean isCtor = SymbolKind.Constructor.name().equals(row.kind());
            if (isCtor) {
                if (row.test()) {
                    counts.ctorTest++;
                } else {
                    counts.ctorProd++;
                }
            } else {
                if (row.test()) {
                    counts.methodTest++;
                } else {
                    counts.methodProd++;
                }
            }
        }
        return counts;
    }
    private static FileCounts countFiles(AnalysisSubmissionModel submission) {
        FileCounts counts = new FileCounts();
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            counts.total++;
            if (Boolean.TRUE.equals(file.getIsTest())) {
                counts.test++;
            } else {
                counts.prod++;
            }
            switch (file.getChangeType()) {
                case ADD, COPY -> counts.added++;
                case MODIFY -> counts.modified++;
                case DELETE -> counts.deleted++;
                case RENAME -> counts.renamed++;
                default -> throw new IllegalArgumentException("Unexpected value: " + file.getChangeType());
            }
        }
        return counts;
    }
    private static BlockCounts countChangedBlocks(AnalysisSubmissionModel submission) {
        BlockCounts counts = new BlockCounts();
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            boolean isTest = Boolean.TRUE.equals(file.getIsTest());
            for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (BooleanUtils.negate(isMethodOrConstructor(unit.getKind())) || unit.getOperation() == OperationEnum.DELETE) {
                    continue;
                }
                if (Boolean.TRUE.equals(unit.getIsTrivial())) {
                    counts.trivialSkipped++;
                    continue;
                }
                counts.total++;
                if (unit.getOperation() == OperationEnum.NEW) {
                    counts.added++;
                } else if (unit.getOperation() == OperationEnum.MODIFY) {
                    counts.modified++;
                }
                if (isTest) {
                    counts.test++;
                } else {
                    counts.prod++;
                }
            }
        }
        return counts;
    }
    private static TrivialCounts aggregateTrivialCounts(SubmissionContext ctx) {
        TrivialCounts counts = new TrivialCounts();
        for (ModuleModel moduleModel : ctx.getProjectModel().getModules()) {
            ModuleQualityTracker tracker = ctx.getQualityTrackers().get(moduleModel.getId());
            if (Objects.nonNull(tracker)) {
                counts.methodProd += tracker.trivialMethodProd().intValue();
                counts.methodTest += tracker.trivialMethodTest().intValue();
                counts.ctorProd += tracker.trivialConstructorProd().intValue();
                counts.ctorTest += tracker.trivialConstructorTest().intValue();
            }
        }
        return counts;
    }
    private static RowBundle buildChangedBlockRows(SubmissionContext ctx, AnalysisSubmissionModel submission) {
        List<BlockRow> nonTrivial = Lists.newArrayList();
        List<BlockRow> trivial = Lists.newArrayList();
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            boolean isTest = Boolean.TRUE.equals(file.getIsTest());
            for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (BooleanUtils.negate(isMethodOrConstructor(unit.getKind())) || unit.getOperation() == OperationEnum.DELETE) {
                    continue;
                }
                buildRow(ctx, file, unit, isTest).ifPresent(row -> {
                    if (Boolean.TRUE.equals(unit.getIsTrivial())) {
                        trivial.add(row);
                    } else {
                        nonTrivial.add(row);
                    }
                });
            }
        }
        nonTrivial.sort(Comparator.comparingDouble(BlockRow::driver).reversed());
        trivial.sort(Comparator.comparingInt(BlockRow::lines).reversed());
        return new RowBundle(nonTrivial, trivial);
    }
    private static MaxContributors maxContributors(String label, SampleMaxTracker tracker) {
        return new MaxContributors(label, Arrays.asList(
                new MaxRow("lines", tracker.lines().value(), tracker.lines().file(), tracker.lines().block()),
                new MaxRow("ncss", tracker.ncss().value(), tracker.ncss().file(), tracker.ncss().block()),
                new MaxRow("invocs", tracker.invocations().value(), tracker.invocations().file(), tracker.invocations().block())));
    }
    private static Optional<BlockRow> buildRow(SubmissionContext ctx, FileChangeModel file, CodeUnitModel unit, boolean isTest) {
        MetricsModel metrics = unit.getMetrics();
        if (Objects.isNull(metrics)) {
            return Optional.empty();
        }
        int lines = Objects.nonNull(metrics.getNonCommentCodeLines()) ? metrics.getNonCommentCodeLines()
                : Optional.ofNullable(metrics.getCodeLines()).orElse(0);
        int ncss = Objects.nonNull(metrics.getNonCommentCodeStatements()) ? metrics.getNonCommentCodeStatements() : 0;
        int invocations = Objects.nonNull(metrics.getDirectInvocationCount()) ? metrics.getDirectInvocationCount() : 0;

        boolean isConstructor = unit.getKind() == SymbolKindModel.CONSTRUCTOR;
        DriverScaler scaler = selectScaler(ctx, isConstructor, isTest);
        double maxDeviation = ctx.getArgs().getDriverFactorMaxDeviation();

        boolean modify = unit.getOperation() == OperationEnum.MODIFY;
        int rowLines = lines;
        int rowNcss = ncss;
        int rowInvocations = invocations;
        double projectedLines;
        double projectedNcss;
        double projectedInvocations;
        double driver;
        double deviationNcss;
        double deviationInvocations;
        if (modify) {
            int effectiveChanged = Optional.ofNullable(unit.getEffectiveLinesChanged()).orElse(0);
            rowLines = Math.min(effectiveChanged, lines);
            rowInvocations = Optional.ofNullable(unit.getEffectiveInvocationsChanged()).orElse(0);
            driver = DriverScore.forModify(scaler, rowLines, rowInvocations);
            projectedLines = rowLines;
            projectedNcss = 0.0;
            projectedInvocations = rowInvocations * scaler.invocationsFactor();
            deviationNcss = 0.0;
            deviationInvocations = 0.0;
        } else {
            driver = DriverScore.forNew(scaler, lines, ncss, invocations);
            projectedLines = lines;
            projectedNcss = ncss * scaler.ncssFactor();
            projectedInvocations = invocations * scaler.invocationsFactor();
            deviationNcss = relativeDeviation(ncss, lines, bucketRatio(scaler.ncss(), scaler.lines()));
            deviationInvocations = relativeDeviation(invocations, lines, bucketRatio(scaler.invocations(), scaler.lines()));
        }

        String fileLabel = shortFileName(file.getPath());
        String blockLabel = StringUtils.normalizeSpace(Optional.ofNullable(unit.getName()).orElse("-"));
        boolean outlier = deviationNcss > maxDeviation || deviationInvocations > maxDeviation;

        return Optional.of(new BlockRow(
                fileLabel,
                blockLabel,
                kindLabel(unit.getKind()),
                isTest,
                unit.getOperation(),
                rowLines, rowNcss, rowInvocations,
                Precision.round(projectedLines, ROUNDING),
                Precision.round(projectedNcss, ROUNDING),
                Precision.round(projectedInvocations, ROUNDING),
                driver,
                Precision.round(deviationNcss, ROUNDING),
                Precision.round(deviationInvocations, ROUNDING),
                outlier));
    }
    private static double bucketRatio(DimensionStats numerator, DimensionStats denominator) {
        if (denominator.p50() <= 0.0) {
            return 0.0;
        }
        return numerator.p50() / denominator.p50();
    }
    private static double relativeDeviation(int blockNumerator, int blockDenominator, double bucketRatio) {
        if (blockDenominator <= 0 || bucketRatio <= 0.0) {
            return 0.0;
        }
        double blockRatio = (double) blockNumerator / blockDenominator;
        return Math.abs(blockRatio - bucketRatio) / bucketRatio;
    }
    private static DriverScaler selectScaler(SubmissionContext ctx, boolean isConstructor, boolean isTest) {
        if (isConstructor) {
            return isTest ? ctx.getConstructorScalerTest() : ctx.getConstructorScalerProd();
        }
        return isTest ? ctx.getMethodScalerTest() : ctx.getMethodScalerProd();
    }
    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100);
    }
    private static boolean isMethodOrConstructor(SymbolKindModel kind) {
        return kind == SymbolKindModel.METHOD || kind == SymbolKindModel.CONSTRUCTOR;
    }
    private static String kindLabel(SymbolKindModel kind) {
        return kind == SymbolKindModel.CONSTRUCTOR ? SymbolKind.Constructor.name() : SymbolKind.Method.name();
    }
    private static String shortFileName(String path) {
        if (Objects.isNull(path)) {
            return "?";
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Getter
    @Accessors(fluent = true)
    public static final class FileCounts {
        private int total;
        private int prod;
        private int test;
        private int added;
        private int modified;
        private int deleted;
        private int renamed;
    }

    @Getter
    @Accessors(fluent = true)
    public static final class BlockCounts {
        private int total;
        private int added;
        private int modified;
        private int prod;
        private int test;
        private int trivialSkipped;
    }

    @Getter
    @Accessors(fluent = true)
    private static final class TrivialCounts {
        private int methodProd;
        private int methodTest;
        private int ctorProd;
        private int ctorTest;
    }

    @Value
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class ScalerRow {
        String label;
        int trivialsExcluded;
        int capQuantile;
        DriverScaler scaler;
        double capMultiplier;

        public int budgetPerBlock() {
            return (int) Math.round(capQuantile * capMultiplier);
        }
    }

    private static final class RowBundle extends ImmutablePair<List<BlockRow>, List<BlockRow>> {
        private RowBundle(List<BlockRow> nonTrivial, List<BlockRow> trivial) {
            super(nonTrivial, trivial);
        }
        public List<BlockRow> nonTrivial() {
            return getLeft();
        }
        public List<BlockRow> trivial() {
            return getRight();
        }
    }

    @Value
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class BlockRow {
        String file;
        String block;
        String kind;
        boolean test;
        OperationEnum operation;
        int lines;
        int ncss;
        int invocations;
        double projectedLines;
        double projectedNcss;
        double projectedInvocations;
        double driver;
        double deviationNcss;
        double deviationInvocations;
        boolean outlier;

        public boolean modify() {
            return operation == OperationEnum.MODIFY;
        }
        public String operationLabel() {
            return Objects.isNull(operation) ? NONE : operation.name();
        }
        public String scopeLabel() {
            return test ? "test" : "prod";
        }
        public String projectedLinesLabel() {
            return fixed(projectedLines);
        }
        /** a modified block has no statement count of its own: only its changed lines and invocations drive it */
        public String projectedNcssLabel() {
            return modify() ? NOT_APPLICABLE : fixed(projectedNcss);
        }
        public String projectedInvocationsLabel() {
            return fixed(projectedInvocations);
        }
        public String driverLabel() {
            return fixed(driver);
        }
        public String driverFormula() {
            if (modify()) {
                return fixed(driver) + " = (" + fixed(projectedLines) + "+" + fixed(projectedInvocations) + ")/2";
            }
            return fixed(driver) + " = (" + fixed(projectedLines) + "+" + fixed(projectedNcss) + "+" + fixed(projectedInvocations) + ")/" + fixed(DriverScore.TOTAL_WEIGHT);
        }
        public String deviationNcssLabel() {
            return modify() ? NOT_APPLICABLE : percent(deviationNcss);
        }
        public String deviationInvocationsLabel() {
            return modify() ? NOT_APPLICABLE : percent(deviationInvocations);
        }
    }

    @Getter
    @Accessors(fluent = true)
    public static final class OutlierBucketCounts {
        private int methodProd;
        private int methodTest;
        private int ctorProd;
        private int ctorTest;
    }

    public static final class MaxContributors extends ImmutablePair<String, List<MaxRow>> {
        private MaxContributors(String label, List<MaxRow> rows) {
            super(label, rows);
        }
        public String label() {
            return getLeft();
        }
        public List<MaxRow> rows() {
            return getRight();
        }
    }

    @Value
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class MaxRow {
        String dim;
        int value;
        String file;
        String block;

        /** a bucket with no sample has no maximum */
        public String valueLabel() {
            return value < 0 ? NONE : String.valueOf(value);
        }
    }
}
