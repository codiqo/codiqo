package io.codiqo.llm.review;

import java.util.EnumSet;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
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
 * Both agents may read anything and run any shell command except those that change the repository or the analysis
 * clone, run a build, or stop processes ({@link #DENIED_SHELL}). OpenCode 2.0.25 parses a shell command with tree-sitter
 * and checks each simple command on its own, a redirect included in its text, so a pipe or an {@code &&} chain is
 * allowed when each of its parts is.
 */
@UtilityClass
public class OpenCodeReviewConfig {
    public static final String PROVIDER = "codiqo";
    public static final String COORDINATOR = "codiqo-review-coordinator";
    public static final String REVIEWER = "codiqo-module-reviewer";

    private static final String PROVIDER_PACKAGE = "@opencode/ai/providers/openai-compatible";
    /**
     * Shell commands the agents may not run, everything else being allowed. The review runs in the analysis clone while
     * the commit's own build and tests run in it, so what is denied is what could change the scored code or that run:
     * git commands that move refs, the index or the checkout, file writes, a second build, and process or container
     * control (the tests' Testcontainers databases run in the same Docker). Each pattern is token-exact: OpenCode turns
     * a trailing {@code " *"} into "optionally followed by arguments", so {@code git merge *} does not match
     * {@code git merge-base}. A determined command can still get past a deny list ({@code bash -c}, a redirect into the
     * clone): it is there for the model's mistakes, not against a hostile commit. Production traces of 12 to 15 Oct 2026
     * had 2,973 of 7,719 shell calls refused by the read-only git list this replaced, nearly all of them pipes into
     * {@code head}, {@code ls}, {@code echo}, {@code grep}, {@code find} and {@code git -C}, every one a wasted step.
     */
    private static final List<String> DENIED_SHELL = List.of(
            "git commit *", "git add *", "git rm *", "git mv *", "git reset *", "git restore *", "git checkout *",
            "git switch *", "git stash *", "git clean *", "git merge *", "git rebase *", "git cherry-pick *",
            "git revert *", "git am *", "git apply *", "git pull *", "git push *", "git fetch *", "git config *",
            "git submodule *", "git update-ref *", "git update-index *", "git symbolic-ref *", "git replace *",
            "git gc *", "git prune *", "git repack *", "git maintenance *", "git bisect *", "git sparse-checkout *",
            "git filter-branch *", "git filter-repo *", "git lfs *", "git init *",
            "git remote add *", "git remote remove *", "git remote rm *", "git remote rename *", "git remote set-* *",
            "git remote prune *", "git remote update *",
            "git notes add *", "git notes append *", "git notes copy *", "git notes edit *", "git notes merge *",
            "git notes prune *", "git notes remove *",
            "git reflog expire *", "git reflog delete *",
            "git worktree add *", "git worktree move *", "git worktree remove *", "git worktree prune *",
            "git worktree lock *", "git worktree unlock *", "git worktree repair *",
            "rm *", "rmdir *", "unlink *", "shred *", "mv *", "cp *", "ln *", "touch *", "chmod *", "chown *",
            "truncate *", "dd *", "tee *", "install *", "patch *", "rsync *", "tar *", "unzip *", "jar *",
            "sed -i*", "sed * -i*", "sed -?i*", "sed * -?i*", "sed * --in-place*",
            "perl -i*", "perl * -i*", "perl -pi*", "perl * -pi*",
            "find * -delete*", "find * -exec rm *", "find * -execdir rm *", "xargs rm *", "xargs * rm *",
            "mvn *", "mvnw *", "./mvnw *", "gradle *", "gradlew *", "./gradlew *", "npm *", "npx *", "yarn *",
            "pnpm *", "make *", "ant *", "sbt *", "bazel *",
            "kill *", "pkill *", "killall *", "docker *", "podman *", "sudo *", "su *", "shutdown *", "reboot *",
            "halt *", "poweroff *", "systemctl *", "launchctl *");
    /**
     * Reading forms of the commands {@link #DENIED_SHELL} denies, allowed again after it. Agents listed tags and
     * branches, read a configuration value, asked Maven for its version and the effective POM during production reviews;
     * none of these writes. Of the 2,973 shell calls refused on 12 to 15 Oct 2026, the only ones the deny list still
     * refuses are three builds in the clone ({@code mvn -q -pl server -am compile}, a single test run), which would
     * rewrite {@code target/} under the commit's own test run. A git command given a repository with {@code -C} is denied by the same verbs ({@link #GIT_C_DENIED}).
     */
    private static final List<String> READING_FORMS = List.of(
            "git stash list *", "git stash show *", "git -C * stash list *", "git -C * stash show *",
            "git -C * config --get *", "git -C * config --list *",
            "git config --get *", "git config --get-all *", "git config --get-regexp *", "git config --list *",
            "git config -l *", "git config get *", "git config list *",
            "git submodule status *", "git submodule summary *", "git lfs ls-files *",
            "tar -t*", "tar t*", "tar --list *", "unzip -l *", "unzip -p *", "unzip -Z *", "unzip -v *",
            "jar t*", "jar -t*", "jar --list *",
            "mvn *help:*", "mvn *maven-help-plugin*", "mvn *dependency:tree*", "mvn *dependency:list*",
            "./mvnw *help:*", "./mvnw *dependency:tree*", "mvn -v *", "mvn --version *", "./mvnw -v *", "gradle --version *",
            "./gradlew --version *");
    /**
     * The git verbs of {@link #DENIED_SHELL} again behind {@code -C <dir>}, the form agents use most for the clone.
     * A {@code *} spans the directory and any options, so a word such as "reset" later in a read-only command's
     * arguments is refused too; that costs a retry, which is cheaper than letting the writing form through.
     */
    private static final List<String> GIT_C_DENIED = List.of(
            "git -C * commit *", "git -C * add *", "git -C * rm *", "git -C * mv *", "git -C * reset *",
            "git -C * restore *", "git -C * checkout *", "git -C * switch *", "git -C * clean *", "git -C * merge *",
            "git -C * rebase *", "git -C * cherry-pick *", "git -C * revert *", "git -C * am *", "git -C * apply *",
            "git -C * pull *", "git -C * push *", "git -C * fetch *", "git -C * gc *", "git -C * update-ref *",
            "git -C * stash *", "git -C * config *");
    /**
     * Flags that make {@code git branch} or {@code git tag} delete, move, copy or force a ref. Listing, the form agents
     * use ({@code git branch --contains}, {@code git tag}), stays allowed; each flag is denied as its own token, since a
     * substring such as {@code -c} is also part of {@code --contains}.
     */
    private static final List<String> BRANCH_WRITE_FLAGS = List.of("-d", "-D", "--delete", "-m", "-M", "--move", "-c", "-C",
            "--copy", "-f", "--force", "-u", "--set-upstream-to*", "--unset-upstream", "--edit-description");
    private static final List<String> TAG_WRITE_FLAGS = List.of("-d", "--delete", "-a", "--annotate", "-s", "--sign",
            "-u", "--local-user*", "-f", "--force", "-m", "-F", "--file*");
    private static final int STEPS_RESERVED_FOR_ANSWER = 3;
    /**
     * A reviewer stops calling tools this many steps before its cap. Reviewers lose count of their steps (traces show one
     * at its 35th step believing it was at about 20), so three steps of slack left some mid tool call when tools were cut.
     */
    private static final int REVIEWER_STEPS_RESERVED_FOR_ANSWER = 8;

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
        reviewer.put("system", prompt("opencode/module-reviewer", args.getReviewReviewerSteps(), REVIEWER_STEPS_RESERVED_FOR_ANSWER, args, conventionGuidance, namingRules, tags));
        addReviewPermissions(reviewer.putArray("permissions"));

        ObjectNode coordinator = agents.putObject(COORDINATOR);
        coordinator.put("mode", "primary");
        coordinator.put("description", "Plans a whole-commit review, runs reviewer sub-agents in parallel and merges their findings");
        coordinator.put("model", PROVIDER + "/" + args.getReviewCoordinatorModel());
        coordinator.put("steps", args.getReviewCoordinatorSteps());
        coordinator.put("system", prompt("opencode/review-coordinator", args.getReviewCoordinatorSteps(), STEPS_RESERVED_FOR_ANSWER, args, conventionGuidance, namingRules, tags));
        ArrayNode coordinatorPermissions = coordinator.putArray("permissions");
        addReviewPermissions(coordinatorPermissions);
        rule(coordinatorPermissions, "subagent", "*", "deny");
        rule(coordinatorPermissions, "subagent", REVIEWER, "allow");

        return toReturn;
    }
    /**
     * Deny first, then allow: OpenCode applies the last matching rule, so the leading deny-all keeps every tool that is
     * not named here (edits, web fetches) out of reach. Directories outside the repository are readable: reviewers
     * looked in the local Maven repository for a dependency's sources and were refused 310 times on 12 to 15 Oct 2026.
     */
    private static void addReviewPermissions(ArrayNode permissions) {
        rule(permissions, "*", "*", "deny");
        for (String tool : List.of("read", "grep", "glob", "external_directory", "shell")) {
            rule(permissions, tool, "*", "allow");
        }
        for (String command : DENIED_SHELL) {
            rule(permissions, "shell", command, "deny");
        }
        for (String command : GIT_C_DENIED) {
            rule(permissions, "shell", command, "deny");
        }
        for (String flag : BRANCH_WRITE_FLAGS) {
            rule(permissions, "shell", "git branch " + flag + " *", "deny");
            rule(permissions, "shell", "git branch * " + flag + " *", "deny");
        }
        for (String flag : TAG_WRITE_FLAGS) {
            rule(permissions, "shell", "git tag " + flag + " *", "deny");
            rule(permissions, "shell", "git tag * " + flag + " *", "deny");
        }
        for (String command : READING_FORMS) {
            rule(permissions, "shell", command, "allow");
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
    public static String repairPrompt(String problem) {
        Context ctx = new Context();
        ctx.setVariable("problem", problem);
        return PromptTemplates.process("opencode/repair-answer", ctx).strip();
    }
    public static String finalAnswerPrompt() {
        return PromptTemplates.process("opencode/final-answer", new Context()).strip();
    }
    public static String resumePrompt(String problem) {
        Context ctx = new Context();
        ctx.setVariable("problem", problem);
        return PromptTemplates.process("opencode/resume-turn", ctx).strip();
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
    private static String prompt(String template, int steps, int reserved, RunArgs args, String conventionGuidance, List<String> namingRules, ReviewTags tags) {
        Context ctx = new Context();
        ctx.setVariable("namingRules", namingRules);
        ctx.setVariable("technicalTags", tags.getTechnical());
        ctx.setVariable("functionalTags", tags.getFunctional());
        ctx.setVariable("maxTasks", args.getReviewMaxTasks());
        ctx.setVariable("reviewer", REVIEWER);
        ctx.setVariable("steps", steps);
        ctx.setVariable("stopBy", steps - reserved);
        ctx.setVariable("bugShape", BUG_SHAPE);
        ctx.setVariable("guidance", conventionGuidance.strip());
        ctx.setVariable("begin", PromptFences.CONVENTIONS_BEGIN);
        ctx.setVariable("end", PromptFences.CONVENTIONS_END);
        return PromptTemplates.process(template, ctx);
    }
}
