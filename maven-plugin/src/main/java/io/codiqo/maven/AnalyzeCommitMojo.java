package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.client.model.AnalysisExcludeCategory;
import io.codiqo.submit.CommitExclusions;
import io.codiqo.submit.CommitExclusions.Exclusion;
import io.codiqo.util.JGit;
import io.codiqo.util.MemoryReport;
import lombok.Value;

@Mojo(name = "analyze-commit",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true,
        aggregator = true)
public class AnalyzeCommitMojo extends AbstractAnalyzeMojo {
    /**
     * back-off ladder mirroring how stale a developer's local snapshot copy may have been (Maven's default
     * updatePolicy is daily): each rung steps the resolution target further back from the commit instant
     */
    private static final List<Duration> TIME_MACHINE_BACKOFF_LADDER = List.of(
            Duration.ZERO,
            Duration.ofMinutes(15),
            Duration.ofHours(1),
            Duration.ofHours(4),
            Duration.ofDays(1));
    private static final String POM_FILE_NAME = "pom.xml";
    private static final String BUILD_ELEMENT = "build";
    private static final String DIRECTORY_ELEMENT_NAME = "directory";
    private static final Set<String> RESOURCE_ELEMENTS = Set.of("resource", "testResource");
    /** a pom under one of these is a test fixture or build output, never a reactor module */
    private static final Set<String> NON_MODULE_PATH_SEGMENTS = Set.of("src", "target");
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";
    /** In-module and deliberately absent, so maven copies nothing from it and m2e maps it without complaint. */
    private static final String NEUTRALISED_RESOURCE_DIR = "target/codiqo-out-of-module-resources";
    /** attribute-tolerant so a {@code <directory>} carrying e.g. {@code xml:space} is still seen */
    private static final Pattern DIRECTORY_ELEMENT = Pattern.compile("(<directory[^>]*>)([^<]*)(</directory>)");
    /**
     * per-attempt excerpt of the structured failure detail kept in the exclusion history — enough to carry the
     * actual compiler/model errors (not just the first line) without ballooning the persisted detail
     */
    private static final int ATTEMPT_DETAIL_LIMIT = 4096;
    private static final String ATTEMPT_SEPARATOR = StringUtils.repeat(CharUtils.LF, 2);

    @Parameter(property = "codiqo.commitId", required = true)
    private String commitId;

    @Parameter(property = "codiqo.firstParentOnly", defaultValue = "true")
    private boolean firstParentOnly;

    @Override
    protected void doPrepare(RunArgs args) throws Exception {
        super.doPrepare(args);

        args.setCommitId(commitId);
        args.setFirstParentOnly(firstParentOnly);
        resolveCommit(args, commitId);
    }
    @Override
    protected void doExecute(RunArgs args) throws Exception {
        Optional<Exclusion> excluded = CommitExclusions.beforeAnalysis(args);
        if (excluded.isPresent()) {
            getLog().warn(String.format("commit %s skipped: %s", commitId, excluded.get().getReason()));
            doExcludeAnalysis(commitId, excluded.get().getReason(), excluded.get().getCategory());
            return;
        }
        if (JGit.isMerge(args.getGit(), commitId)) {
            getLog().info(String.format("merge commit %s analyzed via first-parent delta, credited to side-branch author %s",
                    commitId, CommitExclusions.creditedAuthorEmail(args)));
        }

        File temp = Files.createTempDirectory("codiqo").toFile();
        temp.deleteOnExit();

        // clone to a temporary location, so the user's working directory is untouched and uncommitted files are excluded
        StopWatch stopWatch = StopWatch.createStarted();
        StoredConfig originalConfig = args.getGit().getConfig();
        args.setDefaultBranch(args.getGit().getBranch());

        String sourceUri = args.getGit().getDirectory().toURI().toString();
        ObjectId sourceHead = args.getGit().resolve(Constants.HEAD);

        Repository clone = new FileRepositoryBuilder().setGitDir(new File(temp, ".git")).build();
        clone.create(false);

        StoredConfig initialConfig = clone.getConfig();
        initialConfig.setString("remote", "origin", "url", sourceUri);
        initialConfig.save();

        try (Git tmpGit = Git.wrap(clone)) {
            tmpGit.fetch()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("+refs/*:refs/*"))
                    .call();
        }

        RefUpdate headUpdate = clone.updateRef(Constants.HEAD, true);
        headUpdate.setNewObjectId(sourceHead);
        headUpdate.forceUpdate();

        stopWatch.stop();
        getLog().info(String.format("cloned directory: %s for analysis in %s", temp.getAbsolutePath(), stopWatch.toString()));

        // the clone needs the original remote URLs so relative URLs still resolve during the build
        StoredConfig cloneConfig = clone.getConfig();
        try {
            for (String remote : originalConfig.getSubsections("remote")) {
                String url = originalConfig.getString("remote", remote, "url");
                String fetch = originalConfig.getString("remote", remote, "fetch");
                if (StringUtils.isNotEmpty(url)) {
                    cloneConfig.setString("remote", remote, "url", url);
                    args.getRemoteUrls().add(url);
                }
                if (StringUtils.isNotEmpty(fetch)) {
                    cloneConfig.setString("remote", remote, "fetch", fetch);
                }
            }
        } finally {
            cloneConfig.save();
        }

        try {
            // check the commit out exactly as it was: the clone is clean, with nothing untracked
            args.setGit(clone);
            try (Git git = Git.wrap(clone)) {
                git.clean().setCleanDirectories(true).setForce(true).call();
                git.reset().setMode(ResetCommand.ResetType.HARD).call();

                git.checkout().setForced(true).setName(commitId).call();
                getLog().info(String.format("checked out commit ID: %s", commitId));
            }

            /**
             * host-side ProjectBuilder calls (pre-flight, root and module model building) resolve snapshot parents
             * and BOM imports in THIS JVM, where the time-machine only applies when the extension is loaded via the
             * host maven.ext.class.path. Pin those calls to the commit instant, shifted by the successful attempt's
             * back-off offset, so a later snapshot deploy that breaks historical POM interpolation cannot fail the
             * analysis of an older commit.
             */
            if (args.isTimeMachineEnabled()) {
                args.setTimeMachineTargetOffset(Duration.ZERO);
                warnIfHostTimeMachineMissing();
            }

            ProjectBuildingRequest buildingReq = Maven.buildingRequest(mavenSession);
            Maven.pinMultiModuleProjectDirectory(buildingReq, clone.getWorkTree());
            if (Objects.nonNull(args.getTimeMachineTargetOffset())) {
                Maven.isolateRepositorySession(buildingReq);
            }
            /**
             * pre-flight host model building is advisory only: the host JVM lacks the analyzed project's
             * .mvn/extensions.xml environment, so its resolution failures are not authoritative — the fork build
             * decides, and a genuine dependency problem resurfaces there
             */
            if (resolveDependenciesOffline(args) instanceof BuildOutcome.Skipped skipped) {
                getLog().warn(String.format("pre-flight host model building failed (%s), deferring to fork build", skipped.getReason()));
            }

            /**
             * build with the time-machine snapshot resolver first, pinning each dependency's snapshot deploy closest
             * to the commit date. If that fails — the commit-date snapshot is unavailable, or the source no longer
             * compiles against it — step the resolution target back through the ladder, then fall back to latest
             * snapshots and analyze best-effort.
             */
            BuildOutcome buildOutcome = args.isTimeMachineEnabled()
                    ? buildWithBackoff(args, buildingReq)
                    : buildProject(args, invocationRequest(args, false, Duration.ZERO), buildingReq);
            if (buildOutcome instanceof BuildOutcome.Skipped skipped) {
                doDegradedAnalysis(args, skipped.getReason(), skipped.getCategory(), skipped.getDetail());
                return;
            }
            ProjectBuildingResult result = ((BuildOutcome.Proceeded) buildOutcome).getResult();

            Collection<MavenProject> reactors = new LinkedList<>();
            Optional<BuildOutcome.Skipped> moduleOutcome = buildAndCollectModules(
                    result.getProject(), clone.getWorkTree(), buildingReq, args, reactors);
            if (moduleOutcome.isPresent()) {
                BuildOutcome.Skipped skipped = moduleOutcome.get();
                doDegradedAnalysis(args, skipped.getReason(), skipped.getCategory(), skipped.getDetail());
                return;
            }
            relaxOutOfModuleResourceDirs(clone.getWorkTree());

            try (ClassGraphSpec scan = scanProjects(args, reactors)) {
                getLog().info(MemoryReport.snapshot("after classgraph scan"));
                args.setClassGraph(scan);
                super.doExecute(args);
            }
        } finally {
            clone.close();
            FileUtils.deleteDirectory(temp);
            /**
             * the private local repository deliberately outlives the analysis: it is shared by every commit of this
             * project and its seeded releases are what make the next commit cheap, so cleanup belongs at the end of
             * the run rather than here.
             */
        }
    }
    private BuildOutcome buildWithBackoff(RunArgs args, ProjectBuildingRequest buildingReq) throws Exception {
        Instant commitTimestamp = TimeMachineSupport.resolveCommitTimestamp(args);
        List<String> attemptFailures = new ArrayList<>();
        BuildOutcome.Skipped firstSkipped = null;
        Duration offset = Duration.ZERO;
        for (;;) {
            args.setTimeMachineTargetOffset(offset);
            BuildOutcome outcome = buildProject(args, invocationRequest(args, true, offset), buildingReq);
            if (outcome instanceof BuildOutcome.Proceeded) {
                return outcome;
            }

            /**
             * sidecars must be read before the next invocationRequest call, which deletes the superseded meta dir.
             * A dependency-resolution failure cannot be fixed by stepping further back, so the ladder short-circuits
             * to the latest-snapshots attempt.
             */
            BuildOutcome.Skipped skipped = (BuildOutcome.Skipped) outcome;
            if (Objects.isNull(firstSkipped)) {
                firstSkipped = skipped;
            }
            attemptFailures.add(attemptEntry("offset " + offset, skipped));

            /**
             * a timeout is the one failure the ladder must not answer with another attempt: it is no evidence that
             * the snapshot picks were wrong, and every further rung costs another full buildTimeout — more than the
             * deadline wrapping the whole commit can absorb.
             */
            if (skipped.isTimedOut()) {
                getLog().warn(String.format("commit %s: time-machine build timed out at offset %s, not retrying", commitId, offset));
                return new BuildOutcome.Skipped(
                        firstSkipped.getReason(), firstSkipped.getCategory(), attemptHistoryDetail(attemptFailures), true);
            }

            Optional<Duration> next;
            if (AnalysisExcludeCategory.DEPENDENCY_RESOLUTION_FAILURE == skipped.getCategory()) {
                next = Optional.empty();
            } else {
                List<Instant> pickedDeploys = TimeMachineBackoff.readPickedDeploys(args.getTimeMachineMetaDir());
                next = TimeMachineBackoff.nextOffset(TIME_MACHINE_BACKOFF_LADDER, offset, commitTimestamp, pickedDeploys);
            }
            if (next.isEmpty()) {
                getLog().warn(String.format("commit %s: time-machine build failed at offset %s (%s), retrying with latest snapshots", commitId, offset, skipped.getReason()));
                args.setTimeMachineTargetOffset(null);
                BuildOutcome latest = buildProject(args, invocationRequest(args, false, Duration.ZERO), buildingReq);
                if (latest instanceof BuildOutcome.Skipped lastSkipped) {
                    attemptFailures.add(attemptEntry("latest snapshots", lastSkipped));
                    /**
                     * the first attempt is the commit-faithful build, so its failure describes the commit's true
                     * state; later attempts fail against increasingly anachronistic picks. Headline the first, and
                     * keep the full ladder in the detail.
                     */
                    return new BuildOutcome.Skipped(firstSkipped.getReason(), firstSkipped.getCategory(), attemptHistoryDetail(attemptFailures));
                }
                return latest;
            }

            offset = next.get();
            getLog().warn(String.format("commit %s: time-machine build failed (%s), retrying with target offset %s", commitId, skipped.getReason(), offset));
        }
    }
    private static String attemptEntry(String label, BuildOutcome.Skipped skipped) {
        String toReturn = label + ": " + skipped.getReason();
        if (StringUtils.isNotBlank(skipped.getDetail())) {
            toReturn += CharUtils.LF + StringUtils.abbreviate(skipped.getDetail(), ATTEMPT_DETAIL_LIMIT);
        }
        return toReturn;
    }
    private static String attemptHistoryDetail(List<String> attemptFailures) {
        return "time-machine attempts:" + ATTEMPT_SEPARATOR + StringUtils.join(attemptFailures, ATTEMPT_SEPARATOR);
    }
    /**
     * Drops build resource directories that resolve outside their own module from the POMs of the CLONE, so the
     * language server can import the project. m2e cannot map such a directory into a workspace project — the whole
     * module then fails to configure, and a module with no workspace project answers every call-hierarchy query with
     * nothing, so blast radius silently reads zero
     * (<a href="https://github.com/eclipse-m2e/m2e-core/issues/1790">m2e-core#1790</a>).
     *
     * <p>Only applied to the throwaway clone and only to {@code <build>} resources, which contribute no source roots,
     * dependencies or classpath entries — so nothing the analysis measures changes.
     *
     * <p>Runs after every build attempt, never before one: an escaping {@code testResource} can still be one the
     * build needs, and tests are where coverage comes from. The import this exists for happens later still, when the
     * language server loads.
     */
    void relaxOutOfModuleResourceDirs(File workTree) throws IOException {
        Path root = workTree.toPath().normalize().toAbsolutePath();
        try (Stream<Path> poms = Files.walk(root)) {
            for (Path pom : poms.filter(path -> isBuildPom(root, path)).toList()) {
                stripEscapingResourceDirs(root, pom);
            }
        }
    }
    /**
     * Repoints every escaping directory at a path inside the module instead of deleting the element: m2e only needs
     * a legal in-project path, and maven skips a resource directory that is absent. Editing one text value keeps
     * every other byte of the POM as it was, where a DOM round-trip does not — re-serializing guava's root POM moved
     * its namespace declaration onto {@code <scm>} and maven then rejected the file. Which values to edit still
     * comes from the DOM, so formatting cannot change the outcome.
     */
    private void stripEscapingResourceDirs(Path workTree, Path pom) throws IOException {
        Path moduleDir = pom.getParent().normalize().toAbsolutePath();

        ResourceDirScan scan = scanResourceDirs(pom, moduleDir);
        if (CollectionUtils.isEmpty(scan.getRewritable())) {
            return;
        }

        Matcher matcher = DIRECTORY_ELEMENT.matcher(Files.readString(pom, scan.getCharset()));
        StringBuilder rewritten = new StringBuilder();
        boolean changed = false;
        while (matcher.find()) {
            if (scan.getRewritable().contains(StringUtils.trimToEmpty(matcher.group(2)))) {
                matcher.appendReplacement(rewritten,
                        Matcher.quoteReplacement(matcher.group(1) + NEUTRALISED_RESOURCE_DIR + matcher.group(3)));
                changed = true;
                getLog().info(String.format("repointing resource directory outside its module in %s/pom.xml: %s -> %s (m2e-core#1790)",
                        StringUtils.defaultIfEmpty(workTree.relativize(moduleDir).toString(), "."),
                        StringUtils.trimToEmpty(matcher.group(2)),
                        NEUTRALISED_RESOURCE_DIR));
            }
        }
        matcher.appendTail(rewritten);

        if (changed) {
            Files.writeString(pom, rewritten, scan.getCharset());
        }
    }
    /**
     * Read-only inspection: which {@code <build>} resource directory values of this POM resolve outside the module,
     * and in which charset the file has to be rewritten. A value that also appears outside a {@code <build>} resource
     * is reported and left alone: the two occurrences are indistinguishable in the text.
     */
    private ResourceDirScan scanResourceDirs(Path pom, Path moduleDir) {
        Set<String> escaping = new LinkedHashSet<>();
        Set<String> outsideBuild = new LinkedHashSet<>();
        Charset charset = StandardCharsets.UTF_8;
        try {
            Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(pom.toFile());

            /**
             * the prolog decides how the bytes were read, so it decides how they are written back: assuming UTF-8
             * either fails on an ISO-8859-1 POM or re-encodes it under a declaration that now lies. The declared
             * encoding comes first because getInputEncoding reports the parser's auto-detection, not the declaration.
             */
            charset = Optional.ofNullable(document.getXmlEncoding())
                    .or(() -> Optional.ofNullable(document.getInputEncoding()))
                    .map(Charset::forName)
                    .orElse(StandardCharsets.UTF_8);

            NodeList directories = document.getElementsByTagName(DIRECTORY_ELEMENT_NAME);
            for (int i = 0; i < directories.getLength(); i++) {
                Element directory = (Element) directories.item(i);
                String value = StringUtils.trimToEmpty(directory.getTextContent());
                if (isBuildResourceDirectory(directory)) {
                    if (escapesModule(value, moduleDir, pom)) {
                        escaping.add(value);
                    }
                } else {
                    outsideBuild.add(value);
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException err) {
            /**
             * a POM this cannot parse is one maven itself would reject, so leave it as it is and let the build report
             * the real problem rather than turning it into an XML error from here
             */
            getLog().warn(String.format("could not inspect %s for out-of-module resource directories: %s", pom, err.getMessage()));
        }

        Set<String> rewritable = new LinkedHashSet<>(escaping);
        for (String shared : CollectionUtils.intersection(escaping, outsideBuild)) {
            getLog().warn(String.format("leaving resource directory %s of %s as it is: the same path is configured outside <build> too, and the two cannot be told apart in the text — name the tree in codiqo.excludePaths if the import fails (m2e-core#1790)",
                    shared,
                    pom));
            rewritable.remove(shared);
        }
        return new ResourceDirScan(rewritable, charset);
    }
    private boolean escapesModule(String directory, Path moduleDir, Path pom) {
        if (StringUtils.isBlank(directory)) {
            return false;
        }

        String interpolated = interpolateModulePaths(directory, moduleDir);
        if (interpolated.contains("${")) {
            getLog().warn(String.format("cannot tell whether resource directory %s of %s stays inside its module: it interpolates a property this check does not evaluate — if the import fails with m2e-core#1790, name the tree in codiqo.excludePaths",
                    directory,
                    pom));
            return false;
        }

        Path resolved = Paths.get(interpolated);
        if (BooleanUtils.negate(resolved.isAbsolute())) {
            resolved = moduleDir.resolve(interpolated);
        }
        return BooleanUtils.negate(resolved.normalize().startsWith(moduleDir));
    }
    /**
     * A POM under a {@code src} or {@code target} segment is a test fixture or build output, not a reactor module.
     * Rewriting a fixture changes what the clone's own tests exercise, and the tests are where coverage comes from.
     */
    private static boolean isBuildPom(Path root, Path candidate) {
        if (BooleanUtils.negate(POM_FILE_NAME.equals(candidate.getFileName().toString()))) {
            return false;
        }
        for (Path segment : root.relativize(candidate)) {
            if (NON_MODULE_PATH_SEGMENTS.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }
    /**
     * Only a {@code <build>} resource counts: the great-grandparent check keeps a plugin execution's own
     * {@code <configuration><resources>} out of scope, since m2e never reads it.
     */
    private static boolean isBuildResourceDirectory(Element directory) {
        Node resource = directory.getParentNode();
        if (Objects.isNull(resource) || BooleanUtils.negate(RESOURCE_ELEMENTS.contains(resource.getNodeName()))) {
            return false;
        }

        Node resources = resource.getParentNode();
        if (Objects.isNull(resources) || Objects.isNull(resources.getParentNode())) {
            return false;
        }
        return BUILD_ELEMENT.equals(resources.getParentNode().getNodeName());
    }
    /**
     * The three properties a resource directory realistically uses. {@code ${project.basedir}/../shared} is the
     * common form of the escaping directory this whole pass exists for, so leaving it un-interpolated meant the
     * import kept failing with nothing logged; anything else is reported rather than guessed at.
     */
    private static String interpolateModulePaths(String directory, Path moduleDir) {
        String toReturn = Strings.CS.replace(directory, "${project.build.directory}", moduleDir.resolve("target").toString());
        toReturn = Strings.CS.replace(toReturn, "${project.basedir}", moduleDir.toString());
        return Strings.CS.replace(toReturn, "${basedir}", moduleDir.toString());
    }
    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory toReturn = DocumentBuilderFactory.newInstance();
        toReturn.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        toReturn.setFeature(DISALLOW_DOCTYPE_DECL, true);
        toReturn.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, StringUtils.EMPTY);
        toReturn.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, StringUtils.EMPTY);
        return toReturn;
    }
    @Value
    private static class ResourceDirScan {
        Set<String> rewritable;
        Charset charset;
    }
}
