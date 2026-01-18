package io.codiqo.api;

import java.io.File;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;

import com.google.common.collect.BiMap;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;

public interface MavenProjectSpec extends ProjectSpec {
    String getGroupId();
    String getArtifactId();
    String getPackaging();
    Optional<String> getParent();
    Map<String, String> getProperties();
    BiMap<Artifact, File> getArtifacts();
    Collection<File> getCompileSourceRoots();
    Collection<File> getCompileClasspathElements();
    Collection<File> getTestCompileSourceRoots();
    Collection<File> getTestClasspathElements();
    List<URL> getClasspathURLs();
    ClassInfo getClassInfo(String fqn);
    ClassInfoList getAllClasses();
    ClassInfoList interfaces(String fqn);
    ClassInfoList classesImplementing(String fqn);
    ClassInfoList superclasses(String fqn);
    ClassInfoList subclasses(String fqn);
    ClassInfoList annotationsOnClass(String fqn);
    ClassInfoList classesWithAnnotation(String fqn);
    ClassInfoList classesWithAllAnnotations(String... fqns);
    ClassInfoList classesWithAnyAnnotation(String... fqns);
    ClassInfoList classesWithFieldAnnotation(String fqn);
    ClassInfoList classesWithMethodAnnotation(String fqn);
}
