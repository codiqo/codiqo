package io.codiqo.llm;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.codiqo.api.LlmTokenizers;
import io.codiqo.api.logging.Log;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultLlmTokenizers implements LlmTokenizers, AutoCloseable {
    private static final double MIN_HEALTHY_CHARS_PER_TOKEN = 2.0;
    private static final String TOKENIZER_PROBE = "public static void main(String[] args) { System.out.println(\"the quick brown fox jumps over the lazy dog\"); }";

    private static final Encoding FALLBACK = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    private static final Map<String, String> LOAD_OPTIONS = Map.of(
            "addSpecialTokens", Boolean.FALSE.toString(),
            "truncation", Boolean.FALSE.toString(),
            "padding", Boolean.FALSE.toString());

    private final Log log;
    private final ConcurrentMap<String, Optional<HuggingFaceTokenizer>> loaded = new ConcurrentHashMap<>();

    private final Map<String, String> tokenizerRepos = Map.of(
            "deepseek", "deepseek-ai/DeepSeek-V3",
            "glm", "zai-org/GLM-4.6");

    @Override
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
        return FALLBACK.countTokensOrdinary(text);
    }
    @Override
    public void close() {
        var it = loaded.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var opt = entry.getValue();
            try {
                if (opt.isPresent()) {
                    var tokenizer = opt.get();
                    tokenizer.close();
                }
            } catch (Throwable t) {
                log.error("could not close %s tokenizer (%s)", entry.getKey(), t.toString());
            } finally {
                it.remove();
            }
        }
    }
    private Optional<HuggingFaceTokenizer> load(String family) {
        String repo = tokenizerRepos.get(family);
        try {
            Optional<HuggingFaceTokenizer> downloaded = accept(HuggingFaceTokenizer.newInstance(repo, LOAD_OPTIONS), family, "HuggingFace hub (" + repo + ")");
            if (downloaded.isPresent()) {
                return downloaded;
            }
        } catch (Exception err) {
            log.warn("could not download %s tokenizer (%s), trying bundled resource", family, err.toString());
        }

        String resource = "/tokenizers/" + family + ".json";
        try (InputStream is = DefaultLlmTokenizers.class.getResourceAsStream(resource)) {
            if (Objects.nonNull(is)) {
                return accept(HuggingFaceTokenizer.newInstance(is, LOAD_OPTIONS), family, "bundled resource " + resource);
            }
        } catch (Exception err) {
            log.warn("could not load bundled %s tokenizer %s (%s), using ordinal fallback", family, resource, err.toString());
        }
        return Optional.empty();
    }
    private Optional<HuggingFaceTokenizer> accept(HuggingFaceTokenizer tokenizer, String family, String source) {
        try {
            if (isUsable(tokenizer)) {
                log.info("loaded %s tokenizer from %s", family, source);
                return Optional.of(tokenizer);
            }
            log.warn("%s tokenizer from %s tokenizes at byte level (no merge rules), using ordinal fallback", family, source);
        } catch (Exception err) {
            log.warn("%s tokenizer from %s could not be probed (%s), using ordinal fallback", family, source, err.toString());
        }
        tokenizer.close();
        return Optional.empty();
    }
    private static boolean isUsable(HuggingFaceTokenizer tokenizer) {
        int tokens = tokenizer.encode(TOKENIZER_PROBE, false, false).getIds().length;
        if (tokens == 0) {
            return false;
        }
        double charsPerToken = (double) TOKENIZER_PROBE.length() / tokens;
        return charsPerToken >= MIN_HEALTHY_CHARS_PER_TOKEN;
    }
}
