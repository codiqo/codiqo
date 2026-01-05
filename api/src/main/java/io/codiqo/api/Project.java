package io.codiqo.api;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;

public interface Project {
    String getName();
    String getDescription();
    String getVersion();
    Path getBaseDirectory();
    Collection<File> getCompileSourceRoots();
    Collection<File> getCompileClasspathElements();
    Collection<File> getTestCompileSourceRoots();
    Collection<File> getTestClasspathElements();
}
