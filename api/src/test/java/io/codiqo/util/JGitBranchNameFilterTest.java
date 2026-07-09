package io.codiqo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;


class JGitBranchNameFilterTest {
    @Test
    void noisyBranchNamesReturnNullForLocalAndRemoteRefs() throws Exception {
        for (String branchName : List.of(
                Constants.HEAD,
                "tmp",
                "tmp-skip",
                "bot/rovodev-x",
                "copilot/y",
                "dependabot/npm/z",
                "renovate/foo",
                "user/auto-generated-change-1773339178647")) {
            assertNull(invokeLogicalBranchName(Constants.R_HEADS + branchName), branchName);
            assertNull(invokeLogicalBranchName(Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + branchName),
                    branchName);
        }
    }
    @Test
    void bareDetachedHeadRefIsFiltered() throws Exception {
        assertNull(invokeLogicalBranchName(Constants.HEAD));
    }
    @Test
    void signalBranchNamesPassThroughUnchangedForLocalAndRemoteRefs() throws Exception {
        for (String branchName : List.of(
                "main",
                "dev",
                "feature/x",
                "fix/y",
                "PAY-12390-B",
                "prev-release",
                "test-b2s-release")) {
            assertEquals(branchName, invokeLogicalBranchName(Constants.R_HEADS + branchName), branchName);
            assertEquals(branchName,
                    invokeLogicalBranchName(Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + branchName),
                    branchName);
        }
    }
    private static String invokeLogicalBranchName(String refName) throws Exception {
        Method method = JGit.class.getDeclaredMethod("logicalBranchName", Ref.class);
        method.setAccessible(true);
        return (String) method.invoke(null,
                new ObjectIdRef.Unpeeled(Ref.Storage.NEW, refName, ObjectId.zeroId()));
    }
}