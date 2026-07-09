package io.codiqo.llm;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.codiqo.api.logging.Log;

public class LlmTokenizers implements AutoCloseable {
    private final Log log;
    private final Encoding fallback = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    private final ConcurrentMap<String, Optional<HuggingFaceTokenizer>> loaded = new ConcurrentHashMap<>();
    private final Map<String, String> loadOptions = Map.of(
            "addSpecialTokens", Boolean.FALSE.toString(),
            "truncation", Boolean.FALSE.toString(),
            "padding", Boolean.FALSE.toString());
    private final Map<String, String> tokenizerRepos = Map.of(
            "deepseek", "deepseek-ai/DeepSeek-V3",
            "glm", "zai-org/GLM-4.6",
            "kimi", "Zaynoid/Kimi-K2-Thinking-Tokenizer");

    public LlmTokenizers(Log log) {
        this.log = log;
    }
    public int estimateTokens(String model, String text) {
        String normalized = StringUtils.lowerCase(StringUtils.defaultString(model));
        for (String family : tokenizerRepos.keySet()) {
            if (Strings.CS.contains(normalized, family)) {
                Optional<HuggingFaceTokenizer> tokenizer = loaded.computeIfAbsent(family, this::load);
                if (tokenizer.isPresent()) {
                    return tokenizer.get().encode(text, false, false).getIds().length;
                }
            }
        }
        return fallback.countTokensOrdinary(text);
    }
    @Override
    public void close() {
        loaded.values().forEach(tokenizer -> tokenizer.ifPresent(HuggingFaceTokenizer::close));
        loaded.clear();
    }
    private Optional<HuggingFaceTokenizer> load(String family) {
        try {
            String repo = tokenizerRepos.get(family);
            HuggingFaceTokenizer downloaded = HuggingFaceTokenizer.newInstance(repo, loadOptions);
            log.info("loaded %s tokenizer from HuggingFace hub (%s)", family, repo);
            return Optional.of(downloaded);
        } catch (Exception err) {
            log.warn("could not download %s tokenizer (%s), trying bundled resource", family, err.toString());
        }

        String resource = "/tokenizers/" + family + ".json";
        for (;;) {
            try (InputStream is = LlmTokenizers.class.getResourceAsStream(resource)) {
                if (Objects.nonNull(is)) {
                    log.info("loaded %s tokenizer from bundled resource %s", family, resource);
                    return Optional.of(HuggingFaceTokenizer.newInstance(is, loadOptions));
                }
                return Optional.empty();
            } catch (IOException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
