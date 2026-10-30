package io.codiqo.jdtls;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Comparators;
import com.google.common.collect.Lists;

import io.codiqo.api.RunArgs;
import io.codiqo.util.RuntimeRequirementException;
import lombok.experimental.UtilityClass;

/**
 * The JDK the Eclipse JDT language server runs on: codiqo.javaHome, else the one running codiqo. Checked before a
 * commit's full analysis is paid for (DefaultLanguageProcessors.requireRuntime): on Maven before the commit is built,
 * on Gradle, which builds before its worker starts, before the language server is downloaded and the review starts. A
 * JDK the server cannot start on then ends the run with a message that says what to change, instead of failing later
 * with "JDT LSP process exited with code: 13", and halts nothing (RuntimeRequirementException), since only a change to
 * the machine fixes it and no review was paid for.
 */
@UtilityClass
public class LanguageServerJdk {
    private static final int MIN_JDK = 21;

    /**
     * From jdt.ls 1.62.0 on, platform bundles the server cannot start without (org.eclipse.core.resources,
     * org.eclipse.core.filesystem, org.eclipse.buildship.core) require JavaSE 25. On an older JDK the OSGi framework
     * cannot resolve them and the server exits with code 13 before it answers. Measured by launching each archive on
     * JDK 21: 1.61.0 started, 1.62.0 and 1.63.0 exited with code 13. The snapshots channel builds from the newest line,
     * and a version that names no release number is taken for a new one, so both need Java 25 too.
     */
    private static final int MIN_JDK_FROM_1_62 = 25;
    private static final List<Integer> FIRST_NEEDING_JDK_25 = List.of(1, 62);
    private static final Comparator<Iterable<Integer>> VERSION_ORDER = Comparators.lexicographical(Comparator.<Integer> naturalOrder());
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d{1,9}");

    /** the feature number of a JAVA_VERSION written before Java 9, which put a "1." in front of it ("1.8.0_392") */
    private static final String LEGACY_VERSION_PREFIX = "1.";

    /**
     * The JDK the language server will run on, once it is one the server starts on. The server's version is the
     * configured one, never resolved here: resolving it downloads latest.txt for the snapshots channel or a pinned
     * build missing from the cache, and an outage of download.eclipse.org must not fail a run that may start no server.
     * A JDK that meets the highest minimum needs no version at all.
     */
    public ServerJdk require(RunArgs args) throws IOException {
        ServerJdk toReturn = detect(args.getJavaHome());
        if (toReturn.getVersion().feature() < MIN_JDK_FROM_1_62) {
            String server = args.isJdtlsUseSnapshot()
                    ? "from the snapshots channel"
                    : StringUtils.defaultIfBlank(args.getJdtlsVersion(), RunArgs.JDTLS_MIRRORED_VERSION);
            int minimum = args.isJdtlsUseSnapshot() ? MIN_JDK_FROM_1_62 : minimum(server);
            if (toReturn.getVersion().feature() < minimum) {
                throw new RuntimeRequirementException(String.format(Locale.ROOT,
                        "the Eclipse JDT language server %s needs Java %d or newer, but it would run on Java %s (%s)"
                                + " — point codiqo.javaHome at a Java %d+ JDK; the analysed project keeps its own release level",
                        server,
                        minimum,
                        toReturn.getVersion(),
                        Optional.ofNullable(args.getJavaHome()).map(File::getAbsolutePath).orElse("the JDK running codiqo"),
                        minimum));
            }
        }
        return toReturn;
    }
    /**
     * The least Java feature release a language server release starts on. The leading number of each dot-separated part
     * is read up to the first part without one, so "1.63.0-SNAPSHOT" reads as 1.63.0 and "1.61.0.v2026" as 1.61.0; a
     * version with no number at all is taken for a new release. A part's number is read from at most its first nine
     * digits, which always fit an int: a build stamp such as 202610300819 would overflow one, and a number that long is
     * past every release this check knows anyway.
     */
    @VisibleForTesting
    public int minimum(String jdtlsVersion) {
        List<Integer> parts = Lists.newArrayList();
        for (String part : Splitter.on('.').trimResults().split(jdtlsVersion)) {
            Matcher number = LEADING_NUMBER.matcher(part);
            if (number.find()) {
                parts.add(Integer.valueOf(number.group()));
            } else {
                break;
            }
        }
        if (BooleanUtils.or(new boolean[] { parts.isEmpty(), VERSION_ORDER.compare(parts, FIRST_NEEDING_JDK_25) >= 0 })) {
            return MIN_JDK_FROM_1_62;
        }
        return MIN_JDK;
    }
    /**
     * A configured codiqo.javaHome is read from its release file and never replaced by the JVM running codiqo: the
     * server is started with that home's bin/java, so a home that is no JDK would otherwise pass this check and fail
     * only when the server starts. A home without a release file of its own is resolved through bin/java's real path:
     * Homebrew's opt/openjdk@25 links bin/java into libexec/openjdk.jdk/Contents/Home, which is the JDK home the server
     * must also register as its runtime (JdtLspProcess.runtimeWithoutSources), since the keg root is none.
     */
    private static ServerJdk detect(File javaHome) throws IOException {
        if (Objects.isNull(javaHome)) {
            return new ServerJdk(SystemUtils.getJavaHome(), Runtime.version());
        }
        Optional<Path> home = jdkHome(javaHome.toPath());
        if (home.isPresent()) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(home.get().resolve("release"))) {
                props.load(in);
            }
            return new ServerJdk(home.get().toFile(), parse(javaHome, StringUtils.strip(props.getProperty("JAVA_VERSION"), "\"")));
        }
        throw new RuntimeRequirementException(String.format(Locale.ROOT,
                "codiqo.javaHome %s is not a JDK: neither it nor the JDK its bin/java belongs to has a release file naming its Java version",
                javaHome.getAbsolutePath()));
    }
    private static Optional<Path> jdkHome(Path javaHome) throws IOException {
        if (Files.isRegularFile(javaHome.resolve("release"))) {
            return Optional.of(javaHome);
        }
        Path java = javaHome.resolve("bin").resolve("java");
        if (Files.isRegularFile(java)) {
            Path linked = java.toRealPath().getParent().getParent();
            if (Files.isRegularFile(linked.resolve("release"))) {
                return Optional.of(linked);
            }
        }
        return Optional.empty();
    }
    private static Runtime.Version parse(File javaHome, String javaVersion) throws IOException {
        if (StringUtils.isBlank(javaVersion)) {
            throw new RuntimeRequirementException(String.format(Locale.ROOT,
                    "codiqo.javaHome %s is not a JDK: its release file names no JAVA_VERSION", javaHome.getAbsolutePath()));
        }
        String version = javaVersion;
        if (version.startsWith(LEGACY_VERSION_PREFIX)) {
            version = StringUtils.substringBefore(version.substring(LEGACY_VERSION_PREFIX.length()), ".");
        }
        try {
            return Runtime.Version.parse(version);
        } catch (IllegalArgumentException err) {
            throw new RuntimeRequirementException(String.format(Locale.ROOT,
                    "codiqo.javaHome %s names Java version '%s' in its release file, which is not a Java version", javaHome.getAbsolutePath(), javaVersion));
        }
    }
    /** the JDK home the language server runs on, resolved past any link to the JDK itself, and its Java version */
    public static final class ServerJdk extends ImmutablePair<File, Runtime.Version> {
        public ServerJdk(File home, Runtime.Version version) {
            super(home, version);
        }
        public File getHome() {
            return getLeft();
        }
        public Runtime.Version getVersion() {
            return getRight();
        }
    }
}
