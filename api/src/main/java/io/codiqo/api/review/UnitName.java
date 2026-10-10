package io.codiqo.api.review;

import static java.util.function.Predicate.not;

import java.util.List;
import java.util.stream.IntStream;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * A code unit's name as a review compares it: the member, and the path of containers that declare it, outermost first
 * ({@code [Messages, Request, Builder]} for {@code build()} of the nested class {@code Messages.Request.Builder}). Two
 * units of one member in one file are told apart by their containers.
 */
public final class UnitName extends ImmutablePair<List<String>, String> {
    /**
     * The step of a container that has no name a reviewer can write: an anonymous class, or the body of an enum
     * constant, which the index knows only by a compiler-assigned number ({@code Op$1}). A reviewer names it by what it
     * creates or by the constant, so this step matches any one step of a label, and a label may also leave it out.
     */
    public static final String ANONYMOUS = StringUtils.EMPTY;

    public UnitName(List<String> container, String member) {
        super(List.copyOf(container), member);
    }
    public List<String> getContainer() {
        return getLeft();
    }
    public String getMember() {
        return getRight();
    }
    public boolean isAnonymous() {
        return getContainer().contains(ANONYMOUS);
    }
    /**
     * How well a label's containers name this unit's: {@link Match#EXACT} when they are the innermost steps of this
     * unit's path, {@link Match#LOOSE} when they are only once an anonymous step is read as any name or left out, or
     * when the label or the unit has no container at all and so says nothing either way.
     */
    public Match match(UnitName label) {
        List<String> named = label.getContainer();
        if (BooleanUtils.or(new boolean[] { named.isEmpty(), getContainer().isEmpty() })) {
            return named.size() == getContainer().size() ? Match.EXACT : Match.LOOSE;
        }
        if (endsWith(getContainer(), named, false)) {
            return Match.EXACT;
        }
        if (BooleanUtils.or(new boolean[] { endsWith(getContainer(), named, true), endsWith(getContainer().stream().filter(not(ANONYMOUS::equals)).toList(), named, false) })) {
            return Match.LOOSE;
        }
        return Match.NONE;
    }
    /**
     * Whether the innermost steps of both paths agree, over the shorter one's length: a label may leave out the outer
     * classes, and one that writes its packages anyway ({@code com.example.Totals.run()}) has steps a unit's path, which
     * starts at its top-level class, never holds.
     */
    private static boolean endsWith(List<String> path, List<String> named, boolean anonymousMatchesAny) {
        int length = Math.min(path.size(), named.size());
        List<String> tail = path.subList(path.size() - length, path.size());
        List<String> written = named.subList(named.size() - length, named.size());
        return IntStream.range(0, length).allMatch(i -> tail.get(i).equals(written.get(i)) || (anonymousMatchesAny && ANONYMOUS.equals(tail.get(i))));
    }

    public enum Match {
        EXACT,
        LOOSE,
        NONE
    }
}
