package io.codiqo.llm.review;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * The project's tag vocabulary, technical and functional, which an assessing coordinator reuses before it coins a tag.
 * The run supplies it, so it travels beside {@code RunArgs} like the convention guidance.
 *
 * <p>
 * The tags go into the coordinator's system prompt, and they are model output read back from the server, so only the
 * form the prompt asks for is kept: a lowercase, hyphenated label. Anything else, a sentence above all, is dropped
 * rather than fenced, since a label of that form cannot carry an instruction.
 */
public final class ReviewTags extends ImmutablePair<List<String>, List<String>> {
    public static final ReviewTags NONE = new ReviewTags(List.of(), List.of());

    private static final Pattern LABEL = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private ReviewTags(List<String> technical, List<String> functional) {
        super(technical, functional);
    }
    public List<String> getTechnical() {
        return getLeft();
    }
    public List<String> getFunctional() {
        return getRight();
    }
    public static ReviewTags of(Collection<String> technical, Collection<String> functional) {
        return new ReviewTags(labels(technical), labels(functional));
    }
    private static List<String> labels(Collection<String> tags) {
        return CollectionUtils.emptyIfNull(tags).stream().filter(tag -> LABEL.matcher(String.valueOf(tag)).matches()).distinct().toList();
    }
}
