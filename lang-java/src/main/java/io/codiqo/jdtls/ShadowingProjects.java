package io.codiqo.jdtls;

import static java.util.function.Predicate.not;

import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import io.codiqo.api.JvmProjectSpec;
import io.codiqo.api.PathContainment;
import io.codiqo.api.ProjectSpec;

import lombok.experimental.UtilityClass;

/**
 * Workspace projects that hide a module's sources from the language server.
 *
 * <p>m2e imports a source root declared outside the module directory as a linked folder named after the relative
 * path with its separators flattened: kryo's main/ declares ../src, and the kryo project gets a single-segment
 * {@code .._src} link to it. The aggregator whose directory physically holds src/ is imported too, as a non-Java
 * project. A file URI then matches both resources, and JDTUtils.findResource keeps the one with the shorter
 * project-relative path, the first on a tie. When the root is a direct child of the aggregator the depths tie, the
 * aggregator's copy can win, and it has no compilation unit — every call hierarchy query on that file answers null.
 */
@UtilityClass
class ShadowingProjects {
    /**
     * declared source roots that lie outside the declaring module's own directory; empty for every conventional
     * layout, which keeps the language server workspace untouched there
     */
    List<File> externalSourceRoots(Collection<ProjectSpec> modules) {
        return modules.stream()
                .filter(JvmProjectSpec.class::isInstance)
                .map(JvmProjectSpec.class::cast)
                .flatMap(module -> Stream.concat(module.getCompileSourceRoots().stream(), module.getTestCompileSourceRoots().stream())
                        .filter(not(module::contains)))
                .distinct()
                .toList();
    }
    /**
     * the non-Java projects that can win the lookup for one of those roots: the root sits directly in the project's
     * directory, so its path there is no deeper than the one-segment link. A deeper root already loses to the link,
     * and a Java project is never returned — one that holds a root another module borrows still compiles it.
     */
    List<URI> select(Collection<URI> nonJavaProjects, Collection<File> externalRoots) {
        return nonJavaProjects.stream()
                .filter(project -> externalRoots.stream().anyMatch(root -> holdsDirectly(new File(project), root)))
                .toList();
    }
    private boolean holdsDirectly(File project, File root) {
        File parent = root.getAbsoluteFile().getParentFile();
        return Objects.nonNull(parent) && PathContainment.isUnder(project, root) && PathContainment.isUnder(parent, project);
    }
}
