package io.codiqo.api;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;

import com.google.common.collect.BiMap;

public interface MavenProjectSpec extends ProjectSpec, ClassGraphSpec {
    String getGroupId();
    String getArtifactId();
    String getPackaging();
    Optional<String> parent();
    Map<String, String> getProperties();
    BiMap<Artifact, File> getArtifacts();
    Collection<File> getCompileSourceRoots();
    Collection<File> getCompileClasspathElements();
    Collection<File> getTestCompileSourceRoots();
    Collection<File> getTestClasspathElements();

}
