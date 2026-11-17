package io.codiqo.gradle;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.gradle.api.GradleException;
import org.gradle.api.Project;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.api.Setting;
import io.codiqo.api.Settings;
import lombok.experimental.UtilityClass;

/**
 * The settings a Gradle build gives a run, by their {@link Setting} names: a {@code -P} project property first, else the
 * {@link CodiqoExtension} property of the same name, and otherwise nothing, so the {@link RunArgs} default holds. The
 * extension's property is the setting's name without {@code codiqo.}, its dotted parts joined in camel case
 * ({@code codiqo.review.timeoutMinutes} is {@code reviewTimeoutMinutes}); it declares no default of its own, since a
 * default there always overwrote the RunArgs one and drifted from it.
 *
 * <p>Reads Gradle types, so only the daemon uses it. The analysis worker runs without gradle-api on its classpath and
 * binds the map it is handed with {@link Settings#bind} itself.
 */
@UtilityClass
public class GradleSettings {
    public static final String JAVA_HOME = "codiqo.javaHome";
    public static final String OUTPUT_DIRECTORY = "codiqo.outputDirectory";

    public Map<String, String> of(Project project, CodiqoExtension ext) {
        return of(project::findProperty, ext);
    }
    /**
     * Each setting given, as text under its name, without the space around it; {@code properties} answers a project
     * property by its name, or null. A blank property is no value given, since it is what a CI variable that was not set
     * passes on ({@code -Pcodiqo.review.baseUrl=$UNSET}): the extension's value, or else the default, holds. The
     * extension's value is taken as it was written, so {@code excludeAuthorEmails = ''} there excludes nobody.
     */
    @VisibleForTesting
    public Map<String, String> of(Function<String, Object> properties, CodiqoExtension ext) {
        Map<String, String> toReturn = Maps.newTreeMap();
        for (String name : Settings.names()) {
            Optional.ofNullable(properties.apply(name))
                    .map(value -> StringUtils.trim(String.valueOf(value)))
                    .filter(StringUtils::isNotEmpty)
                    .or(() -> extensionValue(ext, name).map(value -> StringUtils.trim(String.valueOf(value))))
                    .ifPresent(text -> toReturn.put(name, text));
        }
        return toReturn;
    }
    /**
     * The run's settings bound and validated in the daemon, so a value its setting cannot read fails the build as a
     * misconfiguration naming the setting, before any task runs, rather than in the worker after the whole build.
     */
    public RunArgs runArgs(Map<String, String> settings) {
        try {
            return AnalysisEngine.runArgs(settings);
        } catch (IllegalArgumentException err) {
            throw new GradleException(err.getMessage(), err);
        }
    }
    /**
     * A switch of the plugin's own, read as strictly as a setting: read as {@link Boolean#parseBoolean} reads it,
     * {@code -Pcodiqo.sourceHidden=yes} would be false and the run would send its source code.
     */
    public boolean flag(Project project, String name, boolean fallback) {
        return flag(project::findProperty, name, fallback);
    }
    @VisibleForTesting
    public boolean flag(Function<String, Object> properties, String name, boolean fallback) {
        return Optional.ofNullable(properties.apply(name))
                .map(String::valueOf)
                .map(StringUtils::trim)
                .filter(StringUtils::isNotEmpty)
                .map(text -> Optional.ofNullable(BooleanUtils.toBooleanObject(text))
                        .orElseThrow(() -> new GradleException(String.format("%s=%s is not true or false", name, text))))
                .orElse(fallback);
    }
    /** the extension property a setting is read from, which may not exist: not every setting has one */
    @VisibleForTesting
    public String extensionProperty(String setting) {
        List<String> parts = Splitter.on('.').trimResults().omitEmptyStrings().splitToList(StringUtils.removeStart(setting, Settings.PREFIX));
        return parts.iterator().next() + parts.stream().skip(1).map(StringUtils::capitalize).collect(Collectors.joining());
    }
    /** read through the property's getter, which Gradle's decorated extension may override, never its field */
    private Optional<Object> extensionValue(CodiqoExtension ext, String setting) {
        String property = extensionProperty(setting);
        return Optional.ofNullable(FieldUtils.getField(CodiqoExtension.class, property, true)).flatMap(field -> read(ext, "get" + StringUtils.capitalize(property)));
    }
    private Optional<Object> read(CodiqoExtension ext, String getter) {
        for (;;) {
            try {
                return Optional.ofNullable(MethodUtils.invokeMethod(ext, getter));
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
