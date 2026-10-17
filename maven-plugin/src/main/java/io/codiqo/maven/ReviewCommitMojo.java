package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.core.DefaultLanguageProcessors;
import io.codiqo.llm.StaticAnalysisLists;
import io.codiqo.llm.review.FindingTriage;
import io.codiqo.llm.review.FindingVerdict;
import io.codiqo.llm.review.LocalReview;
import io.codiqo.llm.review.LocalReviewModels;
import io.codiqo.llm.review.LocalReviewer;
import io.codiqo.llm.review.ReviewEndpoint;
import io.codiqo.llm.review.ReviewTags;
import io.codiqo.llm.review.ReviewUnit;
import io.codiqo.llm.review.SessionUsage;
import io.codiqo.llm.review.StaticFinding;
import io.codiqo.llm.review.UnitLabelling;
import io.codiqo.llm.review.UnitLabels;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.maven.auth.MavenCredentials;
import io.codiqo.maven.logging.MavenLogFactory;
import lombok.Value;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reviews one commit for bugs with OpenCode, on this machine: the agents read the repository the goal runs in and look
 * at the commit with read-only git, so nothing is built and nothing is submitted. The findings are logged and written
 * to a JSON report together with the tokens every agent session used.
 *
 * <p>Needs the {@code opencode} executable on the PATH. The models are called through the backend's LLM proxy with the
 * member's Codiqo credential (a configured key, or the browser login), counted against the organization; a local
 * Ollama daemon works too, with {@code -Dcodiqo.review.baseUrl=http://127.0.0.1:11434/v1}.
 */
@Mojo(name = "review", requiresProject = false, aggregator = true, threadSafe = true)
public class ReviewCommitMojo extends AbstractMojo {
    private static final Set<String> YAML_EXTENSIONS = Set.of("yaml", "yml");

    @Parameter(property = "codiqo.review.commit", defaultValue = "HEAD")
    private String commit;

    @Parameter(property = "codiqo.review.directory", defaultValue = "${basedir}")
    private File directory;

    @Parameter(property = "codiqo.review.output", defaultValue = "${basedir}/target/codiqo-review.json")
    private File output;

    /** each review setting left unset keeps its {@link RunArgs} default */
    @Parameter(property = "codiqo.review.executable")
    private String executable;

    @Parameter(property = "codiqo.review.baseUrl")
    private String baseUrl;

    @Parameter(property = "codiqo.review.coordinatorModel")
    private String coordinatorModel;

    @Parameter(property = "codiqo.review.reviewerModel")
    private String reviewerModel;

    @Parameter(property = "codiqo.review.timeoutMinutes")
    private Integer timeoutMinutes;

    /** retries of a call through the relay, to the Codiqo proxy or a direct endpoint, that failed on the way (network error, timeout, 429 or 5xx) */
    @Parameter(property = "codiqo.review.relayRetries")
    private Integer relayRetries;

    /** how long a call through the relay, to the Codiqo proxy or a direct endpoint, may take to start answering */
    @Parameter(property = "codiqo.review.relayTimeoutSeconds")
    private Integer relayTimeoutSeconds;

    /** where the trace of every session and tool call goes; unset writes it beside the report */
    @Parameter(property = "codiqo.review.traceDirectory")
    private File traceDirectory;

    /**
     * A JSON array of static-analysis findings on the commit's added lines ({@code StaticFinding}): when given, a fork
     * of the review's session judges each one after the review, and the verdicts go into the report as {@code triage}.
     */
    @Parameter(property = "codiqo.review.findings")
    private File findings;

    /**
     * A submission an analysis goal wrote for this commit ({@code codiqo-submission-<sha>.yaml} or {@code .json}): a
     * fork of the review's session labels its code units by id after the review, and the report compares those labels,
     * unit by unit, with what the reviewers' own labels name.
     */
    @Parameter(property = "codiqo.review.submission")
    private File submission;

    @Parameter(property = "codiqo.apiKey")
    private String apiKey;

    @Parameter(property = "codiqo.authUrl", defaultValue = RunArgs.DEFAULT_AUTH_URL)
    private String authUrl;

    @Parameter(property = "codiqo.resourceUrl", defaultValue = RunArgs.DEFAULT_RESOURCE_URL)
    private String resourceUrl;

    @Override
    public void execute() throws MojoExecutionException {
        RunArgs args = new RunArgs();
        args.setReviewTraceDirectory(Optional.ofNullable(traceDirectory).orElse(output.getAbsoluteFile().getParentFile()));
        Optional.ofNullable(executable).ifPresent(args::setReviewExecutable);
        Optional.ofNullable(baseUrl).ifPresent(args::setReviewBaseUrl);
        Optional.ofNullable(coordinatorModel).ifPresent(args::setReviewCoordinatorModel);
        Optional.ofNullable(reviewerModel).ifPresent(args::setReviewReviewerModel);
        Optional.ofNullable(timeoutMinutes).ifPresent(minutes -> args.setReviewTimeout(Duration.ofMinutes(minutes)));
        Optional.ofNullable(relayRetries).ifPresent(args::setReviewRelayRetries);
        Optional.ofNullable(relayTimeoutSeconds).ifPresent(seconds -> args.setReviewRelayRequestTimeout(Duration.ofSeconds(seconds)));

        ReviewEndpoint endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, null);
        if (endpoint.isProxiedBy(resourceUrl)) {
            endpoint = new ReviewEndpoint(args.getReviewBaseUrl(), null, MavenCredentials.resolve(apiKey, authUrl, resourceUrl, getLog()));
        }

        try {
            Optional<LocalReviewer.BuildSource<StaticFinding>> source = Optional.empty();
            if (Objects.nonNull(findings)) {
                List<StaticFinding> given = JsonMapper.builder().build().readerForListOf(StaticFinding.class).readValue(findings);
                warnUnlisted(given);
                source = Optional.of(() -> given);
            }
            LocalReviewer reviewer = new LocalReviewer(args, endpoint, LocalReviewGuidance.read(directory, getLog()), ReviewTags.NONE, DefaultLanguageProcessors.reviewLanguages(),
                    new MavenLogFactory(getLog()).getLogger(LocalReviewer.class));
            Optional<AnalysisSubmissionModel> analysed = Optional.empty();
            if (Objects.nonNull(submission)) {
                /** the analysis goals write YAML unless told otherwise */
                ObjectMapper mapper = JsonMapper.builder().build();
                if (YAML_EXTENSIONS.contains(FilenameUtils.getExtension(submission.getName()))) {
                    mapper = SubmitAnalysisFileMojo.yamlMapper();
                }
                AnalysisSubmissionModel read = mapper.readerFor(AnalysisSubmissionModel.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(submission);
                requireReviewedCommit(read);
                analysed = Optional.of(read);
            }
            Optional<UnitLabelling.Checklist> checklist = analysed.map(UnitLabelling::checklist);
            Optional<LocalReviewer.BuildSource<ReviewUnit>> units = checklist.map(listed -> listed::getListed);

            LocalReviewer.ReviewOutcome result = reviewer.reviewThenFollowUp(directory.toPath(), commit, units, source);
            logFindings(result.getReview());
            result.getTriage().ifPresent(this::logTriage);

            Optional<LabellingReport> labelling = Optional.empty();
            if (BooleanUtils.and(new boolean[] { analysed.isPresent(), Objects.nonNull(result.getReview().getAssessment()) })) {
                labelling = Optional.of(compare(analysed.get(), checklist.get(), result));
            }
            writeReport(result.getReview(), result.getTriage(), labelling);
        } catch (Exception err) {
            throw new MojoExecutionException("local review of " + commit + " failed: " + err.getMessage(), err);
        }
    }
    /**
     * A findings file may name a tool the static analysis review keeps no lists for: the triage still judges its findings
     * and a defect still becomes a bug, but no other verdict on them is recorded.
     */
    private void warnUnlisted(List<StaticFinding> given) {
        List<String> unlisted = given.stream()
                .map(StaticFinding::getTool)
                .distinct()
                .filter(tool -> StaticAnalysisLists.of(tool).isEmpty())
                .map(String::valueOf)
                .toList();
        if (CollectionUtils.isNotEmpty(unlisted)) {
            getLog().warn(String.format("the static analysis review keeps no lists for %s: a defect among their findings is reported as a bug,"
                    + " every other verdict on them is left out of the review", unlisted));
        }
    }
    private void logFindings(LocalReview review) {
        LlmScoringResponse.Bugs bugs = review.getBugs();
        Map<String, List<LlmScoringResponse.Bug>> bySeverity = Maps.newLinkedHashMap();
        bySeverity.put("blocking", bugs.getBlocking());
        bySeverity.put("major", bugs.getMajor());
        bySeverity.put("minor", bugs.getMinor());

        int total = 0;
        for (Map.Entry<String, List<LlmScoringResponse.Bug>> severity : bySeverity.entrySet()) {
            for (LlmScoringResponse.Bug bug : severity.getValue()) {
                getLog().warn(String.format("[%s] %s:%s %s", severity.getKey(), bug.getFile(), bug.getLine(), bug.getTitle()));
                total++;
            }
        }
        getLog().info(String.format("review of %s: %d finding(s) in %ds; tokens: %,d input (+%,d cached), %,d output across %d session(s); "
                + "%d reviewers",
                review.getCommit(),
                total,
                review.getTook().toSeconds(),
                review.totalInputTokens(),
                review.totalCachedInputTokens(),
                review.totalOutputTokens(),
                review.getSessions().size(),
                review.getReviewers()));
    }
    private void logTriage(FindingTriage triage) {
        for (FindingVerdict verdict : triage.getVerdicts()) {
            getLog().info(String.format("[%s%s] %s %s:%d %s",
                    verdict.getVerdict(),
                    Objects.nonNull(verdict.getSeverity()) ? " " + verdict.getSeverity() : StringUtils.EMPTY,
                    verdict.getRule(),
                    verdict.getFile(),
                    verdict.getLine(),
                    verdict.getReason()));
        }
    }
    /**
     * A submission written for another commit would have the labelling turn label units the review never read, and the
     * report compare labels of two different commits; nothing in either would show it.
     */
    private void requireReviewedCommit(AnalysisSubmissionModel read) throws IOException {
        ObjectId resolved;
        try (Repository repository = new FileRepositoryBuilder().findGitDir(directory).setMustExist(true).build()) {
            resolved = repository.resolve(commit);
        }
        if (Objects.isNull(resolved)) {
            throw new IOException("unknown commit " + commit + " in " + directory);
        }

        String submitted = Objects.isNull(read.getCommit()) ? null : read.getCommit().getSha();
        if (resolved.name().equals(submitted)) {
            return;
        }
        throw new IOException("the submission " + submission + " is of commit " + submitted + ", not of the reviewed commit " + resolved.name());
    }
    /**
     * Every listed unit with the label the reviewers' own labels give it by name and the one the labelling turn gave it
     * by id, and the counts of both, for comparing the two ways of labelling on one review.
     */
    private LabellingReport compare(AnalysisSubmissionModel analysed, UnitLabelling.Checklist checklist, LocalReviewer.ReviewOutcome result) {
        Map<CodeUnitModel, LlmScoringResponse.CodeBlockCategoryView> byName = LocalReviewModels.matchBlockCategories(analysed, result.getReview(), DefaultLanguageProcessors.reviewLanguages()).getLeft();
        Map<String, UnitLabels.UnitLabel> byId = Maps.newHashMap();
        result.getLabels().ifPresent(found -> found.getLabels().stream()
                .filter(label -> Objects.nonNull(label.getCategory()))
                .forEach(label -> byId.put(StringUtils.strip(label.getId()), label)));

        List<LabelledUnit> units = Lists.newArrayList();
        int named = 0;
        int identified = 0;
        int agreed = 0;
        for (ReviewUnit unit : checklist.getListed()) {
            LlmScoringResponse.CodeBlockCategoryView reviewers = byName.get(checklist.getUnits().get(unit.getId()));
            UnitLabels.UnitLabel turn = byId.get(unit.getId());
            LlmScoringResponse.CodeBlockCategory reviewersCategory = Objects.nonNull(reviewers) ? reviewers.getCategory() : null;
            LlmScoringResponse.CodeBlockCategory turnCategory = Objects.nonNull(turn) ? turn.getCategory() : null;
            named += Objects.nonNull(reviewersCategory) ? 1 : 0;
            identified += Objects.nonNull(turnCategory) ? 1 : 0;
            agreed += BooleanUtils.and(new boolean[] { Objects.nonNull(reviewersCategory), reviewersCategory == turnCategory }) ? 1 : 0;
            units.add(new LabelledUnit(unit.getId(), unit.getPath(), unit.getName(), unit.getOperation(), reviewersCategory, turnCategory));
        }
        getLog().info(String.format("labelling of %d code units: the reviewers' labels name %d, the labelling turn labels %d, and they agree on %d of the units both labelled",
                units.size(), named, identified, agreed));
        return new LabellingReport(units.size(), named, identified, agreed, result.getLabels().map(UnitLabels::getUsage), units);
    }
    private void writeReport(LocalReview review, Optional<FindingTriage> triage, Optional<LabellingReport> labelling) throws IOException {
        Map<String, Object> report = Maps.newLinkedHashMap();
        report.put("commit", review.getCommit());
        report.put("tookSeconds", review.getTook().toSeconds());
        report.put("bugs", review.getBugs());
        report.put("sessions", review.getSessions());
        report.put("reviewers", review.getReviewers());
        report.put("answer", review.getAnswer());
        if (Objects.nonNull(review.getAssessment())) {
            report.put("assessment", review.getAssessment());
        }
        triage.ifPresent(found -> report.put("triage", found));
        labelling.ifPresent(found -> report.put("labelling", found));

        FileUtils.forceMkdirParent(output);
        JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValue(output, report);
        getLog().info("review report written to " + output);
    }

    @Value
    private static class LabellingReport {
        int units;
        int reviewersLabelled;
        int turnLabelled;
        int agreed;
        Optional<SessionUsage> turnUsage;
        List<LabelledUnit> labels;
    }

    @Value
    private static class LabelledUnit {
        String id;
        String path;
        String name;
        String operation;
        LlmScoringResponse.CodeBlockCategory reviewers;
        LlmScoringResponse.CodeBlockCategory turn;
    }
}
