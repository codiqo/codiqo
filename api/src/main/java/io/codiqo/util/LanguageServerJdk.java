package io.codiqo.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;

import com.google.common.annotations.VisibleForTesting;

import io.codiqo.api.RunArgs;
import lombok.experimental.UtilityClass;

/**
 * The JDK the Eclipse JDT language server runs on: codiqo.javaHome, else the one running codiqo. Checked where the
 * language server is downloaded ({@link Fetch#downloadJdtls}), which both plugins do before the review starts, so a JDK
 * the server cannot start on ends the run before anything is reviewed or paid for, with a message that says what to
 * change, instead of failing after the build with "JDT LSP process exited with code: 13".
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
    private static final ComparableVersion FIRST_NEEDING_JDK_25 = new ComparableVersion("1.62.0");

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
                    Objects.nonNull(args.getJavaHome()) ? args.getJavaHome().getAbsolutePath() : "the JDK running codiqo",
                    minimum));
        }
        return toReturn;
    }
    @VisibleForTesting
    public int minimum(String jdtlsVersion) {
        if (new ComparableVersion(jdtlsVersion).compareTo(FIRST_NEEDING_JDK_25) >= 0) {
            return MIN_JDK_FROM_1_62;
        }
        return MIN_JDK;
    }
    public Runtime.Version detect(File javaHome) throws IOException {
        if (Objects.nonNull(javaHome)) {
            File releaseFile = new File(javaHome, "release");
            if (releaseFile.isFile()) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(releaseFile.toPath())) {
                    props.load(in);
                }
                String version = StringUtils.strip(props.getProperty("JAVA_VERSION"), "\"");
                if (StringUtils.isNotBlank(version)) {
                    return Runtime.Version.parse(version);
                }
            }
        }
        return Runtime.version();
    }
}
