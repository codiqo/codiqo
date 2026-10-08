package io.codiqo.maven.populator;

import static java.util.function.Predicate.not;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.math3.util.Precision;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.google.common.collect.Lists;

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
 * dimensions, changed files and findings as tables. This class only gathers the values; the
 * console-analysis template lays them out.
 */
public class ConsoleReportBuilder implements ReportBuilder {
    private static final String TITLE = "Codiqo — Commit Analysis";
    private static final String TEMPLATE_NAME = "console-analysis";
    private static final int ROUNDING = 2;
    private static final int COMMIT_SHA_LENGTH = 8;
    private static final int MAX_FILE_ROWS = 25;
    private static final int MAX_FINDING_ROWS = 15;
    private static final int PATH_MAX_CHARS = 62;
    private static final int TITLE_MAX_CHARS = 52;
    private static final String NONE = "-";
    private static final String ELLIPSIS = "...";

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

    private final RunArgs args;

    public ConsoleReportBuilder(RunArgs args) {
        this.args = Objects.requireNonNull(args);
    }
    @Override
    public String buildReport(ScoringResult result, LlmScoringRequest request, ReportContext reportContext) {
        LlmScoringResponse response = result.getResponse();
        Context ctx = new Context(Locale.ENGLISH);

        ctx.setVariable("title", TITLE);

        ctx.setVariable("commitSha", StringUtils.left(StringUtils.defaultString(reportContext.getCommitId()), COMMIT_SHA_LENGTH));
        ctx.setVariable("branches", Optional.ofNullable(reportContext.getBranches()).orElse(Collections.emptyList()));
        ctx.setVariable("author", reportContext.getAuthor());
        ctx.setVariable("authorEmail", reportContext.getAuthorEmail());
        ctx.setVariable("timestamp", reportContext.getTimestamp());
        ctx.setVariable("project", reportContext.getRepositoryName());
        ctx.setVariable("message", StringUtils.defaultString(StringUtils.substringBefore(reportContext.getCommitMessage(), StringUtils.LF)).trim());

        ctx.setVariable("score", response.getScore());
        ctx.setVariable("classification", response.getChangeClassification());
        ctx.setVariable("scoreCalculation", response.getScoreCalculation());
        ctx.setVariable("seniorReview", response.getRequiresSeniorReview());
        ctx.setVariable("seniorReviewThreshold", args.getSeniorReviewThreshold());

        populateQuality(ctx, response);
        populateRisk(ctx, response);
        populateBlastRadius(ctx, request, response);
        populateVolume(ctx, request, response);

        ctx.setVariable("dimensions", dimensionRows(response.getQualityDimensions()));
        ctx.setVariable("files", fileRows(request));
        ctx.setVariable("fileTotal", CollectionUtils.size(request.getFileChanges()));
        ctx.setVariable("findings", findingRows(response));
        ctx.setVariable("findingTotal", countFindings(response));

        ctx.setVariable("technicalTags", Collections.emptyList());
        ctx.setVariable("functionalTags", Collections.emptyList());
        if (Objects.nonNull(response.getTags())) {
            ctx.setVariable("technicalTags", CollectionUtils.emptyIfNull(response.getTags().getTechnical()));
            ctx.setVariable("functionalTags", CollectionUtils.emptyIfNull(response.getTags().getFunctional()));
        }
        ctx.setVariable("summary", StringUtils.defaultString(response.getSummary()));

        ctx.setVariable("llmModel", reportContext.getLlmModel());
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
        ctx.setVariable("riskLevel", null);
        if (Objects.nonNull(response.getRiskAssessment())) {
            ctx.setVariable("riskScore", response.getRiskAssessment().getRiskScore());
            ctx.setVariable("riskLevel", response.getRiskAssessment().getRiskLevel());
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

        ctx.setVariable("blastRiskLevel", null);
        if (Objects.nonNull(response.getBlastRadiusAnalysis())) {
            ctx.setVariable("blastRiskLevel", response.getBlastRadiusAnalysis().getRiskLevel());
        }
    }
    /**
     * File counts come from request.getFileChanges(), the same list the files table renders, not from
     * ChangeSummary.totalFilesChanged, which counts only files whose diff has effective changes.
     * Mixing the two sources made the page contradict itself, with a headline count that did not
     * match the rows below it.
     */
    private static void populateVolume(Context ctx, LlmScoringRequest request, LlmScoringResponse response) {
        ChangeSummary summary = request.getChangeSummary();
        ctx.setVariable("linesChanged", summary.getTotalLinesChanged());
        ctx.setVariable("blocksAdded", summary.getCodeBlocksAdded());
        ctx.setVariable("blocksModified", summary.getCodeBlocksModified());

        List<FileChange> changes = Lists.newArrayList(CollectionUtils.emptyIfNull(request.getFileChanges()));
        long testFiles = changes.stream().filter(FileChange::isTest).count();
        long configFiles = changes.stream().filter(not(FileChange::isTest)).filter(FileChange::isConfig).count();
        ctx.setVariable("filesChanged", changes.size());
        ctx.setVariable("testFilesChanged", testFiles);
        ctx.setVariable("configFilesChanged", configFiles);
        ctx.setVariable("prodFilesChanged", changes.size() - testFiles - configFiles);

        ctx.setVariable("baseEffort", 0.0);
        ctx.setVariable("volumeScore", 0.0);
        EffortBreakdown breakdown = response.getEffortBreakdown();
        if (Objects.nonNull(breakdown)) {
            ctx.setVariable("baseEffort", breakdown.getBaseEffortScore());
            if (Objects.nonNull(breakdown.getVolumeScore())) {
                ctx.setVariable("volumeScore", breakdown.getVolumeScore().getTotalVolumeScore());
            }
        }
    }
    private static List<DimensionRow> dimensionRows(QualityDimensions dims) {
        List<DimensionRow> toReturn = Lists.newArrayList();
        if (Objects.nonNull(dims)) {
            addDimension(toReturn, "Architecture Impact", dims.getArchitectureImpact());
            addDimension(toReturn, "Concurrency Risk", dims.getConcurrencyRisk());
            addDimension(toReturn, "Integration Surface", dims.getIntegrationSurface());
            addDimension(toReturn, "Data Integrity", dims.getDataIntegrity());
            addDimension(toReturn, "Security Sensitivity", dims.getSecuritySensitivity());
            addDimension(toReturn, "Scalability Impact", dims.getScalabilityImpact());
            addDimension(toReturn, "Observability", dims.getObservability());
            addDimension(toReturn, "Resilience", dims.getResilience());
            addDimension(toReturn, "Performance", dims.getPerformance());
            addDimension(toReturn, "Testing Coverage", dims.getTestingCoverage());
        }
        return toReturn;
    }
    /**
     * A dimension the model scored null means "not touched by this change" rather than zero, so it is
     * rendered as a dash instead of being dropped or shown as 0: the absence is itself informative.
     */
    private static void addDimension(List<DimensionRow> rows, String name, DimensionScore dim) {
        if (Objects.nonNull(dim)) {
            String score = NONE;
            String gate = NONE;
            if (Objects.nonNull(dim.getScore())) {
                score = String.valueOf(dim.getScore());
                gate = dim.isQualityGateMet() ? "met" : "FAILED";
            }
            rows.add(new DimensionRow(name, score, gate));
        }
    }
    private static List<FileRow> fileRows(LlmScoringRequest request) {
        List<FileChange> changes = Lists.newArrayList(CollectionUtils.emptyIfNull(request.getFileChanges()));
        changes.sort((left, right) -> Integer.compare(
                right.getLinesAdded() + right.getLinesDeleted(),
                left.getLinesAdded() + left.getLinesDeleted()));

        List<FileRow> toReturn = Lists.newArrayList();
        for (FileChange change : changes.subList(0, Math.min(changes.size(), MAX_FILE_ROWS))) {
            toReturn.add(new FileRow(
                    tail(StringUtils.defaultString(change.getPath())),
                    String.valueOf(change.getChangeType()),
                    change.getLinesAdded(),
                    change.getLinesDeleted(),
                    countBlocks(request, change.getPath()),
                    scope(change)));
        }
        return toReturn;
    }
    private static List<FindingRow> findingRows(LlmScoringResponse response) {
        List<FindingRow> toReturn = Lists.newArrayList();
        if (Objects.nonNull(response.getBugs())) {
            addFindings(toReturn, response.getBugs().getBlocking(), "BLOCKING");
            addFindings(toReturn, response.getBugs().getMajor(), "MAJOR");
            addFindings(toReturn, response.getBugs().getMinor(), "MINOR");
        }
        return toReturn.subList(0, Math.min(toReturn.size(), MAX_FINDING_ROWS));
    }
    private static void addFindings(List<FindingRow> rows, List<Bug> bugs, String severity) {
        for (Bug bug : CollectionUtils.emptyIfNull(bugs)) {
            rows.add(new FindingRow(
                    severity,
                    StringUtils.abbreviate(StringUtils.defaultString(bug.getTitle()), TITLE_MAX_CHARS),
                    tail(StringUtils.defaultString(bug.getFile())),
                    Objects.isNull(bug.getLine()) ? NONE : String.valueOf(bug.getLine())));
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
            if (Strings.CS.equals(path, block.getFile())) {
                toReturn++;
            }
        }
        return toReturn;
    }
    /**
     * Paths are told apart by their tail (module and class), so an over-long one keeps its end and loses its head.
     * StringUtils.abbreviate() does the opposite and would leave rows that differ only in the part it cut.
     */
    private static String tail(String path) {
        if (path.length() <= PATH_MAX_CHARS) {
            return path;
        }
        return ELLIPSIS + StringUtils.right(path, PATH_MAX_CHARS - ELLIPSIS.length());
    }
    private static String scope(FileChange change) {
        if (change.isConfig()) {
            return "config";
        }
        return change.isTest() ? "test" : "prod";
    }

    /** a dimension the model scored null was not touched by the change: its score and gate read "-" */
    public static final class DimensionRow extends ImmutableTriple<String, String, String> {
        public DimensionRow(String name, String score, String gate) {
            super(name, score, gate);
        }
        public String getName() {
            return getLeft();
        }
        public String getScore() {
            return getMiddle();
        }
        public String getGate() {
            return getRight();
        }
    }

    @Value
    public static class FileRow {
        String path;
        String changeType;
        int added;
        int deleted;
        int blocks;
        String scope;
    }

    @Value
    public static class FindingRow {
        String severity;
        String title;
        String file;
        String line;
    }
}
