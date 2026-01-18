package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.util.Optional;

public interface ProjectSpec extends Closeable {
    String getId();
    String getName();
    String getDescription();
    String getVersion();
    File getBaseDirectory();
    File getOutputDirectory();
    Optional<File> coverage();
    boolean isTestResource(File destination);
    boolean contains(File filePath);
}
