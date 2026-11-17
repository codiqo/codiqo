package io.codiqo.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.temporal.ChronoUnit;

import org.apache.commons.lang3.StringUtils;

/**
 * Marks a {@link RunArgs} field as a setting: a value a user gives by name, which {@link Settings} binds onto the field
 * from text. The name and the unit are declared here and nowhere else, and the field's initialiser is the default, so
 * Maven, whose parameters reach RunArgs only through that binding, declares no default of its own. A field without it
 * is no setting: run state the plugins fill in, or a scoring parameter only the server's scoring configuration sets.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Setting {
    /**
     * The name a user gives the setting by. Left empty, it is {@code codiqo.} followed by the field's name. Names users
     * already pass keep their spelling ({@code codiqo.hotspots}, {@code codiqo.review.timeoutMinutes}): a renamed
     * setting would leave every build that sets the old name on the default without a word.
     */
    String name() default StringUtils.EMPTY;
    /**
     * The unit a {@link java.time.Duration} setting is given in, as a whole number of it: every duration a user passes is
     * named for its unit ({@code codiqo.buildTimeoutMinutes=45}). The default, {@link ChronoUnit#FOREVER}, marks a setting
     * that is no duration, and {@link Settings} refuses a duration setting that names no unit.
     */
    ChronoUnit unit() default ChronoUnit.FOREVER;
}
