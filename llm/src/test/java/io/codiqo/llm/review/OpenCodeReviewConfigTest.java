package io.codiqo.llm.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.codiqo.llm.client.LlmJson;
import io.codiqo.api.RunArgs;
import io.codiqo.llm.GoldenText;
import io.codiqo.llm.PromptFences;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class OpenCodeReviewConfigTest {
    private static final List<String> NAMING_RULES = List.of("For a file of the test language, the signature is Container.member(params).");

    @Test
    void agentsUseTheConfiguredModelsAndProvider() throws Exception {
        RunArgs args = new RunArgs();
        args.setReviewCoordinatorModel("coordinator-model");
        args.setReviewReviewerModel("reviewer-model");

        ObjectNode config = OpenCodeReviewConfig.build(args, endpoint("http://127.0.0.1:9/v1", null), StringUtils.EMPTY, NAMING_RULES);

        JsonNode provider = config.path("providers").path(OpenCodeReviewConfig.PROVIDER);
        assertEquals("http://127.0.0.1:9/v1", provider.path("settings").path("baseURL").asString());
        assertTrue(provider.path("models").has("coordinator-model"));
        assertTrue(provider.path("models").has("reviewer-model"));

        JsonNode agents = config.path("agents");
        assertEquals("codiqo/coordinator-model", agents.path(OpenCodeReviewConfig.COORDINATOR).path("model").asString());
        assertEquals("primary", agents.path(OpenCodeReviewConfig.COORDINATOR).path("mode").asString());
        assertEquals("codiqo/reviewer-model", agents.path(OpenCodeReviewConfig.REVIEWER).path("model").asString());
        assertEquals("subagent", agents.path(OpenCodeReviewConfig.REVIEWER).path("mode").asString());
    }
    @Test
    void aKeyIsGivenToTheProviderOnlyWhenThereIsOne() throws Exception {
        JsonNode withKey = OpenCodeReviewConfig.build(new RunArgs(), endpoint(RunArgs.DEFAULT_LLM_PROXY_URL, "relay-secret"), StringUtils.EMPTY, NAMING_RULES)
                .path("providers").path(OpenCodeReviewConfig.PROVIDER).path("settings");
        JsonNode withoutKey = OpenCodeReviewConfig.build(new RunArgs(), endpoint(RunArgs.DEFAULT_LOCAL_LLM_URL, null), StringUtils.EMPTY, NAMING_RULES)
                .path("providers").path(OpenCodeReviewConfig.PROVIDER).path("settings");

        assertEquals("relay-secret", withKey.path("apiKey").asString());
        assertFalse(withoutKey.has("apiKey"), "a local daemon takes no key");
    }
    /** the member's credential goes to the Codiqo deployment it was issued for and nowhere else */
    @Test
    void onlyTheCodiqoDeploymentIsSentTheCredential() {
        String resource = RunArgs.DEFAULT_RESOURCE_URL;

        assertTrue(endpoint(new RunArgs().getReviewBaseUrl(), null).isProxiedBy(resource), "the default is the backend's proxy");
        assertTrue(endpoint("https://MCP.codiqo.io:443/v1", null).isProxiedBy(resource));
        assertTrue(endpoint("http://localhost:8080/v1", null).isProxiedBy("http://localhost:8080/mcp"),
                "a local backend is the deployment its own login names");

        assertFalse(endpoint(RunArgs.DEFAULT_LOCAL_LLM_URL, null).isProxiedBy(resource));
        assertFalse(endpoint("http://192.168.1.20:11434/v1", null).isProxiedBy(resource), "an Ollama on the LAN");
        assertFalse(endpoint("https://llm.example.test/v1", null).isProxiedBy(resource), "a third-party provider");
        assertFalse(endpoint("http://mcp.codiqo.io/v1", null).isProxiedBy(resource), "never in clear text");
    }
    @Test
    void promptsCarryTheStepBudgetAndTaskLimit() throws Exception {
        RunArgs args = new RunArgs();
        args.setReviewCoordinatorSteps(30);
        args.setReviewReviewerSteps(12);
        args.setReviewMaxTasks(5);

        JsonNode agents = build(args, StringUtils.EMPTY);
        String coordinator = agents.path(OpenCodeReviewConfig.COORDINATOR).path("system").asString();
        String reviewer = agents.path(OpenCodeReviewConfig.REVIEWER).path("system").asString();

        assertTrue(coordinator.contains("AT MOST 5 " + OpenCodeReviewConfig.REVIEWER), coordinator);
        assertTrue(coordinator.contains("at most 30 steps") && coordinator.contains("by step 27"), coordinator);
        assertTrue(reviewer.contains("at most 12 steps") && reviewer.contains("by step 9"), reviewer);
        assertTrue(coordinator.contains("JSON RULES") && reviewer.contains("JSON RULES"), "both agents carry the shared JSON rules");
        assertTrue(reviewer.contains("LAST message is ALWAYS the JSON object"), "a reviewer that ends without the JSON loses its findings");
        assertTrue(coordinator.contains("ONE more subagent"), "an unanswered area is retried once");
        for (String placeholder : List.of("[(", "[#", "[/]", "${")) {
            assertFalse(coordinator.contains(placeholder) || reviewer.contains(placeholder), "unfilled placeholder " + placeholder);
        }
    }
    /** the project's conventions reach every agent as evidence, wrapped so they cannot pass for instructions */
    @Test
    void bothAgentsReadTheProjectConventionsAndNoneWithoutThem() throws Exception {
        JsonNode guided = build(new RunArgs(), "Every entity class ends in Entity.");
        JsonNode unguided = build(new RunArgs(), StringUtils.EMPTY);

        for (String agent : List.of(OpenCodeReviewConfig.COORDINATOR, OpenCodeReviewConfig.REVIEWER)) {
            String system = guided.path(agent).path("system").asString();
            assertTrue(system.contains(PromptFences.CONVENTIONS_BEGIN + "\nEvery entity class ends in Entity.\n" + PromptFences.CONVENTIONS_END), system);
            assertTrue(system.contains("never lowers a finding's severity"), "the conventions are advisory");
            assertFalse(unguided.path(agent).path("system").asString().contains("PROJECT CONVENTIONS"));
        }
    }
    @Test
    void theTriagePromptCarriesTheFindingsTheVerdictsAndTheSeverityRules() throws Exception {
        List<StaticFinding> findings = List.of(new StaticFinding("spotbugs", "NP_NONNULL_PARAM_VIOLATION", "warning", "a/Service.java", 2, "flagged"));
        String prompt = OpenCodeReviewConfig.triagePrompt(findings);

        assertTrue(prompt.endsWith(LlmJson.responseMapper().writeValueAsString(findings)), prompt);
        assertTrue(prompt.contains("SpotBugs reported the findings below"), "the prompt names the tools of the findings it asks about");
        assertTrue(prompt.contains("\"verdict\":\"defect|harmless|false_positive\""), prompt);
        assertTrue(prompt.contains("Severity follows certainty and harm") && prompt.contains("JSON RULES"), "the same rules as the review");
        assertTrue(prompt.contains("SECURITY|"), "bug types are offered from BugType");
        for (String placeholder : List.of("[(", "[#", "[/]", "${")) {
            assertFalse(prompt.contains(placeholder), "unfilled placeholder " + placeholder);
        }
    }
    /** a findings file given to the review goal may name any tool, or none */
    @Test
    void theTriagePromptNamesEveryToolReadably() {
        assertTrue(OpenCodeReviewConfig.triagePrompt(List.of(finding("pmd"), finding("spotbugs"), finding("checkstyle"), finding("pmd")))
                .startsWith("The build has finished. PMD, SpotBugs and checkstyle reported the findings below"));
        assertTrue(OpenCodeReviewConfig.triagePrompt(List.of(finding(null)))
                .startsWith("The build has finished. Static analysis reported the findings below"));
    }
    @Test
    void anAssessingReviewAsksBothAgentsForTheirPiecesAndAPlainOneAsksNeither() throws Exception {
        RunArgs assess = new RunArgs();
        assess.setReviewAssess(true);
        JsonNode assessing = build(assess, StringUtils.EMPTY);
        JsonNode plain = build(new RunArgs(), StringUtils.EMPTY);

        String reviewer = assessing.path(OpenCodeReviewConfig.REVIEWER).path("system").asString();
        String coordinator = assessing.path(OpenCodeReviewConfig.COORDINATOR).path("system").asString();
        assertTrue(reviewer.contains("\"blockCategories\"") && reviewer.contains("\"observations\""), reviewer);
        assertTrue(coordinator.contains("\"qualityDimensions\"") && coordinator.contains("\"taskTypes\""), coordinator);
        assertTrue(reviewer.indexOf("ALSO ASSESS") < reviewer.indexOf("JSON RULES"), "the JSON rules close the prompt");

        for (String agent : List.of(OpenCodeReviewConfig.COORDINATOR, OpenCodeReviewConfig.REVIEWER)) {
            assertFalse(plain.path(agent).path("system").asString().contains("ALSO ASSESS"), agent);
        }
    }
    @Test
    void agentsMayOnlyReadAndRunReadOnlyGit() throws Exception {
        JsonNode agents = build(new RunArgs(), StringUtils.EMPTY);

        for (String agent : List.of(OpenCodeReviewConfig.COORDINATOR, OpenCodeReviewConfig.REVIEWER)) {
            List<JsonNode> rules = Lists.newArrayList();
            agents.path(agent).path("permissions").forEach(rules::add);

            JsonNode first = rules.getFirst();
            assertEquals("*", first.path("action").asString(), "rules start with a deny-all so unnamed tools stay out of reach");
            assertEquals("deny", first.path("effect").asString());
            for (JsonNode rule : rules) {
                if ("shell".equals(rule.path("action").asString())) {
                    assertTrue(rule.path("resource").asString().startsWith("git "), "only git commands may run: " + rule);
                }
                assertFalse("edit".equals(rule.path("action").asString()) && "allow".equals(rule.path("effect").asString()), "no agent may edit");
            }
        }
    }
    /** the last matching rule decides, as OpenCode applies them: the read-only commands stay, their writing forms do not */
    @Test
    void theWritingAndExecutingFormsOfTheAllowedGitCommandsAreDenied() throws Exception {
        JsonNode agents = build(new RunArgs(), StringUtils.EMPTY);
        for (String agent : List.of(OpenCodeReviewConfig.COORDINATOR, OpenCodeReviewConfig.REVIEWER)) {
            List<JsonNode> rules = Lists.newArrayList();
            agents.path(agent).path("permissions").forEach(rules::add);

            for (String allowed : List.of("git diff HEAD~1 -- a/B.java", "git log -p -3", "git show HEAD:pom.xml", "git status --short")) {
                assertEquals("allow", shellEffect(rules, allowed), allowed);
            }
            for (String denied : List.of("git difftool -x 'sh -c id' HEAD~1", "git diff --output=.git/config HEAD~1", "git log -p --output=x",
                    "git diff --no-index /etc/hosts a.txt", "git show --ext-diff HEAD", "git log --textconv -p", "git -c core.pager=id log",
                    "git show -c core.pager=id HEAD", "rm -rf .")) {
                assertEquals("deny", shellEffect(rules, denied), denied);
            }
        }
    }
    @Test
    void onlyTheCoordinatorMayStartReviewers() throws Exception {
        JsonNode agents = build(new RunArgs(), StringUtils.EMPTY);

        List<String> coordinatorSubagentRules = Lists.newArrayList();
        agents.path(OpenCodeReviewConfig.COORDINATOR).path("permissions").forEach(rule -> {
            if ("subagent".equals(rule.path("action").asString())) {
                coordinatorSubagentRules.add(rule.path("resource").asString() + "=" + rule.path("effect").asString());
            }
        });
        assertEquals(List.of("*=deny", OpenCodeReviewConfig.REVIEWER + "=allow"), coordinatorSubagentRules);

        agents.path(OpenCodeReviewConfig.REVIEWER).path("permissions").forEach(rule ->
                assertFalse("subagent".equals(rule.path("action").asString()) && "allow".equals(rule.path("effect").asString()),
                        "a reviewer must not start sub-agents of its own"));
    }
    private static JsonNode build(RunArgs args, String conventionGuidance) {
        return OpenCodeReviewConfig.build(args, endpoint(args.getReviewBaseUrl(), null), conventionGuidance, NAMING_RULES).path("agents");
    }
    /** the agents' prompts decide what a review finds, so each shape of them is pinned whole */
    @Test
    void theAgentPromptsAreRenderedExactly() throws Exception {
        RunArgs plain = new RunArgs();
        plain.setReviewAssess(false);
        JsonNode bare = OpenCodeReviewConfig.build(plain, endpoint(RunArgs.DEFAULT_LOCAL_LLM_URL, null), StringUtils.EMPTY, NAMING_RULES).path("agents");
        RunArgs assessing = new RunArgs();
        assessing.setReviewAssess(true);
        JsonNode guided = OpenCodeReviewConfig.build(assessing, endpoint(RunArgs.DEFAULT_LOCAL_LLM_URL, null), "\n### AGENTS.md\n\nPrefer Optional over null returns.\n", NAMING_RULES)
                .path("agents");

        GoldenText.assertMatches("opencode-coordinator", bare.path(OpenCodeReviewConfig.COORDINATOR).path("system").asString());
        GoldenText.assertMatches("opencode-reviewer", bare.path(OpenCodeReviewConfig.REVIEWER).path("system").asString());
        GoldenText.assertMatches("opencode-coordinator-assessing", guided.path(OpenCodeReviewConfig.COORDINATOR).path("system").asString());
        GoldenText.assertMatches("opencode-reviewer-assessing", guided.path(OpenCodeReviewConfig.REVIEWER).path("system").asString());
        GoldenText.assertMatches("opencode-triage", OpenCodeReviewConfig.triagePrompt(List.of(new StaticFinding("pmd", "GodClass", "warning", "a/Service.java", 3, "possible God Class"))));
    }
    @Test
    void theTurnsSentToASessionReadAsBefore() {
        assertEquals("Review commit 1a2b3c of this repository.", OpenCodeReviewConfig.reviewPrompt("1a2b3c"));
        assertEquals("Your last answer is not valid JSON: Unexpected character ('}' (code 125)) at 1:7. Reply with the same answer as one corrected JSON object "
                + "and nothing else. Do not review again and do not call any tool.", OpenCodeReviewConfig.repairPrompt("Unexpected character ('}' (code 125)) at 1:7"));
    }
    private static StaticFinding finding(String tool) {
        return new StaticFinding(tool, "Rule", "warning", "a/Service.java", 2, "flagged");
    }
    private static ReviewEndpoint endpoint(String baseUrl, String apiKey) {
        return new ReviewEndpoint(baseUrl, apiKey, null);
    }
    private static String shellEffect(List<JsonNode> rules, String command) {
        String toReturn = null;
        for (JsonNode rule : rules) {
            String action = rule.path("action").asString();
            if (BooleanUtils.or(new boolean[] { "*".equals(action), "shell".equals(action) })) {
                String glob = rule.path("resource").asString();
                String regex = Arrays.stream(glob.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*"));
                if (command.matches(regex)) {
                    toReturn = rule.path("effect").asString();
                }
            }
        }
        return toReturn;
    }
}
