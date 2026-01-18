package io.codiqo.core.java;

import java.util.Objects;

import org.objectweb.asm.Type;

import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.lang.java.types.JArrayType;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JIntersectionType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JPrimitiveType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.JTypeVar;
import net.sourceforge.pmd.lang.java.types.JTypeVisitor;
import net.sourceforge.pmd.lang.java.types.JWildcardType;

@UtilityClass
public class JavaBinaryFormat {
    public static final String CONSTRUCTOR_NAME = "<init>";
    public static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";

    public static String getInternalName(String binaryName) {
        return binaryName.replace('.', '/');
    }
    public static int getTypeSize(JTypeMirror type) {
        return Type.getType(toTypeDescriptor(type)).getSize();
    }
    public static int getOpcode(JTypeMirror type, int baseOpcode) {
        return Type.getType(toTypeDescriptor(type)).getOpcode(baseOpcode);
    }
    public static String toMethodDescriptor(JMethodSig sig) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (JTypeMirror param : sig.getFormalParameters()) {
            sb.append(toTypeDescriptor(param.getErasure()));
        }
        sb.append(')');
        sb.append(toTypeDescriptor(sig.getReturnType().getErasure()));
        return sb.toString();
    }
    public static String toTypeDescriptor(JTypeMirror type) {
        return Objects.isNull(type) ? OBJECT_DESCRIPTOR : type.acceptVisitor(new JTypeVisitor<String, Object>() {
            @Override
            public String visitPrimitive(JPrimitiveType t, Object data) {
                switch (t.getKind()) {
                    case BOOLEAN:
                        return Type.BOOLEAN_TYPE.getDescriptor();
                    case CHAR:
                        return Type.CHAR_TYPE.getDescriptor();
                    case BYTE:
                        return Type.BYTE_TYPE.getDescriptor();
                    case SHORT:
                        return Type.SHORT_TYPE.getDescriptor();
                    case INT:
                        return Type.INT_TYPE.getDescriptor();
                    case FLOAT:
                        return Type.FLOAT_TYPE.getDescriptor();
                    case LONG:
                        return Type.LONG_TYPE.getDescriptor();
                    case DOUBLE:
                        return Type.DOUBLE_TYPE.getDescriptor();
                    default:
                        return "V";
                }
            }
            @Override
            public String visitArray(JArrayType t, Object data) {
                return "[" + t.getComponentType().acceptVisitor(this, data);
            }
            @Override
            public String visitClass(JClassType t, Object data) {
                String binaryName = t.getSymbol().getBinaryName();
                if (Objects.isNull(binaryName)) {
                    return OBJECT_DESCRIPTOR;
                }
                return Type.getObjectType(binaryName.replace('.', '/')).getDescriptor();
            }
            @Override
            public String visitTypeVar(JTypeVar t, Object data) {
                return t.getUpperBound().acceptVisitor(this, data);
            }
            @Override
            public String visitWildcard(JWildcardType t, Object data) {
                return t.asUpperBound().acceptVisitor(this, data);
            }
            @Override
            public String visitIntersection(JIntersectionType t, Object data) {
                return type.getTypeSystem().glb(t.getComponents()).acceptVisitor(this, data);
            }
            @Override
            public String visit(JTypeMirror t, Object data) {
                return t.isVoid() ? Type.VOID_TYPE.getDescriptor() : OBJECT_DESCRIPTOR;
            }
        }, new Object());
    }
}
