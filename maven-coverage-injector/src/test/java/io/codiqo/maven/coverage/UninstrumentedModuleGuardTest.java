package io.codiqo.maven.coverage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UninstrumentedModuleGuardTest {
    @TempDir
    Path tempDir;

    private Path report;
    private MavenProject project;
    private File reportsDirectory;

    @BeforeEach
    void setUp() throws IOException {
        report = tempDir.resolve("uninstrumented.log");
        System.setProperty(CoverageInjectorConfig.PROP_UNINSTRUMENTED_REPORT, report.toString());
        System.setProperty(CoverageInjectorConfig.PROP_AGENT_JAR, tempDir.resolve("jacocoagent.jar").toString());

        project = project("core", "jar");
        reportsDirectory = tempDir.resolve("core/target/surefire-reports").toFile();
        Files.createDirectories(reportsDirectory.toPath());
    }
    @AfterEach
    void tearDown() {
        System.clearProperty(CoverageInjectorConfig.PROP_UNINSTRUMENTED_REPORT);
        System.clearProperty(CoverageInjectorConfig.PROP_AGENT_JAR);
    }
    @Test
    void testsThatRanWithoutCoverageFailTheBuildAndNameTheModule() throws IOException {
        writeJunitReport();

        MojoExecutionException err = assertThrows(MojoExecutionException.class, () -> new UninstrumentedModuleGuard().afterMojoExecutionSuccess(surefire("test")));

        assertTrue(err.getMessage().contains("org.example:core"), err.getMessage());
        List<String> recorded = Files.readAllLines(report, StandardCharsets.UTF_8);
        assertEquals(1, recorded.size());
        assertTrue(recorded.iterator().next().startsWith("org.example:core ran its tests"), recorded.toString());
    }
    @Test
    void testsThatWroteCoveragePass() throws IOException {
        writeJunitReport();
        Files.createDirectories(JacocoAgentInjector.destFile(project).toPath().getParent());
        Files.writeString(JacocoAgentInjector.destFile(project).toPath(), "exec", StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> new UninstrumentedModuleGuard().afterMojoExecutionSuccess(surefire("test")));
    }
    /** skipped tests, or a module whose test sources hold no @Test, write no report and expect no coverage */
    @Test
    void aTestRunWithoutReportsPasses() {
        assertDoesNotThrow(() -> new UninstrumentedModuleGuard().afterMojoExecutionSuccess(surefire("test")));
        assertTrue(Files.notExists(report));
    }
    @Test
    void anotherGoalIsNotChecked() throws IOException {
        writeJunitReport();

        assertDoesNotThrow(() -> new UninstrumentedModuleGuard().afterMojoExecutionSuccess(surefire("help")));
    }
    @Test
    void aRunWithoutTheReportPropertyIsNotChecked() throws IOException {
        writeJunitReport();
        System.clearProperty(CoverageInjectorConfig.PROP_UNINSTRUMENTED_REPORT);

        assertDoesNotThrow(() -> new UninstrumentedModuleGuard().afterMojoExecutionSuccess(surefire("test")));
    }
    /** a module running its tests in the build JVM records no coverage, which fails the build before it compiles */
    @Test
    void testsInTheBuildJvmFailBeforeTheBuild() throws IOException {
        Files.createDirectories(tempDir.resolve("core/src/test/java/a"));
        Files.writeString(tempDir.resolve("core/src/test/java/a/ATest.java"), "class ATest {}", StandardCharsets.UTF_8);
        project.getBuild().setTestSourceDirectory(tempDir.resolve("core/src/test/java").toString());
        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        Xpp3Dom forkCount = new Xpp3Dom("forkCount");
        forkCount.setValue("${tests.forks}");
        Xpp3Dom config = new Xpp3Dom("configuration");
        config.addChild(forkCount);
        surefire.setConfiguration(config);
        project.getBuild().addPlugin(surefire);
        project.getProperties().setProperty("tests.forks", "0");

        MavenSession session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(List.of(project));

        MavenExecutionException err = assertThrows(MavenExecutionException.class, () -> new JacocoAgentInjector().afterProjectsRead(session));

        assertTrue(err.getMessage().contains("org.example:core"), err.getMessage());
        assertTrue(Files.readString(report).contains("forkCount=0"), "the property the forkCount names is read as Maven will read it");
    }
    @Test
    void aModuleWithoutTestSourcesIsNotChecked() {
        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        Xpp3Dom forkCount = new Xpp3Dom("forkCount");
        forkCount.setValue("0");
        Xpp3Dom config = new Xpp3Dom("configuration");
        config.addChild(forkCount);
        surefire.setConfiguration(config);
        project.getBuild().addPlugin(surefire);
        project.getBuild().setTestSourceDirectory(tempDir.resolve("core/src/test/java").toString());

        MavenSession session = mock(MavenSession.class);
        when(session.getAllProjects()).thenReturn(List.of(project));

        assertDoesNotThrow(() -> new JacocoAgentInjector().afterProjectsRead(session));
    }
    private void writeJunitReport() throws IOException {
        Files.writeString(reportsDirectory.toPath().resolve("TEST-org.example.CoreTest.xml"), "<testsuite/>", StandardCharsets.UTF_8);
    }
    private MojoExecutionEvent surefire(String goal) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        return new MojoExecutionEvent(null, project, new MojoExecution(plugin, goal, "default-" + goal), new FakeSurefireMojo(reportsDirectory));
    }
    private MavenProject project(String artifactId, String packaging) {
        Model model = new Model();
        model.setGroupId("org.example");
        model.setArtifactId(artifactId);
        model.setPackaging(packaging);

        Build build = new Build();
        build.setDirectory(tempDir.resolve(artifactId).resolve("target").toString());
        model.setBuild(build);
        return new MavenProject(model);
    }
    /** stands in for surefire's mojo, which holds the evaluated reportsDirectory parameter in a field of that name */
    private static final class FakeSurefireMojo extends AbstractMojo {
        @SuppressWarnings("unused")
        private final File reportsDirectory;

        private FakeSurefireMojo(File reportsDirectory) {
            this.reportsDirectory = reportsDirectory;
        }
        @Override
        public void execute() {
        }
    }
}
