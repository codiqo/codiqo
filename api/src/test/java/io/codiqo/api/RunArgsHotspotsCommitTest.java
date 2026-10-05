package io.codiqo.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RunArgsHotspotsCommitTest {
    private static final String TIP = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void onlyTheResolvedTipIsTheHotspotsCommit() {
        RunArgs args = new RunArgs();
        args.setHotspotsCommitId(TIP);

        args.setCommitId(TIP);
        assertTrue(args.isHotspotsCommit());

        args.setCommitId("fedcba9876543210fedcba9876543210fedcba98");
        assertFalse(args.isHotspotsCommit());
    }
    @Test
    void anUncommittedChangesRunIsNeverTheHotspotsCommit() {
        assertFalse(new RunArgs().isHotspotsCommit());
    }
}
