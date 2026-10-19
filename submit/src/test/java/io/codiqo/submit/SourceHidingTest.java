package io.codiqo.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.event.Level;
import org.snakeyaml.engine.v2.api.LoadSettings;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;

import io.codiqo.api.RunArgs;
import io.codiqo.api.diff.CommentSyntax;
import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.api.diff.JavaInvocationCounter;
import io.codiqo.api.logging.Log;
import io.codiqo.client.ApiClient;
import io.codiqo.client.model.AnalysisExcludeModel;
import io.codiqo.client.model.AnalysisSubmissionModel;
import io.codiqo.client.model.BugModel;
import io.codiqo.client.model.BugsModel;
import io.codiqo.client.model.CallerModel;
import io.codiqo.client.model.CloneLocationModel;
import io.codiqo.client.model.CloneModel;
import io.codiqo.client.model.CodeUnitModel;
import io.codiqo.client.model.CommitModel;
import io.codiqo.client.model.CoverageModel;
import io.codiqo.client.model.DiagnosticModel;
import io.codiqo.client.model.DimensionScoreModel;
import io.codiqo.client.model.DuplicationReportModel;
import io.codiqo.client.model.FileChangeModel;
import io.codiqo.client.model.FullProjectCoverageModel;
import io.codiqo.client.model.HotspotClassModel;
import io.codiqo.client.model.HotspotFindingKind;
import io.codiqo.client.model.HotspotFindingModel;
import io.codiqo.client.model.HotspotSnapshotModel;
import io.codiqo.client.model.HotspotTypeKind;
import io.codiqo.client.model.InvocationModel;
import io.codiqo.client.model.JavaInfoModel;
import io.codiqo.client.model.LineCoverageModel;
import io.codiqo.client.model.LocalAssessmentModel;
import io.codiqo.client.model.LocalReviewModel;
import io.codiqo.client.model.LocationModel;
import io.codiqo.client.model.MetricsModel;
import io.codiqo.client.model.ModuleModel;
import io.codiqo.client.model.ProjectModel;
import io.codiqo.client.model.RiskDimensionsModel;
import io.codiqo.client.model.SymbolKindModel;
import io.codiqo.llm.MovedLineDetector;
import io.codiqo.llm.PromptBuilder.PromptContext;
import io.codiqo.llm.SubmissionToRequestMapper;
import io.codiqo.llm.client.LocalAssessmentScorer;
import io.codiqo.llm.client.ScoringClient;
import io.codiqo.llm.schema.LlmScoringResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

class SourceHidingTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef-test-key";
    private static final String RETRY = "src/main/java/com/acme/billing/RetryPolicy.java";
    private static final String CLIENT = "src/main/java/com/acme/billing/PaymentClient.java";
    private static final String TEST = "src/test/java/com/acme/billing/RetryPolicyTest.java";
    private static final String POM = "pom.xml";

    private static final String RETRY_DIFF = """
            diff --git a/src/main/java/com/acme/billing/RetryPolicy.java b/src/main/java/com/acme/billing/RetryPolicy.java
            index 1111111..2222222 100644
            --- a/src/main/java/com/acme/billing/RetryPolicy.java
            +++ b/src/main/java/com/acme/billing/RetryPolicy.java
            @@ -1,12 +1,16 @@ package com.acme.billing;
             package com.acme.billing;
            \s
            -import java.util.List;
             import java.time.Duration;
            \s
             public class RetryPolicy {
            -    private final List<String> codes;
            +    // підтримка повторних спроб для платежів
            +    private final int maxAttempts;
            \s
                 public Duration computeBackoff(int attempt) {
            -        return Duration.ofMillis(100L * attempt);
            +        if (attempt > maxAttempts) {
            +            throw new IllegalStateException("retry(" + attempt + ") exceeded");
            +        }
            +        long delay = Math.min(30_000L, 100L * (1L << attempt));
            +        return Duration.ofMillis(delay);
                 }
             }
            """;
    private static final String CLIENT_DIFF = """
            diff --git a/src/main/java/com/acme/billing/PaymentClient.java b/src/main/java/com/acme/billing/PaymentClient.java
            index 3333333..4444444 100644
            --- a/src/main/java/com/acme/billing/PaymentClient.java
            +++ b/src/main/java/com/acme/billing/PaymentClient.java
            @@ -5,6 +5,10 @@ public class PaymentClient {
                 public PaymentClient(RetryPolicy policy) {
                     this.policy = policy;
                 }
            +
            +    private Duration legacyBackoff(int attempt) {
            +        return Duration.ofMillis(100L * attempt);
            +    }
            \s
                 public void charge(String account) {
                     log.info("charging {}", account);
            """;
    private static final String POM_DIFF = """
            diff --git a/pom.xml b/pom.xml
            index 5555555..6666666 100644
            --- a/pom.xml
            +++ b/pom.xml
            @@ -10,7 +10,7 @@
                 <dependencies>
                     <dependency>
                         <groupId>com.acme</groupId>
            -            <version>1.2.0</version>
            +            <version>1.3.0</version>
                     </dependency>
                 </dependencies>
                 <!-- billing -->
            """;
    private static final String TEST_DIFF = """
            diff --git a/src/test/java/com/acme/billing/RetryPolicyTest.java b/src/test/java/com/acme/billing/RetryPolicyTest.java
            new file mode 100644
            index 0000000..7777777
            --- /dev/null
            +++ b/src/test/java/com/acme/billing/RetryPolicyTest.java
            @@ -0,0 +1,9 @@
            +package com.acme.billing;
            +
            +class RetryPolicyTest {
            +    @Test
            +    void backsOff() {
            +        // "quoted" don't
            +        assertEquals(200, new RetryPolicy(3).computeBackoff(1).toMillis());
            +    }
            +}
            """;

    /** every piece of the commit's source that must not leave the build machine */
    private static final List<String> SOURCE = List.of(
            "acme", "billing", "RetryPolicy", "PaymentClient", "computeBackoff", "legacyBackoff", "maxAttempts", "ofMillis",
            "Duration", "attempt", "повторних", "charging", "IllegalStateException", "exceeded", "backsOff",
            "Avoid using literals", "loses the last attempt", "retries forever", "SRP", "the policy now owns the backoff",
            "max attempts", "agents.md");

    private final RunArgs args = new RunArgs();
    private final ObjectMapper json = new ApiClient().getObjectMapper();

    @Test
    void theHiddenSubmissionScoresAsThePlainOne() throws Exception {
        ScoringClient.ScoringResult plain = score(submission());
        AnalysisSubmissionModel hidden = submission();
        SourceHiding.of(true, Optional.of(KEY)).hide(hidden);
        ScoringClient.ScoringResult scored = score(hidden);

        assertTrue(CollectionUtils.isNotEmpty(plain.getResponse().getEffortBreakdown().getDiffClassification().getMovedPairs()),
                "the commit moves a line between files, so the comparison covers moved-line matching");
        assertEquals(plain.getLocalAssessmentScore(), scored.getLocalAssessmentScore());
        assertEquals(numbers(plain), numbers(scored));
    }
    @Test
    void theHiddenSubmissionCarriesNoSource() {
        AnalysisSubmissionModel submission = submission();
        SourceHiding.of(true, Optional.of(KEY)).hide(submission);
        String sent = json.writeValueAsString(submission);

        for (String source : SOURCE) {
            assertFalse(Strings.CI.contains(sent, source), source + " leaked: " + sent);
        }
        assertTrue(submission.getSourceHidden());
        FileChangeModel retry = submission.getFiles().get(0);
        assertNull(retry.getContentAfter());
        assertNull(retry.getCodeUnits().get(0).getBody());
        assertTrue(retry.getPath().endsWith(".java"), "the extension decides the language");
        assertEquals(POM, submission.getFiles().get(2).getPath(), "a config file recognised by name keeps its name");
        assertTrue(submission.getFiles().get(3).getDiff().contains("--- /dev/null"), "an added file's old side stays /dev/null");
        assertTrue(retry.getDiff().contains("+++ b/" + retry.getPath()), "the headers name the hashed path");
        assertEquals(1, submission.getLocalReview().getAssessment().getSolidViolations().size(), "the violations are counted");
    }
    @Test
    void anIdentifierHashesAlikeWhereverItOccursAndOnlyUnderOneKey() {
        AnalysisSubmissionModel submission = submission();
        SourceHiding.of(true, Optional.of(KEY)).hide(submission);
        FileChangeModel retry = submission.getFiles().get(0);
        CloneLocationModel clone = submission.getDuplication().getClones().get(0).getLocations().get(0);
        BugModel bug = submission.getLocalReview().getBugs().getMajor().get(0);

        assertEquals(retry.getPath(), clone.getPath());
        assertEquals(retry.getPath(), bug.getFile());
        assertEquals(retry.getPath(), retry.getCodeUnits().get(0).getDiagnostics().get(0).getFilePath());
        assertEquals(retry.getCodeUnits().get(0).getSignature(), clone.getCodeUnitSignature());

        SourceHiding other = SourceHiding.of(true, Optional.of(KEY.toUpperCase()));
        assertNotEquals(SourceHiding.of(true, Optional.of(KEY)).path(RETRY), other.path(RETRY));
        assertEquals(SourceHiding.of(true, Optional.of(KEY)).path(RETRY), SourceHiding.of(true, Optional.of(KEY)).path(RETRY));
    }
    @Test
    void aPathKeepsItsSeparatorsAndTheNamesTheScorerReads() {
        SourceHiding hiding = SourceHiding.of(true, Optional.of(KEY));

        assertEquals("/dev/null", hiding.path("/dev/null"));
        assertTrue(hiding.path("module/build.gradle.kts").endsWith("/build.gradle.kts"));
        assertTrue(hiding.path("gradle/libs.versions.toml").endsWith("/libs.versions.toml"));
        assertTrue(hiding.path("api/src/main/proto/order.proto").endsWith(".proto"));
        assertEquals(4, StringUtils.countMatches(hiding.path("a/b/c/d/E.java"), '/'));
        assertTrue(hiding.path("a/b/E.java").startsWith(StringUtils.substringBeforeLast(hiding.path("a/b/F.java"), "/")), "siblings share their directory");
    }
    @Test
    void theOtherRequestsLoseTheirSourceToo() {
        SourceHiding hiding = SourceHiding.of(true, Optional.of(KEY));

        AnalysisExcludeModel exclusion = new AnalysisExcludeModel().reason("build failure").detail("RetryPolicy.java:[10,5] cannot find symbol maxAttempts")
                .files(Lists.newArrayList(List.of(file(RETRY, RETRY_DIFF, FileChangeModel.ChangeTypeEnum.MODIFY))));
        hiding.hide(exclusion);
        assertNull(exclusion.getDetail());
        assertEquals("build failure", exclusion.getReason());
        assertTrue(exclusion.getSourceHidden());
        assertFalse(json.writeValueAsString(exclusion).contains("RetryPolicy"));

        HotspotSnapshotModel snapshot = new HotspotSnapshotModel().classes(Lists.newArrayList(List.of(new HotspotClassModel()
                .className("com.acme.billing.RetryPolicy").filePath(RETRY).kind(HotspotTypeKind.propertyClass)
                .findings(Lists.newArrayList(List.of(new HotspotFindingModel().kind(HotspotFindingKind.PMD).member("computeBackoff(int)")
                        .message("Avoid using literals").rule("AvoidLiteralsInIfCondition").otherFilePath(CLIENT)))))));
        hiding.hide(snapshot);
        assertTrue(snapshot.getSourceHidden());
        assertNull(snapshot.getClasses().get(0).getFindings().get(0).getMessage());
        assertEquals("AvoidLiteralsInIfCondition", snapshot.getClasses().get(0).getFindings().get(0).getRule());
        assertFalse(json.writeValueAsString(snapshot).contains("acme"));
    }
    @Test
    void aReasonKeepsItsDescriptionWithoutNames() {
        assertEquals("no analyzable diff, changed files: [******.sql, ******.md]",
                SourceHiding.reason("no analyzable diff, changed files: [db/billing_payments_v2.sql, docs/acme-refund-runbook.md]"));
        assertEquals("[ERROR] ******.java:[10,5] cannot find symbol",
                SourceHiding.reason("[ERROR] /home/runner/work/x/src/main/java/com/acme/billing/RetryPolicy.java:[10,5] cannot find symbol"));
        assertEquals("******: the agent did not attach to version 1.2.0", SourceHiding.reason("com.acme.billing.RetryPolicy: the agent did not attach to version 1.2.0"));
        assertEquals("revert commit (no LLM scoring performed)", SourceHiding.reason("revert commit (no LLM scoring performed)"));
        assertEquals("changed files: [******.java, ******.md]", SourceHiding.reason("changed files: [Secret Invoice.java, Q3 refund plan.md]"));
        assertEquals("cannot read: ******.pdf", SourceHiding.reason("cannot read: Q3 Secret Plan.pdf"));
        assertEquals("******.pdf", SourceHiding.reason("cannot read Q3 Secret Plan.pdf"), "with no delimiter the description goes with the name");
        assertEquals("[ERROR] ****** ****** ******.java:[3,1] cannot find symbol", SourceHiding.reason("[ERROR] /home/x/My Docs/Secret Invoice.java:[3,1] cannot find symbol"));
    }
    /**
     * Lines the fixture's commit does not hold, each checked the way the scorer reads a diff line: its tokens, its call
     * shapes and whether it is a comment must come out of the hidden line exactly as out of the plain one.
     */
    @Test
    void edgeLinesReadAsTheirPlainSelves() {
        List<String> lines = List.of(
                "/* 注释 */\u3000",
                "String group = matcher.replaceAll(\"$1(\");",
                "Object $1 = $1(x);",
                "if (ready) { $(selector).hide(); }",
                "long big = 0x1F_FFL + 30_000L;",
                "char quote = '\\'', tab = '\\t';",
                "// значение — retry(count) don't",
                "import java.util.List;",
                "String text = \"\"\"\";",
                "String arrow = \"<=>\", sep = \"/\", blank = \" \";",
                "/* \"*/\" */ x();");
        SourceHiding hiding = SourceHiding.of(true, Optional.of(KEY));
        StringBuilder plain = new StringBuilder("diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -0,0 +1," + lines.size() + " @@\n");
        lines.forEach(line -> plain.append('+').append(line).append('\n'));
        String[] hidden = StringUtils.splitPreserveAllTokens(hiding.diff(plain.toString(), "A.java", "A.java"), '\n');

        assertFalse(String.join(StringUtils.LF, hidden).contains("<=>"), "a literal without a token is hashed too");
        for (int i = 0; i < lines.size(); i++) {
            String original = lines.get(i);
            String masked = hidden[4 + i].substring(1);
            assertEquals(JavaInvocationCounter.countInLine(original), JavaInvocationCounter.countInLine(masked), original);
            assertEquals(CommentSyntax.C_STYLE.isCommentLine(original.trim()), CommentSyntax.C_STYLE.isCommentLine(masked.trim()), original);
            assertEquals(tokens(original).size(), tokens(masked).size(), original);
            assertEquals(original.trim().startsWith(IneffectiveLineFilter.IMPORT_PREFIX), masked.trim().startsWith(IneffectiveLineFilter.IMPORT_PREFIX), original);
        }
    }
    @Test
    void aModuleDirectoryPrefixesItsFilesAndGitsBlobIdsAreHashed() {
        SourceHiding hiding = SourceHiding.of(true, Optional.of(KEY));
        AnalysisSubmissionModel submission = submission();
        submission.getProject().setModules(Lists.newArrayList(List.of(new ModuleModel().id("payments").baseDirectory("bundles/com.acme.payments"))));
        submission.getFiles().get(0).setPath("bundles/com.acme.payments/src/RetryPolicy.java");
        hiding.hide(submission);

        String module = submission.getProject().getModules().get(0).getBaseDirectory();
        assertFalse(module.contains("payments"));
        assertTrue(submission.getFiles().get(0).getPath().startsWith(module + "/"));
        String diff = submission.getFiles().get(0).getDiff();
        assertFalse(diff.contains("1111111") || diff.contains("2222222"), diff);
        assertTrue(submission.getFiles().get(3).getDiff().contains("index 0000000.."), "a blob that does not exist stays all zeros");
    }
    @Test
    void thePlainPolicyChangesNothing() {
        AnalysisSubmissionModel submission = submission();
        String before = json.writeValueAsString(submission);
        SourceHiding.NONE.hide(submission);

        assertEquals(before, json.writeValueAsString(submission));
        assertFalse(SourceHiding.of(false, Optional.empty()).isHidden());
    }
    @Test
    void aHiddenRunNeedsALongKey() {
        assertThrows(IllegalArgumentException.class, () -> SourceHiding.of(true, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> SourceHiding.of(true, Optional.of("short")));
    }
    /**
     * Opt-in against real commits: point {@code codiqo.hiddenSource.submissions} at a directory of the
     * {@code codiqo-submission-<sha>.json|yaml} files a run with {@code -Dcodiqo.dumpAnalysis=true} writes, and every
     * one is scored plain and hidden. Real diffs carry what no fixture thinks of (generics, text blocks, escapes, CRLF).
     */
    @Test
    @EnabledIfSystemProperty(named = "codiqo.hiddenSource.submissions", matches = ".+")
    void realSubmissionsScoreAsTheirPlainSelves() throws Exception {
        File[] files = new File(System.getProperty("codiqo.hiddenSource.submissions")).listFiles((dir, name) -> name.startsWith("codiqo-submission-"));
        assertTrue(Objects.nonNull(files) && files.length > 0, "no codiqo-submission-* file found");
        for (File file : files) {
            ScoringClient.ScoringResult plain = score(read(file));
            AnalysisSubmissionModel hidden = read(file);
            SourceHiding.of(true, Optional.of(KEY)).hide(hidden);
            ScoringClient.ScoringResult scored = score(hidden);

            assertEquals(plain.getLocalAssessmentScore(), scored.getLocalAssessmentScore(), file.getName());
            assertEquals(numbers(plain), numbers(scored), file.getName());
        }
    }
    /** the review's judgment as the server reads it back: the units' labels, the dimensions, the findings and the bugs */
    private ScoringClient.ScoringResult score(AnalysisSubmissionModel submission) {
        LlmScoringResponse assessment = new LlmScoringResponse();
        List<LlmScoringResponse.CodeBlockCategoryView> labels = Lists.newArrayList();
        for (FileChangeModel file : submission.getFiles()) {
            for (CodeUnitModel unit : CollectionUtils.emptyIfNull(file.getCodeUnits())) {
                if (Objects.nonNull(unit.getCategory())) {
                    labels.add(LlmScoringResponse.CodeBlockCategoryView.builder()
                            .file(file.getPath())
                            .signature(unit.getSignature())
                            .category(LlmScoringResponse.CodeBlockCategory.valueOf(unit.getCategory().getValue()))
                            .build());
                }
            }
        }
        assessment.setBlockCategories(labels);

        LlmScoringResponse.Bugs bugs = LlmScoringResponse.Bugs.builder().build();
        if (Objects.nonNull(submission.getLocalReview())) {
            LocalAssessmentModel review = submission.getLocalReview().getAssessment();
            if (Objects.nonNull(review.getQualityDimensions())) {
                DimensionScoreModel architecture = review.getQualityDimensions().getArchitectureImpact();
                assessment.setQualityDimensions(LlmScoringResponse.QualityDimensions.builder()
                        .architectureImpact(LlmScoringResponse.DimensionScore.builder().score(architecture.getScore()).qualityGateMet(architecture.getQualityGateMet()).build())
                        .build());
            }
            assessment.setQualityMultiplier(LlmScoringResponse.QualityMultiplier.builder()
                    .architectureAnalysis(LlmScoringResponse.ArchitectureAnalysis.builder()
                            .solidViolations(CollectionUtils.emptyIfNull(review.getSolidViolations()).stream().toList())
                            .build())
                    .build());
            for (BugModel bug : CollectionUtils.emptyIfNull(submission.getLocalReview().getBugs().getMajor())) {
                bugs.getMajor().add(LlmScoringResponse.Bug.builder().title(bug.getTitle()).file(bug.getFile()).line(bug.getLine()).build());
            }
        }
        return new LocalAssessmentScorer(args, new QuietLog()).score(new SubmissionToRequestMapper(args).apply(submission),
                PromptContext.builder().args(args).build(), assessment, bugs);
    }
    private AnalysisSubmissionModel read(File file) throws Exception {
        if (file.getName().endsWith(".json")) {
            return json.readValue(file, AnalysisSubmissionModel.class);
        }
        YAMLFactory yaml = YAMLFactory.builder().loadSettings(LoadSettings.builder().setCodePointLimit(Integer.MAX_VALUE).build()).build();
        return YAMLMapper.builder(yaml).build().readValue(file, AnalysisSubmissionModel.class);
    }
    /**
     * Every number and flag of the result, without where it sits: a list sorted by a hashed path comes in another order,
     * and a key or a text is expected to differ, but no count, line, score or multiplier may.
     */
    private static Multiset<String> numbers(ScoringClient.ScoringResult result) {
        Multiset<String> toReturn = HashMultiset.create();
        ObjectMapper mapper = JsonMapper.builder().build();
        collect(mapper.valueToTree(result.getResponse()), "response", toReturn);
        collect(mapper.valueToTree(result.getPreComputedScores()), "preComputed", toReturn);
        return toReturn;
    }
    private static List<String> tokens(String line) {
        List<String> toReturn = Lists.newArrayList();
        Matcher matcher = MovedLineDetector.WORD_TOKEN.matcher(line);
        while (matcher.find()) {
            toReturn.add(matcher.group());
        }
        return toReturn;
    }
    private static void collect(JsonNode node, String name, Multiset<String> target) {
        if (node.isNumber() || node.isBoolean()) {
            target.add(name + "=" + node.asString());
        } else if (node.isArray()) {
            node.forEach(item -> collect(item, name, target));
        } else if (node.isObject()) {
            node.properties().forEach(entry -> collect(entry.getValue(), entry.getKey(), target));
        }
    }
    private static AnalysisSubmissionModel submission() {
        FileChangeModel retry = file(RETRY, RETRY_DIFF, FileChangeModel.ChangeTypeEnum.MODIFY);
        CodeUnitModel backoff = unit("computeBackoff", "com.acme.billing.RetryPolicy.computeBackoff(int)", 9, 15, CodeUnitModel.OperationEnum.MODIFY, CodeUnitModel.CategoryEnum.SUBSTANTIVE);
        backoff.setBody("public Duration computeBackoff(int attempt) { ... }");
        backoff.setCategoryReason("caps the backoff at max attempts");
        backoff.setCallers(Lists.newArrayList(List.of(new CallerModel().name("PaymentClient.charge").kind(SymbolKindModel.METHOD).path(CLIENT).location(location(13, 15))
                .symbol("com.acme.billing.PaymentClient.charge(String)"))));
        backoff.setDiagnostics(Lists.newArrayList(List.of(new DiagnosticModel().tool(DiagnosticModel.ToolEnum.PMD).ruleId("AvoidLiteralsInIfCondition")
                .severity(DiagnosticModel.SeverityEnum.WARNING).message("Avoid using literals such as 30000 in if condition").filePath(RETRY)
                .location(location(13, 13)))));
        backoff.setCoverage(new CoverageModel().coveredLines(5).missedLines(1).linePercent(83.3).lines(Lists.newArrayList(List.of(
                new LineCoverageModel().line(10).status(LineCoverageModel.StatusEnum.COVERED),
                new LineCoverageModel().line(11).status(LineCoverageModel.StatusEnum.MISSED),
                new LineCoverageModel().line(13).status(LineCoverageModel.StatusEnum.COVERED)))));
        backoff.setJavaInfo(new JavaInfoModel().packageName("com.acme.billing").className("RetryPolicy").invocations(Lists.newArrayList(List.of(
                new InvocationModel().owner("java/time/Duration").name("ofMillis").descriptor("(J)Ljava/time/Duration;").location(location(14, 14))))));
        CodeUnitModel field = unit("maxAttempts", "com.acme.billing.RetryPolicy.maxAttempts", 7, 7, CodeUnitModel.OperationEnum.NEW, CodeUnitModel.CategoryEnum.MECHANICAL);
        field.setKind(SymbolKindModel.FIELD);
        retry.setCodeUnits(Lists.newArrayList(List.of(backoff, field)));
        retry.setContentAfter("package com.acme.billing; public class RetryPolicy { ... }");

        FileChangeModel client = file(CLIENT, CLIENT_DIFF, FileChangeModel.ChangeTypeEnum.MODIFY);
        client.setCodeUnits(Lists.newArrayList(List.of(unit("legacyBackoff", "com.acme.billing.PaymentClient.legacyBackoff(int)", 9, 11,
                CodeUnitModel.OperationEnum.NEW, CodeUnitModel.CategoryEnum.ROUTINE))));

        FileChangeModel pom = file(POM, POM_DIFF, FileChangeModel.ChangeTypeEnum.MODIFY);
        pom.setLanguage(null);

        FileChangeModel test = file(TEST, TEST_DIFF, FileChangeModel.ChangeTypeEnum.ADD);
        test.setIsTest(true);
        test.setCodeUnits(Lists.newArrayList(List.of(unit("backsOff", "com.acme.billing.RetryPolicyTest.backsOff()", 5, 8,
                CodeUnitModel.OperationEnum.NEW, CodeUnitModel.CategoryEnum.ROUTINE))));

        CloneModel clone = new CloneModel().tokenCount(40).lineCount(2).isCrossFile(true).locations(Lists.newArrayList(List.of(
                new CloneLocationModel().path(RETRY).location(location(13, 14)).codeUnitSignature("com.acme.billing.RetryPolicy.computeBackoff(int)")
                        .sourceSlice("return Duration.ofMillis(delay);"),
                new CloneLocationModel().path(CLIENT).location(location(9, 10)).codeUnitSignature("com.acme.billing.PaymentClient.legacyBackoff(int)")
                        .sourceSlice("return Duration.ofMillis(100L * attempt);"))));

        RiskDimensionsModel dimensions = new RiskDimensionsModel().architectureImpact(new DimensionScoreModel().score(4).qualityGateMet(true).rationale("the policy now owns the backoff"));
        LocalAssessmentModel assessment = new LocalAssessmentModel().summary("caps the retry backoff at max attempts").qualityDimensions(dimensions)
                .solidViolations(Lists.newArrayList(List.of("SRP: the client also computes a backoff"))).seniorReviewReasons(Lists.newArrayList(List.of("retries forever")));
        BugModel bug = new BugModel().type(BugModel.TypeEnum.LOGIC).title("loses the last attempt").description("an off-by-one in max attempts")._file(RETRY).line(10)
                .suggestedBlockCode("if (attempt >= maxAttempts) {");
        LocalReviewModel review = new LocalReviewModel().assessment(assessment).bugs(new BugsModel().major(Lists.newArrayList(List.of(bug))))
                .reasoning("the coordinator read RetryPolicy and PaymentClient").observations(Lists.newArrayList(List.of("computeBackoff is untested")));

        AnalysisSubmissionModel toReturn = new AnalysisSubmissionModel();
        toReturn.setProject(new ProjectModel().code("org.example:service").name("service"));
        toReturn.setCommit(new CommitModel().sha("0123456789abcdef0123456789abcdef01234567").message("cap the retry backoff").author("Jane")
                .timestamp(OffsetDateTime.parse("2026-10-01T10:00:00Z")).branches(Lists.newArrayList(List.of("main"))));
        toReturn.setFiles(Lists.newArrayList(List.of(retry, client, pom, test)));
        toReturn.setDuplication(new DuplicationReportModel().clones(Lists.newArrayList(List.of(clone))));
        toReturn.setFullProjectCoverage(new FullProjectCoverageModel().linePercentage(61.0).branchPercentage(48.0));
        toReturn.setAgentInstructions("### AGENTS.md\nuse the acme retry helpers");
        toReturn.setLocalReview(review);
        return toReturn;
    }
    private static FileChangeModel file(String path, String diff, FileChangeModel.ChangeTypeEnum changeType) {
        FileChangeModel toReturn = new FileChangeModel();
        toReturn.setPath(path);
        toReturn.setChangeType(changeType);
        toReturn.setLanguage(FileChangeModel.LanguageEnum.JAVA);
        toReturn.setIsTest(false);
        toReturn.setDiff(diff);
        return toReturn;
    }
    private static CodeUnitModel unit(String name, String signature, int startLine, int endLine, CodeUnitModel.OperationEnum operation, CodeUnitModel.CategoryEnum category) {
        MetricsModel metrics = new MetricsModel();
        metrics.setCyclomaticComplexity(3);
        metrics.setCognitiveComplexity(2);
        metrics.setCodeLines(endLine - startLine + 1);
        metrics.setNonCommentCodeLines(endLine - startLine + 1);
        metrics.setNonCommentCodeStatements(endLine - startLine);
        metrics.setDirectInvocationCount(2);
        metrics.setParameterCount(1);

        CodeUnitModel toReturn = new CodeUnitModel();
        toReturn.setName(name);
        toReturn.setSignature(signature);
        toReturn.setKind(SymbolKindModel.METHOD);
        toReturn.setOperation(operation);
        toReturn.setLocation(location(startLine, endLine));
        toReturn.setMetrics(metrics);
        toReturn.setCategory(category);
        toReturn.setCallers(Lists.newArrayList());
        toReturn.setDiagnostics(Lists.newArrayList());
        return toReturn;
    }
    private static LocationModel location(int startLine, int endLine) {
        return new LocationModel().startLine(startLine).endLine(endLine);
    }

    private static final class QuietLog implements Log {
        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public void log(Level level, String message, Object... formatArgs) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
