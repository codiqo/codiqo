package io.codiqo.core.java;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

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
 * name, the return type, the resolved parameter types and the position of every anonymous class
 */
@UtilityClass
class JavaSourceIdentity {
    private static final String PRIMARY_TYPE = "*";
    private static final String ANONYMOUS_MARKER = "$new";
    private static final String INITIALIZER_MARKER = "{init}";
    private static final Pattern TYPE_ANNOTATION = Pattern.compile("@[\\w.]+(\\([^)]*\\))?");
    private static final List<Class<? extends Node>> CONTEXTS = List.of(
            ASTTypeDeclaration.class,
            ASTExecutableDeclaration.class,
            ASTCompactConstructorDeclaration.class,
            ASTEnumConstant.class,
            ASTVariableDeclarator.class,
            ASTInitializer.class);

    public String of(ASTExecutableDeclaration executable, String primaryTypeName) {
        return key(executable, primaryTypeName, true, true);
    }
    public String of(ASTCompactConstructorDeclaration constructor, String primaryTypeName) {
        return key(constructor, primaryTypeName, true, true);
    }
    public String memberOf(ASTExecutableDeclaration executable, String primaryTypeName) {
        return key(executable, primaryTypeName, false, true);
    }
    public String memberOf(ASTCompactConstructorDeclaration constructor, String primaryTypeName) {
        return key(constructor, primaryTypeName, false, true);
    }
    public String nameOf(ASTExecutableDeclaration executable, String primaryTypeName) {
        return key(executable, primaryTypeName, false, false);
    }
    public String nameOf(ASTCompactConstructorDeclaration constructor, String primaryTypeName) {
        return key(constructor, primaryTypeName, false, false);
    }
    public List<String> parametersOf(ASTExecutableDeclaration executable) {
        return executable.getFormalParameters().toList().stream().map(parameter -> simpleTypeName(parameter.getTypeNode())).toList();
    }
    public List<String> parametersOf(ASTCompactConstructorDeclaration constructor) {
        return constructor.getEnclosingType().getRecordComponents().toList().stream().map(component -> simpleTypeName(component.getTypeNode())).toList();
    }
    private String key(ASTExecutableDeclaration executable, String primaryTypeName, boolean pathParameters, boolean ownParameters) {
        String name = executable instanceof ASTConstructorDeclaration ? JavaBinaryFormat.CONSTRUCTOR_NAME : executable.getName();
        String toReturn = typeKey(executable.getEnclosingType(), primaryTypeName, pathParameters) + '#' + name;
        if (ownParameters) {
            toReturn += '(' + executable.getFormalParameters().toList().stream()
                    .map(parameter -> simpleTypeName(parameter.getTypeNode()))
                    .collect(Collectors.joining(",")) + ')';
        }
        return toReturn;
    }
    private String key(ASTCompactConstructorDeclaration constructor, String primaryTypeName, boolean pathParameters, boolean ownParameters) {
        String toReturn = typeKey(constructor.getEnclosingType(), primaryTypeName, pathParameters) + '#' + JavaBinaryFormat.CONSTRUCTOR_NAME;
        if (ownParameters) {
            toReturn += '(' + constructor.getEnclosingType().getRecordComponents().toList().stream()
                    .map(component -> simpleTypeName(component.getTypeNode()))
                    .collect(Collectors.joining(",")) + ')';
        }
        return toReturn;
    }
    private String typeKey(ASTTypeDeclaration type, String primaryTypeName, boolean withParameters) {
        Node context = context(type);
        if (Objects.isNull(context)) {
            return type.getSimpleName().equals(primaryTypeName) ? PRIMARY_TYPE : type.getSimpleName();
        }
        if (type instanceof ASTAnonymousClassDeclaration) {
            int ordinal = context.descendants(ASTAnonymousClassDeclaration.class)
                    .crossFindBoundaries()
                    .filter(candidate -> context(candidate) == context)
                    .toList()
                    .indexOf(type);
            return contextKey(context, primaryTypeName, withParameters) + ANONYMOUS_MARKER + ordinal;
        }
        return contextKey(context, primaryTypeName, withParameters) + '$' + type.getSimpleName();
    }
    private String contextKey(Node context, String primaryTypeName, boolean withParameters) {
        if (context instanceof ASTTypeDeclaration type) {
            return typeKey(type, primaryTypeName, withParameters);
        }
        if (context instanceof ASTExecutableDeclaration executable) {
            return key(executable, primaryTypeName, withParameters, withParameters);
        }
        if (context instanceof ASTCompactConstructorDeclaration constructor) {
            return key(constructor, primaryTypeName, withParameters, withParameters);
        }
        if (context instanceof ASTEnumConstant constant) {
            return contextKey(context(constant), primaryTypeName, withParameters) + '.' + constant.getName();
        }
        if (context instanceof ASTVariableDeclarator variable) {
            return contextKey(context(variable), primaryTypeName, withParameters) + '.' + variable.getVarId().getName();
        }
        return contextKey(context(context), primaryTypeName, withParameters) + INITIALIZER_MARKER;
    }
    private Node context(Node node) {
        return node.ancestors()
                .filter(ancestor -> CONTEXTS.stream().anyMatch(kind -> kind.isInstance(ancestor)))
                .first();
    }
    private String simpleTypeName(ASTType type) {
        String erased = TYPE_ANNOTATION.matcher(eraseTypeArguments(type.getText().toString())).replaceAll(StringUtils.EMPTY);
        String compact = StringUtils.deleteWhitespace(erased).replace("...", "[]");
        return compact.substring(compact.lastIndexOf('.') + 1);
    }
    private String eraseTypeArguments(String text) {
        StringBuilder toReturn = new StringBuilder();
        int depth = 0;
        for (char next : text.toCharArray()) {
            if (next == '<') {
                depth++;
            } else if (next == '>') {
                depth--;
            } else if (depth == 0) {
                toReturn.append(next);
            }
        }
        return toReturn.toString();
    }
}
