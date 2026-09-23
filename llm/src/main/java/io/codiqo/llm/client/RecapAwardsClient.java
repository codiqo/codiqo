package io.codiqo.llm.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.thymeleaf.context.Context;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.PromptTemplates;
import io.codiqo.llm.schema.RecapAwardsResponse;
import io.codiqo.llm.schema.RecapContender;

/**
 * every figure in the prompt was measured by the caller and is rendered beside the text, so the model
 * contributes wording only and is told to invent no number
 */
public class RecapAwardsClient implements LlmClient {
    private static final String TEMPLATE_RECAP_AWARDS = "recap-awards-prompt";
    private static final String LABEL = "recap awards";

    private final Log log;
    private final JsonCompletionClient completions;

    public RecapAwardsClient(RunArgs args, ExecutorService executor, Log log, Map<String, String> additionalHeaders) {
        this.log = Objects.requireNonNull(log);
        this.completions = new JsonCompletionClient(args, executor, log, additionalHeaders);
    }
    public JsonCompletionClient.Result<RecapAwardsResponse> write(
            String organization,
            String period,
            String metric,
            List<RecapContender> contenders) throws Exception {
        Context ctx = new Context();
        ctx.setVariable("organization", organization);
        ctx.setVariable("period", period);
        ctx.setVariable("metric", metric);
        ctx.setVariable("contenders", describe(contenders));

        String prompt = PromptTemplates.process(TEMPLATE_RECAP_AWARDS, ctx);
        log.info(String.format(Locale.ROOT, "recap awards prompt: %d chars (%d contributors, ranked by %s)",
                prompt.length(),
                contenders.size(),
                metric));

        return completions.complete(LABEL, prompt, RecapAwardsResponse.class);
    }
    @Override
    public void close() {
        completions.close();
    }
    private static String describe(List<RecapContender> contenders) {
        StringBuilder toReturn = new StringBuilder();
        for (RecapContender contender : contenders) {
            toReturn.append("#").append(contender.getRank()).append(' ').append(contender.getName());
            if (StringUtils.isNotBlank(contender.getSeniority())) {
                toReturn.append(" (").append(contender.getSeniority()).append(')');
            }
            toReturn.append(StringUtils.LF);

            appendFact(toReturn, contender.getHeadlineLabel(), contender.getHeadlineValue());
            appendFact(toReturn, "commits", contender.getCommits());
            appendFact(toReturn, "senior-grade commits", contender.getSeniorGradeCommits());
            appendFact(toReturn, "files touched", contender.getFilesChanged());
            appendFact(toReturn, "lines changed", contender.getLinesChanged());
            appendFact(toReturn, "average task complexity (1-10)", contender.getAvgComplexity());
            appendFact(toReturn, "changed-line test coverage %", contender.getAvgCoverage());
            appendFact(toReturn, "leads the team on", contender.getDistinction());
            if (CollectionUtils.isNotEmpty(contender.getProjects())) {
                appendFact(toReturn, "worked mostly in", String.join(", ", contender.getProjects()));
            }
            appendFact(toReturn, "hardest piece of work (analysis summary)", contender.getBestWork());

            toReturn.append(StringUtils.LF);
        }
        return toReturn.toString();
    }
    private static void appendFact(StringBuilder target, String label, Object value) {
        if (Objects.isNull(value)) {
            return;
        }
        target.append("  - ").append(label).append(": ").append(value).append(StringUtils.LF);
    }
}
