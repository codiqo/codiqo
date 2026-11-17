package io.codiqo.maven;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;

import io.codiqo.api.RunArgs;
import io.codiqo.api.Setting;
import io.codiqo.api.Settings;
import lombok.experimental.UtilityClass;

/**
 * Copies the values a mojo's parameters were given onto {@link RunArgs}, by the property each parameter reads. A mojo
 * declares a setting's parameter only for Maven (its documentation, IDE completion and {@code <configuration>}), with no
 * default and no copy of its own: every parameter whose property is a {@link Setting}'s name reaches its field here, and
 * one left unset keeps the RunArgs default. The parameters are read from the descriptor Maven runs the goal with, since
 * {@code @Parameter} is not kept at run time.
 */
@UtilityClass
public class MojoSettings {
    private static final String EXPRESSION_START = "${";
    private static final String EXPRESSION_END = "}";

    public RunArgs runArgs(Object mojo, MojoExecution execution) throws MojoExecutionException {
        return runArgs(mojo, execution, defaults -> {
        });
    }
    /**
     * The run's settings as the goal was given them, over {@code defaults}, validated: the defaults are what only the
     * goal or the build knows, such as where its output goes, so a value the user gave still wins. A value its setting
     * cannot read fails the goal naming the setting, as Maven reports a misconfiguration, not as the plugin's crash.
     */
    public RunArgs runArgs(Object mojo, MojoExecution execution, Consumer<RunArgs> defaults) throws MojoExecutionException {
        RunArgs toReturn = new RunArgs();
        defaults.accept(toReturn);
        try {
            Settings.bind(toReturn, of(mojo, execution.getMojoDescriptor()));
            toReturn.validate();
        } catch (IllegalArgumentException err) {
            throw new MojoExecutionException(err.getMessage(), err);
        }
        return toReturn;
    }
    /** each setting a parameter of the goal was given, as text under the setting's name */
    @VisibleForTesting
    public Map<String, String> of(Object mojo, MojoDescriptor descriptor) {
        Map<String, String> toReturn = Maps.newTreeMap();
        for (Parameter parameter : descriptor.getParameters()) {
            String property = StringUtils.substringBetween(parameter.getExpression(), EXPRESSION_START, EXPRESSION_END);
            if (Objects.nonNull(property) && Settings.names().contains(property)) {
                read(mojo, parameter.getName()).ifPresent(value -> toReturn.put(property, String.valueOf(value)));
            }
        }
        return toReturn;
    }
    /** empty for a parameter the goal was not given, which Maven leaves null */
    private Optional<Object> read(Object mojo, String field) {
        for (;;) {
            try {
                return Optional.ofNullable(FieldUtils.readField(mojo, field, true));
            } catch (IllegalAccessException err) {
                ExceptionUtils.wrapAndThrow(err);
            }
        }
    }
}
