package io.codiqo.llm.client;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.thymeleaf.context.Context;

import io.codiqo.api.RunArgs;
import io.codiqo.api.logging.Log;
import io.codiqo.llm.PromptFences;
import io.codiqo.llm.PromptTemplates;
import io.codiqo.llm.schema.SkipRequestResponse;
import lombok.Value;

/**
 * decides whether a commit message asks, in prose, that codiqo not count the commit.
 *
 * this runs as its own call with the message as its only input, deliberately NOT as a field on the scoring
 * response: the scoring prompt must never be the place where author-controlled text can request an outcome.
 * because the output schema carries nothing but "exclude yes/no" plus a quote, the most a crafted message can
 * achieve here is the exclusion it is asking for — which is the feature — and the quote is verified against the
 * message afterwards, so a hallucinated or paraphrased justification cannot exclude anything
 */
public class SkipRequestClient implements LlmClient {
    private static final String TEMPLATE_SKIP_REQUEST = "skip-request-prompt";
    private static final String LABEL = "skip-request detection";
    private static final String TOOL_MENTION = "codiqo";
    private static final int MIN_QUOTE_WORDS = 3;
    private static final int MIN_QUOTE_UNSPACED_LETTERS = 6;

    private static final Set<Character.UnicodeScript> UNSPACED_SCRIPTS = EnumSet.of(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR);

    /**
     * a squash-merge or release commit can carry a few hundred KB of concatenated changelog, which overflows a
     * narrow provider's window outright and bills six figures of prompt tokens on every commit to answer one
     * yes/no question. the cap sits far above any hand-written message, and grounding still runs against the
     * full text, so a quote taken from beyond it simply fails to verify rather than excluding anything
     */
    private static final int MAX_COMMIT_MESSAGE_CHARS = 16 * 1024;

    /**
     * the answer is two fields, but reasoning models spend real tokens before emitting it — Kimi was observed
     * at ~2100 completion tokens on one classification, so this leaves headroom over that while staying far
     * below the scoring allowance, which a narrow-window provider would reject alongside the prompt
     */
    private static final int MAX_COMPLETION_TOKENS = 8 * 1024;

    private final Log log;
    private final JsonCompletionClient completions;

    public SkipRequestClient(RunArgs args, ExecutorService executor, Log log, Map<String, String> additionalHeaders) {
        this.log = Objects.requireNonNull(log);
        this.completions = new JsonCompletionClient(args, executor, log, additionalHeaders, MAX_COMPLETION_TOKENS);
    }
    /**
     * the grounded skip reason plus what the classification cost. usage is returned even when no request was
     * found — the tokens were spent either way and every LLM operation is billed, so dropping them here would
     * silently under-report the ledger on the overwhelmingly common negative answer
     */
    public Detection detect(String commitMessage) throws Exception {
        /**
         * the message is author-controlled and the prompt fences it as untrusted data, so a message carrying
         * the closing marker would end that fence early and have its remainder read as prompt — in the one
         * call whose entire purpose is resisting author-controlled text. grounding still runs against the
         * original, so a quote spanning a stripped marker simply fails to verify
         */
        /**
         * truncate BEFORE stripping, never after: strip repeats until the text stops shrinking, so running it
         * over the whole message first makes a defence against nested markers quadratic in an input the author
         * controls and git does not bound. Cutting on a code point keeps a supplementary-plane character from
         * being halved into an unpaired surrogate, which serializes as invalid JSON string content
         */
        String classified = PromptFences.stripBounded(commitMessage, MAX_COMMIT_MESSAGE_CHARS);
        if (classified.length() < commitMessage.length()) {
            log.warn(String.format("%s: commit message reduced to %d of %d chars", LABEL, classified.length(), commitMessage.length()));
        }

        Context ctx = new Context();
        ctx.setVariable("commit_message", classified);

        JsonCompletionClient.Result<SkipRequestResponse> result = completions
                .complete(LABEL, PromptTemplates.process(TEMPLATE_SKIP_REQUEST, ctx), SkipRequestResponse.class);
        return new Detection(groundedQuote(commitMessage, result.getResponse(), log), result.getUsage());
    }
    @Override
    public void close() {
        completions.close();
    }
    /**
     * the verification step that makes a non-deterministic classifier safe to act on: the model must point at
     * the words that carry the request, and those words must really be in the message. whitespace is normalised
     * because a wrapped message re-flows, but nothing else is — a paraphrase is treated as no request at all
     */
    static Optional<String> groundedQuote(String commitMessage, SkipRequestResponse response, Log log) {
        if (response.isExcludeRequested()) {
            /**
             * the schema asks for an empty string when nothing is cited, but a model that simply omits the
             * field leaves it null — and a null quote must read as "no evidence", not blow up the whole
             * detection and get swallowed into "scoring normally", which silently ignores the author
             */
            String quote = StringUtils.normalizeSpace(StringUtils.defaultString(response.getQuote()));
            if (citesNoRequest(quote)) {
                log.warn(String.format("%s cited too little to be a request — ignoring: '%s'", LABEL, quote));
                return Optional.empty();
            }
            if (Strings.CI.contains(StringUtils.normalizeSpace(commitMessage), quote)) {
                return Optional.of(quote);
            }
            log.warn(String.format("%s quoted text absent from the commit message — ignoring: '%s'", LABEL, quote));
        }
        return Optional.empty();
    }
    /**
     * grounding proves the cited words are the author's; this proves they are evidence of anything. a message
     * that names the tool contains that word wherever the model looks, so citing only it passes the substring
     * check while carrying no request — which is exactly what a jail broken model was observed to produce. one
     * ordinary word lifted from anywhere in the message passes just as easily, so the quote has to read as a
     * clause once the tool's own name is removed. this errs toward scoring: a request phrased in fewer words
     * than that is counted normally, which is the recoverable direction — silently dropping the author's work
     * is not. counting is over code points because a letter above U+FFFF is two chars and neither is a letter
     */
    private static boolean citesNoRequest(String quote) {
        String cited = Strings.CI.remove(quote, TOOL_MENTION);
        if (countWords(cited) >= MIN_QUOTE_WORDS) {
            return false;
        }
        return countUnspacedScriptLetters(cited) < MIN_QUOTE_UNSPACED_LETTERS;
    }
    private static int countWords(String text) {
        return (int) Arrays.stream(StringUtils.split(text))
                .filter(word -> word.codePoints().anyMatch(Character::isLetterOrDigit))
                .count();
    }
    /**
     * The word count is meaningless for scripts that do not separate words with spaces — Chinese, Japanese
     * and Thai all collapse to a single token, so the clause floor was unreachable and every request written
     * in them was silently discarded. Those scripts get a code-point threshold instead, which is the same
     * "more than one bare token" test expressed in the unit that script actually uses. {@link java.text.BreakIterator}
     * was tried first and rejected: with no locale to key on it returns a run of Han as one token.
     */
    private static int countUnspacedScriptLetters(String text) {
        return (int) text.codePoints()
                .filter(Character::isLetter)
                .filter(codePoint -> UNSPACED_SCRIPTS.contains(Character.UnicodeScript.of(codePoint)))
                .count();
    }

    @Value
    public static class Detection {
        Optional<String> skipReason;
        LlmUsage usage;
    }
}
