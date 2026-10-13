package io.codiqo.maven.populator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
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
import org.apache.maven.plugin.logging.Log;
import org.eclipse.lsp4j.SymbolKind;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.google.common.collect.Lists;
import com.google.common.collect.Streams;

import io.codiqo.api.metrics.DriverScaler;
import io.codiqo.api.metrics.DriverScore;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CodeUnitModel.OperationEnum;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.llm.SubmissionToRequestMapper;
import io.codiqo.llm.VolumeScoreCalculator;
import io.codiqo.llm.VolumeScoreCalculator.CodeBlockEffort;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
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
        String text = TEMPLATE_ENGINE.process(TEMPLATE_NAME, templateContext(ctx, scoredRows(ctx)));
        for (String line : StringUtils.splitPreserveAllTokens(Strings.CS.removeEnd(text, StringUtils.LF), CharUtils.LF)) {
            log.info(line);
        }
    }
    /**
     * The scored blocks' rows, or empty when the submission's code blocks cannot be mapped the way the server maps
     * them (a diff JGit cannot parse, a code unit without metrics or a location). The summary is printed before the
     * rest of the submission is assembled precisely so that a commit which fails later still leaves it in the log
     * (see {@code AbstractAnalyzeMojo}), and goals that never submit print it too: a mapping failure must therefore
     * cost only the block table, reported as a warning naming the cause, never the analysis. The server reports the
     * same failure when it scores the commit.
     */
    private Optional<List<BlockRow>> scoredRows(SubmissionContext ctx) {
        try {
            return Optional.of(buildScoredRows(ctx));
        } catch (RuntimeException err) {
            log.warn("calibration summary: the changed code blocks could not be mapped as the server maps them, so their table is left out", err);
            return Optional.empty();
        }
    }
    private static Context templateContext(SubmissionContext ctx, Optional<List<BlockRow>> scoredRows) {
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

        List<BlockRow> changedBlocks = scoredRows.orElse(Collections.emptyList());
        List<BlockRow> trivialBlocks = buildTrivialRows(submission);
        toReturn.setVariable("blocks", scoredRows.map(rows -> countChangedBlocks(rows, trivialBlocks)).orElse(null));
        toReturn.setVariable("changedBlocks", changedBlocks);
        toReturn.setVariable("trivialBlocks", trivialBlocks);
        toReturn.setVariable("outliers", changedBlocks.stream()
                .filter(BlockRow::outlier)
                .sorted(Comparator.comparingDouble((BlockRow r) -> Math.max(r.deviationNcss(), r.deviationInvocations())).reversed())
                .collect(Collectors.toList()));
        toReturn.setVariable("maxDeviation", ctx.getArgs().getDriverFactorMaxDeviation());
        toReturn.setVariable("outlierCounts", countOutliersByBucket(changedBlocks));
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
    /** counted from the rows the tables print, so the header and the tables describe the same blocks */
    private static BlockCounts countChangedBlocks(List<BlockRow> scoredRows, List<BlockRow> trivialRows) {
        BlockCounts counts = new BlockCounts();
        counts.trivialSkipped = trivialRows.size();
        for (BlockRow row : scoredRows) {
            counts.total++;
            if (row.modify()) {
                counts.modified++;
            } else {
                counts.added++;
            }
            if (row.test()) {
                counts.test++;
            } else {
                counts.prod++;
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
    /**
     * One row per block the server scores, from the same {@link CodeBlockChange}s and one
     * {@link VolumeScoreCalculator#calculateCodeBlockEfforts} pass over them, so the driver column is the server's
     * driver score before the diff classification, moved-line and category factors. The operation, test and cap
     * multipliers only shape the effort, which the summary does not print, so they are passed as 1.0. The pass returns
     * one effort per block in order, skipping only DELETE blocks, which {@code codeBlockChanges} never yields.
     */
    private static List<BlockRow> buildScoredRows(SubmissionContext ctx) {
        List<CodeBlockChange> blocks = SubmissionToRequestMapper.codeBlockChanges(ctx.getSubmissionModel().getFiles());
        List<CodeBlockEffort> efforts = VolumeScoreCalculator.calculateCodeBlockEfforts(blocks,
                ctx.getMethodScalerProd(), ctx.getMethodScalerTest(), ctx.getConstructorScalerProd(), ctx.getConstructorScalerTest(),
                ctx.getMethodCapQuantileProd(), ctx.getMethodCapQuantileTest(), ctx.getConstructorCapQuantileProd(), ctx.getConstructorCapQuantileTest(),
                1.0, 1.0, 1.0, ctx.getArgs().getDriverFactorMaxDeviation(), false);

        List<BlockRow> toReturn = Streams.zip(blocks.stream(), efforts.stream(), (block, effort) -> scoredRow(ctx, block, effort))
                .collect(Collectors.toList());
        toReturn.sort(Comparator.comparingDouble(BlockRow::driver).reversed());
        return toReturn;
    }
    private static List<BlockRow> buildTrivialRows(AnalysisSubmissionModel submission) {
        List<BlockRow> toReturn = Lists.newArrayList();
        for (FileChangeModel file : CollectionUtils.emptyIfNull(submission.getFiles())) {
            for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (BooleanUtils.and(new boolean[] {
                        Boolean.TRUE.equals(unit.getIsTrivial()),
                        isMethodOrConstructor(unit.getKind()),
                        unit.getOperation() != OperationEnum.DELETE,
                        Objects.nonNull(unit.getMetrics()) })) {
                    toReturn.add(trivialRow(file, unit));
                }
            }
        }
        toReturn.sort(Comparator.comparingInt(BlockRow::lines).reversed());
        return toReturn;
    }
    private static MaxContributors maxContributors(String label, SampleMaxTracker tracker) {
        return new MaxContributors(label, Arrays.asList(
                new MaxRow("lines", tracker.lines().value(), tracker.lines().file(), tracker.lines().block()),
                new MaxRow("ncss", tracker.ncss().value(), tracker.ncss().file(), tracker.ncss().block()),
                new MaxRow("invocs", tracker.invocations().value(), tracker.invocations().file(), tracker.invocations().block())));
    }
    /** L and I as the driver formula read them: a MODIFY block's changed lines (already clamped to its body) and calls */
    private static BlockRow scoredRow(SubmissionContext ctx, CodeBlockChange block, CodeBlockEffort effort) {
        int lines = effort.getBodyCodeLines();
        int invocations = effort.getDirectInvocationCount();
        if (block.isModify()) {
            lines = (int) effort.getScaledLines();
            invocations = effort.getEffectiveInvocationsChanged();
        }

        return new BlockRow(
                shortFileName(block.getFile()),
                StringUtils.normalizeSpace(Optional.ofNullable(block.getName()).orElse(NONE)),
                block.isConstructor() ? SymbolKind.Constructor.name() : SymbolKind.Method.name(),
                block.isTest(),
                block.isModify() ? OperationEnum.MODIFY : OperationEnum.NEW,
                lines, effort.getNonCommentCodeStatements(), invocations,
                effort.getScaledLines(),
                effort.getScaledNcss(),
                effort.getScaledInvocations(),
                effort.getDriverScore(),
                effort.getBlockRatioDeviationNcss(),
                effort.getBlockRatioDeviationInvocations(),
                effort.isBlockRatioOutlier(),
                selectScaler(ctx, block.isConstructor(), block.isTest()).isEmpty());
    }
    /**
     * A trivial block is never scored, so it has no changed-line count of its own: its row shows the block's size —
     * body lines, statements and calls — whatever the operation.
     */
    private static BlockRow trivialRow(FileChangeModel file, CodeUnitModel unit) {
        MetricsModel metrics = unit.getMetrics();
        int lines = Optional.ofNullable(metrics.getBodyCodeLines())
                .or(() -> Optional.ofNullable(metrics.getNonCommentCodeLines()))
                .or(() -> Optional.ofNullable(metrics.getCodeLines()))
                .orElse(0);

        return new BlockRow(
                shortFileName(file.getPath()),
                StringUtils.normalizeSpace(Optional.ofNullable(unit.getName()).orElse(NONE)),
                kindLabel(unit.getKind()),
                Boolean.TRUE.equals(file.getIsTest()),
                unit.getOperation(),
                lines, Optional.ofNullable(metrics.getNonCommentCodeStatements()).orElse(0),
                Optional.ofNullable(metrics.getDirectInvocationCount()).orElse(0),
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, false);
    }
    private static DriverScaler selectScaler(SubmissionContext ctx, boolean isConstructor, boolean isTest) {
        if (isConstructor) {
            return isTest ? ctx.getConstructorScalerTest() : ctx.getConstructorScalerProd();
        }
        return isTest ? ctx.getMethodScalerTest() : ctx.getMethodScalerProd();
    }
    private static String weighted(double weight, double projected) {
        return fixed(weight) + "×" + fixed(projected);
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
        boolean emptyScaler;

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
        /**
         * pL, pS and pI are printed unweighted, so the formula applies each {@link DriverScore} weight itself. A bucket
         * without samples has no scaler and scores 0, which no weighted sum of the printed components reproduces.
         */
        public String driverFormula() {
            if (emptyScaler) {
                return fixed(driver) + " (no baseline samples in this bucket)";
            }
            String lines = weighted(DriverScore.WEIGHT_LINES, projectedLines);
            String invocations = weighted(DriverScore.WEIGHT_INVOCATIONS, projectedInvocations);
            if (modify()) {
                return fixed(driver) + " = (" + lines + "+" + invocations + ")/" + fixed(DriverScore.MODIFY_WEIGHT);
            }
            return fixed(driver) + " = (" + lines + "+" + weighted(DriverScore.WEIGHT_NCSS, projectedNcss) + "+" + invocations + ")/"
                    + fixed(DriverScore.TOTAL_WEIGHT);
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
