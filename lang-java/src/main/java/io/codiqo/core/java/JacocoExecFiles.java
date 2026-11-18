package io.codiqo.core.java;

import java.io.File;
import java.io.IOException;

import org.jacoco.core.tools.ExecFileLoader;

import lombok.experimental.UtilityClass;

/** Reads JaCoCo's exec files into a loader, keeping what a write cut short left readable. */
@UtilityClass
public class JacocoExecFiles {
    /**
     * Loads the file, and says whether all of it was read. codiqo's test agent has JaCoCo append to its file while the
     * tests run (TestRecords), and surefire's kill at the test-run timeout ({@code Runtime.halt}) can land in the
     * middle of such a write: the file then ends inside a block, or, where a later test JVM of the same module appends to
     * the same file, holds a cut block followed by whole ones that read as garbage. Each file is read on its own and what
     * was read before the failure is kept: failing the analysis over it would halt the project, and reading nothing would
     * drop the coverage of every test that finished. A file of which nothing could be read, one that is not an exec
     * file at all, still fails.
     */
    public boolean load(ExecFileLoader loader, File file) throws IOException {
        ExecFileLoader part = new ExecFileLoader();
        boolean toReturn = true;
        try {
            part.load(file);
        } catch (IOException err) {
            if (part.getExecutionDataStore().getContents().isEmpty()) {
                throw err;
            }
            toReturn = false;
        }

        part.getSessionInfoStore().accept(loader.getSessionInfoStore());
        part.getExecutionDataStore().accept(loader.getExecutionDataStore());
        return toReturn;
    }
}
