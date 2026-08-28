package io.codiqo.core;

import java.net.URL;
import java.util.List;
import java.util.Map;

import io.codiqo.api.ClassGraphSpec;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;

/**
 * An empty {@link ClassGraphSpec} for the source-only degraded path: a failed build resolves no compiled classpath,
 * so class lookups return nothing. PMD invocation enrichment tolerates a null {@link ClassInfo}, so blocks still
 * carry the syntactic metrics the volume scorer needs.
 */
public class NoOpClassGraphSpec implements ClassGraphSpec {
    @Override
    public List<URL> getClasspathURLs() {
        return List.of();
    }
    @Override
    public ClassInfo getClassInfo(String fqn) {
        return null;
    }
    @Override
    public ClassInfoList getAllClasses() {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList interfaces(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList classesImplementing(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList superclasses(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList subclasses(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList annotationsOnClass(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList classesWithAnnotation(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList classesWithAllAnnotations(String... fqns) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList classesWithAnyAnnotation(String... fqns) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList classesWithFieldAnnotation(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public ClassInfoList classesWithMethodAnnotation(String fqn) {
        return ClassInfoList.emptyList();
    }
    @Override
    public Map<MethodKey, MethodEntry> getMethods(ClassInfo fqn) {
        return Map.of();
    }
    @Override
    public Map<MethodKey, MethodEntry> getConstructors(ClassInfo fqn) {
        return Map.of();
    }
    @Override
    public void close() {
    }
}
