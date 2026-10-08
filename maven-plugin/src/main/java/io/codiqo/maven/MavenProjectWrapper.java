package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.MavenProjectSpec;
import io.codiqo.api.PathContainment;
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
    private Map<String, String> properties = Maps.newHashMap();
    private Optional<File> coverage = Optional.empty();
    private Collection<File> compileSourceRoots = Lists.newArrayList();
    private Collection<File> compileClasspathElements = Lists.newArrayList();
    private Collection<File> testCompileSourceRoots = Lists.newArrayList();
    private Collection<File> testClasspathElements = Lists.newArrayList();
    private Collection<File> declaredSourceRoots = Lists.newArrayList();
    private Collection<File> declaredTestSourceRoots = Lists.newArrayList();
    private Collection<File> testReportDirectories = Lists.newArrayList();
    private BiMap<Artifact, File> artifacts = HashBiMap.create();
    @Delegate
    private ClassGraphSpec scan;

    @Override
    public Optional<File> coverage() {
        return coverage;
    }
    @Override
    public boolean isTestResource(File destination) {
        return declaredTestSourceRoots.stream().anyMatch(dir -> PathContainment.isUnder(dir, destination));
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
        return Optional.ofNullable(artifacts.inverse().get(classpathFile)).map(Artifact::getId);
    }
    @Override
    public String toString() {
        return name;
    }
    @Override
    public void close() throws IOException {

    }
}
