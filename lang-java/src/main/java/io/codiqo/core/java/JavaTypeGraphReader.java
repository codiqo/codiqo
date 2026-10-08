package io.codiqo.core.java;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.Triple;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;

import io.codiqo.api.code.DeclaredType;
import io.codiqo.api.code.TypeKind;
import io.codiqo.api.code.TypeReference;
import io.codiqo.api.code.TypeReferenceKind;
import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.ASTClassType;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.ast.ASTExtendsList;
import net.sourceforge.pmd.lang.java.ast.ASTImplementsList;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTMethodReference;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.InvocationNode;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.metrics.MetricsUtil;

/**
 * Reads the type graph from the PMD source AST, not from bytecode. Generated code would distort the graph: Lombok's
 * {@code @Delegate} alone puts a hundred generated calls per class into bytecode, which would inflate the call
 * references that hotspot importance is ranked on.
 */
@UtilityClass
public class JavaTypeGraphReader {
    public void read(ASTCompilationUnit tree, File file, boolean test, List<DeclaredType> types, List<TypeReference> references) {
        for (ASTTypeDeclaration declaration : tree.getTypeDeclarations()) {
            String from = declaration.getBinaryName();
            types.add(new DeclaredType(from, file, kind(declaration), MetricsUtil.computeMetric(JavaMetrics.NCSS, declaration), test));

            Multiset<Triple<String, String, TypeReferenceKind>> counts = HashMultiset.create();
            /**
             * Only direct ASTClassType children of the extends/implements lists are supertypes. The type arguments in
             * {@code implements Function<Api, Model>} are nested deeper, and collecting them too would record Api and
             * Model as supertypes of the declaring class.
             */
            Set<Node> supertypes = Sets.newHashSet();
            declaration.descendants(ASTExtendsList.class).crossFindBoundaries()
                    .forEach(list -> list.children(ASTClassType.class).forEach(supertypes::add));
            declaration.descendants(ASTImplementsList.class).crossFindBoundaries()
                    .forEach(list -> list.children(ASTClassType.class).forEach(supertypes::add));

            Predicate<String> foreign = Predicate.not(from::equals);
            declaration.descendants().crossFindBoundaries().forEach(node -> {
                if (BooleanUtils.or(new boolean[] { node instanceof ASTMethodCall, node instanceof ASTConstructorCall })) {
                    called(((InvocationNode) node).getMethodType()).filter(foreign).ifPresent(to -> counts.add(Triple.of(from, to, TypeReferenceKind.CALL)));
                } else if (node instanceof ASTMethodReference reference) {
                    called(reference.getReferencedMethod()).filter(foreign).ifPresent(to -> counts.add(Triple.of(from, to, TypeReferenceKind.CALL)));
                } else if (node instanceof ASTClassType type) {
                    TypeReferenceKind kind = supertypes.contains(node) ? TypeReferenceKind.INHERIT : TypeReferenceKind.TYPE;
                    topLevel(type.getTypeMirror()).filter(foreign).ifPresent(to -> counts.add(Triple.of(from, to, kind)));
                }
            });

            for (Triple<String, String, TypeReferenceKind> key : counts.elementSet()) {
                references.add(new TypeReference(key.getLeft(), key.getMiddle(), key.getRight(), counts.count(key)));
            }
        }
    }
    private static TypeKind kind(ASTTypeDeclaration declaration) {
        if (declaration.isAnnotation()) {
            return TypeKind.ANNOTATION;
        } else if (declaration.isInterface()) {
            return TypeKind.INTERFACE;
        } else if (declaration.isEnum()) {
            return TypeKind.ENUM;
        } else if (declaration.isRecord()) {
            return TypeKind.RECORD;
        } else if (declaration.isAbstract()) {
            return TypeKind.ABSTRACT_CLASS;
        }
        return TypeKind.CLASS;
    }
    private static Optional<String> called(JMethodSig signature) {
        if (Objects.nonNull(signature) && BooleanUtils.negate(signature.getSymbol().isUnresolved())) {
            return topLevel(signature.getDeclaringType());
        }
        return Optional.empty();
    }
    private static Optional<String> topLevel(JTypeMirror type) {
        JTypeDeclSymbol symbol = type.getSymbol();
        if (symbol instanceof JClassSymbol cls && BooleanUtils.negate(cls.isUnresolved())) {
            JClassSymbol outermost = cls;
            while (Objects.nonNull(outermost.getEnclosingClass())) {
                outermost = outermost.getEnclosingClass();
            }
            return Optional.of(outermost.getBinaryName());
        }
        return Optional.empty();
    }
}
