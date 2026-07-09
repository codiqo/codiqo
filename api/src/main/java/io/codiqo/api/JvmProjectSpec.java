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

    Optional<String> artifactCoordinate(File classpathFile);
}
