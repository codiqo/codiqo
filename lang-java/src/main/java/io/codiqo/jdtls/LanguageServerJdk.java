package io.codiqo.jdtls;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Splitter;

import io.codiqo.api.RunArgs;
import lombok.experimental.UtilityClass;

/**
 * The JDK the Eclipse JDT language server runs on: codiqo.javaHome, else the one running codiqo. Both plugins check it
 * when an analysis starts (DefaultLanguageProcessors.requireRuntime), so a JDK the server cannot start on ends the run
 * before anything is built, reviewed or paid for, with a message that says what to change, instead of failing after the
 * build with "JDT LSP process exited with code: 13".
 */
@UtilityClass
public class LanguageServerJdk {
    private static final int MIN_JDK = 21;

    /**
     * From jdt.ls 1.62.0 on, platform bundles the server cannot start without (org.eclipse.core.resources,
     * org.eclipse.core.filesystem, org.eclipse.buildship.core) require JavaSE 25. On an older JDK the OSGi framework
     * cannot resolve them and the server exits with code 13 before it answers. Measured by launching each archive on
     * JDK 21: 1.61.0 started, 1.62.0 and 1.63.0 exited with code 13.
     */
    private static final int MIN_JDK_FROM_1_62 = 25;
    private static final List<Integer> FIRST_NEEDING_JDK_25 = List.of(1, 62);

    /** the feature number of a JAVA_VERSION written before Java 9, which put a "1." in front of it ("1.8.0_392") */
    private static final String LEGACY_VERSION_PREFIX = "1.";

    /** the Java version the language server will run on, once it is one the server starts on */
    public Runtime.Version require(RunArgs args) throws IOException {
        Runtime.Version toReturn = detect(args.getJavaHome());
        String jdtlsVersion = args.effectiveJdtlsVersion();
        int minimum = minimum(jdtlsVersion);
        if (toReturn.feature() < minimum) {
            throw new IOException(String.format(Locale.ROOT,
                    "the Eclipse JDT language server %s needs Java %d or newer, but it would run on Java %s (%s)"
                            + " — point codiqo.javaHome at a Java %d+ JDK; the analysed project keeps its own release level",
                    jdtlsVersion,
                    minimum,
                    toReturn,
                    Optional.ofNullable(args.getJavaHome()).map(File::getAbsolutePath).orElse("the JDK running codiqo"),
                    minimum));
        }
        return toReturn;
    }
    /** the least Java feature release a language server release starts on; the version is jdt.ls's X.Y.Z */
    public int minimum(String jdtlsVersion) {
        List<Integer> parts = Splitter.on('.').trimResults().omitEmptyStrings().splitToStream(jdtlsVersion)
                .map(Integer::valueOf)
                .toList();
        for (int i = 0; i < FIRST_NEEDING_JDK_25.size(); i++) {
            int part = i < parts.size() ? parts.get(i) : 0;
            if (part != FIRST_NEEDING_JDK_25.get(i)) {
                return part > FIRST_NEEDING_JDK_25.get(i) ? MIN_JDK_FROM_1_62 : MIN_JDK;
            }
        }
        return MIN_JDK_FROM_1_62;
    }
    /**
     * A configured codiqo.javaHome is read from its release file and never replaced by the JVM running codiqo: the
     * server is started with that home's bin/java, so a home that is no JDK would otherwise pass this check and fail
     * only when the server starts, after the build.
     */
    private static Runtime.Version detect(File javaHome) throws IOException {
        if (Objects.isNull(javaHome)) {
            return Runtime.version();
        }
        File releaseFile = new File(javaHome, "release");
        if (releaseFile.isFile()) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(releaseFile.toPath())) {
                props.load(in);
            }
            return parse(javaHome, StringUtils.strip(props.getProperty("JAVA_VERSION"), "\""));
        }
        throw new IOException(String.format(Locale.ROOT,
                "codiqo.javaHome %s is not a JDK: it has no release file naming its Java version", javaHome.getAbsolutePath()));
    }
    private static Runtime.Version parse(File javaHome, String javaVersion) throws IOException {
        if (StringUtils.isBlank(javaVersion)) {
            throw new IOException(String.format(Locale.ROOT,
                    "codiqo.javaHome %s is not a JDK: its release file names no JAVA_VERSION", javaHome.getAbsolutePath()));
        }
        String version = javaVersion;
        if (version.startsWith(LEGACY_VERSION_PREFIX)) {
            version = StringUtils.substringBefore(version.substring(LEGACY_VERSION_PREFIX.length()), ".");
        }
        try {
            return Runtime.Version.parse(version);
        } catch (IllegalArgumentException err) {
            throw new IOException(String.format(Locale.ROOT,
                    "codiqo.javaHome %s names Java version '%s' in its release file, which is not a Java version", javaHome.getAbsolutePath(), javaVersion), err);
        }
    }
}
