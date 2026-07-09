package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.ArrayList;

import org.apache.maven.artifact.Artifact;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.MavenProjectSpec;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;

@Setter
@Getter
public class MavenProjectWrapper implements MavenProjectSpec {
    private String id;
    private String code;
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
    private BidiMap<Artifact, File> artifacts = new DualHashBidiMap<>();
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
    @Override
    public Optional<String> artifactCoordinate(File classpathFile) {
        return Optional.ofNullable(artifacts.getKey(classpathFile)).map(Artifact::getId);
    }
    @Override
    public String toString() {
        return name;
    }
    @Override
    public void close() throws IOException {

    }
}
