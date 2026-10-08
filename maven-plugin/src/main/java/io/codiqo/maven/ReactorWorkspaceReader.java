package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.text.StringSubstitutor;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Resolves the modules of the analysed reactor to the commit's own files, the way Maven's ReactorReader does in an
 * ordinary multi-module build: a sibling's POM to the clone's {@code pom.xml}, its jar to what the fork build compiled.
 *
 * <p>
 * The host rebuilds every module's model in an isolated repository session, and without a workspace reader that session
 * resolves a sibling like any external artifact: through the time machine, as the repository snapshot deployed before the
 * commit. A commit that adds a nested class to one module and uses it in another therefore saw the sibling without that
 * class: PMD resolved the sibling from the snapshot deployed days before the commit, left the new type unresolved and
 * under-counted fan-out, and the result changed with whichever snapshot the local repository happened to hold. Answering sibling POMs locally also keeps the
 * reactor's
 * parent and imported-BOM chain on the commit's own lineage, which is what the per-module session isolation exists to
 * protect: remote snapshot POMs of siblings once leaked into the model cache and broke managed versions.
 *
 * <p>
 * The reader is filled in two steps, because model building itself resolves sibling POMs. {@link #fromPoms} registers
 * every module's POM from the raw XML, with no model building and no network, so the effective models that follow never
 * fetch a reactor POM remotely. {@link #add} then registers each effective model, whose interpolated output directories
 * answer the jar lookups. Only exact coordinates match: a sibling referenced at another version is a genuine external
 * dependency and keeps resolving from the repository. A module that is not registered, or whose output directory does not
 * exist, is not answered, which is Maven's own contract and leaves that module exactly as it resolved before this reader.
 */
public final class ReactorWorkspaceReader implements WorkspaceReader {
    private static final String POM_FILE = "pom.xml";
    private static final String POM_EXTENSION = "pom";
    private static final String JAR_EXTENSION = "jar";
    private static final String TESTS_CLASSIFIER = "tests";

    private final WorkspaceRepository repository = new WorkspaceRepository("codiqo-reactor");
    private final Map<Triple<String, String, String>, File> poms = Maps.newHashMap();
    private final Map<Triple<String, String, String>, MavenProject> projects = Maps.newHashMap();

    @VisibleForTesting
    public ReactorWorkspaceReader() {}
    /**
     * Registers an effective module model. Its POM is registered as well, so a module whose raw coordinates were
     * expressions (CI-friendly {@code ${revision}} versions) becomes answerable once its model is interpolated.
     */
    @VisibleForTesting
    public void add(MavenProject project) {
        Triple<String, String, String> coordinates = Triple.of(project.getGroupId(), project.getArtifactId(), project.getVersion());
        projects.put(coordinates, project);
        poms.put(coordinates, project.getFile());
    }
    @Override
    public WorkspaceRepository getRepository() {
        return repository;
    }
    @Override
    public File findArtifact(Artifact artifact) {
        Triple<String, String, String> coordinates = Triple.of(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion());
        File toReturn = null;
        if (POM_EXTENSION.equals(artifact.getExtension())) {
            toReturn = poms.get(coordinates);
        } else if (JAR_EXTENSION.equals(artifact.getExtension()) && projects.containsKey(coordinates)) {
            toReturn = outputDirectory(projects.get(coordinates), artifact.getClassifier());
        }
        return toReturn;
    }
    @Override
    public List<String> findVersions(Artifact artifact) {
        List<String> toReturn = Lists.newArrayList();
        for (Triple<String, String, String> coordinates : poms.keySet()) {
            if (BooleanUtils.and(new boolean[] { coordinates.getLeft().equals(artifact.getGroupId()), coordinates.getMiddle().equals(artifact.getArtifactId()) })) {
                toReturn.add(coordinates.getRight());
            }
        }
        return toReturn;
    }
    /**
     * Reads the coordinates of every module reachable from the root POM straight from the XML: the {@code <modules>} of
     * every POM and of every profile in it, so a module only a profile activates is known too. A module POM missing at
     * this commit is skipped, as the module walk skips it. A POM that cannot be read is reported to {@code warnings} and
     * left out, with its sub-modules: those modules then resolve as they did before this reader existed, rather than the
     * reader failing an analysis that would otherwise succeed.
     */
    public static ReactorWorkspaceReader fromPoms(File rootPom, Consumer<String> warnings) {
        ReactorWorkspaceReader toReturn = new ReactorWorkspaceReader();
        toReturn.readPom(rootPom, Objects.requireNonNull(warnings));
        return toReturn;
    }
    private void readPom(File pom, Consumer<String> warnings) {
        Model model;
        try (InputStream in = Files.newInputStream(pom.toPath())) {
            model = new MavenXpp3Reader().read(in);
        } catch (IOException | XmlPullParserException err) {
            warnings.accept(String.format("reactor POM %s not readable, its modules resolve from the repository: %s", pom, err.getMessage()));
            return;
        }

        String groupId = model.getGroupId();
        String version = model.getVersion();
        if (Objects.nonNull(model.getParent())) {
            groupId = StringUtils.defaultIfBlank(groupId, model.getParent().getGroupId());
            version = StringUtils.defaultIfBlank(version, model.getParent().getVersion());
        }
        if (isLiteral(groupId, model.getArtifactId(), version)) {
            poms.put(Triple.of(groupId, model.getArtifactId(), version), pom);
        }

        Set<String> modules = Sets.newLinkedHashSet(model.getModules());
        for (Profile profile : model.getProfiles()) {
            modules.addAll(profile.getModules());
        }
        for (String module : modules) {
            File modulePom = new File(new File(pom.getParentFile(), module), POM_FILE);
            if (modulePom.isFile()) {
                readPom(modulePom, warnings);
            }
        }
    }
    private static File outputDirectory(MavenProject project, String classifier) {
        String directory = null;
        if (StringUtils.isEmpty(classifier)) {
            directory = project.getBuild().getOutputDirectory();
        } else if (TESTS_CLASSIFIER.equals(classifier)) {
            directory = project.getBuild().getTestOutputDirectory();
        }
        if (Objects.nonNull(directory) && new File(directory).isDirectory()) {
            return new File(directory);
        }
        return null;
    }
    private static boolean isLiteral(String... values) {
        return StringUtils.isNoneBlank(values) && Arrays.stream(values).noneMatch(value -> value.contains(StringSubstitutor.DEFAULT_VAR_START));
    }
}
