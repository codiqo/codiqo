package io.codiqo.api;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface JvmProjectSpec extends ProjectSpec, ClassGraphSpec {
    String getGroupId();
    String getArtifactId();
    String getPackaging();
    Optional<String> parent();
    Map<String, String> getProperties();
    Collection<File> getCompileSourceRoots();
    Collection<File> getCompileClasspathElements();
    Collection<File> getTestCompileSourceRoots();
    Collection<File> getTestClasspathElements();

    /**
     * Where this module's test run writes its JUnit XML, as reported by the build tool that owns the layout —
     * surefire's and failsafe's {@code reportsDirectory} on Maven, every {@code Test} task's
     * {@code reports.junitXml} location on Gradle. Neither is derivable from the compiled-output directory, so the
     * build side has to hand it over: it is the only evidence that a module's test fork actually ran.
     */
    Collection<File> getTestReportDirectories();

    Optional<String> artifactCoordinate(File classpathFile);
}
