package io.codiqo.core.java;

import java.util.List;
import java.util.Objects;

import com.google.common.collect.Lists;

import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.MethodUsage;

@UtilityClass
public class InvocationCounter {
    public static List<Integer> collectDirect(ASTExecutableDeclaration executable) {
        Node body = executable.getBody();
        if (Objects.isNull(body)) {
            return Lists.newArrayList();
        }
        List<Integer> toReturn = Lists.newArrayList();
        walk(body, toReturn);
        return toReturn;
    }
    private static void walk(Node node, List<Integer> out) {
        int numChildren = node.getNumChildren();
        for (int i = 0; i < numChildren; i++) {
            Node child = node.getChild(i);
            if (child instanceof MethodUsage) {
                out.add(child.getBeginLine());
            }
            if (child instanceof ASTTypeDeclaration) {
                continue;
            }
            walk(child, out);
        }
    }
}
