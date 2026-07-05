package io.codiqo.maven.populator;

import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Sets;

import io.codiqo.api.diff.IneffectiveLineFilter;
import io.codiqo.llm.UnifiedDiffLines;
import io.codiqo.llm.UnifiedDiffLines.ChangeBlock;
import lombok.experimental.UtilityClass;

/**
 * Splits a file's effective changed lines into pure ADDED vs MODIFIED (amended) new-file lines:
 * within each unified-diff change block the first min(deleted, added) added lines pair with the
 * deletions and count as modified, the rest are new code. Shared by the changed-line coverage and
 * changed-line CPD metrics so both describe the identical changed-line set. Returns empty sets for
 * a null/blank diff.
 */
@UtilityClass
public class ChangedLineClassifier {
    public ChangedLines classify(String diff, IneffectiveLineFilter filter) {
        Set<Integer> added = Sets.newHashSet();
        Set<Integer> modified = Sets.newHashSet();
        if (StringUtils.isNotEmpty(diff)) {
            for (ChangeBlock block : UnifiedDiffLines.parse(diff, filter).getBlocks()) {
                List<Integer> blockAdded = block.getAddedLines();
                int modifiedCount = Math.min(block.getDeletedLines().size(), blockAdded.size());
                for (int i = 0; i < blockAdded.size(); i++) {
                    if (i < modifiedCount) {
                        modified.add(blockAdded.get(i));
                    } else {
                        added.add(blockAdded.get(i));
                    }
                }
            }
        }
        return new ChangedLines(added, modified);
    }
}
