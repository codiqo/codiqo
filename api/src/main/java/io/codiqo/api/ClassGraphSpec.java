package io.codiqo.api;

import java.net.URL;
import java.util.List;
import java.util.Map;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import org.apache.commons.lang3.tuple.ImmutablePair;

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

    public static final class MethodKey extends ImmutablePair<String, String> {
        public MethodKey(String name, String descriptor) {
            super(name, descriptor);
        }
        public String getName() {
            return getLeft();
        }
        public String getDescriptor() {
            return getRight();
        }
    }

    public static final class MethodEntry extends ImmutablePair<String, String> {
        public MethodEntry(String descriptor, String signature) {
            super(descriptor, signature);
        }
        public String getDescriptor() {
            return getLeft();
        }
        public String getSignature() {
            return getRight();
        }
    }
}
