package io.codiqo.llm.review;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.Range;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.schema.LlmScoringResponse;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools the review's agents answer with, one per answer: the arguments of the call are the answer, and nothing an
 * agent writes as text is read. A text answer had to be found inside whatever the model wrote, and in about 2 of 85
 * reviews the model's last message held only prose ("All checks are done...", "Final answer:") with the JSON left in its
 * reasoning, which failed the review however the prompt asked for the JSON. A tool call's arguments cannot be announced
 * instead of given.
 *
 * <p>
 * Each schema states what its prompt asks for, and OpenCode checks a call's arguments against it before the call
 * reaches {@link ReviewAnswers}: a missing field or a value of the wrong type is refused within the turn, with the path
 * of what is wrong, and the agent calls again. A field the prompt allows to be left out is not required.
 */
public enum AnswerTool {
    REVIEW("submit_review", "Submits your review of your paths. This call is your answer: the review is read from its arguments only, and a reviewer that ends without it fails the whole review."),
    ANSWER("submit_answer", "Submits the merged review and assessment of the whole commit. This call is your answer: it is read from its arguments only, and a review that ends without it fails."),
    LABELS("submit_labels", "Submits one label per listed code unit. This call is your answer: it is read from its arguments only."),
    TRIAGE("submit_triage", "Submits one verdict per listed static-analysis finding. This call is your answer: it is read from its arguments only.");

    /** what a reviewer is asked to report a defect with; LOW is the scoring prompt's, never asked of the review */
    public static final EnumSet<LlmScoringResponse.Confidence> REPORTED_CONFIDENCE = EnumSet.of(LlmScoringResponse.Confidence.HIGH, LlmScoringResponse.Confidence.MEDIUM);
    /** the dimensions the coordinator scores, as its prompt lists them; the scoring response's testingCoverage is not asked for */
    public static final List<String> QUALITY_DIMENSIONS = List.of("architectureImpact", "concurrencyRisk", "integrationSurface", "dataIntegrity",
            "securitySensitivity", "scalabilityImpact", "observability", "resilience", "performance");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String toolName;
    private final String description;

    AnswerTool(String toolName, String description) {
        this.toolName = toolName;
        this.description = description;
    }
    /** the name {@link ReviewAnswers} lists the tool under */
    public String getToolName() {
        return toolName;
    }
    /** the name the agents call the tool by: OpenCode prefixes every tool of an MCP server with the server's name */
    public String getOpenCodeName() {
        return ReviewAnswers.openCodeName(toolName);
    }
    public String getDescription() {
        return description;
    }
    public ObjectNode inputSchema() {
        return switch (this) {
            case REVIEW -> reviewSchema();
            case ANSWER -> answerSchema();
            case LABELS -> labelsSchema();
            case TRIAGE -> triageSchema();
        };
    }
    public static Optional<AnswerTool> byToolName(String toolName) {
        return Stream.of(values()).filter(tool -> tool.toolName.equals(toolName)).findFirst();
    }
    private static ObjectNode reviewSchema() {
        ObjectNode toReturn = object();
        ObjectNode properties = toReturn.putObject("properties");
        addBugLists(properties);

        ObjectNode label = object();
        ObjectNode labelProperties = label.putObject("properties");
        labelProperties.set("file", string());
        labelProperties.set("signature", string());
        labelProperties.set("category", enumOf(Stream.of(LlmScoringResponse.CodeBlockCategory.values()).map(Enum::name).toList()));
        labelProperties.set("reason", string());
        required(label, "file", "signature", "category");
        properties.set("unitLabels", arrayOf(label));

        properties.set("observations", arrayOf(string()));
        properties.set("notChecked", arrayOf(string()));
        required(toReturn, "blocking", "major", "minor", "unitLabels", "observations", "notChecked");
        return toReturn;
    }
    /**
     * The coordinator's answer as its prompt asks for it, the blast radius flat (see LocalReviewer's FlatBlastRadius). Its
     * own unit labels are not asked for: the reviewers' are used.
     */
    private static ObjectNode answerSchema() {
        ObjectNode toReturn = object();
        ObjectNode properties = toReturn.putObject("properties");

        ObjectNode task = object();
        ObjectNode taskProperties = task.putObject("properties");
        ObjectNode paths = arrayOf(string());
        paths.put("minItems", 1);
        taskProperties.set("paths", paths);
        taskProperties.set("findings", integer());
        required(task, "paths", "findings");
        ObjectNode tasks = arrayOf(task);
        tasks.put("minItems", 1);
        properties.set("tasks", tasks);
        addBugLists(properties);

        properties.set("summary", string());
        ObjectNode tags = object();
        ObjectNode tagProperties = tags.putObject("properties");
        tagProperties.set("technical", arrayOf(string()));
        tagProperties.set("functional", arrayOf(string()));
        required(tags, "technical", "functional");
        properties.set("tags", tags);
        properties.set("taskTypes", arrayOf(enumOf(Stream.of(LlmScoringResponse.TaskType.values()).map(type -> type.name().toLowerCase(Locale.ROOT)).toList())));
        properties.set("taskComplexity", scale(RunArgs.COMPLEXITY_SCALE));
        properties.set("taskComplexityRationale", string());
        properties.set("taskComplexityNew", scale(RunArgs.COMPLEXITY_SCALE));
        properties.set("taskComplexityModified", scale(RunArgs.COMPLEXITY_SCALE));

        ObjectNode dimension = object();
        ObjectNode dimensionProperties = dimension.putObject("properties");
        dimensionProperties.set("score", scale(RunArgs.SCORE_SCALE));
        dimensionProperties.set("rationale", string());
        dimensionProperties.set("qualityGateMet", bool());
        required(dimension, "score", "rationale");
        ObjectNode dimensions = object();
        ObjectNode dimensionsProperties = dimensions.putObject("properties");
        QUALITY_DIMENSIONS.forEach(name -> dimensionsProperties.set(name, dimension.deepCopy()));
        dimensions.put("additionalProperties", false);
        properties.set("qualityDimensions", dimensions);

        ObjectNode architecture = object();
        ObjectNode architectureProperties = architecture.putObject("properties");
        architectureProperties.set("solidViolations", arrayOf(string()));
        architectureProperties.set("architectureIssues", arrayOf(string()));
        required(architecture, "solidViolations", "architectureIssues");
        ObjectNode multiplier = object();
        multiplier.putObject("properties").set("architectureAnalysis", architecture);
        required(multiplier, "architectureAnalysis");
        properties.set("qualityMultiplier", multiplier);

        ObjectNode blast = object();
        ObjectNode blastProperties = blast.putObject("properties");
        blastProperties.set("moduleType", enumOf(Stream.of(LlmScoringResponse.ModuleType.values()).map(Enum::name).toList()));
        blastProperties.set("signatureChanges", arrayOf(string()));
        blastProperties.set("hasBreakingChanges", bool());
        blastProperties.set("breakingChangeType", enumOf(Stream.of(LlmScoringResponse.BreakingChangeType.values()).map(Enum::name).toList()));
        blastProperties.set("explanation", string());
        /**
         * The signature fields stay optional, as FlatBlastRadius reads them: a coordinator that wrote breakingChangeType
         * without hasBreakingChanges, while its explanation said no caller breaks, would be pushed by a required flag to
         * write it as true and be charged the breaking-change penalty its own explanation rules out.
         */
        required(blast, "moduleType", "explanation");
        properties.set("blastRadiusAnalysis", blast);

        properties.set("requiresSeniorReview", scale(RunArgs.SCORE_SCALE));
        properties.set("seniorReviewReasons", arrayOf(string()));
        required(toReturn, "tasks", "blocking", "major", "minor", "summary", "tags", "taskTypes", "taskComplexity", "taskComplexityRationale",
                "qualityDimensions", "qualityMultiplier", "blastRadiusAnalysis", "requiresSeniorReview", "seniorReviewReasons");
        return toReturn;
    }
    private static ObjectNode labelsSchema() {
        ObjectNode label = object();
        ObjectNode labelProperties = label.putObject("properties");
        labelProperties.set("id", string());
        labelProperties.set("category", enumOf(Stream.of(LlmScoringResponse.CodeBlockCategory.values()).map(Enum::name).toList()));
        labelProperties.set("reason", string());
        required(label, "id", "category");

        ObjectNode toReturn = object();
        toReturn.putObject("properties").set("labels", arrayOf(label));
        required(toReturn, "labels");
        return toReturn;
    }
    private static ObjectNode triageSchema() {
        ObjectNode verdict = object();
        ObjectNode verdictProperties = verdict.putObject("properties");
        verdictProperties.set("tool", string());
        verdictProperties.set("rule", string());
        verdictProperties.set("file", string());
        verdictProperties.set("line", integer());
        verdictProperties.set("verdict", enumOf(lowercase(FindingVerdict.Verdict.values())));
        verdictProperties.set("severity", enumOf(lowercase(FindingVerdict.Severity.values())));
        verdictProperties.set("type", enumOf(Stream.of(LlmScoringResponse.BugType.values()).map(Enum::name).toList()));
        verdictProperties.set("reason", string());
        verdictProperties.set("suggestedFix", string());
        /** a findings file given to the review goal may name no tool or rule, and a verdict copies the finding as it is */
        required(verdict, "file", "line", "verdict", "reason");

        ObjectNode toReturn = object();
        toReturn.putObject("properties").set("verdicts", arrayOf(verdict));
        required(toReturn, "verdicts");
        return toReturn;
    }
    /** one bug as the shared bug shape describes it, under each of the three severities */
    private static void addBugLists(ObjectNode properties) {
        ObjectNode bug = object();
        ObjectNode bugProperties = bug.putObject("properties");
        bugProperties.set("type", enumOf(Stream.of(LlmScoringResponse.BugType.values()).map(Enum::name).toList()));
        bugProperties.set("title", string());
        bugProperties.set("description", string());
        bugProperties.set("file", string());
        bugProperties.set("line", integer());
        bugProperties.set("confidence", enumOf(REPORTED_CONFIDENCE.stream().map(Enum::name).toList()));
        bugProperties.set("suggestedFix", string());
        bugProperties.set("suggestedFileFix", string());
        required(bug, "type", "title", "description", "file", "line", "confidence");

        for (String severity : List.of("blocking", "major", "minor")) {
            properties.set(severity, arrayOf(bug.deepCopy()));
        }
    }
    private static ObjectNode object() {
        return MAPPER.createObjectNode().put("type", "object");
    }
    private static ObjectNode string() {
        return MAPPER.createObjectNode().put("type", "string");
    }
    private static ObjectNode integer() {
        return MAPPER.createObjectNode().put("type", "integer");
    }
    private static ObjectNode bool() {
        return MAPPER.createObjectNode().put("type", "boolean");
    }
    private static ObjectNode scale(Range<Integer> scale) {
        return integer().put("minimum", scale.getMinimum()).put("maximum", scale.getMaximum());
    }
    private static ObjectNode arrayOf(ObjectNode items) {
        ObjectNode toReturn = MAPPER.createObjectNode().put("type", "array");
        toReturn.set("items", items);
        return toReturn;
    }
    private static ObjectNode enumOf(List<String> values) {
        ObjectNode toReturn = string();
        ArrayNode allowed = toReturn.putArray("enum");
        values.forEach(allowed::add);
        return toReturn;
    }
    private static void required(ObjectNode schema, String... fields) {
        ArrayNode toReturn = schema.putArray("required");
        for (String field : fields) {
            toReturn.add(field);
        }
    }
    private static List<String> lowercase(Enum<?>[] values) {
        return Stream.of(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
    }
}
