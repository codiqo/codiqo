package io.codiqo.maven.populator;

import static java.util.function.Predicate.not;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Precision;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.github.freva.asciitable.AsciiTable;
import com.github.freva.asciitable.Column;
import com.github.freva.asciitable.ColumnData;
import com.github.freva.asciitable.HorizontalAlign;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.ReportBuilder;
import io.codiqo.llm.client.ScoringClient.ScoringResult;
import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringRequest.CallerInfo;
import io.codiqo.llm.schema.LlmScoringRequest.ChangeSummary;
import io.codiqo.llm.schema.LlmScoringRequest.CodeBlockChange;
import io.codiqo.llm.schema.LlmScoringRequest.FileChange;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.LlmScoringResponse.Bug;
import io.codiqo.llm.schema.LlmScoringResponse.DimensionScore;
import io.codiqo.llm.schema.LlmScoringResponse.EffortBreakdown;
import io.codiqo.llm.schema.LlmScoringResponse.QualityDimensions;
import lombok.Value;

/**
 * Renders a commit analysis as an ASCII page for the console, mirroring the basic layout of the
 * web analysis view: identity, score with its calculation, the headline metrics, then the quality
 * dimensions, changed files and findings as tables.
 *
 * <p>Replaces the former single-page HTML report, which nobody read: it was written to a file the
 * runner threw away, and only on the local-LLM path — the CI goal overrides {@code doLlmScoring} and
 * never reached it.
 *
 * <p>Tables are rendered to strings here rather than looped in the template, because column widths
 * have to be measured across all rows before the first one can be emitted.
 */
public class ConsoleReportBuilder implements ReportBuilder {
    private static final String TITLE = "Codiqo — Commit Analysis";
    private static final String TEMPLATE_NAME = "console-analysis";
    private static final int ROUNDING = 2;
    private static final int COMMIT_SHA_LENGTH = 8;
    private static final int MESSAGE_MAX_CHARS = 100;
    private static final int SUMMARY_WRAP_CHARS = 96;
    private static final int MAX_FILE_ROWS = 25;
    private static final int MAX_FINDING_ROWS = 15;
    private static final int PATH_MAX_CHARS = 62;
    private static final int TITLE_MAX_CHARS = 52;
    private static final int MAX_BRANCHES = 2;

    private static final TemplateEngine TEMPLATE_ENGINE;
    private static final List<ColumnData<DimensionRow>> DIMENSION_COLUMNS;
    private static final List<ColumnData<FileRow>> FILE_COLUMNS;
    private static final List<ColumnData<FindingRow>> FINDING_COLUMNS;

    static {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("thymeleaf/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

        TEMPLATE_ENGINE = new TemplateEngine();
        TEMPLATE_ENGINE.setTemplateResolver(resolver);

        DIMENSION_COLUMNS = Arrays.<ColumnData<DimensionRow>> asList(
                new Column().header("Dimension").dataAlign(HorizontalAlign.LEFT).with(DimensionRow::getName),
                new Column().header("Score").dataAlign(HorizontalAlign.RIGHT).with(DimensionRow::getScore),
                new Column().header("Gate").dataAlign(HorizontalAlign.CENTER).with(DimensionRow::getGate));

        FILE_COLUMNS = Arrays.<ColumnData<FileRow>> asList(
                new Column().header("File").dataAlign(HorizontalAlign.LEFT).with(FileRow::getPath),
                new Column().header("Type").dataAlign(HorizontalAlign.LEFT).with(FileRow::getChangeType),
                new Column().header("+").dataAlign(HorizontalAlign.RIGHT).with(FileRow::getAdded),
                new Column().header("-").dataAlign(HorizontalAlign.RIGHT).with(FileRow::getDeleted),
                new Column().header("Blocks").dataAlign(HorizontalAlign.RIGHT).with(FileRow::getBlocks),
                new Column().header("Scope").dataAlign(HorizontalAlign.LEFT).with(FileRow::getScope));

        FINDING_COLUMNS = Arrays.<ColumnData<FindingRow>> asList(
                new Column().header("Severity").dataAlign(HorizontalAlign.LEFT).with(FindingRow::getSeverity),
                new Column().header("Finding").dataAlign(HorizontalAlign.LEFT).with(FindingRow::getTitle),
                new Column().header("File").dataAlign(HorizontalAlign.LEFT).with(FindingRow::getFile),
                new Column().header("Line").dataAlign(HorizontalAlign.RIGHT).with(FindingRow::getLine));
    }

    private final RunArgs args;

    public ConsoleReportBuilder(RunArgs args) {
        this.args = Objects.requireNonNull(args);
    }
    @Override
    public String buildReport(ScoringResult result, LlmScoringRequest request, ReportContext reportContext) {
        LlmScoringResponse response = result.getResponse();
        Context ctx = new Context(Locale.ENGLISH);

        ctx.setVariable("title", TITLE);
        ctx.setVariable("separator", StringUtils.repeat('=', TITLE.length()));

        ctx.setVariable("commitSha", StringUtils.left(StringUtils.defaultString(reportContext.getCommitId()), COMMIT_SHA_LENGTH));
        ctx.setVariable("branches", branchLabel(reportContext.getBranches()));
        ctx.setVariable("author", StringUtils.defaultString(reportContext.getAuthor()));
        ctx.setVariable("authorEmail", StringUtils.defaultString(reportContext.getAuthorEmail()));
        ctx.setVariable("timestamp", StringUtils.defaultString(reportContext.getTimestamp()));
        ctx.setVariable("project", StringUtils.defaultString(reportContext.getRepositoryName()));
        ctx.setVariable("message", firstLine(reportContext.getCommitMessage()));

        ctx.setVariable("score", String.format("%.0f", Optional.ofNullable(response.getScore()).orElse(0.0)));
        ctx.setVariable("classification", label(response.getChangeClassification()));
        ctx.setVariable("scoreCalculation", StringUtils.defaultIfBlank(response.getScoreCalculation(), "-"));
        ctx.setVariable("seniorReview", Optional.ofNullable(response.getRequiresSeniorReview()).orElse(0));
        ctx.setVariable("seniorReviewThreshold", args.getSeniorReviewThreshold());

        populateQuality(ctx, response);
        populateRisk(ctx, response);
        populateBlastRadius(ctx, request, response);
        populateVolume(ctx, request, response);

        ctx.setVariable("dimensionTable", renderDimensions(response.getQualityDimensions()));
        ctx.setVariable("fileTable", renderFiles(request));
        ctx.setVariable("findingTable", renderFindings(response));
        ctx.setVariable("findingCount", countFindings(response));
        ctx.setVariable("fileOverflow", overflow(CollectionUtils.size(request.getFileChanges()), MAX_FILE_ROWS));
        ctx.setVariable("findingOverflow", overflow(countFindings(response), MAX_FINDING_ROWS));

        ctx.setVariable("technicalTags", tags(response, true));
        ctx.setVariable("functionalTags", tags(response, false));
        ctx.setVariable("summary", wrap(response.getSummary()));

        ctx.setVariable("llmModel", StringUtils.defaultString(reportContext.getLlmModel()));
        ctx.setVariable("promptTokens", result.getPromptTokens());
        ctx.setVariable("completionTokens", result.getCompletionTokens());
        ctx.setVariable("durationSeconds", reportContext.getAnalysisDuration().toSeconds());

        return TEMPLATE_ENGINE.process(TEMPLATE_NAME, ctx);
    }
    private void populateQuality(Context ctx, LlmScoringResponse response) {
        double multiplier = 1.0;
        if (Objects.nonNull(response.getQualityMultiplier())) {
            multiplier = response.getQualityMultiplier().getFinalMultiplier();
        }
        ctx.setVariable("qualityMultiplier", Precision.round(multiplier, ROUNDING));
        ctx.setVariable("qualityMultiplierMin", args.getQualityMultiplierMin());
        ctx.setVariable("qualityMultiplierMax", args.getQualityMultiplierMax());
    }
    private void populateRisk(Context ctx, LlmScoringResponse response) {
        ctx.setVariable("riskScore", 0);
        ctx.setVariable("riskLevel", "-");
        if (Objects.nonNull(response.getRiskAssessment())) {
            ctx.setVariable("riskScore", Optional.ofNullable(response.getRiskAssessment().getRiskScore()).orElse(0));
            ctx.setVariable("riskLevel", label(response.getRiskAssessment().getRiskLevel()));
        }
        ctx.setVariable("riskScoreMax", args.getRiskScoreMax());
    }
    private static void populateBlastRadius(Context ctx, LlmScoringRequest request, LlmScoringResponse response) {
        int production = 0;
        int test = 0;
        for (CodeBlockChange block : CollectionUtils.emptyIfNull(request.getCodeBlockChanges())) {
            for (CallerInfo caller : CollectionUtils.emptyIfNull(block.getCallers())) {
                if (caller.isTestCaller()) {
                    test++;
                } else {
                    production++;
                }
            }
        }
        ctx.setVariable("callersTotal", production + test);
        ctx.setVariable("callersProduction", production);
        ctx.setVariable("callersTest", test);

        ctx.setVariable("blastRiskLevel", "-");
        if (Objects.nonNull(response.getBlastRadiusAnalysis())) {
            ctx.setVariable("blastRiskLevel", label(response.getBlastRadiusAnalysis().getRiskLevel()));
        }
    }
    /**
     * File counts come from request.getFileChanges() — the same list the table renders — not from
     * ChangeSummary.totalFilesChanged, which counts only files whose diff has *effective* changes
     * (DiffStats.effectiveChanges(), after blanks / imports / comment-only lines are filtered).
     * Mixing the two made the page contradict itself on 5 of 10 real commits, and deriving the prod
     * count by subtracting a payload-derived test count from the summary total rendered
     * "prod: -1" on a deletion-heavy commit. Splitting one list three ways cannot go negative.
     */
    private static void populateVolume(Context ctx, LlmScoringRequest request, LlmScoringResponse response) {
        ChangeSummary summary = request.getChangeSummary();
        ctx.setVariable("linesChanged", summary.getTotalLinesChanged());
        ctx.setVariable("blocksAdded", summary.getCodeBlocksAdded());
        ctx.setVariable("blocksModified", summary.getCodeBlocksModified());

        List<FileChange> changes = new ArrayList<>(CollectionUtils.emptyIfNull(request.getFileChanges()));
        long testFiles = changes.stream().filter(FileChange::isTest).count();
        long configFiles = changes.stream().filter(not(FileChange::isTest)).filter(FileChange::isConfig).count();
        ctx.setVariable("filesChanged", changes.size());
        ctx.setVariable("testFilesChanged", testFiles);
        ctx.setVariable("configFilesChanged", configFiles);
        ctx.setVariable("prodFilesChanged", changes.size() - testFiles - configFiles);

        ctx.setVariable("baseEffort", "0.00");
        ctx.setVariable("volumeScore", "0.00");
        EffortBreakdown breakdown = response.getEffortBreakdown();
        if (Objects.nonNull(breakdown)) {
            ctx.setVariable("baseEffort", String.format("%.2f", breakdown.getBaseEffortScore()));
            if (Objects.nonNull(breakdown.getVolumeScore())) {
                ctx.setVariable("volumeScore", String.format("%.2f", breakdown.getVolumeScore().getTotalVolumeScore()));
            }
        }
    }
    private static String renderDimensions(QualityDimensions dims) {
        if (Objects.isNull(dims)) {
            return "  (not assessed)";
        }
        List<DimensionRow> rows = new ArrayList<>();
        addDimension(rows, "Architecture Impact", dims.getArchitectureImpact());
        addDimension(rows, "Concurrency Risk", dims.getConcurrencyRisk());
        addDimension(rows, "Integration Surface", dims.getIntegrationSurface());
        addDimension(rows, "Data Integrity", dims.getDataIntegrity());
        addDimension(rows, "Security Sensitivity", dims.getSecuritySensitivity());
        addDimension(rows, "Scalability Impact", dims.getScalabilityImpact());
        addDimension(rows, "Observability", dims.getObservability());
        addDimension(rows, "Resilience", dims.getResilience());
        addDimension(rows, "Performance", dims.getPerformance());
        addDimension(rows, "Testing Coverage", dims.getTestingCoverage());

        if (rows.isEmpty()) {
            return "  (not assessed)";
        }
        return AsciiTable.getTable(AsciiTable.BASIC_ASCII_NO_DATA_SEPARATORS, rows, DIMENSION_COLUMNS);
    }
    /**
     * a dimension the model scored null is "not touched by this change" rather than zero, so it is
     * rendered as a dash instead of being dropped — the absence is itself informative
     */
    private static void addDimension(List<DimensionRow> rows, String name, DimensionScore dim) {
        if (Objects.nonNull(dim)) {
            boolean assessed = Objects.nonNull(dim.getScore());
            String score = assessed ? String.valueOf(dim.getScore()) : "-";
            String gate = "-";
            if (assessed) {
                gate = dim.isQualityGateMet() ? "met" : "FAILED";
            }
            rows.add(new DimensionRow(name, score, gate));
        }
    }
    private static String renderFiles(LlmScoringRequest request) {
        List<FileChange> changes = new ArrayList<>(CollectionUtils.emptyIfNull(request.getFileChanges()));
        if (changes.isEmpty()) {
            return "  (none)";
        }
        changes.sort((left, right) -> Integer.compare(
                right.getLinesAdded() + right.getLinesDeleted(),
                left.getLinesAdded() + left.getLinesDeleted()));

        List<FileRow> rows = new ArrayList<>();
        for (FileChange change : changes.subList(0, Math.min(changes.size(), MAX_FILE_ROWS))) {
            rows.add(new FileRow(
                    abbreviateLeft(change.getPath(), PATH_MAX_CHARS),
                    String.valueOf(change.getChangeType()),
                    String.valueOf(change.getLinesAdded()),
                    String.valueOf(change.getLinesDeleted()),
                    String.valueOf(countBlocks(request, change.getPath())),
                    scope(change)));
        }
        return AsciiTable.getTable(AsciiTable.BASIC_ASCII_NO_DATA_SEPARATORS, rows, FILE_COLUMNS);
    }
    private static String renderFindings(LlmScoringResponse response) {
        List<FindingRow> rows = new ArrayList<>();
        if (Objects.nonNull(response.getBugs())) {
            addFindings(rows, response.getBugs().getBlocking(), "BLOCKING");
            addFindings(rows, response.getBugs().getMajor(), "MAJOR");
            addFindings(rows, response.getBugs().getMinor(), "MINOR");
        }
        if (rows.isEmpty()) {
            return "  (none)";
        }
        return AsciiTable.getTable(AsciiTable.BASIC_ASCII_NO_DATA_SEPARATORS, rows.subList(0, Math.min(rows.size(), MAX_FINDING_ROWS)), FINDING_COLUMNS);
    }
    private static void addFindings(List<FindingRow> rows, List<Bug> bugs, String severity) {
        for (Bug bug : CollectionUtils.emptyIfNull(bugs)) {
            rows.add(new FindingRow(
                    severity,
                    StringUtils.abbreviate(StringUtils.defaultString(bug.getTitle()), TITLE_MAX_CHARS),
                    abbreviateLeft(bug.getFile(), PATH_MAX_CHARS),
                    Objects.nonNull(bug.getLine()) ? String.valueOf(bug.getLine()) : "-"));
        }
    }
    private static int countFindings(LlmScoringResponse response) {
        if (Objects.isNull(response.getBugs())) {
            return 0;
        }
        return CollectionUtils.size(response.getBugs().getBlocking())
                + CollectionUtils.size(response.getBugs().getMajor())
                + CollectionUtils.size(response.getBugs().getMinor());
    }
    private static int countBlocks(LlmScoringRequest request, String path) {
        int toReturn = 0;
        for (CodeBlockChange block : CollectionUtils.emptyIfNull(request.getCodeBlockChanges())) {
            if (StringUtils.equals(path, block.getFile())) {
                toReturn++;
            }
        }
        return toReturn;
    }
    private static String scope(FileChange change) {
        if (change.isConfig()) {
            return "config";
        }
        return change.isTest() ? "test" : "prod";
    }
    private static String tags(LlmScoringResponse response, boolean technical) {
        if (Objects.isNull(response.getTags())) {
            return "-";
        }
        List<String> values = technical ? response.getTags().getTechnical() : response.getTags().getFunctional();
        if (CollectionUtils.isEmpty(values)) {
            return "-";
        }
        return StringUtils.join(values, ", ");
    }
    /**
     * a commit reachable from many refs carries all of them, which is unreadable on one line and not
     * what the reader wants anyway — keep a couple and count the rest
     */
    private static String branchLabel(List<String> branches) {
        List<String> values = Optional.ofNullable(branches).orElse(Collections.emptyList());
        if (values.isEmpty()) {
            return StringUtils.EMPTY;
        }
        if (values.size() <= MAX_BRANCHES) {
            return StringUtils.join(values, ", ");
        }
        return StringUtils.join(values.subList(0, MAX_BRANCHES), ", ") + " +" + (values.size() - MAX_BRANCHES) + " more";
    }
    /** an absent enum is "the model did not say", which a literal "null" misrepresents as a value. */
    private static String label(Object value) {
        return Objects.isNull(value) ? "-" : String.valueOf(value);
    }
    private static String overflow(int total, int shown) {
        if (total > shown) {
            return String.format("  ... %d more (%d of %d shown)", total - shown, shown, total);
        }
        return StringUtils.EMPTY;
    }
    private static String firstLine(String message) {
        return StringUtils.abbreviate(StringUtils.defaultString(StringUtils.substringBefore(message, StringUtils.LF)).trim(), MESSAGE_MAX_CHARS);
    }
    /**
     * paths are discriminated by their tail (module + class), so an over-long one keeps its end and
     * loses its head — the opposite of what abbreviate() does
     */
    private static String abbreviateLeft(String path, int max) {
        String value = StringUtils.defaultString(path);
        if (value.length() <= max) {
            return value;
        }
        return "..." + StringUtils.right(value, max - 3);
    }
    /**
     * word wrap, done here rather than with commons-text: that artifact is only in
     * dependencyManagement and unused by any module, and lang3's WordUtils is deprecated — neither
     * is worth taking on for one paragraph.
     */
    private static String wrap(String text) {
        if (StringUtils.isBlank(text)) {
            return "  (none)";
        }
        StringBuilder toReturn = new StringBuilder("  ");
        int lineLength = 0;
        for (String word : StringUtils.split(text.trim())) {
            if (lineLength > 0 && lineLength + word.length() + 1 > SUMMARY_WRAP_CHARS) {
                toReturn.append("\n  ");
                lineLength = 0;
            } else if (lineLength > 0) {
                toReturn.append(' ');
                lineLength++;
            }
            toReturn.append(word);
            lineLength += word.length();
        }
        return toReturn.toString();
    }
    @Value
    private static class DimensionRow {
        String name;
        String score;
        String gate;
    }
    @Value
    private static class FileRow {
        String path;
        String changeType;
        String added;
        String deleted;
        String blocks;
        String scope;
    }
    @Value
    private static class FindingRow {
        String severity;
        String title;
        String file;
        String line;
    }
}
