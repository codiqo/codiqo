package io.codiqo.jdtls;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Comparators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import lombok.experimental.UtilityClass;

/**
 * The JDK the Eclipse JDT language server runs on: codiqo.javaHome, else the one running codiqo. Checked twice: once
 * before a commit's full analysis is paid for (DefaultLanguageProcessors.requireRuntime, which makes a refusal a
 * RuntimeRequirementException that halts nothing), and again by JdtLspProcess as the server starts, where a refusal is
 * an ordinary failure. A JDK the server cannot start on then ends the run with a message that says what to change,
 * instead of failing later with "JDT LSP process exited with code: 13".
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

    private static final String JAVA_HOME = "java.home";
    private static final String JAVA_SPECIFICATION_VERSION = "java.specification.version";

    /** one "    name = value" line of -XshowSettings:properties, for the two settings the check reads */
    private static final Pattern SETTING = Pattern.compile("^\\s+(java\\.home|java\\.specification\\.version) = (.+)$");

    /** the specification version a JDK before 9 reports, which puts "1." in front of its feature release ("1.8") */
    private static final String LEGACY_VERSION_PREFIX = "1.";

    /**
     * A JVM that only prints its settings exits in about a tenth of a second; the limit is there for a JDK that hangs
     * as it starts (a broken install, a blocked file system), which would otherwise hold the run with nothing logged.
     */
    private static final Duration SETTINGS_TIMEOUT = Duration.ofSeconds(30);

    /** how much of a JVM's output a failure quotes: its own error comes first, and the rest is its usage text */
    private static final int SETTINGS_FAILURE_PREVIEW = 500;

    /**
     * The JDK the language server will run on, once it is one the server starts on. The server's version is read as
     * configured, exactly as RunArgs downloads it, and never resolved here: resolving it downloads latest.txt for the
     * snapshots channel or a pinned build missing from the cache, and an outage of download.eclipse.org must not fail a
     * run that may start no server.
     */
    public ServerJdk require(RunArgs args) throws IOException {
        ServerJdk toReturn = detect(args.getJavaHome());

        int minimum;
        String server;
        if (args.isJdtlsUseSnapshot()) {
            minimum = MIN_JDK_FROM_1_62;
            server = "from the snapshots channel";
        } else {
            minimum = minimum(args.getJdtlsVersion());
            server = "version '" + args.getJdtlsVersion() + "'";
        }

        if (toReturn.getFeature() >= minimum) {
            return toReturn;
        }
        throw new IOException(String.format(Locale.ROOT,
                "the Eclipse JDT language server %s needs Java %d or newer, but it would run on Java %d (%s)"
                        + " — point codiqo.javaHome at a Java %d+ JDK; the analysed project keeps its own release level",
                server,
                minimum,
                toReturn.getFeature(),
                Optional.ofNullable(args.getJavaHome()).map(File::getAbsolutePath).orElse("the JDK running codiqo"),
                minimum));
    }
    /**
     * The least Java feature release a language server release starts on. The leading number of each dot-separated part
     * is read up to the first part without one, so "1.63.0-SNAPSHOT" reads as 1.63.0 and "1.61.0.v2026" as 1.61.0; a
     * version with no number at all, a blank one included, is taken for a new release. A part's number is read from at
     * most its first nine digits, which always fit an int: a build stamp such as 202610300819 would overflow one, and a
     * number that long is past every release this check knows anyway.
     */
    @VisibleForTesting
    public int minimum(String jdtlsVersion) {
        List<Integer> parts = Lists.newArrayList();
        for (String part : Splitter.on('.').trimResults().split(StringUtils.defaultString(jdtlsVersion))) {
            Matcher number = LEADING_NUMBER.matcher(part);
            if (number.find()) {
                parts.add(Integer.valueOf(number.group()));
            } else {
                break;
            }
        }
        if (BooleanUtils.or(new boolean[] { CollectionUtils.isEmpty(parts), VERSION_ORDER.compare(parts, FIRST_NEEDING_JDK_25) >= 0 })) {
            return MIN_JDK_FROM_1_62;
        }
        return MIN_JDK;
    }
    /**
     * A configured codiqo.javaHome is asked for its own java.home and java.specification.version, by running its
     * bin/java with -XshowSettings:properties, and never replaced by the JVM running codiqo: the server is started with
     * that bin/java, so a home that is no JDK would otherwise pass this check and fail only when the server starts. The
     * reported java.home is the JDK's real home, which is also the runtime the server must register
     * (JdtLspProcess.runtimeWithoutSources): Homebrew's opt/openjdk@25 only links bin/java into
     * libexec/openjdk.jdk/Contents/Home, and holds no lib directory of its own.
     */
    private static ServerJdk detect(File javaHome) throws IOException {
        if (Objects.isNull(javaHome)) {
            return new ServerJdk(SystemUtils.getJavaHome(), Runtime.version().feature());
        }
        Path java = javaHome.toPath().resolve("bin").resolve(SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java");
        if (Files.isExecutable(java)) {
            Map<String, String> settings = settings(java);
            String home = settings.get(JAVA_HOME);
            String release = Strings.CS.removeStart(settings.get(JAVA_SPECIFICATION_VERSION), LEGACY_VERSION_PREFIX);
            if (BooleanUtils.and(new boolean[] { StringUtils.isNotBlank(home), NumberUtils.isDigits(release) })) {
                return new ServerJdk(new File(home), Integer.parseInt(release));
            }
            throw new IOException(String.format(Locale.ROOT,
                    "codiqo.javaHome %s is not a JDK: %s reported java.home '%s' and java.specification.version '%s'",
                    javaHome.getAbsolutePath(), java, home, settings.get(JAVA_SPECIFICATION_VERSION)));
        }
        throw new IOException(String.format(Locale.ROOT, "codiqo.javaHome %s is not a JDK: it has no executable %s", javaHome.getAbsolutePath(), java));
    }
    /**
     * The settings -XshowSettings:properties prints to the error stream. The child JVM writes them in the platform's
     * native encoding, which this JVM shares, so a home path outside ASCII reads back as it was written.
     */
    private static Map<String, String> settings(Path java) throws IOException {
        File output = Files.createTempFile("codiqo-java-settings", ".txt").toFile();
        try {
            Process process = new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version").redirectErrorStream(true).redirectOutput(output).start();
            if (process.waitFor(SETTINGS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                String printed = FileUtils.readFileToString(output, Charset.forName(System.getProperty("native.encoding")));
                if (process.exitValue() == 0) {
                    Map<String, String> toReturn = Maps.newHashMap();
                    printed.lines().map(SETTING::matcher).filter(Matcher::matches).forEach(setting -> toReturn.putIfAbsent(setting.group(1), setting.group(2).strip()));
                    return toReturn;
                }
                throw new IOException(String.format(Locale.ROOT, "%s -XshowSettings:properties -version failed (exit %d): %s",
                        java, process.exitValue(), StringUtils.abbreviate(StringUtils.normalizeSpace(printed), SETTINGS_FAILURE_PREVIEW)));
            }
            process.destroyForcibly();
            throw new IOException(String.format(Locale.ROOT, "%s -XshowSettings:properties -version did not finish within %s", java, SETTINGS_TIMEOUT));
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException(err.getMessage(), err);
        } finally {
            FileUtils.deleteQuietly(output);
        }
    }
    /** the JDK home the language server runs on, as that JDK reports its java.home, and its Java feature release */
    public static final class ServerJdk extends ImmutablePair<File, Integer> {
        public ServerJdk(File home, int feature) {
            super(home, feature);
        }
        public File getHome() {
            return getLeft();
        }
        public int getFeature() {
            return getRight();
        }
    }
}
