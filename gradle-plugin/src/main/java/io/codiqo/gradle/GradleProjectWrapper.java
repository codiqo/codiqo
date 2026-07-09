package io.codiqo.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.ArrayList;


import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.JvmProjectSpec;
import io.codiqo.gradle.model.DependencyData;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;

@Setter
@Getter
public class GradleProjectWrapper implements JvmProjectSpec {
    private String id;
    private String groupId;
    private String artifactId;
    private String name;
    private String packaging;

    @Getter(AccessLevel.NONE)
    private Optional<String> parent = Optional.empty();

    @Getter(AccessLevel.NONE)
    private Optional<Date> latestModified = Optional.empty();

    @Getter(AccessLevel.NONE)
    private Optional<Date> latestSourceModified = Optional.empty();

    private String description;
    private String version;
    private File baseDirectory;
    private File outputDirectory;
    private Map<String, String> properties = new HashMap<>();
    private Optional<File> coverage = Optional.empty();
    private Collection<File> compileSourceRoots = new ArrayList<>();
    private Collection<File> compileClasspathElements = new ArrayList<>();
    private Collection<File> testCompileSourceRoots = new ArrayList<>();
    private Collection<File> testClasspathElements = new ArrayList<>();

    private List<DependencyData> dependencies = new ArrayList<>();

    @Getter(AccessLevel.NONE)
    private final Map<File, String> artifactCoordinates = new HashMap<>();

    @Delegate
    private ClassGraphSpec scan;

    @Override
    public Optional<File> coverage() {
        return coverage;
    }
    @Override
    public boolean isTestResource(File destination) {
        for (File dir : getTestCompileSourceRoots()) {
            if (dir.isDirectory()) {
                Path dirPath = dir.toPath().normalize();
                Path filePath = destination.toPath().normalize();

                if (filePath.startsWith(dirPath)) {
                    return true;
                }
            }
        }
        return false;
    }
    @Override
    public boolean contains(File filePath) {
        return filePath.toPath().normalize().startsWith(getBaseDirectory().toPath().normalize());
    }
    @Override
    public void setLatestModified(Date date) {
        this.latestModified = Optional.of(date);
    }
    @Override
    public Optional<Date> latestModified() {
        return latestModified;
    }
    @Override
    public void setLatestSourceModified(Date date) {
        this.latestSourceModified = Optional.of(date);
    }
    @Override
    public Optional<Date> latestSourceModified() {
        return latestSourceModified;
    }
    @Override
    public Optional<String> parent() {
        return parent;
    }
    public void setDependencies(List<DependencyData> dependencies) {
        this.dependencies = dependencies;
        artifactCoordinates.clear();
        for (DependencyData dependency : dependencies) {
            artifactCoordinates.put(new File(dependency.getFilePath()), dependency.getCoordinate());
        }
    }
    @Override
    public Optional<String> artifactCoordinate(File classpathFile) {
        return Optional.ofNullable(artifactCoordinates.get(classpathFile));
    }
    @Override
    public String toString() {
        return name;
    }
    @Override
    public void close() throws IOException {

    }
}
