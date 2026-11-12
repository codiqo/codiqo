package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
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
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.text.StringSubstitutor;
import org.apache.maven.plugin.logging.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.google.common.collect.Sets;

/** The resource directories of the analysis clone's POMs that the language server's import cannot map. */
public final class OutOfModuleResources {
    private static final String POM_FILE_NAME = "pom.xml";
    private static final String BUILD_ELEMENT = "build";
    private static final String DIRECTORY_ELEMENT_NAME = "directory";
    private static final Set<String> RESOURCE_ELEMENTS = Set.of("resource", "testResource");
    private static final Set<String> NON_MODULE_PATH_SEGMENTS = Set.of("src", "target");
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";
    /** In-module and deliberately absent, so maven copies nothing from it and m2e maps it without complaint. */
    private static final String NEUTRALISED_RESOURCE_DIR = "target/codiqo-out-of-module-resources";
    /**
     * Attribute-tolerant, so a {@code <directory>} carrying an attribute such as {@code xml:space} is still matched;
     * a pattern for the bare element would leave that directory escaping the module.
     */
    private static final Pattern DIRECTORY_ELEMENT = Pattern.compile("(<directory[^>]*>)([^<]*)(</directory>)");

    private final Log log;

    public OutOfModuleResources(Log log) {
        this.log = Objects.requireNonNull(log);
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
    public void relax(File workTree) throws IOException {
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
                log.info(String.format("repointing resource directory outside its module in %s/pom.xml: %s -> %s (m2e-core#1790)",
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
        Set<String> escaping = Sets.newLinkedHashSet();
        Set<String> outsideBuild = Sets.newLinkedHashSet();
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
            log.warn(String.format("could not inspect %s for out-of-module resource directories: %s", pom, err.getMessage()));
        }

        Set<String> rewritable = Sets.newLinkedHashSet(escaping);
        for (String shared : CollectionUtils.intersection(escaping, outsideBuild)) {
            log.warn(String.format("leaving resource directory %s of %s as it is: the same path is configured outside <build> too, and the two cannot be told apart in the text — name the tree in codiqo.excludePaths if the import fails (m2e-core#1790)",
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
        if (interpolated.contains(StringSubstitutor.DEFAULT_VAR_START)) {
            log.warn(String.format("cannot tell whether resource directory %s of %s stays inside its module: it interpolates a property this check does not evaluate — if the import fails with m2e-core#1790, name the tree in codiqo.excludePaths",
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
        Map<String, String> paths = Map.of(
                "project.build.directory", moduleDir.resolve("target").toString(),
                "project.basedir", moduleDir.toString(),
                "basedir", moduleDir.toString());
        /**
         * The value delimiter is switched off because Maven has no ${name:-default} form: with it on, a directory such
         * as ${env.OUT:-x} would be rewritten to x, which Maven itself never does. A property this map does not know is
         * left exactly as written.
         */
        return new StringSubstitutor(paths).setValueDelimiterMatcher(null).setPreserveEscapes(true).replace(directory);
    }
    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory toReturn = DocumentBuilderFactory.newInstance();
        toReturn.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        toReturn.setFeature(DISALLOW_DOCTYPE_DECL, true);
        toReturn.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, StringUtils.EMPTY);
        toReturn.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, StringUtils.EMPTY);
        return toReturn;
    }
    private static final class ResourceDirScan extends ImmutablePair<Set<String>, Charset> {
        public ResourceDirScan(Set<String> rewritable, Charset charset) {
            super(rewritable, charset);
        }
        public Set<String> getRewritable() {
            return getLeft();
        }
        public Charset getCharset() {
            return getRight();
        }
    }
}
