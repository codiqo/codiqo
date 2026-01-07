package io.codiqo.api;

import java.io.File;
import java.util.Collection;
import java.util.Optional;

public interface Project {
    String getName();
    String getDescription();
    String getVersion();
    File getBaseDirectory();
    File getOutputDirectory();
    Collection<File> getCompileSourceRoots();
    Collection<File> getCompileClasspathElements();
    Collection<File> getTestCompileSourceRoots();
    Collection<File> getTestClasspathElements();
    Optional<File> coverage();
}
