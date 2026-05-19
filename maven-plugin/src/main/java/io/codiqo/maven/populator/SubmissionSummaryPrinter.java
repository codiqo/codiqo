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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Precision;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.lsp4j.SymbolKind;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.github.freva.asciitable.AsciiTable;
import com.github.freva.asciitable.Column;
import com.github.freva.asciitable.ColumnData;
import com.github.freva.asciitable.HorizontalAlign;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
public class SubmissionSummaryPrinter implements SubmissionPopulator {
    private static final String TITLE = "Codiqo — Driver Score Calibration";
    private static final String TEMPLATE_NAME = "submission-summary";
    private static final int ROUNDING = 2;
    private static final int COMMIT_SHA_LENGTH = 12;

    private static final TemplateEngine TEMPLATE_ENGINE;

    private static final List<ColumnData<MaxRow>> MAX_CONTRIBUTOR_COLUMNS;
    private static final List<ColumnData<BlockRow>> CHANGED_BLOCKS_FIXED_COLUMNS;
    private static final List<ColumnData<BlockRow>> TRIVIAL_BLOCKS_FIXED_COLUMNS;
    private static final List<ColumnData<BlockRow>> OUTLIER_BLOCKS_FIXED_COLUMNS;

    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

        TEMPLATE_ENGINE = new TemplateEngine();
        TEMPLATE_ENGINE.setTemplateResolver(resolver);

        MAX_CONTRIBUTOR_COLUMNS = Arrays.<ColumnData<MaxRow>> asList(
                new Column().header("Dim").dataAlign(HorizontalAlign.LEFT).with((MaxRow r) -> r.dim()),
                new Column().header("Max").dataAlign(HorizontalAlign.RIGHT).with((MaxRow r) -> r.value() < 0 ? "-" : String.valueOf(r.value())),
                new Column().header("File").dataAlign(HorizontalAlign.LEFT).with((MaxRow r) -> r.file()),
                new Column().header("Block").dataAlign(HorizontalAlign.LEFT).with((MaxRow r) -> r.block()));

        CHANGED_BLOCKS_FIXED_COLUMNS = Arrays.<ColumnData<BlockRow>> asList(
                new Column().header("Kind").dataAlign(HorizontalAlign.LEFT).with((BlockRow r) -> r.kind()),
                new Column().header("Op").dataAlign(HorizontalAlign.LEFT).with(SubmissionSummaryPrinter::formatOperation),
                new Column().header("L").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> String.valueOf(r.lines())),
                new Column().header("S").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> String.valueOf(r.ncss())),
                new Column().header("I").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> String.valueOf(r.invocations())),
                new Column().header("pL").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> formatDouble(r.projectedLines())),
                new Column().header("pS").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> r.operation() == OperationEnum.MODIFY ? "—" : formatDouble(r.projectedNcss())),
                new Column().header("pI").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> formatDouble(r.projectedInvocations())),
                new Column().header("Driver").dataAlign(HorizontalAlign.LEFT).with(SubmissionSummaryPrinter::formatDriver));

        TRIVIAL_BLOCKS_FIXED_COLUMNS = Arrays.<ColumnData<BlockRow>> asList(
                new Column().header("Kind").dataAlign(HorizontalAlign.LEFT).with((BlockRow r) -> r.kind()),
                new Column().header("Op").dataAlign(HorizontalAlign.LEFT).with(SubmissionSummaryPrinter::formatOperation),
                new Column().header("L").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> String.valueOf(r.lines())),
                new Column().header("S").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> String.valueOf(r.ncss())),
                new Column().header("I").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> String.valueOf(r.invocations())));

        OUTLIER_BLOCKS_FIXED_COLUMNS = Arrays.<ColumnData<BlockRow>> asList(
                new Column().header("Kind").dataAlign(HorizontalAlign.LEFT).with((BlockRow r) -> r.kind()),
                new Column().header("Scope").dataAlign(HorizontalAlign.LEFT).with((BlockRow r) -> r.test() ? "test" : "prod"),
                new Column().header("Op").dataAlign(HorizontalAlign.LEFT).with(SubmissionSummaryPrinter::formatOperation),
                new Column().header("Driver").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> formatDouble(r.driver())),
                new Column().header("S/L_dev").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> r.operation() == OperationEnum.MODIFY ? "—" : formatPercent(r.deviationNcss())),
                new Column().header("I/L_dev").dataAlign(HorizontalAlign.RIGHT).with((BlockRow r) -> r.operation() == OperationEnum.MODIFY ? "—" : formatPercent(r.deviationInvocations())));
    }

    private final Log log;

    @Override
    public void accept(SubmissionContext ctx) {
        log.info("");
        logLines(StringUtils.stripEnd(renderTextSummary(ctx), "\n"));
        log.info("");

        log.info("max contributors:");
        log.info("  method/prod:");
        logLines(renderMaxContributorsTable(ctx.getMethodMaxProd()));
        log.info("  method/test:");
        logLines(renderMaxContributorsTable(ctx.getMethodMaxTest()));
        log.info("  constructor/prod:");
        logLines(renderMaxContributorsTable(ctx.getConstructorMaxProd()));
        log.info("  constructor/test:");
        logLines(renderMaxContributorsTable(ctx.getConstructorMaxTest()));

        RowBundle rowBundle = buildChangedBlockRows(ctx, ctx.getSubmissionModel());
        if (CollectionUtils.isNotEmpty(rowBundle.nonTrivial())) {
            log.info("");
            log.info("Changed code blocks (ordered by driver score):");
            logLines(renderChangedBlocksTable(rowBundle.nonTrivial()));
        }
        if (CollectionUtils.isNotEmpty(rowBundle.trivial())) {
            log.info("");
            log.info("Trivial changed code blocks (excluded from driver score):");
            logLines(renderTrivialBlocksTable(rowBundle.trivial()));
        }

        List<BlockRow> outliers = rowBundle.nonTrivial().stream()
                .filter(BlockRow::outlier)
                .sorted(Comparator.comparingDouble((BlockRow r) -> Math.max(r.deviationNcss(), r.deviationInvocations())).reversed())
                .collect(Collectors.toList());
        double maxDeviation = ctx.getArgs().getDriverFactorMaxDeviation();
        log.info("");
        log.info(String.format("Ratio outliers in this commit (block S/L or I/L deviates > %.2f from bucket median):", maxDeviation));
        if (outliers.isEmpty()) {
            log.info("  none");
        } else {
            logLines(renderOutliersTable(outliers));
        }

        OutlierBucketCounts bucketCounts = countOutliersByBucket(rowBundle.nonTrivial());
        log.info("");
        log.info("Ratio outlier counts by bucket:");
        log.info(String.format("  method/prod:       %d", bucketCounts.methodProd()));
        log.info(String.format("  method/test:       %d", bucketCounts.methodTest()));
        log.info(String.format("  constructor/prod:  %d", bucketCounts.ctorProd()));
        log.info(String.format("  constructor/test:  %d", bucketCounts.ctorTest()));
    }
    private static String renderOutliersTable(List<BlockRow> rows) {
        List<ColumnData<BlockRow>> columns = Lists.newArrayList();
        columns.add(new Column().header("File").dataAlign(HorizontalAlign.LEFT).maxWidth(determineWidth(rows, BlockRow::file, "File")).with((BlockRow r) -> r.file()));
        columns.add(new Column().header("Block").dataAlign(HorizontalAlign.LEFT).maxWidth(determineWidth(rows, BlockRow::block, "Block")).with((BlockRow r) -> r.block()));
        columns.addAll(OUTLIER_BLOCKS_FIXED_COLUMNS);
        return AsciiTable.getTable(rows, columns);
    }
    private static OutlierBucketCounts countOutliersByBucket(List<BlockRow> rows) {
        OutlierBucketCounts counts = new OutlierBucketCounts();
        for (BlockRow row : rows) {
            if (!row.outlier()) {
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
    private void logLines(String text) {
        for (String line : text.split("\n", -1)) {
            log.info(line);
        }
    }
    private static String renderTextSummary(SubmissionContext ctx) {
        AnalysisSubmissionModel submission = ctx.getSubmissionModel();
        CommitModel commit = submission.getCommit();
        TrivialCounts trivials = aggregateTrivialCounts(ctx);
        double capMultiplier = ctx.getArgs().getDriverScoreCapMultiplier();

        List<ScalerRow> rows = Arrays.asList(
                new ScalerRow("method/prod", trivials.methodProd(), ctx.getMethodCapQuantileProd(), ctx.getMethodScalerProd(), capMultiplier),
                new ScalerRow("method/test", trivials.methodTest(), ctx.getMethodCapQuantileTest(), ctx.getMethodScalerTest(), capMultiplier),
                new ScalerRow("constructor/prod", trivials.ctorProd(), ctx.getConstructorCapQuantileProd(), ctx.getConstructorScalerProd(), capMultiplier),
                new ScalerRow("constructor/test", trivials.ctorTest(), ctx.getConstructorCapQuantileTest(), ctx.getConstructorScalerTest(), capMultiplier));

        Context tctx = new Context(Locale.ENGLISH);
        tctx.setVariable("title", TITLE);
        tctx.setVariable("separator", StringUtils.repeat('-', TITLE.length()));
        tctx.setVariable("projectId", submission.getProject().getCode());
        tctx.setVariable("commitSha", Objects.nonNull(commit) ? StringUtils.substring(commit.getSha(), 0, COMMIT_SHA_LENGTH) : null);
        tctx.setVariable("commitAuthor", Objects.nonNull(commit) ? commit.getAuthor() : null);
        tctx.setVariable("files", countFiles(submission));
        tctx.setVariable("blocks", countChangedBlocks(submission));
        tctx.setVariable("rows", rows);
        tctx.setVariable("quantilePercent", (int) Math.round(ctx.getArgs().getStatsQuantile() * 100));
        tctx.setVariable("capMultiplierFmt", String.format("%.2f", capMultiplier));
        tctx.setVariable("weightLines", String.format("%.2f", DriverScore.WEIGHT_LINES));
        tctx.setVariable("weightNcss", String.format("%.2f", DriverScore.WEIGHT_NCSS));
        tctx.setVariable("weightInvocs", String.format("%.2f", DriverScore.WEIGHT_INVOCATIONS));
        tctx.setVariable("totalWeight", String.format("%.2f", DriverScore.TOTAL_WEIGHT));
        return TEMPLATE_ENGINE.process(TEMPLATE_NAME, tctx);
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
                if (!isMethodOrConstructor(unit.getKind()) || unit.getOperation() == OperationEnum.DELETE) {
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
            ModuleQualityTracker tracker = ctx.getQualityTrackers().getIfPresent(moduleModel.getId());
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
                if (!isMethodOrConstructor(unit.getKind()) || unit.getOperation() == OperationEnum.DELETE) {
                    continue;
                }
                BlockRow row = buildRow(ctx, file, unit, isTest);
                if (Objects.isNull(row)) {
                    continue;
                }
                if (Boolean.TRUE.equals(unit.getIsTrivial())) {
                    trivial.add(row);
                } else {
                    nonTrivial.add(row);
                }
            }
        }
        nonTrivial.sort(Comparator.comparingDouble(BlockRow::driver).reversed());
        trivial.sort(Comparator.comparingInt(BlockRow::lines).reversed());
        return new RowBundle(nonTrivial, trivial);
    }
    private static String renderChangedBlocksTable(List<BlockRow> rows) {
        List<ColumnData<BlockRow>> columns = Lists.newArrayList();
        columns.add(new Column().header("File").dataAlign(HorizontalAlign.LEFT).maxWidth(determineWidth(rows, BlockRow::file, "File")).with((BlockRow r) -> r.file()));
        columns.add(new Column().header("Block").dataAlign(HorizontalAlign.LEFT).maxWidth(determineWidth(rows, BlockRow::block, "Block")).with((BlockRow r) -> r.block()));
        columns.addAll(CHANGED_BLOCKS_FIXED_COLUMNS);
        return AsciiTable.getTable(rows, columns);
    }
    private static String renderTrivialBlocksTable(List<BlockRow> rows) {
        List<ColumnData<BlockRow>> columns = Lists.newArrayList();
        columns.add(new Column().header("File").dataAlign(HorizontalAlign.LEFT).maxWidth(determineWidth(rows, BlockRow::file, "File")).with((BlockRow r) -> r.file()));
        columns.add(new Column().header("Block").dataAlign(HorizontalAlign.LEFT).maxWidth(determineWidth(rows, BlockRow::block, "Block")).with((BlockRow r) -> r.block()));
        columns.addAll(TRIVIAL_BLOCKS_FIXED_COLUMNS);
        return AsciiTable.getTable(rows, columns);
    }
    private static String renderMaxContributorsTable(SampleMaxTracker tracker) {
        List<MaxRow> rows = Arrays.asList(
                new MaxRow("lines", tracker.lines().value(), tracker.lines().file(), tracker.lines().block()),
                new MaxRow("ncss", tracker.ncss().value(), tracker.ncss().file(), tracker.ncss().block()),
                new MaxRow("invocs", tracker.invocations().value(), tracker.invocations().file(), tracker.invocations().block()));
        return AsciiTable.getTable(rows, MAX_CONTRIBUTOR_COLUMNS);
    }
    private static BlockRow buildRow(SubmissionContext ctx, FileChangeModel file, CodeUnitModel unit, boolean isTest) {
        MetricsModel metrics = unit.getMetrics();
        if (Objects.isNull(metrics)) {
            return null;
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

        return new BlockRow(
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
                outlier);
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
    private static String formatDriver(BlockRow r) {
        if (r.operation() == OperationEnum.MODIFY) {
            return String.format("%.2f = (%.2f+%.2f)/2",
                    r.driver(),
                    r.projectedLines(), r.projectedInvocations());
        }
        return String.format("%.2f = (%.2f+%.2f+%.2f)/%.2f",
                r.driver(),
                r.projectedLines(), r.projectedNcss(), r.projectedInvocations(),
                DriverScore.TOTAL_WEIGHT);
    }
    private static String formatOperation(BlockRow r) {
        return Objects.isNull(r.operation()) ? "-" : r.operation().name();
    }
    private static int determineWidth(List<BlockRow> rows, java.util.function.Function<BlockRow, String> extractor, String header) {
        int longest = StringUtils.length(header);
        for (BlockRow row : rows) {
            longest = Math.max(longest, StringUtils.length(extractor.apply(row)));
        }
        return longest + 3;
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
    private static String formatDouble(double value) {
        return String.format("%.2f", value);
    }
    private static String formatPercent(double ratio) {
        return String.format("%.0f%%", ratio * 100);
    }
    private static String formatDimension(DimensionStats stats) {
        return String.format("min=%-4d p50=%-6.1f p75=%-6.1f p90=%-6.1f p95=%-6.1f max=%d",
                stats.min(),
                stats.p50(),
                stats.p75(),
                stats.p90(),
                stats.p95(),
                stats.max());
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

        public String baselineLine() {
            return String.format("  %-17s  N=%-5d  trivials_excluded=%d", label + ":", scaler.population(), trivialsExcluded);
        }
        public String capLine() {
            int budgetPerBlock = (int) Math.round(capQuantile * capMultiplier);
            return String.format("  %-17s quantile=%-5d bucket_budget_per_block=%d", label + ":", capQuantile, budgetPerBlock);
        }
        public List<String> scalerLines() {
            if (scaler.isEmpty()) {
                return Arrays.asList(String.format("  %s: (N=0)", label));
            }
            return Arrays.asList(
                    String.format("  %s: (N=%d)", label, scaler.population()),
                    String.format("    lines:   %s", formatDimension(scaler.lines())),
                    String.format("    ncss:    %s", formatDimension(scaler.ncss())),
                    String.format("    invocs:  %s", formatDimension(scaler.invocations())));
        }
        public String factorLine() {
            if (scaler.isEmpty()) {
                return String.format("    %-17s (N=0)", label + ":");
            }
            return String.format("    %-17s k_S=%.3f  k_I=%.3f   (lines.p50=%.1f, ncss.p50=%.1f, invocs.p50=%.1f)",
                    label + ":",
                    scaler.ncssFactor(),
                    scaler.invocationsFactor(),
                    scaler.lines().p50(),
                    scaler.ncss().p50(),
                    scaler.invocations().p50());
        }
    }

    @Value
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class RowBundle {
        List<BlockRow> nonTrivial;
        List<BlockRow> trivial;
    }

    @Value
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class BlockRow {
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
    }

    @Getter
    @Accessors(fluent = true)
    private static final class OutlierBucketCounts {
        private int methodProd;
        private int methodTest;
        private int ctorProd;
        private int ctorTest;
    }

    @Value
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class MaxRow {
        String dim;
        int value;
        String file;
        String block;
    }
}
