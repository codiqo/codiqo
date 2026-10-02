package io.codiqo.api;

import java.io.Closeable;
import java.io.File;
import java.util.Date;
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

    default boolean contains(File filePath) {
        return PathContainment.isUnder(getBaseDirectory(), filePath);
    }
    /**
     * Whether the module compiles the file from a source root it declares, wherever that root lives: kryo's main/
     * declares ../src and ../test, so its every source sits outside the module's own directory.
     */
    default boolean declaresSource(File filePath) {
        return false;
    }
    Optional<Date> latestModified();
    void setLatestModified(Date date);
    Optional<Date> latestSourceModified();
    void setLatestSourceModified(Date date);
}
