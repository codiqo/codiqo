package io.codiqo.api;

import java.net.URL;
import java.util.List;
import java.util.Map;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import lombok.Value;

public interface ClassGraphSpec extends AutoCloseable {
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

    Map<MethodKey, MethodEntry> getMethods(ClassInfo fqn);
    Map<MethodKey, MethodEntry> getConstructors(ClassInfo fqn);

    @Value
    public static class MethodKey {
        String name;
        String descriptor;
    }

    @Value
    public static class MethodEntry {
        String descriptor;
        String signature;
    }
}
