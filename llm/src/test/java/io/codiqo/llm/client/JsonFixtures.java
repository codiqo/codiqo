package io.codiqo.llm.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/** Builds an instance with every field set, so a round trip proves no field is lost or renamed. */
final class JsonFixtures {
    private static final int MAX_DEPTH = 6;
    private static final String CODIQO_PACKAGE = "io.codiqo.";

    private final AtomicInteger counter = new AtomicInteger();

    @SuppressWarnings("unchecked")
    <T> T populated(Class<T> type) throws Exception {
        return (T) value(type, 0, type.getSimpleName(), Map.of());
    }
    private Object value(Type type, int depth, String hint, Map<String, Type> bindings) throws Exception {
        if (type instanceof TypeVariable<?> variable) {
            return value(bindings.getOrDefault(variable.getName(), String.class), depth, hint, Map.of());
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw) || Collection.class.equals(raw)) {
                List<Object> toReturn = Lists.newArrayList();
                addIfPresent(toReturn, value(arguments[0], depth + 1, hint, bindings));
                return toReturn;
            }
            if (Set.class.isAssignableFrom(raw)) {
                Set<Object> toReturn = Sets.newLinkedHashSet();
                addIfPresent(toReturn, value(arguments[0], depth + 1, hint, bindings));
                return toReturn;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<Object, Object> toReturn = Maps.newLinkedHashMap();
                Object key = value(arguments[0], depth + 1, hint + "Key", bindings);
                Object entry = value(arguments[1], depth + 1, hint, bindings);
                if (key != null && entry != null) {
                    toReturn.put(key, entry);
                }
                return toReturn;
            }
            if (Optional.class.equals(raw)) {
                return Optional.ofNullable(value(arguments[0], depth + 1, hint, bindings));
            }
            Map<String, Type> bound = Maps.newLinkedHashMap();
            TypeVariable<?>[] variables = raw.getTypeParameters();
            for (int i = 0; i < variables.length; i++) {
                bound.put(variables[i].getName(), arguments[i]);
            }
            return value(raw, depth, hint, bound);
        }
        Class<?> cls = (Class<?>) type;
        int n = counter.incrementAndGet();
        if (cls == String.class) {
            return hint + "-" + n;
        } else if (cls == int.class || cls == Integer.class) {
            return n;
        } else if (cls == long.class || cls == Long.class) {
            return (long) n;
        } else if (cls == double.class || cls == Double.class) {
            return n + 0.25;
        } else if (cls == float.class || cls == Float.class) {
            return n + 0.5f;
        } else if (cls == boolean.class || cls == Boolean.class) {
            return true;
        } else if (cls == BigDecimal.class) {
            return new BigDecimal(n + ".25");
        } else if (cls == Instant.class) {
            return Instant.ofEpochSecond(1_700_000_000L + n);
        } else if (cls == LocalDate.class) {
            return LocalDate.of(2026, 1, 1).plusDays(n);
        } else if (cls == Date.class) {
            return new Date(1_700_000_000_000L + n * 1000L);
        } else if (cls.isEnum()) {
            Object[] constants = cls.getEnumConstants();
            return constants[n % constants.length];
        }
        if (cls.isInterface() || Modifier.isAbstract(cls.getModifiers()) || cls == Object.class || depth > MAX_DEPTH) {
            return null;
        }
        return bean(cls, depth, bindings);
    }
    private Object bean(Class<?> cls, int depth, Map<String, Type> bindings) throws Exception {
        Optional<Constructor<?>> noArgs = constructors(cls).stream().filter(c -> c.getParameterCount() == 0).findFirst();
        if (noArgs.isPresent()) {
            Object toReturn = noArgs.get().newInstance();
            for (Field field : fields(cls)) {
                field.set(toReturn, value(field.getGenericType(), depth + 1, field.getName(), bindings));
            }
            return toReturn;
        }
        Constructor<?> widest = constructors(cls).stream().max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Type[] parameters = widest.getGenericParameterTypes();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            arguments[i] = value(parameters[i], depth + 1, cls.getSimpleName() + i, bindings);
        }
        return widest.newInstance(arguments);
    }
    private static List<Constructor<?>> constructors(Class<?> cls) {
        List<Constructor<?>> toReturn = Lists.newArrayList();
        for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            toReturn.add(constructor);
        }
        return toReturn;
    }
    static List<Field> fields(Class<?> cls) {
        List<Field> toReturn = Lists.newArrayList();
        for (Class<?> c = cls; c != null && c.getName().startsWith(CODIQO_PACKAGE); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !field.isSynthetic() && !field.isAnnotationPresent(JsonIgnore.class)) {
                    field.setAccessible(true);
                    toReturn.add(field);
                }
            }
        }
        return toReturn;
    }
    private static void addIfPresent(Collection<Object> target, Object element) {
        if (element != null) {
            target.add(element);
        }
    }
}
