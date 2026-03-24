package io.codiqo.core;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.codiqo.api.ClassGraphSpec;
import io.codiqo.api.logging.Log;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ClassGraphWrapper implements ClassGraphSpec {
    private final Log log;
    private final ScanResult scan;
    private final Set<String> warnedMissing = Sets.newConcurrentHashSet();

    private final LoadingCache<ClassInfo, Map<MethodKey, MethodEntry>> methods = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public Map<MethodKey, MethodEntry> load(ClassInfo info) throws Exception {
            Map<MethodKey, MethodEntry> toReturn = Maps.newHashMap();

            for (MethodInfo method : info.getMethodInfo()) {
                String name = method.getName();
                String descriptor = method.getTypeDescriptorStr();
                String signature = method.getTypeSignatureStr();
                MethodKey key = new MethodKey(name, descriptor);
                toReturn.put(key, new MethodEntry(descriptor, signature));
            }

            return toReturn;
        }
    });
    private final LoadingCache<ClassInfo, Map<MethodKey, MethodEntry>> constructors = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public Map<MethodKey, MethodEntry> load(ClassInfo info) throws Exception {
            Map<MethodKey, MethodEntry> toReturn = Maps.newHashMap();

            for (MethodInfo method : info.getConstructorInfo()) {
                String name = method.getName();
                String descriptor = method.getTypeDescriptorStr();
                String signature = method.getTypeSignatureStr();
                MethodKey key = new MethodKey(name, descriptor);
                toReturn.put(key, new MethodEntry(descriptor, signature));
            }

            return toReturn;
        }
    });

    @Override
    public List<URL> getClasspathURLs() {
        return scan.getClasspathURLs();
    }
    @Override
    public ClassInfo getClassInfo(String fqn) {
        ClassInfo toReturn = scan.getClassInfo(fqn);
        if (Objects.nonNull(toReturn)) {
            methods.getUnchecked(toReturn);
        } else if (warnedMissing.add(fqn)) {
            log.warn("class not found in ClassGraph scan: %s", fqn);
        }
        return toReturn;
    }
    @Override
    public Map<MethodKey, MethodEntry> getMethods(ClassInfo fqn) {
        return Optional.ofNullable(methods.getUnchecked(fqn)).orElseGet(Collections::emptyMap);
    }
    @Override
    public Map<MethodKey, MethodEntry> getConstructors(ClassInfo fqn) {
        return Optional.ofNullable(constructors.getUnchecked(fqn)).orElseGet(Collections::emptyMap);
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
}
