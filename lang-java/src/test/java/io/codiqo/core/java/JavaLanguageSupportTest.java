package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.codiqo.lang.config.FileKinds;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;

class JavaLanguageSupportTest {
    /**
     * Java's file kind in lang-config names the extensions the analysis selects Java files by, and PMD parses a file by
     * its own Java module's list. The two must agree: a file of an extension only the row lists would be selected and
     * never parsed as Java, and one only PMD lists would be left out of the analysis.
     */
    @Test
    void javasFileKindListsTheExtensionsPmdReadsAsJava() {
        assertEquals(new JavaLanguageModule().getExtensions(), FileKinds.JAVA.getExtensions());
        assertEquals(FileKinds.JAVA, new JavaLanguageSupport().language());
    }
}
