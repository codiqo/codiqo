package io.codiqo.submit;

import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;


public final class ChangedLines extends ImmutablePair<Set<Integer>, Set<Integer>> {
    public ChangedLines(Set<Integer> added, Set<Integer> modified) {
        super(added, modified);
    }
    public Set<Integer> getAdded() {
        return getLeft();
    }
    public Set<Integer> getModified() {
        return getRight();
    }
    public boolean contains(int line) {
        return BooleanUtils.or(new boolean[] { getAdded().contains(line), getModified().contains(line) });
    }
}
