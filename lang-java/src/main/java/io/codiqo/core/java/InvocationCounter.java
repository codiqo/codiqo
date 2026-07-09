package io.codiqo.core.java;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;


import org.apache.commons.lang3.BooleanUtils;

import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.MethodUsage;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypeOps;

@UtilityClass
public class InvocationCounter {
    public static List<Integer> collectDirect(ASTExecutableDeclaration executable) {
        Node body = executable.getBody();
        if (Objects.isNull(body)) {
            return new ArrayList<>();
        }
        List<Integer> toReturn = new ArrayList<>();
        walk(body, toReturn);
        return toReturn;
    }
    private static void walk(Node node, List<Integer> out) {
        int numChildren = node.getNumChildren();
        for (int i = 0; i < numChildren; i++) {
            Node child = node.getChild(i);
            if (child instanceof MethodUsage) {
                if (isChainHead(child)) {
                    out.add(child.getBeginLine());
                }
            }
            if (child instanceof ASTTypeDeclaration) {
                continue;
            }
            walk(child, out);
        }
    }
    /**
     * A run of consecutive chained calls that return the SAME type is one logical operation — a builder or
     * {@code return this} fluent run like {@code b.setA().setB()} — so only its head is counted. A call whose
     * receiver is itself a call (or constructor call) of the same return type is a chained continuation and is
     * not counted again. A call that changes type mid-chain ({@code builder.build()}) or navigates across types
     * ({@code a.getB().getC()}) starts a new invocation and is counted. When either type cannot be resolved we
     * count conservatively rather than collapse. A receiver is a chain continuation only when it is itself a
     * call — i.e. a {@link MethodUsage} (a method or constructor call); a lambda or method reference cannot
     * legally be a receiver, so no other {@code MethodUsage} kind occurs here. Every other receiver — variable,
     * {@code this}, cast — leaves the call a head. Unmatched forms fall to "head" (counted), so the rule can
     * only ever under-collapse, never over-collapse.
     */
    private static boolean isChainHead(Node node) {
        if (node instanceof ASTMethodCall call) {
            ASTExpression qualifier = call.getQualifier();
            if (qualifier instanceof MethodUsage) {
                return BooleanUtils.negate(sameResolvedType(call.getTypeMirror(), qualifier.getTypeMirror()));
            }
        }
        return true;
    }
    private static boolean sameResolvedType(JTypeMirror a, JTypeMirror b) {
        boolean bothResolved = BooleanUtils.and(new boolean[] {
                BooleanUtils.negate(JavaBinaryFormat.isUnknown(a)),
                BooleanUtils.negate(JavaBinaryFormat.isUnknown(b)) });
        if (bothResolved) {
            return TypeOps.isSameType(a, b);
        }
        return false;
    }
}
