package io.codiqo.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Lists;

/**
 * A sibling module must resolve to the commit's own files, never to a repository snapshot: a snapshot deployed before
 * the commit lacks any class the commit adds to the sibling, so PMD leaves that class unresolved. The raw POM
 * reading must also never be the reason an analysis fails, since the module builds it precedes once succeeded without it.
 */
class ReactorWorkspaceReaderTest {
    private static final String GROUP = "com.example";
    private static final String VERSION = "2.0.104-SNAPSHOT";

    @TempDir
    Path dir;

    @Test
    void aSiblingJarResolvesToItsCompiledClasses() throws Exception {
        MavenProject api = module("api", true);
        ReactorWorkspaceReader reader = new ReactorWorkspaceReader();
        reader.add(api);

        assertEquals(new File(api.getBuild().getOutputDirectory()), reader.findArtifact(new DefaultArtifact(GROUP, "api", "jar", VERSION)));
    }
    @Test
    void aTimeMachinePinnedSnapshotStillMatchesByItsBaseVersion() throws Exception {
        MavenProject api = module("api", true);
        ReactorWorkspaceReader reader = new ReactorWorkspaceReader();
        reader.add(api);

        File resolved = reader.findArtifact(new DefaultArtifact(GROUP, "api", "jar", "2.0.104-20260821.122130-8"));

        assertEquals(new File(api.getBuild().getOutputDirectory()), resolved);
    }
    @Test
    void theTestJarResolvesToTheTestClassesAndThePomToTheModulePom() throws Exception {
        MavenProject api = module("api", true);
        ReactorWorkspaceReader reader = new ReactorWorkspaceReader();
        reader.add(api);

        assertEquals(new File(api.getBuild().getTestOutputDirectory()), reader.findArtifact(new DefaultArtifact(GROUP, "api", "tests", "jar", VERSION)));
        assertEquals(api.getFile(), reader.findArtifact(new DefaultArtifact(GROUP, "api", "pom", VERSION)));
    }
    @Test
    void anotherVersionOfASiblingIsAnExternalDependency() throws Exception {
        ReactorWorkspaceReader reader = new ReactorWorkspaceReader();
        reader.add(module("api", true));

        assertNull(reader.findArtifact(new DefaultArtifact(GROUP, "api", "jar", "2.0.90")));
        assertNull(reader.findArtifact(new DefaultArtifact(GROUP, "unrelated", "jar", VERSION)));
    }
    @Test
    void aModuleTheBuildDidNotCompileIsLeftToTheRepository() throws Exception {
        ReactorWorkspaceReader reader = new ReactorWorkspaceReader();
        reader.add(module("api", false));

        assertNull(reader.findArtifact(new DefaultArtifact(GROUP, "api", "jar", VERSION)));
    }
    @Test
    void theTimeMachineIsToldTheSiblingIsInTheWorkspace() throws Exception {
        ReactorWorkspaceReader reader = new ReactorWorkspaceReader();
        reader.add(module("api", true));

        assertTrue(reader.findVersions(new DefaultArtifact(GROUP, "api", "jar", VERSION)).contains(VERSION));
        assertTrue(reader.findVersions(new DefaultArtifact(GROUP, "unrelated", "jar", VERSION)).isEmpty());
    }
    @Test
    void rawPomsAnswerSiblingPomsWithoutAnyModelBuilding() throws Exception {
        Path root = dir.resolve("reactor");
        pom(root, """
                <groupId>com.example</groupId><artifactId>parent</artifactId><version>2.0.104-SNAPSHOT</version><packaging>pom</packaging>
                <modules><module>bom</module><module>missing</module></modules>
                <profiles><profile><id>extra</id><modules><module>extra</module></modules></profile></profiles>""");
        pom(root.resolve("bom"), """
                <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>2.0.104-SNAPSHOT</version></parent>
                <artifactId>bom</artifactId><packaging>pom</packaging>""");
        pom(root.resolve("extra"), """
                <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>2.0.104-SNAPSHOT</version></parent>
                <artifactId>extra</artifactId>""");

        ReactorWorkspaceReader reader = ReactorWorkspaceReader.fromPoms(root.resolve("pom.xml").toFile(), Lists.<String>newArrayList()::add);

        assertEquals(root.resolve("bom/pom.xml").toFile(), reader.findArtifact(new DefaultArtifact(GROUP, "bom", "pom", VERSION)),
                "the inherited coordinates of an imported BOM resolve to the clone's own POM");
        assertEquals(root.resolve("extra/pom.xml").toFile(), reader.findArtifact(new DefaultArtifact(GROUP, "extra", "pom", VERSION)),
                "a module only a profile lists is still part of the reactor");
        assertNull(reader.findArtifact(new DefaultArtifact(GROUP, "extra", "jar", VERSION)),
                "jars are answered only once the effective model gives the output directory");
    }
    @Test
    void anExpressionVersionOrAnUnreadablePomIsLeftAsItResolvedBefore() throws Exception {
        Path root = dir.resolve("ci-friendly");
        pom(root, """
                <groupId>com.example</groupId><artifactId>parent</artifactId><version>${revision}</version><packaging>pom</packaging>
                <modules><module>broken</module></modules>""");
        Files.createDirectories(root.resolve("broken"));
        Files.writeString(root.resolve("broken/pom.xml"), "<project><unclosed>", StandardCharsets.UTF_8);
        List<String> warnings = Lists.newArrayList();

        ReactorWorkspaceReader reader = ReactorWorkspaceReader.fromPoms(root.resolve("pom.xml").toFile(), warnings::add);

        assertTrue(reader.findVersions(new DefaultArtifact(GROUP, "parent", "pom", VERSION)).isEmpty());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    private MavenProject module(String artifactId, boolean compiled) throws Exception {
        Path base = dir.resolve(artifactId);
        Path classes = base.resolve("target/classes");
        Path testClasses = base.resolve("target/test-classes");
        if (compiled) {
            Files.createDirectories(classes);
            Files.createDirectories(testClasses);
        }

        Build build = new Build();
        build.setOutputDirectory(classes.toString());
        build.setTestOutputDirectory(testClasses.toString());

        Model model = new Model();
        model.setGroupId(GROUP);
        model.setArtifactId(artifactId);
        model.setVersion(VERSION);
        model.setBuild(build);

        MavenProject toReturn = new MavenProject(model);
        toReturn.setFile(base.resolve("pom.xml").toFile());
        return toReturn;
    }
    private static void pom(Path moduleDir, String body) throws Exception {
        Files.createDirectories(moduleDir);
        Files.writeString(moduleDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + body + "</project>", StandardCharsets.UTF_8);
    }
}
