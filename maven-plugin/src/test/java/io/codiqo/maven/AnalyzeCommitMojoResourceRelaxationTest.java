package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the m2e-core#1790 resource relaxation against the formatting it has to survive. The pass runs on a clone of
 * an arbitrary repository, so a POM that puts {@code <resources>} anywhere other than alone on its own line, or
 * declares a charset other than UTF-8, is the normal case rather than the exotic one.
 */
class AnalyzeCommitMojoResourceRelaxationTest {
    private static final String NEUTRALISED = "target/codiqo-out-of-module-resources";

    @TempDir
    Path workTree;

    @Test
    void escapingDirectoryOnItsOwnLineIsRepointedIntoTheModule() throws Exception {
        Path pom = writeModulePom("core", """
                  <build>
                    <resources>
                      <resource>
                        <directory>../shared/resources</directory>
                      </resource>
                    </resources>
                  </build>
                """);

        relax();

        assertTrue(read(pom).contains("<directory>" + NEUTRALISED + "</directory>"), read(pom));
    }
    @Test
    void escapingDirectoryOnASingleCompactLineIsRepointedToo() throws Exception {
        Path pom = writeModulePom("core",
                "  <build><resources><resource><directory>../shared/resources</directory></resource></resources></build>\n");

        relax();

        assertTrue(read(pom).contains("<directory>" + NEUTRALISED + "</directory>"), read(pom));
    }
    /**
     * An inline opening tag paired with a standalone closing tag used to drive the nesting counter below zero, and
     * every later block in that POM was then skipped — one badly formatted block disabled the whole file.
     */
    @Test
    void aMalformedFirstBlockDoesNotDisableLaterRewrites() throws Exception {
        Path pom = writeModulePom("core", """
                  <build>
                    <testResources><testResource><directory>src/test/resources</directory></testResource>
                    </testResources>
                    <resources>
                      <resource>
                        <directory>../shared/resources</directory>
                      </resource>
                    </resources>
                  </build>
                """);

        relax();

        assertTrue(read(pom).contains("<directory>" + NEUTRALISED + "</directory>"), read(pom));
    }
    @Test
    void interpolatedBasedirEscapeIsResolvedRatherThanSkipped() throws Exception {
        Path pom = writeModulePom("core", """
                  <build>
                    <resources>
                      <resource>
                        <directory>${project.basedir}/../shared/resources</directory>
                      </resource>
                    </resources>
                  </build>
                """);

        relax();

        assertTrue(read(pom).contains("<directory>" + NEUTRALISED + "</directory>"), read(pom));
    }
    @Test
    void interpolatedInModuleDirectoryIsLeftAlone() throws Exception {
        Path pom = writeModulePom("core", """
                  <build>
                    <resources>
                      <resource>
                        <directory>${project.basedir}/src/main/resources</directory>
                      </resource>
                    </resources>
                  </build>
                """);

        relax();

        assertTrue(read(pom).contains("<directory>${project.basedir}/src/main/resources</directory>"), read(pom));
    }
    @Test
    void inModuleDirectoryIsLeftAlone() throws Exception {
        Path pom = writeModulePom("core", """
                  <build>
                    <resources>
                      <resource>
                        <directory>src/main/resources</directory>
                      </resource>
                    </resources>
                  </build>
                """);

        relax();

        assertTrue(read(pom).contains("<directory>src/main/resources</directory>"), read(pom));
    }
    /** m2e never reads a plugin execution's own resources, so repointing one would only break the clone's build. */
    @Test
    void pluginConfigurationDirectoryIsLeftAlone() throws Exception {
        Path pom = writeModulePom("core", """
                  <build>
                    <resources>
                      <resource>
                        <directory>../shared/resources</directory>
                      </resource>
                    </resources>
                    <plugins>
                      <plugin>
                        <artifactId>maven-remote-resources-plugin</artifactId>
                        <configuration>
                          <resources>
                            <resource>
                              <directory>../plugin/only</directory>
                            </resource>
                          </resources>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                """);

        relax();

        String rewritten = read(pom);
        assertTrue(rewritten.contains("<directory>" + NEUTRALISED + "</directory>"), rewritten);
        assertTrue(rewritten.contains("<directory>../plugin/only</directory>"), rewritten);
    }
    /** the same text in both scopes cannot be told apart in the file, so neither occurrence is touched */
    @Test
    void aDirectorySharedWithPluginConfigurationIsLeftAloneEntirely() throws Exception {
        Path pom = writeModulePom("core", """
                  <build>
                    <resources>
                      <resource>
                        <directory>../shared/resources</directory>
                      </resource>
                    </resources>
                    <plugins>
                      <plugin>
                        <artifactId>maven-remote-resources-plugin</artifactId>
                        <configuration>
                          <resources>
                            <resource>
                              <directory>../shared/resources</directory>
                            </resource>
                          </resources>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                """);
        String before = read(pom);

        relax();

        assertEquals(before, read(pom));
    }
    @Test
    void aPomDeclaringLatinOneIsRewrittenInThatCharsetAndKeepsItsBytes() throws Exception {
        Path pom = workTree.resolve("core/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.write(pom, ("""
                <?xml version="1.0" encoding="ISO-8859-1"?>
                <project>
                  <artifactId>core</artifactId>
                  <!-- Bjorn Norregaard, spelled Bjørn Nørregaard -->
                  <build>
                    <resources>
                      <resource>
                        <directory>../shared/resources</directory>
                      </resource>
                    </resources>
                  </build>
                </project>
                """).getBytes(StandardCharsets.ISO_8859_1));

        relax();

        String rewritten = Files.readString(pom, StandardCharsets.ISO_8859_1);
        assertTrue(rewritten.contains("<directory>" + NEUTRALISED + "</directory>"), rewritten);
        assertTrue(rewritten.contains("encoding=\"ISO-8859-1\""), rewritten);
        assertTrue(rewritten.contains("Bjørn Nørregaard"), rewritten);
    }
    /** a fixture POM is what the clone's own tests exercise, and the tests are where the coverage data comes from */
    @Test
    void aFixturePomUnderTestResourcesIsNotTouched() throws Exception {
        Path pom = workTree.resolve("core/src/test/resources/broken/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, pomText("fixture", """
                  <build>
                    <resources>
                      <resource>
                        <directory>../shared/resources</directory>
                      </resource>
                    </resources>
                  </build>
                """));
        String before = read(pom);

        relax();

        assertEquals(before, read(pom));
    }
    /** an untrusted POM must not be able to make the inspection fetch anything; a doctype is refused outright */
    @Test
    void aPomCarryingADoctypeIsRefusedAndLeftUnchanged() throws Exception {
        Path pom = workTree.resolve("core/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE project [<!ENTITY escape SYSTEM "file:///etc/passwd">]>
                <project>
                  <artifactId>core</artifactId>
                  <build>
                    <resources>
                      <resource>
                        <directory>../shared/resources</directory>
                      </resource>
                    </resources>
                  </build>
                </project>
                """);
        String before = read(pom);

        relax();

        assertEquals(before, read(pom));
        assertFalse(read(pom).contains(NEUTRALISED), read(pom));
    }
    private void relax() throws Exception {
        AnalyzeCommitMojo mojo = new AnalyzeCommitMojo();
        mojo.setLog(new SystemStreamLog());
        mojo.relaxOutOfModuleResourceDirs(new File(workTree.toString()));
    }
    private Path writeModulePom(String module, String build) throws Exception {
        Path pom = workTree.resolve(module).resolve("pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, pomText(module, build));
        return pom;
    }
    private static String pomText(String artifactId, String build) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n  <artifactId>" + artifactId + "</artifactId>\n"
                + build + "</project>\n";
    }
    private static String read(Path pom) throws Exception {
        return Files.readString(pom, StandardCharsets.UTF_8);
    }
}
