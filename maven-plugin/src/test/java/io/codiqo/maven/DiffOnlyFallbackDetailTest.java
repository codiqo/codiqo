package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.NoSuchFileException;

import org.junit.jupiter.api.Test;

/**
 * A degraded analysis that falls back to diff-only is scored in a different regime: no project metrics, so
 * driver scores are raw line counts and the zero baseline stops the global cap binding. The reason has to
 * survive in the persisted detail, because the log line does not — log retention is finite, and the question
 * of why an analysis scored the way it did outlives it.
 */
class DiffOnlyFallbackDetailTest {
    private static final String MARKER = "[codiqo] source-only degraded index failed";

    @Test
    void theRootCauseIsAppendedToAnExistingDetail() throws Exception {
        String detail = detailFor("[ERROR] COMPILATION ERROR : cannot find symbol",
                new IOException("outer", new NoSuchFileException("/tmp/codiqo123/module/src")));

        assertTrue(detail.startsWith("[ERROR] COMPILATION ERROR : cannot find symbol"),
                "the build failure stays first — it is what the reader came for: " + detail);
        assertTrue(detail.contains(MARKER), detail);
        assertTrue(detail.contains("/tmp/codiqo123/module/src"),
                "the ROOT cause is what names the failing path, not the wrapper: " + detail);
        assertTrue(detail.contains("the global cap does not bind"),
                "the consequence is spelled out so the row explains its own scoring regime: " + detail);
    }
    /** a category that carries no detail of its own must still record why the regime changed */
    @Test
    void theNoteStandsAloneWhenThereIsNoDetail() throws Exception {
        String detail = detailFor("   ", new IOException("disk went away"));

        assertTrue(detail.startsWith(MARKER), detail);
        assertTrue(detail.contains("disk went away"), detail);
    }
    @Test
    void aBlankDetailIsNotPrefixedWithSeparatorNoise() throws Exception {
        assertEquals(detailFor(null, new IOException("boom")), detailFor("", new IOException("boom")),
                "null and empty detail must produce the same note, with no leading blank lines");
    }
    private static String detailFor(String detail, IOException err) throws Exception {
        Method method = AbstractAnalyzeMojo.class.getDeclaredMethod("diffOnlyFallbackDetail", String.class, IOException.class);
        method.setAccessible(true);
        return (String) method.invoke(null, detail, err);
    }
}
