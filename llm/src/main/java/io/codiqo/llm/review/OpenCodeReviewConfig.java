package io.codiqo.llm.review;

import java.util.EnumSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.thymeleaf.context.Context;

import com.google.common.base.Joiner;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.PromptFences;
import io.codiqo.llm.PromptTemplates;
import io.codiqo.llm.schema.LlmScoringResponse;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OpenCode configuration a local review runs with: one OpenAI-compatible provider and two agents, a coordinator
 * that plans the review and the reviewer sub-agents it starts.
 *
 * <p>
 * Both agents may read files and run read-only git commands only. OpenCode checks every part of a compound shell
 * command against these rules, so {@code git show ... | tail} or a redirect is refused rather than slipping through a
 * {@code git show*} pattern; this was observed on OpenCode 2.0.20, and the agents recover from the refusal on their own.
 */
@UtilityClass
public class OpenCodeReviewConfig {
    public static final String PROVIDER = "codiqo";
    public static final String COORDINATOR = "codiqo-review-coordinator";
    public static final String REVIEWER = "codiqo-module-reviewer";

    private static final String PROVIDER_PACKAGE = "@opencode/ai/providers/openai-compatible";
    private static final List<String> READ_ONLY_GIT = List.of("git show*", "git diff*", "git log*", "git status*", "git ls-files*", "git rev-parse*");
    /**
     * Forms of the allowed commands that are not read-only: {@code git diff*} also matches {@code git difftool}, which
     * runs any program given with {@code -x}; {@code --output} writes a file (a commit under review could have the agent
     * write {@code .git/config}); {@code --no-index} diffs files outside the repository; an external diff or textconv
     * driver runs a configured program. Denied after the allow rules, since the last matching rule wins.
     */
    private static final List<String> UNSAFE_GIT = List.of(
            "git difftool*",
            "git *--output*",
            "git *--no-index*",
            "git *--ext-diff*",
            "git *--textconv*",
            "git *-c *",
            "git *--exec*");
    private static final int STEPS_RESERVED_FOR_ANSWER = 3;

    private static final EnumSet<LlmScoringResponse.Confidence> REPORTED_CONFIDENCE = EnumSet.of(
            LlmScoringResponse.Confidence.HIGH,
            LlmScoringResponse.Confidence.MEDIUM);
    private static final String BUG_SHAPE = bugShape();

    public ObjectNode build(RunArgs args, ReviewEndpoint endpoint, String conventionGuidance) {
        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode toReturn = mapper.createObjectNode();
        toReturn.put("share", "disabled");
        toReturn.put("update", "disable");
        toReturn.put("snapshots", false);
        toReturn.put("model", PROVIDER + "/" + args.getReviewCoordinatorModel());

        ObjectNode provider = toReturn.putObject("providers").putObject(PROVIDER);
        provider.put("name", "Codiqo review");
        provider.put("package", PROVIDER_PACKAGE);
        ObjectNode settings = provider.putObject("settings");
        settings.put("baseURL", endpoint.getBaseUrl());
        if (StringUtils.isNotBlank(endpoint.getApiKey())) {
            settings.put("apiKey", endpoint.getApiKey());
        }
        ObjectNode models = provider.putObject("models");
        for (String model : List.of(args.getReviewCoordinatorModel(), args.getReviewReviewerModel())) {
            ObjectNode limit = models.putObject(model).putObject("limit");
            limit.put("context", args.getReviewContextWindow());
            limit.put("output", args.getReviewOutputLimit());
        }

        ObjectNode agents = toReturn.putObject("agents");

        ObjectNode reviewer = agents.putObject(REVIEWER);
        reviewer.put("mode", "subagent");
        reviewer.put("description", "Reviews part of a commit with read-only git and file tools and returns confirmed defects as JSON");
        reviewer.put("model", PROVIDER + "/" + args.getReviewReviewerModel());
        reviewer.put("steps", args.getReviewReviewerSteps());
        reviewer.put("system", prompt("opencode/module-reviewer", args.getReviewReviewerSteps(), args, conventionGuidance));
        addReadOnlyPermissions(reviewer.putArray("permissions"));

        ObjectNode coordinator = agents.putObject(COORDINATOR);
        coordinator.put("mode", "primary");
        coordinator.put("description", "Plans a whole-commit review, runs reviewer sub-agents in parallel and merges their findings");
        coordinator.put("model", PROVIDER + "/" + args.getReviewCoordinatorModel());
        coordinator.put("steps", args.getReviewCoordinatorSteps());
        coordinator.put("system", prompt("opencode/review-coordinator", args.getReviewCoordinatorSteps(), args, conventionGuidance));
        ArrayNode coordinatorPermissions = coordinator.putArray("permissions");
        addReadOnlyPermissions(coordinatorPermissions);
        rule(coordinatorPermissions, "subagent", "*", "deny");
        rule(coordinatorPermissions, "subagent", REVIEWER, "allow");

        return toReturn;
    }
    /**
     * Deny first, then allow: OpenCode applies the last matching rule, so the leading deny-all is what keeps every tool
     * that is not named here (edits, web fetches, other shell commands) out of reach.
     */
    private static void addReadOnlyPermissions(ArrayNode permissions) {
        rule(permissions, "*", "*", "deny");
        for (String tool : List.of("read", "grep", "glob")) {
            rule(permissions, tool, "*", "allow");
        }
        for (String command : READ_ONLY_GIT) {
            rule(permissions, "shell", command, "allow");
        }
        for (String command : UNSAFE_GIT) {
            rule(permissions, "shell", command, "deny");
        }
        rule(permissions, "read", "*.env", "deny");
        rule(permissions, "read", "*.env.*", "deny");
    }
    private static void rule(ArrayNode permissions, String action, String resource, String effect) {
        ObjectNode rule = permissions.addObject();
        rule.put("action", action);
        rule.put("resource", resource);
        rule.put("effect", effect);
    }
    public static String triagePrompt(String findingsJson) {
        Context ctx = new Context();
        ctx.setVariable("bugTypes", List.of(LlmScoringResponse.BugType.values()));
        ctx.setVariable("findings", findingsJson);
        return PromptTemplates.process("opencode/triage-findings", ctx).strip();
    }
    public static String reviewPrompt(String sha) {
        Context ctx = new Context();
        ctx.setVariable("sha", sha);
        return PromptTemplates.process("opencode/review-commit", ctx).strip();
    }
    public static String repairPrompt(String problem) {
        Context ctx = new Context();
        ctx.setVariable("problem", problem);
        return PromptTemplates.process("opencode/repair-answer", ctx).strip();
    }
    /**
     * One bug in the shape {@link LlmScoringResponse.Bug} reads, each value describing what goes there. The types are
     * taken from {@link LlmScoringResponse.BugType}, so a type added there is offered to the agents too.
     */
    private static String bugShape() {
        ObjectNode toReturn = JsonMapper.builder().build().createObjectNode();
        toReturn.put("type", Joiner.on('|').join(LlmScoringResponse.BugType.values()));
        toReturn.put("title", "...");
        toReturn.put("description", "what is wrong, the input or path that triggers it, and the lines you checked as File.java:line");
        toReturn.put("file", "repository-relative path");
        toReturn.put("line", "new-file line number, as a JSON number");
        toReturn.put("confidence", Joiner.on('|').join(REPORTED_CONFIDENCE));
        toReturn.put("suggestedFix", "one line");
        toReturn.put("suggestedFileFix", "how to fix it");
        return toReturn.toString();
    }
    /**
     * The JSON rules are one shared template for both agents, distilled from the scoring prompt's rules: invalid escapes
     * from code quoted in a description (a regex {@code \s}, a Windows path) are what most often breaks a model's JSON.
     *
     * <p>
     * The project's conventions are fenced so the agent reads them as evidence about intended behaviour rather than
     * as instructions: the developers under review wrote them, and a rule there must never hide or downgrade a defect.
     * The fence is the scoring prompt's own, which {@code ConventionGuidance} strips from every file, so a file cannot
     * close it early; Thymeleaf inserts a value as it is, so a {@code [(...)]} inside one is not evaluated.
     */
    private static String prompt(String template, int steps, RunArgs args, String conventionGuidance) {
        Context ctx = new Context();
        ctx.setVariable("assess", args.isReviewAssess());
        ctx.setVariable("maxTasks", args.getReviewMaxTasks());
        ctx.setVariable("reviewer", REVIEWER);
        ctx.setVariable("steps", steps);
        ctx.setVariable("stopBy", steps - STEPS_RESERVED_FOR_ANSWER);
        ctx.setVariable("bugShape", BUG_SHAPE);
        ctx.setVariable("guidance", conventionGuidance.strip());
        ctx.setVariable("begin", PromptFences.CONVENTIONS_BEGIN);
        ctx.setVariable("end", PromptFences.CONVENTIONS_END);
        return PromptTemplates.process(template, ctx);
    }
}
