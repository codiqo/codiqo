package io.codiqo.submit;

import java.util.Set;

import org.apache.commons.lang3.BooleanUtils;

import lombok.Value;

@Value
public class ChangedLines {
    Set<Integer> added;
    Set<Integer> modified;

    public boolean contains(int line) {
        return BooleanUtils.or(new boolean[] { added.contains(line), modified.contains(line) });
    }
}
