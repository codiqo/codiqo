package io.codiqo.maven;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.codiqo.api.MavenProjectSpec;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
class MavenProjectWrapper implements MavenProjectSpec {
    private String id;
    private String groupId;
    private String artifactId;
    private String name;
    private String packaging;
    @Getter(AccessLevel.NONE)
    private Optional<String> parent = Optional.empty();
    @Getter(AccessLevel.NONE)
    private Optional<Date> latestModified = Optional.empty();
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
    private BiMap<Artifact, File> artifacts = HashBiMap.create();
    private ScanResult scan;

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
    public Optional<String> parent() {
        return parent;
    }
    @Override
    public List<URL> getClasspathURLs() {
        return scan.getClasspathURLs();
    }
    @Override
    public ClassInfo getClassInfo(String fqn) {
        return scan.getClassInfo(fqn);
    }
    @Override
    public ClassInfoList getAllClasses() {
        return scan.getAllClasses();
    }
    @Override
    public ClassInfoList interfaces(String fqn) {
        return scan.getInterfaces(fqn);
    }
    @Override
    public ClassInfoList classesImplementing(String fqn) {
        return scan.getClassesImplementing(fqn);
    }
    @Override
    public ClassInfoList superclasses(String fqn) {
        return scan.getSuperclasses(fqn);
    }
    @Override
    public ClassInfoList subclasses(String fqn) {
        return scan.getSubclasses(fqn);
    }
    @Override
    public ClassInfoList annotationsOnClass(String fqn) {
        return scan.getAnnotationsOnClass(fqn);
    }
    @Override
    public ClassInfoList classesWithAnnotation(String fqn) {
        return scan.getClassesWithAnnotation(fqn);
    }
    @Override
    public ClassInfoList classesWithAllAnnotations(String... fqns) {
        return scan.getClassesWithAllAnnotations(fqns);
    }
    @Override
    public ClassInfoList classesWithAnyAnnotation(String... fqns) {
        return scan.getClassesWithAnyAnnotation(fqns);
    }
    @Override
    public ClassInfoList classesWithFieldAnnotation(String fqn) {
        return scan.getClassesWithFieldAnnotation(fqn);
    }
    @Override
    public ClassInfoList classesWithMethodAnnotation(String fqn) {
        return scan.getClassesWithMethodAnnotation(fqn);
    }
    @Override
    public String toString() {
        return name;
    }
    @Override
    public void close() throws IOException {

    }
}
