package io.codiqo.llm.review;

import java.util.EnumSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.thymeleaf.context.Context;

import com.google.common.base.Joiner;

import io.codiqo.api.RunArgs;
import io.codiqo.llm.PromptFences;
import io.codiqo.llm.PromptTemplates;
import io.codiqo.llm.StaticAnalysisLists;
import io.codiqo.llm.client.LlmJson;
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
 * Both agents run with the permissions of OpenCode's built-in {@code explore} agent ({@link #EXPLORE_ALLOWED}), less
 * the git writes and builds that would change the analysis clone ({@link #DENIED_SHELL}).
 */
@UtilityClass
public class OpenCodeReviewConfig {
    public static final String PROVIDER = "codiqo";
    public static final String COORDINATOR = "codiqo-review-coordinator";
    public static final String REVIEWER = "codiqo-module-reviewer";

    private static final String PROVIDER_PACKAGE = "@opencode/ai/providers/openai-compatible";
    /**
     * The permissions of OpenCode 2.0.25's built-in {@code explore} agent, its read-only codebase explorer, read from
     * the binary: everything denied, then the shell (any command), the file tools, directories outside the project and
     * web access allowed. Its {@code ask} on {@code .env} files becomes {@code deny}: an {@code ask}, and any call no
     * rule matches, waits for an answer nobody gives in a headless review. The read-only git list this replaced refused
     * 2,973 of 7,719 shell calls in four days of production reviews, nearly all of them read-only pipes.
     */
    private static final List<Pair<String, String>> EXPLORE_ALLOWED = List.of(
            Pair.of("shell", "*"), Pair.of("read", "*"), Pair.of("grep", "*"), Pair.of("glob", "*"),
            Pair.of("external_directory", "*"), Pair.of("webfetch", "*"), Pair.of("websearch", "*"));
    /**
     * The one addition to the {@code explore} preset: the review runs in the analysis clone while the commit's own build
     * and tests run in it, so a command that moves its refs or checkout, or a second build that rewrites its
     * {@code target/}, would change what is scored. In those 7,719 production calls agents never tried a git write, and
     * the only damaging commands were three builds in the clone. OpenCode checks each simple command of a pipe or chain
     * and reads a trailing {@code " *"} as optional arguments, so each name is matched as a whole word.
     */
    private static final List<String> DENIED_SHELL = List.of(
            "git commit *", "git checkout *", "git switch *", "git reset *", "git restore *", "git stash *",
            "git clean *", "git merge *", "git rebase *", "git cherry-pick *", "git apply *", "git pull *", "git push *",
            "git fetch *", "mvn *", "./mvnw *", "gradle *", "./gradlew *");
    /**
     * An agent stops calling tools this many steps before its cap. Agents lose count of their steps (traces show a reviewer
     * at its 35th step believing it was at about 20), so three steps of slack left some mid tool call when tools were cut;
     * the coordinator, which had the three, reached its cap while still checking its reviewers' reports.
     */
    private static final int STEPS_RESERVED_FOR_ANSWER = 8;

    private static final EnumSet<LlmScoringResponse.Confidence> REPORTED_CONFIDENCE = EnumSet.of(
            LlmScoringResponse.Confidence.HIGH,
            LlmScoringResponse.Confidence.MEDIUM);
    private static final String BUG_SHAPE = bugShape();

    /** @param namingRules how a reviewer writes a unit's signature, one rule per language the commit changes */
    public ObjectNode build(RunArgs args, ReviewEndpoint endpoint, String conventionGuidance, List<String> namingRules, ReviewTags tags) {
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
        settings.put("apiKey", endpoint.getApiKey());
        /**
         * OpenCode's own request timeout is the review timeout, the longest LlmRelay lets a direct end point (a local
         * Ollama daemon evaluating a long prompt) take to start answering, so OpenCode never aborts and resends a call
         * the relay is still waiting on; the Codiqo proxy's shorter first-byte limit is the relay's to enforce. A
         * rate-limited call is not held anywhere: the relay answers its 429 at once with the round's wait, and OpenCode
         * keeps that wait itself. Each turn is bounded by the review timeout anyway.
         */
        settings.put("timeout", args.getReviewTimeout().toMillis());
        ObjectNode models = provider.putObject("models");
        for (String model : List.of(args.getReviewCoordinatorModel(), args.getReviewReviewerModel())) {
            ObjectNode limit = models.putObject(model).putObject("limit");
            limit.put("context", args.getReviewContextWindow());
            limit.put("output", args.getReviewOutputLimit());
        }

        ObjectNode agents = toReturn.putObject("agents");

        ObjectNode reviewer = agents.putObject(REVIEWER);
        reviewer.put("mode", "subagent");
        reviewer.put("description", "Reviews part of a commit with shell and file tools that leave the checkout untouched and returns confirmed defects as JSON");
        reviewer.put("model", PROVIDER + "/" + args.getReviewReviewerModel());
        reviewer.put("steps", args.getReviewReviewerSteps());
        reviewer.put("system", prompt("opencode/module-reviewer", args.getReviewReviewerSteps(), args, conventionGuidance, namingRules, tags));
        addReviewPermissions(reviewer.putArray("permissions"));

        ObjectNode coordinator = agents.putObject(COORDINATOR);
        coordinator.put("mode", "primary");
        coordinator.put("description", "Plans a whole-commit review, runs reviewer sub-agents in parallel and merges their findings");
        coordinator.put("model", PROVIDER + "/" + args.getReviewCoordinatorModel());
        coordinator.put("steps", args.getReviewCoordinatorSteps());
        coordinator.put("system", prompt("opencode/review-coordinator", args.getReviewCoordinatorSteps(), args, conventionGuidance, namingRules, tags));
        ArrayNode coordinatorPermissions = coordinator.putArray("permissions");
        addReviewPermissions(coordinatorPermissions);
        rule(coordinatorPermissions, "subagent", "*", "deny");
        rule(coordinatorPermissions, "subagent", REVIEWER, "allow");

        return toReturn;
    }
    /** OpenCode applies the last matching rule, so the deny-all comes first and the denied commands last */
    private static void addReviewPermissions(ArrayNode permissions) {
        rule(permissions, "*", "*", "deny");
        EXPLORE_ALLOWED.forEach(allowed -> rule(permissions, allowed.getLeft(), allowed.getRight(), "allow"));
        rule(permissions, "read", "*.env", "deny");
        rule(permissions, "read", "*.env.*", "deny");
        rule(permissions, "read", "*.env.example", "allow");
        DENIED_SHELL.forEach(command -> rule(permissions, "shell", command, "deny"));
    }
    private static void rule(ArrayNode permissions, String action, String resource, String effect) {
        ObjectNode rule = permissions.addObject();
        rule.put("action", action);
        rule.put("resource", resource);
        rule.put("effect", effect);
    }
    /** @param findings the findings asked about, whose tools the prompt names */
    public static String triagePrompt(List<StaticFinding> findings) {
        Context ctx = new Context();
        ctx.setVariable("reporters", reporters(findings));
        ctx.setVariable("bugTypes", List.of(LlmScoringResponse.BugType.values()));
        ctx.setVariable("findings", LlmJson.responseMapper().writeValueAsString(findings));
        return PromptTemplates.process("opencode/triage-findings", ctx).strip();
    }
    /** @param units the submission's added and changed units, each under the id the answer names it by */
    public static String labelPrompt(String sha, List<ReviewUnit> units) {
        Context ctx = new Context();
        ctx.setVariable("sha", sha);
        ctx.setVariable("units", units);
        return PromptTemplates.process("opencode/label-units", ctx).strip();
    }
    public static String reviewPrompt(String sha) {
        Context ctx = new Context();
        ctx.setVariable("sha", sha);
        return PromptTemplates.process("opencode/review-commit", ctx).strip();
    }
    /**
     * The subject of the triage prompt's first sentence: "PMD", "PMD and SpotBugs", "PMD, SpotBugs and Checkstyle". A
     * findings file given to the review goal may name a tool without lists, or none at all, so such a tool is named as
     * written and a findings list without any tool reads "Static analysis".
     */
    private static String reporters(List<StaticFinding> findings) {
        List<String> names = findings.stream()
                .map(StaticFinding::getTool)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .map(tool -> StaticAnalysisLists.of(tool).map(StaticAnalysisLists.ToolLists::getDisplayName).orElse(tool))
                .toList();
        if (names.isEmpty()) {
            return "Static analysis";
        }
        if (names.size() == 1) {
            return names.getFirst();
        }
        return Joiner.on(", ").join(names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }
    /**
     * One bug in the shape {@link LlmScoringResponse.Bug} reads, each value describing what goes there. The types are
     * taken from {@link LlmScoringResponse.BugType}, so a type added there is offered to the agents too.
     */
    private static String bugShape() {
        ObjectNode toReturn = JsonMapper.builder().build().createObjectNode();
        toReturn.put("type", Joiner.on('|').join(LlmScoringResponse.BugType.values()));
        toReturn.put("title", "...");
        toReturn.put("description", "what is wrong, the input or path that triggers it, and the lines you checked as path:line");
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
    private static String prompt(String template, int steps, RunArgs args, String conventionGuidance, List<String> namingRules, ReviewTags tags) {
        Context ctx = new Context();
        ctx.setVariable("namingRules", namingRules);
        ctx.setVariable("technicalTags", tags.getTechnical());
        ctx.setVariable("functionalTags", tags.getFunctional());
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
