package io.codiqo.jdtls.extension;

import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.internal.corext.callhierarchy.CallHierarchyCore;
import org.eclipse.jdt.ls.core.internal.IDelegateCommandHandler;

/**
 * Limits every later call hierarchy search to the source folders of the workspace's Java projects and answers
 * how many projects that covers. The server's own scope is the whole workspace, every library jar and the JDK
 * included, and no setting narrows it: java.search.scope only filters test code out of the results. A caller inside
 * a library is outside the analysed project anyway, and searching libraries cost both time and correctness: a class
 * file JDT cannot resolve fails the whole search, as scala-library 2.13's Ordering$$anon$1 does with its
 * self-bounded type variable (eclipse-jdt/eclipse.jdt.core#5561) for every one-argument apply(..) lookup.
 *
 * <p>The scope is built from the projects present when the command runs, so the client sends it once the import has
 * settled; a project imported later would not be searched.
 */
public class SourcesOnlyCallerSearch implements IDelegateCommandHandler {
    @Override
    public Object executeCommand(String commandId, List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IJavaProject[] projects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
        CallHierarchyCore.getDefault().setSearchScope(SearchEngine.createJavaSearchScope(projects, IJavaSearchScope.SOURCES));
        return projects.length;
    }
}
