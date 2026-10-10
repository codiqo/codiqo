package io.codiqo.core.java;

import java.util.List;
import java.util.Objects;

import com.google.common.collect.ImmutableList;

import lombok.Value;
import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.ASTAnonymousClassDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTCompactConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTEnumConstant;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTInitializer;
import net.sourceforge.pmd.lang.java.ast.ASTType;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTVariableDeclarator;

/**
 * pairs the executables of two revisions of one file. A JVM descriptor cannot: it embeds the package, the primary type's
 * name, the return type, the resolved parameter types and the position of every anonymous class. An identity is the
 * path of {@link Step}s from the file's top-level type down to the executable.
 */
@UtilityClass
public class JavaSourceIdentity {
    private static final List<Class<? extends Node>> CONTEXTS = List.of(
            ASTTypeDeclaration.class,
            ASTExecutableDeclaration.class,
            ASTCompactConstructorDeclaration.class,
            ASTEnumConstant.class,
            ASTVariableDeclarator.class,
            ASTInitializer.class);

    /** every executable on the path keyed by its parameters, its own included */
    public List<Step> of(ASTExecutableDeclaration executable, String primaryTypeName) {
        return path(executable, primaryTypeName, true, true);
    }
    public List<Step> of(ASTCompactConstructorDeclaration constructor, String primaryTypeName) {
        return path(constructor, primaryTypeName, true, true);
    }
    /** only the executable's own parameters: an enclosing method that changed its parameter list keeps its members */
    public List<Step> memberOf(ASTExecutableDeclaration executable, String primaryTypeName) {
        return path(executable, primaryTypeName, false, true);
    }
    public List<Step> memberOf(ASTCompactConstructorDeclaration constructor, String primaryTypeName) {
        return path(constructor, primaryTypeName, false, true);
    }
    /** no parameters anywhere */
    public List<Step> nameOf(ASTExecutableDeclaration executable, String primaryTypeName) {
        return path(executable, primaryTypeName, false, false);
    }
    public List<Step> nameOf(ASTCompactConstructorDeclaration constructor, String primaryTypeName) {
        return path(constructor, primaryTypeName, false, false);
    }
    public List<String> parametersOf(ASTExecutableDeclaration executable) {
        return executable.getFormalParameters().toList().stream().map(parameter -> simpleTypeName(parameter.getTypeNode())).toList();
    }
    public List<String> parametersOf(ASTCompactConstructorDeclaration constructor) {
        return constructor.getEnclosingType().getRecordComponents().toList().stream().map(component -> simpleTypeName(component.getTypeNode())).toList();
    }
    private List<Step> path(ASTExecutableDeclaration executable, String primaryTypeName, boolean pathParameters, boolean ownParameters) {
        List<String> parameters = ownParameters ? parametersOf(executable) : List.of();
        Step own = executable instanceof ASTConstructorDeclaration ? Step.constructor(parameters) : Step.of(StepKind.METHOD, executable.getName(), parameters);
        return append(typePath(executable.getEnclosingType(), primaryTypeName, pathParameters), own);
    }
    private List<Step> path(ASTCompactConstructorDeclaration constructor, String primaryTypeName, boolean pathParameters, boolean ownParameters) {
        List<String> parameters = ownParameters ? parametersOf(constructor) : List.of();
        return append(typePath(constructor.getEnclosingType(), primaryTypeName, pathParameters), Step.constructor(parameters));
    }
    private List<Step> typePath(ASTTypeDeclaration type, String primaryTypeName, boolean withParameters) {
        Node context = context(type);
        if (Objects.isNull(context)) {
            return List.of(type.getSimpleName().equals(primaryTypeName) ? Step.PRIMARY_TYPE : Step.of(StepKind.TYPE, type.getSimpleName(), List.of()));
        }
        if (type instanceof ASTAnonymousClassDeclaration) {
            int ordinal = context.descendants(ASTAnonymousClassDeclaration.class)
                    .crossFindBoundaries()
                    .filter(candidate -> context(candidate) == context)
                    .toList()
                    .indexOf(type);
            return append(contextPath(context, primaryTypeName, withParameters), Step.anonymous(ordinal));
        }
        return append(contextPath(context, primaryTypeName, withParameters), Step.of(StepKind.TYPE, type.getSimpleName(), List.of()));
    }
    private List<Step> contextPath(Node context, String primaryTypeName, boolean withParameters) {
        if (context instanceof ASTTypeDeclaration type) {
            return typePath(type, primaryTypeName, withParameters);
        }
        if (context instanceof ASTExecutableDeclaration executable) {
            return path(executable, primaryTypeName, withParameters, withParameters);
        }
        if (context instanceof ASTCompactConstructorDeclaration constructor) {
            return path(constructor, primaryTypeName, withParameters, withParameters);
        }
        if (context instanceof ASTEnumConstant constant) {
            return append(contextPath(context(constant), primaryTypeName, withParameters), Step.of(StepKind.FIELD, constant.getName(), List.of()));
        }
        if (context instanceof ASTVariableDeclarator variable) {
            return append(contextPath(context(variable), primaryTypeName, withParameters), Step.of(StepKind.FIELD, variable.getVarId().getName(), List.of()));
        }
        return append(contextPath(context(context), primaryTypeName, withParameters), Step.INITIALIZER);
    }
    private Node context(Node node) {
        return node.ancestors()
                .filter(ancestor -> CONTEXTS.stream().anyMatch(kind -> kind.isInstance(ancestor)))
                .first();
    }
    private String simpleTypeName(ASTType type) {
        return JavaSignatures.simpleTypeName(type.getText().toString());
    }
    private static List<Step> append(List<Step> path, Step step) {
        return ImmutableList.<Step> builder().addAll(path).add(step).build();
    }

    public enum StepKind {
        /** the type named after the file, whatever it is called: a renamed file keeps its members */
        PRIMARY_TYPE,
        TYPE,
        /** an anonymous class, by its position among the anonymous classes of the same context */
        ANONYMOUS_TYPE,
        METHOD,
        CONSTRUCTOR,
        /** a field or an enum constant, whose initializer may hold anonymous classes and lambdas */
        FIELD,
        INITIALIZER
    }

    /** one step of the path; the name is null where the kind alone identifies it, and the parameters are empty unless compared */
    @Value
    public static class Step {
        private static final Step PRIMARY_TYPE = of(StepKind.PRIMARY_TYPE, null, List.of());
        private static final Step INITIALIZER = of(StepKind.INITIALIZER, null, List.of());

        StepKind kind;
        String name;
        List<String> parameters;
        int ordinal;

        private static Step of(StepKind kind, String name, List<String> parameters) {
            return new Step(kind, name, ImmutableList.copyOf(parameters), 0);
        }
        private static Step constructor(List<String> parameters) {
            return of(StepKind.CONSTRUCTOR, null, parameters);
        }
        private static Step anonymous(int ordinal) {
            return new Step(StepKind.ANONYMOUS_TYPE, null, ImmutableList.of(), ordinal);
        }
    }
}
