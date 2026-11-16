package io.codiqo.api;

import java.io.File;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSortedMap;

import lombok.experimental.UtilityClass;

/**
 * Binds the settings a user gave by name ({@link Setting}) onto {@link RunArgs}, each as text under the setting's name,
 * so a setting has one name, one parser and the field's initialiser as its default. A setting declared again in each
 * goal, with a default and a hand copy of its own, drifts: the {@code review} goal had no way to set the review's
 * sampling, and adding one setting touched nine files.
 */
@UtilityClass
public class Settings {
    private static final String PREFIX = "codiqo.";
    /**
     * How a setting's text becomes its value, by the field's type with primitives boxed. A list is comma-separated, as
     * every list setting has always been given; a {@link Duration} is read as a whole number of its {@link Setting#unit()}.
     */
    private static final Map<Class<?>, Function<String, Object>> PARSERS = Map.of(
            String.class, value -> value,
            Boolean.class, Settings::parseBoolean,
            Integer.class, Integer::valueOf,
            Double.class, Double::valueOf,
            File.class, File::new,
            List.class, value -> Splitter.on(',').trimResults().omitEmptyStrings().splitToList(value));
    /** every setting by its name, checked once when the class loads so a field no parser can set fails every run, not one */
    private static final ImmutableSortedMap<String, Field> BY_NAME = byName();

    public SortedSet<String> names() {
        return BY_NAME.keySet();
    }
    /** a setting's name: the one its {@link Setting} declares, else {@code codiqo.} followed by the field's name */
    @VisibleForTesting
    public String nameOf(Field field) {
        return StringUtils.defaultIfEmpty(field.getAnnotation(Setting.class).name(), PREFIX + field.getName());
    }
    /**
     * Sets each setting named in {@code values} on {@code args} from its text; a setting left out keeps the value
     * {@code args} has, and so does a list setting given no entry: an empty {@code codiqo.pmdRules} would run PMD
     * with no rule at all, report nothing and earn the clean bonus, and Maven has always read a blank list as unset. A
     * name no setting has is refused rather than skipped, since an adapter that passes one has a setting's name wrong
     * and the user's value would be lost without a word, and so is a value its setting cannot read, naming the setting.
     * The caller then calls {@link RunArgs#validate()}.
     */
    public void bind(RunArgs args, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Field field = Optional.ofNullable(BY_NAME.get(entry.getKey()))
                    .orElseThrow(() -> new IllegalArgumentException("no setting is named " + entry.getKey()));
            Object value = parse(entry.getKey(), field, Objects.requireNonNull(entry.getValue(), entry.getKey()));
            if (isGiven(value)) {
                write(args, field, value);
            }
        }
    }
    private Object parse(String name, Field field, String value) {
        ChronoUnit unit = field.getAnnotation(Setting.class).unit();
        try {
            if (Duration.class == field.getType()) {
                return Duration.of(Long.parseLong(value), unit);
            }
            return PARSERS.get(ClassUtils.primitiveToWrapper(field.getType())).apply(value);
        } catch (IllegalArgumentException | ArithmeticException err) {
            String expected = Duration.class == field.getType() ? "a whole number of " + StringUtils.lowerCase(unit.toString()) : field.getType().getSimpleName();
            throw new IllegalArgumentException(String.format("%s=%s cannot be read as %s", name, value, expected), err);
        }
    }
    /** a list with no entry, from blank text or separators alone, is no value given */
    private boolean isGiven(Object value) {
        if (value instanceof List<?> list) {
            return CollectionUtils.isNotEmpty(list);
        }
        return true;
    }
    private void write(RunArgs args, Field field, Object value) {
        for (;;) {
            try {
                field.set(args, value);
                return;
            } catch (IllegalAccessException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
    private ImmutableSortedMap<String, Field> byName() {
        ImmutableSortedMap.Builder<String, Field> toReturn = ImmutableSortedMap.naturalOrder();
        for (Field field : FieldUtils.getFieldsListWithAnnotation(RunArgs.class, Setting.class)) {
            ChronoUnit unit = field.getAnnotation(Setting.class).unit();
            boolean duration = Duration.class == field.getType();
            /** Duration.of refuses an estimated unit (a week, a month), so a duration names an exact one */
            boolean unitFits = duration ? BooleanUtils.negate(unit.isDurationEstimated()) : ChronoUnit.FOREVER == unit;
            boolean parsed = BooleanUtils.or(new boolean[] { duration, PARSERS.containsKey(ClassUtils.primitiveToWrapper(field.getType())) });
            if (BooleanUtils.and(new boolean[] { unitFits, parsed })) {
                field.setAccessible(true);
                toReturn.put(nameOf(field), field);
            } else {
                throw new IllegalStateException(String.format(
                        "setting %s, a %s in %s, is neither a duration in an exact unit nor of a type a parser reads",
                        field.getName(),
                        field.getType().getName(),
                        unit));
            }
        }
        return toReturn.buildOrThrow();
    }
    /**
     * Strict, unlike {@link Boolean#parseBoolean}, which reads every word but "true" as false: a misspelt
     * {@code codiqo.review=ture} given as text would turn the review off. commons-lang3 also reads yes/no and on/off.
     * Maven converts a Boolean parameter itself, as {@link Boolean#valueOf} does, before its value reaches this.
     */
    private Boolean parseBoolean(String value) {
        return Optional.ofNullable(BooleanUtils.toBooleanObject(value))
                .orElseThrow(() -> new IllegalArgumentException("not true or false: " + value));
    }
}
