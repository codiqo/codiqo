package io.codiqo.core.java;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import com.google.common.base.Joiner;

import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JArrayType;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JIntersectionType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JPrimitiveType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.JTypeVar;
import net.sourceforge.pmd.lang.java.types.JTypeVisitor;
import net.sourceforge.pmd.lang.java.types.JWildcardType;
import net.sourceforge.pmd.lang.java.types.TypePrettyPrint;

@UtilityClass
public class JavaBinaryFormat {
    public static final ASMDescriptorVisitor INSTANCE = new ASMDescriptorVisitor();
    public static final String CONSTRUCTOR_NAME = "<init>";
    public static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";

    public static boolean isFullyResolved(JMethodSig sig) {
        if (isUnknown(sig.getReturnType())) {
            return false;
        }
        return sig.getFormalParameters().stream().noneMatch(JavaBinaryFormat::isUnknown);
    }
    public static boolean isUnknown(JTypeMirror type) {
        return type == type.getTypeSystem().UNKNOWN;
    }
    public static String getInternalName(String binaryName) {
        return binaryName.replace('.', '/');
    }
    public static Type toOwnerType(JClassSymbol symbol) {
        return Type.getObjectType(getInternalName(symbol.getBinaryName()));
    }
    public static Method toMethod(JMethodSig sig) {
        String name = sig.isConstructor() ? CONSTRUCTOR_NAME : sig.getName();
        return new Method(name, toDescriptor(sig));
    }
    public static int getTypeSize(JTypeMirror type) {
        return Type.getType(toTypeDescriptor(type)).getSize();
    }
    public static int getOpcode(JTypeMirror type, int baseOpcode) {
        return Type.getType(toTypeDescriptor(type)).getOpcode(baseOpcode);
    }
    public static String toDisplayName(JMethodSig sig, boolean includeReturnType) {
        StringBuilder sb = new StringBuilder();

        if (sig.isConstructor()) {
            sb.append(sig.getDeclaringType().getSymbol().getSimpleName());
        } else {
            sb.append(sig.getName());
        }

        sb.append('(');
        sb.append(Joiner.on(", ").join(sig.getFormalParameters().stream().map(p -> TypePrettyPrint.prettyPrintWithSimpleNames(p)).iterator()));
        sb.append(')');

        if (CollectionUtils.isNotEmpty(sig.getTypeParameters())) {
            sb.append(" <");
            sb.append(Joiner.on(", ").join(sig.getTypeParameters().stream().map(JTypeVar::getName).iterator()));
            sb.append('>');
        }

        if (includeReturnType && BooleanUtils.negate(sig.isConstructor())) {
            sb.append(" : ");
            sb.append(TypePrettyPrint.prettyPrintWithSimpleNames(sig.getReturnType()));
        }

        return sb.toString();
    }
    public static String toDescriptor(JMethodSig sig) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (JTypeMirror param : sig.getFormalParameters()) {
            sb.append(toTypeDescriptor(param.getErasure()));
        }
        sb.append(')');
        if (sig.isConstructor()) {
            sb.append(Type.VOID_TYPE.getDescriptor());
        } else {
            sb.append(toTypeDescriptor(sig.getReturnType().getErasure()));
        }
        return sb.toString();
    }
    public static String toTypeDescriptor(JTypeMirror type) {
        if (Objects.isNull(type)) {
            return OBJECT_DESCRIPTOR;
        }
        return type.acceptVisitor(INSTANCE, null);
    }

    private static final class ASMDescriptorVisitor implements JTypeVisitor<String, Void> {
        @Override
        public String visitPrimitive(JPrimitiveType t, Void data) {
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
                    return Type.VOID_TYPE.getDescriptor();
            }
        }
        @Override
        public String visitArray(JArrayType t, Void data) {
            return "[" + t.getComponentType().getErasure().acceptVisitor(this, data);
        }
        @Override
        public String visitClass(JClassType t, Void data) {
            JClassSymbol sym = t.getSymbol();
            String binaryName = sym.getBinaryName();

            if (Objects.isNull(binaryName)) {
                return OBJECT_DESCRIPTOR;
            }
            return Type.getObjectType(binaryName.replace('.', '/')).getDescriptor();
        }
        @Override
        public String visitTypeVar(JTypeVar t, Void data) {
            return t.getUpperBound().getErasure().acceptVisitor(this, data);
        }
        @Override
        public String visitWildcard(JWildcardType t, Void data) {
            return t.asUpperBound().getErasure().acceptVisitor(this, data);
        }
        @Override
        public String visitIntersection(JIntersectionType t, Void data) {
            List<JTypeMirror> components = t.getComponents();
            Iterator<JTypeMirror> iterator = components.iterator();
            JTypeMirror first = iterator.next();
            return first.getErasure().acceptVisitor(this, data);
        }
        @Override
        public String visit(JTypeMirror t, Void data) {
            return t.isVoid() ? Type.VOID_TYPE.getDescriptor() : OBJECT_DESCRIPTOR;
        }
    }
}
