package io.codiqo.llm.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.thymeleaf.context.Context;

import com.google.common.annotations.VisibleForTesting;

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
        String prompt = prompt(organization, period, metric, contenders);
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
    @VisibleForTesting
    public static String prompt(String organization, String period, String metric, List<RecapContender> contenders) {
        Context ctx = new Context();
        ctx.setVariable("organization", organization);
        ctx.setVariable("period", period);
        ctx.setVariable("metric", metric);
        ctx.setVariable("contenders", contenders.stream().map(ContenderView::new).toList());
        return PromptTemplates.process(TEMPLATE_RECAP_AWARDS, ctx);
    }

    /** one contender as the prompt lists it: the facts as measured, the first name it may use, the projects it names */
    public static final class ContenderView extends ImmutableTriple<RecapContender, String, List<String>> {
        public ContenderView(RecapContender contender) {
            super(contender,
                    StringUtils.substringBefore(StringUtils.trim(contender.getName()), StringUtils.SPACE),
                    CollectionUtils.emptyIfNull(contender.getProjects()).stream().filter(Objects::nonNull).toList());
        }
        public RecapContender getContender() {
            return getLeft();
        }
        public String getFirstName() {
            return getMiddle();
        }
        public List<String> getProjects() {
            return getRight();
        }
    }
}
