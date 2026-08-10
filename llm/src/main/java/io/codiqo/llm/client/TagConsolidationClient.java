package io.codiqo.llm.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.thymeleaf.context.Context;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.PromptTemplates;
import io.codiqo.llm.schema.TagConsolidationResponse;

public class TagConsolidationClient implements LlmClient {
    private static final String TEMPLATE_TAG_CONSOLIDATION = "tag-consolidation-prompt";
    private static final String LABEL = "tag consolidation";

    private final Log log;
    private final JsonCompletionClient completions;

    public TagConsolidationClient(RunArgs args, ExecutorService executor, Log log, Map<String, String> additionalHeaders) {
        this.log = Objects.requireNonNull(log);
        this.completions = new JsonCompletionClient(args, executor, log, additionalHeaders);
    }
    public JsonCompletionClient.Result<TagConsolidationResponse> consolidate(
            List<String> technicalTags, List<String> functionalTags, int vocabularyCap) throws Exception {
        Context ctx = new Context();
        ctx.setVariable("technical_tags", String.join(", ", technicalTags));
        ctx.setVariable("functional_tags", String.join(", ", functionalTags));
        ctx.setVariable("technical_count", technicalTags.size());
        ctx.setVariable("functional_count", functionalTags.size());
        ctx.setVariable("vocabulary_cap", vocabularyCap);

        String prompt = PromptTemplates.process(TEMPLATE_TAG_CONSOLIDATION, ctx);
        log.info(String.format("tag consolidation prompt: %d chars (%d technical, %d functional, cap %d)",
                prompt.length(), technicalTags.size(), functionalTags.size(), vocabularyCap));

        return completions.complete(LABEL, prompt, TagConsolidationResponse.class);
    }
    @Override
    public void close() {
        completions.close();
    }
}
