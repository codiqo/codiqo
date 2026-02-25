package io.codiqo.lang.spec;

import java.util.Optional;
import java.util.function.Consumer;

import org.apache.maven.artifact.Artifact;

import io.codiqo.api.MavenProjectSpec;
import io.github.classgraph.ClassInfo;
import net.sourceforge.pmd.lang.java.ast.JavaNode;
import net.sourceforge.pmd.lang.java.types.JMethodSig;

public interface JBinaryMethodSig extends JMethodSig, JavaBinarySignatureFormatter, Consumer<MavenProjectSpec> {
    int getBeginLine();
    int getBeginColumn();
    int getEndLine();
    int getEndColumn();
    String getSignature();
    String getMethodName();
    String getMethodDescriptor();
    JavaNode getCall();
    boolean isInterfaceCall();
    Optional<ClassInfo> classInfo();
    Optional<Artifact> artifact();
}
