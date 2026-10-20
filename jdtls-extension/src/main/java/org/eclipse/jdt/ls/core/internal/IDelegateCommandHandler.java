package org.eclipse.jdt.ls.core.internal;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Compile-time copy of the language server's own interface: org.eclipse.jdt.ls.core is not published to Maven
 * Central, so the bundle compiles against this declaration, and the jar leaves the package out (see the module's
 * pom), so that at run time the bundle wires to the interface the server exports. The signature must stay the
 * server's: a mismatch compiles here and fails only when the server instantiates the handler.
 */
public interface IDelegateCommandHandler {
    Object executeCommand(String commandId, List<Object> arguments, IProgressMonitor monitor) throws Exception;
}
