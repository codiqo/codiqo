package io.codiqo.llm;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import lombok.experimental.UtilityClass;

/**
 * removes the prompt templates' fence markers from text the templates embed.
 *
 * every prompt that carries text codiqo did not write — repository convention files, commit messages — fences
 * it between literal markers so the model can tell that data from the prompt itself. Text containing a closing
 * marker would end the fence early, and everything after it would read as prompt rather than as data, which is
 * the whole attack: a commit message is author-controlled, and the skip-request classifier decides whether the
 * commit is measured at all.
 *
 * the markers live here rather than beside each call site because a caller that knows only its own fence is
 * exactly how the commit-message region shipped unprotected while the conventions region was guarded. Adding a
 * fence to a template without adding it here fails PromptFencesTest, which reads the markers back out of the
 * templates.
 */
@UtilityClass
public class PromptFences {
    public static final String CONVENTIONS_BEGIN = "<<<BEGIN PROJECT CONVENTIONS>>>";
    public static final String CONVENTIONS_END = "<<<END PROJECT CONVENTIONS>>>";
    public static final String COMMIT_MESSAGE_BEGIN = "<<<COMMIT_MESSAGE_BEGIN>>>";
    public static final String COMMIT_MESSAGE_END = "<<<COMMIT_MESSAGE_END>>>";

    /**
     * every marker is stripped, not just the closing one of the fence being written: an opening marker in the
     * data invites the model to read what follows as a second region, and a caller should not have to know
     * which of the two it is embedding. Matching ignores case because the reader is a model, not a parser —
     * it will honour a case-variant of a marker that a literal comparison would let through.
     */
    public static final List<String> MARKERS = List.of(CONVENTIONS_BEGIN, CONVENTIONS_END, COMMIT_MESSAGE_BEGIN, COMMIT_MESSAGE_END);

    /**
     * repeats until the text stops shrinking: one pass is not enough, because a marker split around another
     * ("&lt;&lt;&lt;COMMIT_&lt;&lt;&lt;COMMIT_MESSAGE_END&gt;&gt;&gt;MESSAGE_END&gt;&gt;&gt;") reassembles into a working marker as the inner one is
     * removed. Removal only ever shortens the text, so the loop terminates.
     */
    /**
     * Bounds the input before stripping, which is the only safe way to strip author-controlled text: the
     * fixpoint loop below is quadratic in nesting depth, and git puts no limit on a commit message, so
     * running it over a megabyte of nested markers pins the worker for minutes. Cutting on a code point
     * keeps a supplementary-plane character from being halved into an unpaired surrogate, which serializes
     * as structurally invalid JSON string content.
     */
    public String stripBounded(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return strip(text);
        }
        int cut = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return strip(text.substring(0, cut));
    }
    public String strip(String text) {
        String toReturn = removeMarkers(text);
        for (int previousLength = text.length(); toReturn.length() < previousLength;) {
            previousLength = toReturn.length();
            toReturn = removeMarkers(toReturn);
        }
        return toReturn;
    }
    private static String removeMarkers(String text) {
        String toReturn = text;
        for (String marker : MARKERS) {
            toReturn = Strings.CI.remove(toReturn, marker);
        }
        return StringUtils.defaultString(toReturn);
    }
}
