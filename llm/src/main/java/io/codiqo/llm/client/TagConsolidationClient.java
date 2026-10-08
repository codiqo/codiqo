package io.codiqo.llm.client;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.thymeleaf.context.Context;

import com.google.common.annotations.VisibleForTesting;

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
        String prompt = prompt(technicalTags, functionalTags, vocabularyCap);
        log.info(String.format(Locale.ROOT, "tag consolidation prompt: %d chars (%d technical, %d functional, cap %d)",
                prompt.length(),
                technicalTags.size(),
                functionalTags.size(),
                vocabularyCap));

        return completions.complete(LABEL, prompt, TagConsolidationResponse.class);
    }
    @Override
    public void close() {
        completions.close();
    }
    @VisibleForTesting
    public static String prompt(List<String> technicalTags, List<String> functionalTags, int vocabularyCap) {
        Context ctx = new Context();
        ctx.setVariable("technical_tags", technicalTags.stream().filter(Objects::nonNull).toList());
        ctx.setVariable("functional_tags", functionalTags.stream().filter(Objects::nonNull).toList());
        ctx.setVariable("technical_count", technicalTags.size());
        ctx.setVariable("functional_count", functionalTags.size());
        ctx.setVariable("vocabulary_cap", vocabularyCap);
        return PromptTemplates.process(TEMPLATE_TAG_CONSOLIDATION, ctx);
    }
}
