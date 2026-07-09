package io.codiqo.core;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.codiqo.api.ClassGraphSpec;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ClassGraphWrapper implements ClassGraphSpec {
    private final ScanResult scan;

    private final Map<ClassInfo, Map<MethodKey, MethodEntry>> methods = new ConcurrentHashMap<>();
    private final Map<ClassInfo, Map<MethodKey, MethodEntry>> constructors = new ConcurrentHashMap<>();

    @Override
    public List<URL> getClasspathURLs() {
        return scan.getClasspathURLs();
    }
    @Override
    public ClassInfo getClassInfo(String fqn) {
        ClassInfo toReturn = scan.getClassInfo(fqn);
        if (Objects.nonNull(toReturn)) {
            methods.computeIfAbsent(toReturn, ClassGraphWrapper::loadMethods);
        }
        return toReturn;
    }
    @Override
    public Map<MethodKey, MethodEntry> getMethods(ClassInfo fqn) {
        return methods.computeIfAbsent(fqn, ClassGraphWrapper::loadMethods);
    }
    @Override
    public Map<MethodKey, MethodEntry> getConstructors(ClassInfo fqn) {
        return constructors.computeIfAbsent(fqn, ClassGraphWrapper::loadConstructors);
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
    public void close() throws Exception {
        scan.close();
    }
    private static Map<MethodKey, MethodEntry> loadMethods(ClassInfo info) {
        return buildEntries(info.getMethodInfo());
    }
    private static Map<MethodKey, MethodEntry> loadConstructors(ClassInfo info) {
        return buildEntries(info.getConstructorInfo());
    }
    private static Map<MethodKey, MethodEntry> buildEntries(Iterable<MethodInfo> methodInfos) {
        Map<MethodKey, MethodEntry> toReturn = new HashMap<>();
        for (MethodInfo method : methodInfos) {
            String descriptor = method.getTypeDescriptorStr();
            toReturn.put(new MethodKey(method.getName(), descriptor), new MethodEntry(descriptor, method.getTypeSignatureStr()));
        }
        return toReturn;
    }
}
