package io.codiqo.core.java;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;

import org.jacoco.core.tools.ExecFileLoader;

import lombok.experimental.UtilityClass;

/** Reads JaCoCo's exec files into a loader, keeping what a write cut short left whole. */
@UtilityClass
public class JacocoExecFiles {
    /**
     * Loads the file, and says whether all of it was read. codiqo's test agent has JaCoCo append to its file while the
     * tests run (TestRecords), and surefire's kill at the test-run timeout ({@code Runtime.halt}) can land in the
     * middle of such a write: the file then ends inside a block. JaCoCo hands every block to the loader's stores as it
     * reads it, so the blocks before the cut are already there and are kept; failing the analysis over the last one
     * would halt the project, and reading nothing would drop the coverage of every test that finished. A file that is
     * not an exec file at all still fails.
     */
    public boolean load(ExecFileLoader loader, File file) throws IOException {
        try {
            loader.load(file);
            return true;
        } catch (EOFException err) {
            return false;
        }
    }
}
